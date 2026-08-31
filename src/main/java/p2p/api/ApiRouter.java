package p2p.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import p2p.auth.AuthContext;
import p2p.auth.AuthService;
import p2p.device.DeviceIdentity;
import p2p.device.QrPairingService;
import p2p.device.TrustedDeviceService;
import p2p.engine.TransferEngine;
import p2p.observability.Metrics;
import p2p.plan.Entitlements;
import p2p.plan.PlanLimitException;
import p2p.plan.PlanService;
import p2p.security.RateLimiter;
import p2p.share.DirectShareService;
import p2p.share.LinkShareService;
import p2p.share.ShareLink;
import p2p.share.UsernameShareService;
import p2p.user.NotificationService;
import p2p.user.PresenceService;
import p2p.user.TransferHistoryService;
import p2p.user.User;
import p2p.user.UserService;
import p2p.utils.MultipartUploads;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ONE API layer for account-based and unified-platform endpoints,
 * mounted on the same HTTP server as the legacy gateway routes:
 *
 * <pre>
 * POST /api/v1/auth/register|login|refresh|logout        (auth)
 * GET  /api/v1/users/me · /api/v1/users/search?q=        (users)
 * GET  /api/v1/notifications                              (drain queue)
 * GET  /api/v1/history                                    (transfer history)
 * POST /api/v1/requests · GET /api/v1/requests            (mode 3)
 * POST /api/v1/requests/{id}                              (accept/reject)
 * POST /api/v1/links/upload · GET /api/v1/links           (mode 4)
 * DELETE /api/v1/links/{slug}
 * GET  /s/{slug}                                          (public download, Range)
 * GET  /api/v1/devices/trusted · POST /api/v1/devices/{id}/trust
 * POST /api/v1/pair/start · POST /api/v1/pair/complete    (QR pairing)
 * GET  /api/v1/direct/{code}                              (share-code lookup)
 * </pre>
 *
 * Direct/Nearby transfer endpoints ({@code /upload}, {@code /download},
 * {@code /lan/*}, {@code /transfers}) stay where they are — this class adds
 * the unified surface without duplicating them.
 */
public final class ApiRouter {

    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d+)-(\\d*)");

    private final ObjectMapper json = new ObjectMapper();
    private final AuthService auth;
    private final UserService userService;
    private final PresenceService presence;
    private final NotificationService notifications;
    private final TransferHistoryService history;
    private final UsernameShareService usernameShare;
    private final LinkShareService linkShare;
    private final DirectShareService directShare;
    private final TransferEngine engine;
    private final TrustedDeviceService trustedDevices;
    private final QrPairingService pairing;
    private final DeviceIdentity identity;
    private final Path uploadTmpDir;
    private final PlanService plans;
    private final RateLimiter rateLimiter;
    private final Metrics metrics;
    // Set by the composition root before mount().
    private volatile p2p.observability.HealthService health;
    private volatile int wsPort;
    private volatile p2p.transfer.UploadProgressRegistry uploadProgress;

    public void setUploadProgress(p2p.transfer.UploadProgressRegistry registry) {
        this.uploadProgress = registry;
    }

    public ApiRouter(AuthService auth, UserService userService, PresenceService presence,
                     NotificationService notifications, TransferHistoryService history,
                     UsernameShareService usernameShare, LinkShareService linkShare,
                     DirectShareService directShare, TransferEngine engine,
                     TrustedDeviceService trustedDevices, QrPairingService pairing,
                     DeviceIdentity identity, Path uploadTmpDir,
                     PlanService plans, RateLimiter rateLimiter, Metrics metrics) {
        this.auth = auth;
        this.userService = userService;
        this.presence = presence;
        this.notifications = notifications;
        this.history = history;
        this.usernameShare = usernameShare;
        this.linkShare = linkShare;
        this.directShare = directShare;
        this.engine = engine;
        this.trustedDevices = trustedDevices;
        this.pairing = pairing;
        this.identity = identity;
        this.uploadTmpDir = uploadTmpDir;
        this.plans = plans;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    public void setHealth(p2p.observability.HealthService health) {
        this.health = health;
    }

    public void setWebSocketPort(int wsPort) {
        this.wsPort = wsPort;
    }

    /** Mounts the unified routes onto the shared server. */
    public void mount(HttpServer server) {
        server.createContext("/api/v1", exchange -> dispatch(exchange, this::routeApi));
        server.createContext("/s/", exchange -> dispatch(exchange, this::routePublicLink));
        if (health != null) {
            // Backbone §14.4: /health + Spring-Actuator-compatible alias.
            server.createContext("/health", health.handler());
            server.createContext("/actuator/health", health.handler());
        }
        server.createContext("/ws/events", exchange -> dispatch(exchange, this::routeWsInfo));
    }

    /**
     * The WebSocket endpoint lives on its own listener (plain HttpServer
     * cannot hand its socket to an upgrade). This HTTP route enforces the
     * backbone contract: 401 without a valid token, else 426 Upgrade
     * Required carrying the real ws:// URL for the client to dial.
     */
    private void routeWsInfo(HttpExchange exchange, String method, String path)
            throws IOException {
        String token = queryParam(exchange, "token");
        if (auth.authenticate(token).isEmpty()) {
            throw new ApiError(401, "Missing or invalid token");
        }
        exchange.getResponseHeaders().set("Upgrade", "websocket");
        String host = exchange.getRequestHeaders().getFirst("Host");
        String hostname = host == null ? "localhost"
                : (host.contains(":") ? host.substring(0, host.indexOf(':')) : host);
        sendJson(exchange, 426, Map.of(
                "error", "Upgrade Required",
                "wsUrl", "ws://" + hostname + ":" + wsPort + "/ws/events?token=<accessToken>"));
    }

    private interface Route {
        void handle(HttpExchange exchange, String method, String path) throws IOException;
    }

    private void dispatch(HttpExchange exchange, Route route) throws IOException {
        addCors(exchange);
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        String path = exchange.getRequestURI().getPath();
        RateLimiter.Policy policy = policyFor(path, exchange.getRequestMethod());
        String client = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!rateLimiter.tryAcquire(client, policy)) {
            metrics.increment("fylo_http_requests_total{route=\"" + routeGroup(path)
                    + "\",status=\"429\"}");
            exchange.getResponseHeaders().set("Retry-After",
                    Long.toString(policy.retryAfterSeconds()));
            sendJson(exchange, 429, Map.of("error", "Too many requests"));
            exchange.close();
            return;
        }
        int status = 200;
        try {
            route.handle(exchange, exchange.getRequestMethod().toUpperCase(), path);
        } catch (PlanLimitException e) {
            status = 403;
            sendJson(exchange, 403, Map.of(
                    "error", e.getMessage(), "code", "plan_limit", "limit", e.limit()));
        } catch (ApiError e) {
            status = e.status;
            sendJson(exchange, e.status, Map.of("error", e.getMessage()));
        } catch (IOException e) {
            status = 400;
            sendJson(exchange, 400, Map.of("error", e.getMessage() == null
                    ? "Bad request" : e.getMessage()));
        } catch (Exception e) {
            status = 500;
            System.err.println("API error on " + exchange.getRequestURI() + ": " + e);
            sendJson(exchange, 500, Map.of("error", "Internal error"));
        } finally {
            metrics.increment("fylo_http_requests_total{route=\"" + routeGroup(path)
                    + "\",status=\"" + status + "\"}");
            exchange.close();
        }
    }

    private static RateLimiter.Policy policyFor(String path, String method) {
        if (path.startsWith("/api/v1/auth/")) {
            return RateLimiter.Policy.AUTH;
        }
        if (path.startsWith("/s/")) {
            return RateLimiter.Policy.DOWNLOAD;
        }
        if (path.startsWith("/api/v1/links/upload") && method.equalsIgnoreCase("POST")) {
            return RateLimiter.Policy.UPLOAD;
        }
        return RateLimiter.Policy.API;
    }

    private static String routeGroup(String path) {
        if (path.startsWith("/s/")) {
            return "link_download";
        }
        String rest = path.startsWith("/api/v1/") ? path.substring("/api/v1/".length()) : path;
        int slash = rest.indexOf('/');
        return slash == -1 ? rest : rest.substring(0, slash);
    }

    // ── /api/v1 routing ──────────────────────────────────────────────────

    private void routeApi(HttpExchange exchange, String method, String path) throws IOException {
        String route = path.substring("/api/v1".length());

        switch (method + " " + normalize(route)) {
            // Auth (guest-accessible)
            case "POST /auth/register" -> handleRegister(exchange);
            case "POST /auth/login" -> handleLogin(exchange);
            case "POST /auth/refresh" -> handleRefresh(exchange);
            case "POST /auth/logout" -> handleLogout(exchange);

            // Users
            case "GET /users/me" -> {
                AuthContext ctx = require(exchange);
                sendJson(exchange, 200, userService.profileById(ctx.userId())
                        .orElseThrow(() -> new ApiError(404, "Account not found")));
            }
            case "GET /users/search" -> {
                require(exchange);
                sendJson(exchange, 200, userService.search(queryParam(exchange, "q")));
            }

            // Notifications / presence heartbeat / history
            case "GET /notifications" -> {
                AuthContext ctx = require(exchange);
                sendJson(exchange, 200, notifications.drain(ctx.userId()));
            }
            case "GET /history" -> {
                AuthContext ctx = require(exchange);
                sendJson(exchange, 200, history.listFor(ctx.userId(), 100));
            }

            // Mode 3 — Username Share
            case "POST /requests" -> handleCreateRequest(exchange);
            case "GET /requests" -> {
                AuthContext ctx = require(exchange);
                sendJson(exchange, 200, usernameShare.incoming(ctx));
            }
            case "POST /requests/{id}" -> handleRequestAction(exchange, lastSegment(path));

            // Mode 4 — Link Share
            case "POST /links/upload" -> handleLinkUpload(exchange);
            case "GET /uploads/{id}/progress" -> handleUploadProgress(exchange, path.split("/")[4]);
            case "GET /links" -> {
                AuthContext ctx = require(exchange);
                sendJson(exchange, 200, linkShare.listFor(ctx).stream()
                        .map(ApiRouter::linkView).toList());
            }
            case "DELETE /links/{slug}" -> {
                AuthContext ctx = require(exchange);
                if (!linkShare.delete(ctx, lastSegment(path))) {
                    throw new ApiError(404, "Unknown link");
                }
                sendJson(exchange, 200, Map.of("ok", true));
            }
            case "POST /links/{slug}/revoke" -> {
                AuthContext ctx = require(exchange);
                String slug = path.split("/")[4]; // /api/v1/links/{slug}/revoke
                if (!linkShare.revoke(ctx, slug)) {
                    throw new ApiError(404, "Unknown link");
                }
                sendJson(exchange, 200, Map.of("ok", true));
            }
            case "GET /links/{slug}/stats" -> handleLinkStats(exchange, path.split("/")[4]);

            // Plan & usage
            case "GET /plan" -> handlePlan(exchange);

            // Mode 1 — Direct Share code lookup (guest)
            case "GET /direct/{code}" -> handleDirectLookup(exchange, lastSegment(path));

            // Mode 1 — backbone §10 rendezvous endpoints (guest)
            case "POST /transfers/direct/initiate" -> handleDirectInitiate(exchange);
            case "POST /transfers/direct/join" -> handleDirectJoin(exchange);

            // Mode 3 — backbone §10 route names (aliases of /requests)
            case "POST /transfers/username/request" -> handleCreateRequest(exchange);
            case "POST /transfers/username/respond" -> handleUsernameRespond(exchange);

            // Devices: trust + pairing
            case "GET /devices/trusted" -> sendJson(exchange, 200, trustedDevices.trustedDevices());
            case "POST /devices/{id}/trust" -> handleTrust(exchange, path);
            case "POST /pair/start" -> {
                QrPairingService.PairingOffer offer = pairing.start();
                sendJson(exchange, 200, Map.of(
                        "pairingId", offer.pairingId(),
                        "payload", offer.payload(),
                        "expiresAt", offer.expiresAtEpochMs()));
            }
            case "POST /pair/complete" -> handlePairComplete(exchange);

            default -> throw new ApiError(404, "Not found");
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────

    private void handleRegister(HttpExchange exchange) throws IOException {
        JsonNode body = readBody(exchange);
        try {
            User user = auth.register(text(body, "username"), text(body, "displayName"),
                    text(body, "email"), chars(body, "password"));
            AuthService.TokenPair pair = auth.login(user.username(), chars(body, "password"));
            sendJson(exchange, 201, Map.of(
                    "user", user.profile(true), "tokens", pair));
        } catch (AuthService.AuthException e) {
            throw new ApiError(400, e.getMessage());
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        JsonNode body = readBody(exchange);
        try {
            AuthService.TokenPair pair = auth.login(text(body, "username"), chars(body, "password"));
            sendJson(exchange, 200, pair);
        } catch (AuthService.AuthException e) {
            throw new ApiError(401, e.getMessage());
        }
    }

    private void handleRefresh(HttpExchange exchange) throws IOException {
        JsonNode body = readBody(exchange);
        try {
            sendJson(exchange, 200, auth.refresh(text(body, "refreshToken")));
        } catch (AuthService.AuthException e) {
            throw new ApiError(401, e.getMessage());
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        JsonNode body = readBody(exchange);
        auth.logout(text(body, "refreshToken"));
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void handleCreateRequest(HttpExchange exchange) throws IOException {
        AuthContext ctx = require(exchange);
        JsonNode body = readBody(exchange);
        var request = usernameShare.request(ctx, text(body, "toUsername"),
                body.path("port").asInt());
        sendJson(exchange, 201, Map.of("requestId", request.requestId(),
                "state", request.state().name()));
    }

    /** Backbone alias: body {requestId, action} instead of /requests/{id}. */
    private void handleUsernameRespond(HttpExchange exchange) throws IOException {
        AuthContext ctx = require(exchange);
        JsonNode body = readBody(exchange);
        String requestId = text(body, "requestId");
        if (requestId == null) {
            throw new ApiError(400, "requestId is required");
        }
        switch (text(body, "action")) {
            case "accept" -> sendJson(exchange, 200, usernameShare.accept(ctx, requestId));
            case "reject" -> {
                usernameShare.reject(ctx, requestId);
                sendJson(exchange, 200, Map.of("ok", true));
            }
            default -> throw new ApiError(400, "action must be accept or reject");
        }
    }

    /** Mode 1 rendezvous: sender announces file metadata, gets code + token. */
    private void handleDirectInitiate(HttpExchange exchange) throws IOException {
        JsonNode body = readBody(exchange);
        String fileName = text(body, "fileName");
        long fileSize = body.path("fileSizeBytes").asLong(0);
        if (fileName == null || fileName.isBlank() || fileSize < 0) {
            throw new ApiError(400, "fileName and fileSizeBytes are required");
        }
        DirectShareService.DirectSession session =
                directShare.initiate(fileName, fileSize, text(body, "checksum"));
        sendJson(exchange, 200, Map.of(
                "sessionId", session.sessionId(),
                "sessionCode", session.sessionCode(),
                "transferToken", session.transferToken(),
                "expiresAt", session.expiresAtEpochMs()));
    }

    /** Mode 1 rendezvous: receiver joins with the 6-char code. */
    private void handleDirectJoin(HttpExchange exchange) throws IOException {
        JsonNode body = readBody(exchange);
        DirectShareService.DirectSession session = directShare.join(text(body, "code"))
                .orElseThrow(() -> new ApiError(404, "Code not found or expired"));
        sendJson(exchange, 200, Map.of(
                "sessionId", session.sessionId(),
                "transferToken", session.transferToken(),
                "fileName", session.fileName(),
                "fileSizeBytes", session.fileSizeBytes(),
                "senderReady", true));
    }

    private void handleRequestAction(HttpExchange exchange, String requestId) throws IOException {
        AuthContext ctx = require(exchange);
        JsonNode body = readBody(exchange);
        switch (text(body, "action")) {
            case "accept" -> sendJson(exchange, 200, usernameShare.accept(ctx, requestId));
            case "reject" -> {
                usernameShare.reject(ctx, requestId);
                sendJson(exchange, 200, Map.of("ok", true));
            }
            default -> throw new ApiError(400, "action must be accept or reject");
        }
    }

    private void handleLinkUpload(HttpExchange exchange) throws IOException {
        AuthContext ctx = require(exchange);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            throw new ApiError(400, "Content-Type must be multipart/form-data");
        }
        String boundary = MultipartUploads.extractBoundary(contentType);
        if (boundary == null) {
            throw new ApiError(400, "Missing multipart boundary");
        }

        // Plan-enforced ingress throttle (FREE 2 MB/s; PREMIUM = no-op) and
        // live progress. Clients may supply ?uploadId=<uuid> so they can open
        // the SSE stream BEFORE posting; otherwise one is generated.
        Entitlements plan = plans.entitlementsFor(ctx.userId());
        String uploadId = firstPresent(queryParam(exchange, "uploadId"),
                java.util.UUID.randomUUID().toString());
        long declaredTotal = optionalLong(
                exchange.getRequestHeaders().getFirst("Content-Length"));
        var throttle = new p2p.transfer.UploadThrottle(plan.uploadSpeedBytesPerSecond());
        var progress = uploadProgress == null ? null
                : uploadProgress.register(uploadId, declaredTotal);

        // Stream to a temp file first, then into storage: keeps the storage
        // write path identical for every provider and lets us hash in one pass.
        Path tmp;
        try {
            tmp = MultipartUploads.streamFirstFileToDir(
                    exchange.getRequestBody(), boundary, uploadTmpDir, throttle,
                    progress == null ? null : progress.bytesTransferred::set);
        } catch (IOException e) {
            if (uploadProgress != null) {
                uploadProgress.finish(uploadId, false);
            }
            throw e;
        }
        if (tmp == null) {
            if (uploadProgress != null) {
                uploadProgress.finish(uploadId, false);
            }
            throw new ApiError(400, "No file field in request");
        }
        try {
            // Options: ?expiryDays=N (backbone name; ttlDays kept as alias;
            // 0 = never expire, premium), ?password=…, ?maxDownloads=N —
            // all validated against the plan by the service.
            String expiryRaw = firstPresent(
                    queryParam(exchange, "expiryDays"), queryParam(exchange, "ttlDays"));
            long expiryDays = optionalLong(expiryRaw);
            Duration ttl = expiryRaw == null
                    ? null
                    : (expiryDays <= 0 ? Duration.ZERO : Duration.ofDays(expiryDays));
            LinkShareService.LinkOptions options = new LinkShareService.LinkOptions(
                    ttl, queryParam(exchange, "password"),
                    optionalLong(queryParam(exchange, "maxDownloads")));
            long size = Files.size(tmp);
            ShareLink link = linkShare.create(ctx, Files.newInputStream(tmp),
                    MultipartUploads.stripUploadPrefix(tmp.getFileName().toString()),
                    size, options);
            metrics.add("fylo_link_upload_bytes_total", size);
            if (uploadProgress != null) {
                uploadProgress.finish(uploadId, true);
            }
            Map<String, Object> view = new java.util.LinkedHashMap<>(linkView(link));
            view.put("uploadId", uploadId);
            sendJson(exchange, 201, view);
        } catch (IOException | RuntimeException e) {
            if (uploadProgress != null) {
                uploadProgress.finish(uploadId, false);
            }
            throw e;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * SSE progress stream (backbone §6.4 fallback path for browsers, since
     * EventSource cannot send Authorization headers the uploadId itself is
     * the capability). Emits an event every 500 ms until terminal.
     */
    private void handleUploadProgress(HttpExchange exchange, String uploadId)
            throws IOException {
        var registry = uploadProgress;
        var progress = registry == null
                ? java.util.Optional.<p2p.transfer.UploadProgressRegistry.UploadProgress>empty()
                : registry.find(uploadId);
        if (progress.isEmpty()) {
            throw new ApiError(404, "Unknown upload");
        }
        var state = progress.get();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            long lastBytes = state.bytesTransferred.get();
            long lastAtMs = System.currentTimeMillis();
            while (true) {
                long bytes = state.bytesTransferred.get();
                long now = System.currentTimeMillis();
                long speedBps = now > lastAtMs
                        ? (bytes - lastBytes) * 1000 / Math.max(1, now - lastAtMs) : 0;
                long total = state.totalBytes;
                double percentage = total > 0
                        ? Math.min(100.0, Math.round(1000.0 * bytes / total) / 10.0) : 0;
                long eta = speedBps > 0 && total > bytes ? (total - bytes) / speedBps : -1;
                String event = "data: {\"uploadId\":\"" + state.uploadId
                        + "\",\"bytesTransferred\":" + bytes
                        + ",\"totalBytes\":" + total
                        + ",\"percentage\":" + percentage
                        + ",\"speedBps\":" + speedBps
                        + ",\"etaSeconds\":" + eta
                        + ",\"status\":\"" + state.status + "\"}\n\n";
                out.write(event.getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (!p2p.transfer.UploadProgressRegistry.IN_PROGRESS.equals(state.status)) {
                    return; // COMPLETED / FAILED: final event sent, close stream
                }
                lastBytes = bytes;
                lastAtMs = now;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Premium analytics for one owned link. */
    private void handleLinkStats(HttpExchange exchange, String slug) throws IOException {
        AuthContext ctx = require(exchange);
        Entitlements plan = plans.entitlementsFor(ctx.userId());
        if (!plan.linkAnalytics()) {
            throw new PlanLimitException("link_analytics", "Link analytics require Premium");
        }
        ShareLink link = linkShare.listFor(ctx).stream()
                .filter(l -> l.slug().equals(slug)).findFirst()
                .orElseThrow(() -> new ApiError(404, "Unknown link"));
        sendJson(exchange, 200, Map.of(
                "slug", link.slug(),
                "fileName", link.fileName(),
                "size", link.sizeBytes(),
                "downloads", link.downloadCount(),
                "maxDownloads", link.maxDownloads(),
                "protected", link.passwordProtected(),
                "revoked", link.revoked(),
                "createdAt", link.createdAtEpochMs(),
                "expiresAt", link.expiresAtEpochMs()));
    }

    /** Current plan, entitlements, and live usage — drives upgrade UI. */
    private void handlePlan(HttpExchange exchange) throws IOException {
        AuthContext ctx = require(exchange);
        Entitlements plan = plans.entitlementsFor(ctx.userId());
        Map<String, Object> entitlements = new java.util.LinkedHashMap<>();
        entitlements.put("uploadSpeedBytesPerSecond", plan.uploadSpeedBytesPerSecond());
        entitlements.put("uploadSpeedLabel", plan.uploadSpeedLabel());
        entitlements.put("maxFileBytes", plan.maxFileBytes());
        entitlements.put("maxStorageBytes", plan.maxStorageBytes());
        entitlements.put("maxActiveLinks", plan.maxActiveLinks());
        entitlements.put("linkExpiryDayOptions", plan.linkExpiryDayOptions());
        entitlements.put("customExpiryAllowed", plan.customExpiryAllowed());
        entitlements.put("neverExpireAllowed", plan.neverExpireAllowed());
        entitlements.put("maxLinkTtlDays", plan.maxLinkTtl().toDays());
        entitlements.put("passwordProtectedLinks", plan.passwordProtectedLinks());
        entitlements.put("downloadLimits", plan.downloadLimits());
        entitlements.put("linkAnalytics", plan.linkAnalytics());
        entitlements.put("linkRevocation", plan.linkRevocation());
        entitlements.put("maxConcurrentTransfers", plan.maxConcurrentTransfers());
        entitlements.put("historyEntries", plan.historyEntries());
        entitlements.put("maxTrustedDevices", plan.maxTrustedDevices());
        entitlements.put("ecosystemSync", plan.ecosystemSync());
        sendJson(exchange, 200, Map.of(
                "tier", plan.tierName(),
                "entitlements", entitlements,
                "usage", Map.of(
                        "storageBytesUsed", linkShare.storageUsed(ctx.userId()),
                        "activeLinks", linkShare.activeLinkCount(ctx.userId()))));
    }

    private void handleDirectLookup(HttpExchange exchange, String rawCode) throws IOException {
        var code = directShare.resolve(rawCode)
                .orElseThrow(() -> new ApiError(400, "Invalid share code"));
        var info = engine.shareInfo(code.port())
                .filter(i -> p2p.security.TransferTokens.matches(i.token(), code.token()))
                .orElseThrow(() -> new ApiError(404, "Share is no longer available"));
        sendJson(exchange, 200, Map.of(
                "name", info.fileName(), "size", info.size(),
                "port", info.port(), "token", info.token()));
    }

    private void handleTrust(HttpExchange exchange, String path) throws IOException {
        // /api/v1/devices/{id}/trust
        String[] segments = path.split("/");
        String deviceId = segments[segments.length - 2];
        JsonNode body = readBody(exchange);
        if (body.path("trusted").asBoolean(true)) {
            trustedDevices.trust(deviceId);
        } else {
            trustedDevices.revoke(deviceId);
        }
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void handlePairComplete(HttpExchange exchange) throws IOException {
        JsonNode body = readBody(exchange);
        String peerHost = exchange.getRemoteAddress().getAddress().getHostAddress();
        boolean paired = pairing.complete(
                text(body, "pairingId"), text(body, "secret"),
                text(body, "deviceId"), text(body, "name"),
                text(body, "os"), text(body, "deviceType"),
                peerHost, body.path("apiPort").asInt());
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

    // ── Public link download: GET /s/{slug} ──────────────────────────────

    private void routePublicLink(HttpExchange exchange, String method, String path)
            throws IOException {
        if (!method.equals("GET")) {
            throw new ApiError(405, "Method not allowed");
        }
        String slug = lastSegment(path);
        ShareLink link = linkShare.resolve(slug)
                .orElseThrow(() -> new ApiError(404, "Link not found or expired"));

        // Premium: password-protected links (?pw=… or X-Link-Password header).
        String presented = queryParam(exchange, "pw");
        if (presented == null) {
            presented = exchange.getRequestHeaders().getFirst("X-Link-Password");
        }
        if (!linkShare.passwordMatches(link, presented)) {
            throw new ApiError(401, "This link requires a password");
        }

        long offset = parseRangeOffset(exchange.getRequestHeaders().getFirst("Range"));
        if (offset < 0 || offset > link.sizeBytes()) {
            throw new ApiError(416, "Requested range not satisfiable");
        }
        long length = link.sizeBytes() - offset;

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/octet-stream");
        headers.set("Content-Disposition", "attachment; filename=\"" + link.fileName() + "\"");
        headers.set("Accept-Ranges", "bytes");
        if (offset > 0) {
            headers.set("Content-Range", "bytes " + offset + "-"
                    + (link.sizeBytes() - 1) + "/" + link.sizeBytes());
            exchange.sendResponseHeaders(206, length);
        } else {
            exchange.sendResponseHeaders(200, length);
        }

        // Provider-aware streaming: zero-copy for local storage, ranged GET
        // for MinIO/S3 — the engine picks; constant memory either way.
        try (OutputStream out = exchange.getResponseBody()) {
            engine.streamStored(link.objectId(), offset, length, out);
        }
        linkShare.recordDownload(slug);
        metrics.add("fylo_link_download_bytes_total", length);
    }

    // ── Plumbing ─────────────────────────────────────────────────────────

    private static final class ApiError extends IOException {
        final int status;

        ApiError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** Authenticates the request and refreshes the caller's presence. */
    private AuthContext require(HttpExchange exchange) throws ApiError {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String token = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length()).trim() : null;
        AuthContext ctx = auth.authenticate(token)
                .orElseThrow(() -> new ApiError(401, "Login required"));
        presence.touch(ctx.userId());
        return ctx;
    }

    private JsonNode readBody(HttpExchange exchange) throws IOException {
        return json.readTree(exchange.getRequestBody());
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static char[] chars(JsonNode body, String field) {
        String value = text(body, field);
        return value == null ? new char[0] : value.toCharArray();
    }

    /** First non-null value — parameter aliasing (expiryDays | ttlDays). */
    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static long optionalLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return null;
        }
        for (String param : query.split("&")) {
            int eq = param.indexOf('=');
            if (eq > 0 && param.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(
                        param.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static Map<String, Object> linkView(ShareLink link) {
        return Map.of(
                "slug", link.slug(),
                "url", "/s/" + link.slug(),
                "fileName", link.fileName(),
                "size", link.sizeBytes(),
                "createdAt", link.createdAtEpochMs(),
                "expiresAt", link.expiresAtEpochMs(),
                "downloads", link.downloadCount(),
                "protected", link.passwordProtected(),
                "maxDownloads", link.maxDownloads(),
                "revoked", link.revoked());
    }

    /** Collapses ids/slugs so routes can be matched literally. */
    private static String normalize(String route) {
        String trimmed = route.endsWith("/") && route.length() > 1
                ? route.substring(0, route.length() - 1) : route;
        List<Map.Entry<Pattern, String>> rewrites = List.of(
                Map.entry(Pattern.compile("^/requests/[^/]+$"), "/requests/{id}"),
                Map.entry(Pattern.compile("^/uploads/[^/]+/progress$"), "/uploads/{id}/progress"),
                Map.entry(Pattern.compile("^/links/[^/]+/revoke$"), "/links/{slug}/revoke"),
                Map.entry(Pattern.compile("^/links/[^/]+/stats$"), "/links/{slug}/stats"),
                Map.entry(Pattern.compile("^/links/(?!upload$)[^/]+$"), "/links/{slug}"),
                Map.entry(Pattern.compile("^/direct/[^/]+$"), "/direct/{code}"),
                Map.entry(Pattern.compile("^/devices/[^/]+/trust$"), "/devices/{id}/trust"));
        for (var rewrite : rewrites) {
            if (rewrite.getKey().matcher(trimmed).matches()) {
                return rewrite.getValue();
            }
        }
        return trimmed;
    }

    private static String lastSegment(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    private static long parseRangeOffset(String rangeHeader) {
        if (rangeHeader == null) {
            return 0;
        }
        Matcher matcher = RANGE_PATTERN.matcher(rangeHeader);
        return matcher.matches() ? Long.parseLong(matcher.group(1)) : 0;
    }

    private static void addCors(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization,Range");
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
