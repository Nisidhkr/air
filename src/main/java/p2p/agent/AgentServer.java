package p2p.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import p2p.device.DeviceIdentity;
import p2p.device.DeviceInfo;
import p2p.device.DeviceRegistry;
import p2p.device.QrPairingService;
import p2p.lan.ControlPlaneClient;
import p2p.lan.IncomingOffer;
import p2p.lan.LanMessages;
import p2p.lan.LanShareService;
import p2p.lan.OfferManager;
import p2p.transfer.TransferManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Browser-facing Local Agent API.
 *
 * <p>The routes mounted here are a narrow, loopback-only facade over the
 * existing LAN implementation. Device-to-device control remains under
 * `/lan/*` for this migration phase.
 */
public final class AgentServer {

    public record LocalDeviceView(
            String deviceId,
            String name,
            String os,
            String deviceType,
            int apiPort,
            String mdnsServiceType) {
    }

    public record DeviceView(
            String deviceId,
            String name,
            String os,
            String deviceType,
            String host,
            int apiPort,
            long lastSeenEpochMs,
            boolean online,
            boolean trusted) {
        static DeviceView from(DeviceInfo device) {
            return new DeviceView(device.deviceId(), device.name(), device.os(),
                    device.deviceType(), device.host(), device.apiPort(),
                    device.lastSeenEpochMs(), device.online(), device.trusted());
        }
    }

    public record DevicesResponse(LocalDeviceView self, List<DeviceView> devices) {
    }

    public record StatusView(
            String name,
            String state,
            String host,
            int port,
            int webSocketPort,
            String mdnsServiceType,
            String version,
            long startedAtEpochMs,
            boolean loopbackOnly,
            List<String> securityLimitations) {
    }

    public record TransferView(
            String id,
            String direction,
            String status,
            String fileName,
            String peerName,
            long totalBytes,
            long transferredBytes,
            double percent,
            double mbPerSec,
            long etaSeconds,
            String error,
            long createdAtEpochMs) {
        static TransferView from(TransferManager.TransferView view) {
            return new TransferView(view.id(), view.direction(), view.status(),
                    view.fileName(), view.peerName(), view.totalBytes(),
                    view.transferredBytes(), view.percent(), view.mbPerSec(),
                    view.etaSeconds(), view.error(), view.createdAtEpochMs());
        }
    }

    public record OfferView(
            String offerId,
            String fromDeviceId,
            String fromName,
            String fromOs,
            int fileCount,
            long totalBytes,
            List<String> fileNames,
            long createdAtEpochMs) {
        static OfferView from(IncomingOffer offer) {
            return new OfferView(offer.offerId(), offer.senderDeviceId(),
                    offer.senderName(), offer.senderOs(), offer.files().size(),
                    offer.totalBytes(),
                    offer.files().stream().map(LanMessages.OfferFile::name).toList(),
                    offer.createdAtEpochMs());
        }
    }

    public record SendRequest(String deviceId, List<Integer> ports) {
    }

    public record ConnectRequest(String host, Integer port) {
    }

    public record OfferActionRequest(String action, Boolean trust) {
    }

    public record TransferActionRequest(String action) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final AgentConfig config;
    private final DeviceIdentity identity;
    private final DeviceRegistry deviceRegistry;
    private final TransferManager transferManager;
    private final LanShareService lanShareService;
    private final OfferManager offerManager;
    private final ControlPlaneClient controlPlane;
    private final QrPairingService pairing;
    private final long startedAtEpochMs = System.currentTimeMillis();

    public AgentServer(AgentConfig config, DeviceIdentity identity,
                       DeviceRegistry deviceRegistry, TransferManager transferManager,
                       LanShareService lanShareService, OfferManager offerManager,
                       ControlPlaneClient controlPlane, QrPairingService pairing) {
        this.config = config;
        this.identity = identity;
        this.deviceRegistry = deviceRegistry;
        this.transferManager = transferManager;
        this.lanShareService = lanShareService;
        this.offerManager = offerManager;
        this.controlPlane = controlPlane;
        this.pairing = pairing;
    }

    public void mount(HttpServer server) {
        server.createContext("/agent", new Handler());
    }

