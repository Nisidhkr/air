package p2p.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * PeerLink binary wire protocol, version 1.
 *
 * <p>Every message is a length-prefixed frame. Bulk file data is the single
 * exception: it is sent raw (unframed) between a {@link #MSG_DATA_START} and a
 * {@link #MSG_COMPLETE} frame so the sender can use zero-copy
 * {@code FileChannel.transferTo}.
 *
 * <pre>
 * Frame layout (all integers big-endian):
 *   +---------+----------+--------+----------------+----------------+
 *   | MAGIC   | VERSION  | TYPE   | PAYLOAD_LENGTH | PAYLOAD        |
 *   | 4 bytes | 1 byte   | 1 byte | 4 bytes        | variable       |
 *   +---------+----------+--------+----------------+----------------+
 * </pre>
 */
public final class PeerLinkProtocol {

    private PeerLinkProtocol() {
    }

    /** "PLNK" in ASCII. */
    public static final int MAGIC = 0x504C4E4B;
    public static final byte VERSION = 1;
    public static final int FRAME_HEADER_BYTES = 4 + 1 + 1 + 4;

    /** Control frames are small; anything larger indicates a corrupt or hostile stream. */
    public static final int MAX_PAYLOAD_BYTES = 1 << 20;

    // Client -> server: authentication token. Payload: string token.
    public static final byte MSG_HELLO = 0x01;
    // Server -> client: file metadata. Payload: see TransferManifest.
    public static final byte MSG_MANIFEST = 0x02;
    // Client -> server: request byte range. Payload: offset(8), length(8), -1 length = to EOF.
    public static final byte MSG_RANGE_REQUEST = 0x03;
    // Server -> client: raw data follows. Payload: offset(8), length(8).
    public static final byte MSG_DATA_START = 0x04;
    // Client -> server: request per-chunk CRC32C checksums. Payload: firstChunk(8), count(4).
    public static final byte MSG_CHUNK_CHECKSUM_REQUEST = 0x05;
    // Server -> client: checksum list. Payload: firstChunk(8), count(4), count x crc32c(4).
    public static final byte MSG_CHUNK_CHECKSUMS = 0x06;
    // Server -> client: transfer completion marker. Payload: bytesSent(8).
    public static final byte MSG_COMPLETE = 0x07;
    // Either direction: fatal error. Payload: code(2), string message.
    public static final byte MSG_ERROR = 0x7F;

    public static final short ERR_AUTH_FAILED = 1;
    public static final short ERR_INVALID_RANGE = 2;
    public static final short ERR_NOT_FOUND = 3;
    public static final short ERR_INTERNAL = 4;
    public static final short ERR_BAD_REQUEST = 5;

    public record Frame(byte type, ByteBuffer payload) {
    }

    public static void writeFrame(WritableByteChannel channel, byte type, ByteBuffer payload) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(FRAME_HEADER_BYTES);
        header.putInt(MAGIC).put(VERSION).put(type).putInt(payload.remaining()).flip();
        writeFully(channel, header);
        writeFully(channel, payload);
    }

    public static void writeError(WritableByteChannel channel, short code, String message) throws IOException {
        ByteBuffer payload = ByteBuffer.allocate(2 + stringSize(message));
        payload.putShort(code);
        putString(payload, message);
        payload.flip();
        writeFrame(channel, MSG_ERROR, payload);
    }

    public static Frame readFrame(ReadableByteChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(FRAME_HEADER_BYTES);
        readFully(channel, header);
        header.flip();
        if (header.getInt() != MAGIC) {
            throw new IOException("Bad protocol magic; peer is not speaking PeerLink v" + VERSION);
        }
        byte version = header.get();
        if (version != VERSION) {
            throw new IOException("Unsupported protocol version: " + version);
        }
        byte type = header.get();
        int length = header.getInt();
        if (length < 0 || length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Invalid frame payload length: " + length);
        }
        ByteBuffer payload = ByteBuffer.allocate(length);
        readFully(channel, payload);
        payload.flip();
        return new Frame(type, payload);
    }

    /**
     * Reads a frame and converts an ERROR frame into a {@link TransferException}.
     */
    public static Frame readFrameExpecting(ReadableByteChannel channel, byte expectedType) throws IOException {
        Frame frame = readFrame(channel);
        if (frame.type() == MSG_ERROR) {
            short code = frame.payload().getShort();
            throw new TransferException(code, getString(frame.payload()));
        }
        if (frame.type() != expectedType) {
            throw new IOException("Expected frame type " + expectedType + " but got " + frame.type());
        }
        return frame;
    }

    public static void readFully(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new EOFException("Connection closed mid-message");
            }
        }
    }

    public static void writeFully(WritableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    public static void putString(ByteBuffer buffer, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) {
            throw new IllegalArgumentException("String too long for protocol: " + bytes.length);
        }
        buffer.putShort((short) bytes.length);
        buffer.put(bytes);
    }

    public static String getString(ByteBuffer buffer) {
        int length = buffer.getShort() & 0xFFFF;
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static int stringSize(String value) {
        return 2 + value.getBytes(StandardCharsets.UTF_8).length;
    }
}
