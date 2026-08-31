package p2p.ws;

import p2p.auth.AuthContext;
import p2p.auth.AuthService;
import p2p.infra.RedisClient;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal RFC 6455 WebSocket server for real-time events (backbone §6.4).
 *
 * <p>{@code com.sun.net.httpserver} cannot hand over its socket for a
 * protocol upgrade, so the WebSocket endpoint listens on its own port
 * (API port + 1) — one backend process, two listeners. The HTTP route
 * {@code GET /ws/events} answers 426 with the actual ws:// URL so clients
 * self-configure; in production the edge proxy maps both onto :443.
 *
 * <p>Auth: {@code ?token=<accessToken>} validated against the ONE
 * {@link AuthService} before the 101 handshake completes. Multiple
 * connections per user (multi-device) are supported.
 *
 * <p>Cross-instance delivery (backbone §14.3): with Redis, sends are
 * published to {@code ws:user:{userId}} and every instance delivers to its
 * local connections; without Redis, delivery is local-only (single node).
 *
 * <p>One virtual thread per connection; frames handled: text (ignored from
 * clients for now), ping→pong, close.
 */
public final class WebSocketServer implements Closeable {

    private static final String WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final int port;
    private final AuthService auth;
    private final RedisClient redis; // may be null/disabled
    private final ConcurrentHashMap<String, Set<Connection>> connectionsByUser =
            new ConcurrentHashMap<>();
    private final AtomicLong connectionCount = new AtomicLong();
    private volatile ServerSocket serverSocket;
    private volatile boolean closed;

    public WebSocketServer(int port, AuthService auth, RedisClient redis) {
        this.port = port;
        this.auth = auth;
        this.redis = redis;
    }

    public int port() {
        return port;
    }