    private final class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isLoopback(exchange.getRemoteAddress().getAddress())) {
                sendText(exchange, 403, "Agent API is only available from loopback");
                return;
            }
            addCors(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String method = exchange.getRequestMethod().toUpperCase();
            String path = normalize(exchange.getRequestURI().getPath());
            try {
                switch (method + " " + route(path)) {
                    case "GET /agent/status" -> sendJson(exchange, 200, status());
                    case "GET /agent/device" -> sendJson(exchange, 200, localDevice());
                    case "GET /agent/devices" -> sendJson(exchange, 200, devices());
                    case "POST /agent/connect" -> handleConnect(exchange);
                    case "POST /agent/send" -> handleSend(exchange);
                    case "POST /agent/pair" -> handlePair(exchange);
                    case "GET /agent/offers" -> sendJson(exchange, 200,
                            offerManager.pending().stream().map(OfferView::from).toList());
                    case "POST /agent/offers/{id}" -> handleOfferAction(exchange, lastSegment(path));
                    case "GET /agent/transfers" -> sendJson(exchange, 200,
                            transferManager.snapshots().stream().map(TransferView::from).toList());
                    case "POST /agent/transfers/{id}" -> handleTransferAction(exchange, lastSegment(path));
                    case "POST /agent/transfers/{id}/cancel" ->
                            handleCancel(exchange, transferIdFromCancelPath(path));
                    default -> sendText(exchange, 404, "Not Found");
                }
            } catch (ApiError e) {
                sendJson(exchange, e.status, Map.of("error", e.getMessage()));
            } catch (IOException e) {
                sendJson(exchange, 400, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                System.err.println("event=agent.api.error path=" + path
                        + " error=" + e.getClass().getSimpleName());
                sendJson(exchange, 500, Map.of("error", "Internal error"));
            } finally {
                exchange.close();
            }
        }
    }

    private StatusView status() {
        return new StatusView(
                "Fylo Local Agent",
                "READY",
                config.agentHost(),
                config.agentPort(),
                config.webSocketPort(),
                config.mdnsServiceType(),
                AgentConfig.AGENT_VERSION,
                startedAtEpochMs,
                true,
                List.of(
                        "LAN control uses unauthenticated HTTP in this phase",
                        "Device identity is UUID-based until key pairing lands",
                        "PeerLink file bytes are token-gated but not encrypted"));
    }

    private LocalDeviceView localDevice() {
        return new LocalDeviceView(identity.deviceId(), identity.name(),
                identity.os(), identity.type(), config.agentPort(),
                config.mdnsServiceType());
    }

    private DevicesResponse devices() {
        return new DevicesResponse(localDevice(),
                deviceRegistry.snapshots().stream().map(DeviceView::from).toList());
    }

    private void handleConnect(HttpExchange exchange) throws IOException {
        JsonNode body = readBody(exchange);
        String host = text(body, "host");
        if (host == null || host.isBlank()) {
            throw new ApiError(400, "host is required");
        }
        int defaultPeerPort = config.agentPort() > 0
                ? config.agentPort() : AgentConfig.DEFAULT_AGENT_PORT;
        int peerPort = body.path("port").asInt(defaultPeerPort);
        try {
            System.out.println("event=agent.connect.start host=" + host + " port=" + peerPort);
            LanMessages.Hello peer = controlPlane.exchangeHello(host, peerPort,
                    new LanMessages.Hello(identity.deviceId(), identity.name(),
                            identity.os(), identity.type(), config.agentPort()));
            if (peer.deviceId() == null || peer.deviceId().equals(identity.deviceId())) {
                throw new ApiError(400, "That address is this device");
            }
            deviceRegistry.upsertOnline(peer.deviceId(), peer.name(), peer.os(),
                    peer.deviceType(), host, peerPort);
            System.out.println("event=agent.connect.success peerDeviceId=" + peer.deviceId()
                    + " host=" + host + " port=" + peerPort);
            sendJson(exchange, 200, Map.of("deviceId", peer.deviceId(), "name", peer.name()));
        } catch (IOException e) {
            System.err.println("event=agent.connect.failed host=" + host + " port=" + peerPort
                    + " error=" + e.getMessage());
            throw new ApiError(502, "Could not reach " + host + ":" + peerPort
                    + " (" + e.getMessage() + ")");
        }
    }

    private void handleSend(HttpExchange exchange) throws IOException {
        SendRequest request = json.readValue(exchange.getRequestBody(), SendRequest.class);
        if (request.deviceId() == null || request.deviceId().isBlank()
                || request.ports() == null || request.ports().isEmpty()) {
            throw new ApiError(400, "deviceId and ports are required");
        }
        try {
            String offerId = lanShareService.send(request.deviceId(), request.ports());
            sendJson(exchange, 200, Map.of("offerId", offerId));
        } catch (IOException e) {
            throw new ApiError(502, e.getMessage());
        }
    }

    private void handlePair(HttpExchange exchange) throws IOException {
        JsonNode body = readBody(exchange);
        String action = text(body, "action");
        if (action == null || action.isBlank() || action.equals("start")) {
            QrPairingService.PairingOffer offer = pairing.start();
            sendJson(exchange, 200, Map.of(
                    "pairingId", offer.pairingId(),
                    "payload", offer.payload(),
                    "expiresAt", offer.expiresAtEpochMs()));
            return;
        }
        if (!action.equals("complete")) {
            throw new ApiError(400, "action must be start or complete");
        }

        String peerHost = text(body, "host");
        if (peerHost == null || peerHost.isBlank()) {
            throw new ApiError(400, "host is required for pair completion");
        }
        boolean paired = pairing.complete(
                text(body, "pairingId"), text(body, "secret"),
                text(body, "deviceId"), text(body, "name"),
                text(body, "os"), text(body, "deviceType"),
                peerHost, body.path("apiPort").asInt(AgentConfig.DEFAULT_AGENT_PORT));
        if (!paired) {
            throw new ApiError(403, "Pairing code invalid or expired");
        }
        sendJson(exchange, 200, Map.of(
                "ok", true,
                "deviceId", identity.deviceId(),
                "name", identity.name(),
                "os", identity.os(),
                "deviceType", identity.type()));
    }

    private void handleOfferAction(HttpExchange exchange, String offerId) throws IOException {
        OfferActionRequest action = json.readValue(exchange.getRequestBody(), OfferActionRequest.class);
        boolean ok = switch (action.action() == null ? "" : action.action()) {
            case "accept" -> offerManager.accept(offerId, Boolean.TRUE.equals(action.trust()));
            case "reject" -> offerManager.reject(offerId);
            default -> false;
        };
        if (!ok) {
            throw new ApiError(404, "Unknown offer or already handled");
        }
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void handleTransferAction(HttpExchange exchange, String transferId) throws IOException {
        TransferActionRequest action =
                json.readValue(exchange.getRequestBody(), TransferActionRequest.class);
        boolean ok = switch (action.action() == null ? "" : action.action()) {
            case "pause" -> transferManager.pause(transferId);
            case "resume" -> transferManager.resume(transferId);
            case "cancel" -> transferManager.cancel(transferId);
            default -> false;
        };
        if (!ok) {
            throw new ApiError(404, "Unknown transfer or action not applicable");
        }
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void handleCancel(HttpExchange exchange, String transferId) throws IOException {
        if (!transferManager.cancel(transferId)) {
            throw new ApiError(404, "Unknown transfer");
        }
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private JsonNode readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        return bytes.length == 0 ? json.createObjectNode() : json.readTree(bytes);
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void addCors(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization,Range,X-Relative-Path");
    }

    private static boolean isLoopback(InetAddress address) {
        return address != null && address.isLoopbackAddress();
    }

    private static String normalize(String path) {
        return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }

    private static String route(String path) {
        if (path.startsWith("/agent/offers/")) {
            return "/agent/offers/{id}";
        }
        if (path.startsWith("/agent/transfers/") && path.endsWith("/cancel")) {
            return "/agent/transfers/{id}/cancel";
        }
        if (path.startsWith("/agent/transfers/")) {
            return "/agent/transfers/{id}";
        }
        return path;
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String transferIdFromCancelPath(String path) {
        String rest = path.substring("/agent/transfers/".length());
        return rest.substring(0, rest.length() - "/cancel".length());
    }

    private static final class ApiError extends IOException {
        final int status;

        ApiError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}

