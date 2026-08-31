package p2p.protocol;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Immutable description of an offered file, exchanged as the payload of a
 * {@code MSG_MANIFEST} frame.
 *
 * <pre>
 * Payload layout:
 *   transferId      16 bytes (UUID, two big-endian longs)
 *   filename        2-byte length + UTF-8 bytes
 *   fileSize        8 bytes (signed long, bytes)
 *   chunkSize       4 bytes (checksum granularity, bytes)
 *   sha256Length    1 byte  (0 if hash not yet available, else 32)
 *   sha256          sha256Length bytes
 *   metadataLength  2 bytes
 *   metadata        metadataLength bytes (application-defined, e.g. JSON)
 * </pre>
 */
public record TransferManifest(
        UUID transferId,
        String filename,
        long fileSize,
        int chunkSize,
        byte[] sha256,
        byte[] metadata) {

    public static final int SHA256_LENGTH = 32;

    public TransferManifest {
        if (fileSize < 0) {
            throw new IllegalArgumentException("fileSize must be >= 0: " + fileSize);
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0: " + chunkSize);
        }
        if (sha256 != null && sha256.length != SHA256_LENGTH) {
            throw new IllegalArgumentException("sha256 must be 32 bytes");
        }
        if (metadata == null) {
            metadata = new byte[0];
        }
    }

    public long chunkCount() {
        return (fileSize + chunkSize - 1) / (long) chunkSize;
    }

    public boolean hasHash() {
        return sha256 != null;
    }

    public ByteBuffer encode() {
        int hashLength = sha256 == null ? 0 : SHA256_LENGTH;
        ByteBuffer buffer = ByteBuffer.allocate(
                16 + PeerLinkProtocol.stringSize(filename) + 8 + 4 + 1 + hashLength + 2 + metadata.length);
        buffer.putLong(transferId.getMostSignificantBits());
        buffer.putLong(transferId.getLeastSignificantBits());
        PeerLinkProtocol.putString(buffer, filename);
        buffer.putLong(fileSize);
        buffer.putInt(chunkSize);
        buffer.put((byte) hashLength);
        if (sha256 != null) {
            buffer.put(sha256);
        }
        buffer.putShort((short) metadata.length);
        buffer.put(metadata);
        buffer.flip();
        return buffer;
    }

    public static TransferManifest decode(ByteBuffer buffer) {
        UUID transferId = new UUID(buffer.getLong(), buffer.getLong());
        String filename = PeerLinkProtocol.getString(buffer);
        long fileSize = buffer.getLong();
        int chunkSize = buffer.getInt();
        int hashLength = buffer.get() & 0xFF;
        byte[] sha256 = null;
        if (hashLength == SHA256_LENGTH) {
            sha256 = new byte[SHA256_LENGTH];
            buffer.get(sha256);
        } else if (hashLength != 0) {
            throw new IllegalArgumentException("Unexpected hash length: " + hashLength);
        }
        int metadataLength = buffer.getShort() & 0xFFFF;
        byte[] metadata = new byte[metadataLength];
        buffer.get(metadata);
        return new TransferManifest(transferId, filename, fileSize, chunkSize, sha256, metadata);
    }
}
