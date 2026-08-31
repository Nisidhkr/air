package p2p.utils;

import org.apache.commons.fileupload.MultipartStream;
import p2p.transfer.FileReceiver;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ONE multipart-upload implementation, shared by every HTTP entry point
 * that accepts a file (Direct Share upload, Link Share upload). Streams the
 * first file part to disk through a fixed buffer — constant memory for any
 * file size.
 */
public final class MultipartUploads {

    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final Pattern FILENAME_PATTERN = Pattern.compile("filename=\"([^\"]*)\"");

    private MultipartUploads() {
    }

    /**
     * Streams the first file part of {@code body} into {@code targetDir} as
     * {@code <uuid>_<sanitized original name>}; null when no file part exists.
     */
    public static Path streamFirstFileToDir(InputStream body, String boundary, Path targetDir)
            throws IOException {
        return streamFirstFileToDir(body, boundary, targetDir, null, null);
    }

    /**
     * Same, with plan enforcement hooks on the ingress loop: {@code throttle}
     * paces byte acceptance (FREE = 2 MB/s; PREMIUM's unlimited throttle is a
     * no-op) and {@code progress} receives the cumulative byte count for the
     * live progress stream. This is THE upload chunk loop — bytes are delayed
     * before they are accepted, not after.
     */
    public static Path streamFirstFileToDir(InputStream body, String boundary, Path targetDir,
                                            p2p.transfer.UploadThrottle throttle,
                                            java.util.function.LongConsumer progress)
            throws IOException {
        MultipartStream multipart = new MultipartStream(
                body, boundary.getBytes(StandardCharsets.ISO_8859_1), COPY_BUFFER_BYTES, null);
        Path savedFile = null;
        boolean hasNext = multipart.skipPreamble();
        while (hasNext) {
            String partHeaders = multipart.readHeaders();
            Matcher matcher = FILENAME_PATTERN.matcher(partHeaders);
            if (savedFile == null && matcher.find()) {
                String safeName = FileReceiver.sanitizeFilename(matcher.group(1));
                Path destination = targetDir.resolve(UUID.randomUUID() + "_" + safeName);
                try (OutputStream out = new BufferedOutputStream(
                        wrap(Files.newOutputStream(destination), throttle, progress),
                        COPY_BUFFER_BYTES)) {
                    multipart.readBodyData(out);
                }
                savedFile = destination;
            } else {
                multipart.discardBodyData();
            }
            hasNext = multipart.readBoundary();
        }
        return savedFile;
    }

    /** Applies throttle.acquire(chunk) BEFORE each chunk is accepted. */
    private static OutputStream wrap(OutputStream out,
                                     p2p.transfer.UploadThrottle throttle,
                                     java.util.function.LongConsumer progress) {
        if (throttle == null && progress == null) {
            return out;
        }
        return new java.io.FilterOutputStream(out) {
            private long total;

            @Override
            public void write(byte[] chunk, int off, int len) throws IOException {
                if (throttle != null) {
                    try {
                        throttle.acquire(len);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Upload interrupted", e);
                    }
                }
                out.write(chunk, off, len);
                total += len;
                if (progress != null) {
                    progress.accept(total);
                }
            }

            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }
        };
    }

    /** Extracts the boundary parameter from a multipart/form-data Content-Type. */
    public static String extractBoundary(String contentType) {
        int index = contentType.indexOf("boundary=");
        if (index == -1) {
            return null;
        }
        String boundary = contentType.substring(index + "boundary=".length());
        int semicolon = boundary.indexOf(';');
        if (semicolon != -1) {
            boundary = boundary.substring(0, semicolon);
        }
        boundary = boundary.trim();
        if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }
        return boundary.isEmpty() ? null : boundary;
    }

    /** Uploads are stored as {@code <uuid>_<original name>}; recover the original. */
    public static String stripUploadPrefix(String storedName) {
        int underscore = storedName.indexOf('_');
        return underscore == 36 ? storedName.substring(underscore + 1) : storedName;
    }
}