    public long connectionCount() {
        return connectionCount.get();
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        Thread.ofVirtual().name("ws-accept").start(this::acceptLoop);
        if (redis != null) {
            // Cross-instance fan-out: deliver published events to local conns.
            redis.subscribePattern("ws:user:*", (channel, message) -> {
                String userId = channel.substring("ws:user:".length());
                deliverLocal(userId, message);
            });
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                Thread.ofVirtual().start(() -> handle(socket));
            } catch (IOException e) {
                if (!closed) {
                    System.err.println("WS accept failed: " + e.getMessage());
                }
                return;
            }
        }
    }

    // ── Sending ──────────────────────────────────────────────────────────

    /** Sends to every connection of {@code userId}, on every instance. */
    public void sendToUser(String userId, String jsonPayload) {
        if (redis != null && redis.isAvailable()) {
            // Our own subscription echoes this back and delivers locally too.
            redis.publish("ws:user:" + userId, jsonPayload);
            return;
        }
        deliverLocal(userId, jsonPayload);
    }

    /** Sends to every local connection (session-scoped progress events). */
    public void broadcast(String jsonPayload) {
        connectionsByUser.values().forEach(set ->
                set.forEach(conn -> conn.trySendText(jsonPayload)));
    }

    private void deliverLocal(String userId, String jsonPayload) {
        Set<Connection> conns = connectionsByUser.get(userId);
        if (conns != null) {
            conns.forEach(conn -> conn.trySendText(jsonPayload));
        }
    }

    // ── Connection lifecycle ─────────────────────────────────────────────

    private void handle(Socket socket) {
        Connection connection = null;
        String userId = null;
        try {
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            Handshake handshake = readHandshake(in);
            if (handshake == null || !handshake.isWebSocketUpgrade()) {
                writeHttpError(out, "426 Upgrade Required",
                        "{\"error\":\"WebSocket upgrade required\"}");
                return;
            }
            AuthContext ctx = auth.authenticate(handshake.queryParam("token")).orElse(null);
            if (ctx == null) {
                writeHttpError(out, "401 Unauthorized",
                        "{\"error\":\"Missing or invalid token\"}");
                return;
            }

            String accept = Base64.getEncoder().encodeToString(
                    sha1((handshake.secWebSocketKey + WS_MAGIC_GUID)
                            .getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            userId = ctx.userId();
            connection = new Connection(socket, out);
            connectionsByUser.computeIfAbsent(userId, id -> new CopyOnWriteArraySet<>())
                    .add(connection);
            connectionCount.incrementAndGet();

            readFrames(in, connection);
        } catch (IOException ignored) {
            // Normal disconnect.
        } finally {
            if (connection != null) {
                Set<Connection> conns = connectionsByUser.get(userId);
                if (conns != null) {
                    conns.remove(connection);
                    if (conns.isEmpty()) {
                        connectionsByUser.remove(userId, Set.of());
                    }
                }
                connectionCount.decrementAndGet();
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Frame loop: answer pings, honor close; inbound text is ignored. */
    private void readFrames(InputStream in, Connection connection) throws IOException {
        while (true) {
            int b0 = in.read();
            if (b0 == -1) {
                return;
            }
            int opcode = b0 & 0x0F;
            int b1 = in.read();
            boolean masked = (b1 & 0x80) != 0;
            long length = b1 & 0x7F;
            if (length == 126) {
                length = ((long) in.read() << 8) | in.read();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | in.read();
                }
            }
            byte[] mask = new byte[4];
            if (masked) {
                in.readNBytes(mask, 0, 4);
            }
            byte[] payload = in.readNBytes((int) Math.min(length, 1 << 20));
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            switch (opcode) {
                case 0x8 -> { // close
                    connection.trySendFrame(0x8, payload);
                    return;
                }
                case 0x9 -> connection.trySendFrame(0xA, payload); // ping → pong
                default -> {
                    // Text/binary/pong from clients: no inbound protocol yet.
                }
            }
        }
    }

    private record Handshake(String path, String query, Map<String, String> headers,
                             String secWebSocketKey) {

        boolean isWebSocketUpgrade() {
            return secWebSocketKey != null
                    && "websocket".equalsIgnoreCase(headers.getOrDefault("upgrade", ""));
        }

        String queryParam(String name) {
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
    }

    private static Handshake readHandshake(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.US_ASCII));
        String requestLine = reader.readLine();
        if (requestLine == null || !requestLine.startsWith("GET ")) {
            return null;
        }
        String target = requestLine.split(" ")[1];
        int q = target.indexOf('?');
        String path = q == -1 ? target : target.substring(0, q);
        String query = q == -1 ? null : target.substring(q + 1);

        Map<String, String> headers = new java.util.HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).strip().toLowerCase(java.util.Locale.ROOT),
                        line.substring(colon + 1).strip());
            }
        }
        return new Handshake(path, query, headers, headers.get("sec-websocket-key"));
    }

    private static void writeHttpError(OutputStream out, String status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static byte[] sha1(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-1 is mandatory in every JRE", e);
        }
    }

    /** One live client connection; writes are serialized per connection. */
    private static final class Connection {
        private final Socket socket;
        private final OutputStream out;

        Connection(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        void trySendText(String text) {
            trySendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        synchronized void trySendFrame(int opcode, byte[] payload) {
            try {
                out.write(0x80 | opcode); // FIN + opcode
                if (payload.length <= 125) {
                    out.write(payload.length);
                } else if (payload.length <= 0xFFFF) {
                    out.write(126);
                    out.write(payload.length >> 8);
                    out.write(payload.length & 0xFF);
                } else {
                    out.write(127);
                    for (int i = 7; i >= 0; i--) {
                        out.write((int) ((long) payload.length >> (8 * i)) & 0xFF);
                    }
                }
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                try {
                    socket.close(); // reader thread cleans up the registry
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        connectionsByUser.values().forEach(set ->
                set.forEach(c -> c.trySendFrame(0x8, new byte[0])));
    }
}
