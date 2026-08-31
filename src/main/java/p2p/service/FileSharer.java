package p2p.service;

import p2p.security.TransferTokens;
import p2p.transfer.FileSender;
import p2p.transfer.TransferConfig;
import p2p.utils.UploadUtils;

import java.io.Closeable;
import java.io.IOException;
import java.net.BindException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of currently shared files. Each offered file gets its own
 * {@link FileSender} (one port, one token); a sender keeps serving any number
 * of concurrent clients until the share is stopped.
 */
public class FileSharer implements Closeable {

    public record Offer(int port, String token) {
    }

    /** Everything the LAN mode needs to point a peer at this share. */
    public record ShareInfo(int port, String token, Path path, String fileName, long size,
                            String relativePath) {
    }

    private record Share(FileSender sender, String token, Path path, String relativePath,
                         String fileName) {
    }

    private static final int BIND_ATTEMPTS = 20;

    private final ConcurrentHashMap<Integer, Share> shares = new ConcurrentHashMap<>();
    private final TransferConfig config;

    public FileSharer() {
        this(TransferConfig.defaults());
    }

    public FileSharer(TransferConfig config) {
        this.config = config;
    }

    /** Starts sharing {@code file}; returns the port to connect to and the access token. */
    public Offer offer(Path file) throws IOException {
        return offer(file, null);
    }

    /**
     * Starts sharing {@code file}; {@code relativePath} (e.g. {@code photos/2024/a.jpg})
     * is carried into LAN offers so folder uploads keep their structure on the receiver.
     */
    public Offer offer(Path file, String relativePath) throws IOException {
        String token = TransferTokens.generate();
        String displayName = stripUploadPrefix(file.getFileName().toString());
        BindException lastBindFailure = null;
        for (int attempt = 0; attempt < BIND_ATTEMPTS; attempt++) {
            int port = UploadUtils.generateCode();
            if (shares.containsKey(port)) {
                continue;
            }
            try {
                FileSender sender = new FileSender(file, displayName, token, config, port);
                if (shares.putIfAbsent(port,
                        new Share(sender, token, file, relativePath, displayName)) != null) {
                    sender.close();
                    continue;
                }
                sender.start();
                return new Offer(port, token);
            } catch (BindException e) {
                lastBindFailure = e; // port taken by another process; try another
            }
        }
        throw new IOException("Could not bind a sharing port after " + BIND_ATTEMPTS + " attempts",
                lastBindFailure);
    }

    /** Uploads are stored as {@code <uuid>_<original name>}; advertise the original. */
    private static String stripUploadPrefix(String storedName) {
        int underscore = storedName.indexOf('_');
        return underscore == 36 ? storedName.substring(underscore + 1) : storedName;
    }

    /** Metadata of an active share, for handing to a LAN peer. */
    public java.util.Optional<ShareInfo> shareInfo(int port) {
        Share share = shares.get(port);
        if (share == null) {
            return java.util.Optional.empty();
        }
        long size;
        try {
            size = java.nio.file.Files.size(share.path());
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ShareInfo(port, share.token(), share.path(),
                share.fileName(), size,
                share.relativePath() != null ? share.relativePath() : share.fileName()));
    }

    public void stopSharing(int port) {
        Share share = shares.remove(port);
        if (share != null) {
            try {
                share.sender().close();
            } catch (IOException e) {
                System.err.println("Error closing sender on port " + port + ": " + e.getMessage());
            }
        }
    }

    /**
     * @deprecated kept for source compatibility; use {@link #offer(Path)} which
     * also returns the access token required by protocol v1.
     */
    @Deprecated
    public int offerFile(String filePath) {
        try {
            return offer(Path.of(filePath)).port();
        } catch (IOException e) {
            throw new RuntimeException("Failed to offer file: " + filePath, e);
        }
    }

    /**
     * @deprecated the server now starts inside {@link #offer(Path)} and serves
     * multiple clients; this method is a no-op.
     */
    @Deprecated
    public void startFileServer(int port) {
        // Intentionally empty.
    }

    @Override
    public void close() {
        shares.keySet().forEach(this::stopSharing);
    }
}
