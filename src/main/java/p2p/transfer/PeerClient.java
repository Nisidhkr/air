package p2p.transfer;

import p2p.protocol.TransferManifest;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static p2p.protocol.PeerLinkProtocol.*;

/**
 * One authenticated protocol connection to a {@link FileSender}. Thin enough
 * to be used both by {@link FileReceiver} (download to disk) and by the HTTP
 * gateway (stream straight to a browser response).
 */
public final class PeerClient implements Closeable {

    private final SocketChannel channel;
    private final TransferManifest manifest;

    private PeerClient(SocketChannel channel, TransferManifest manifest) {
        this.channel = channel;
        this.manifest = manifest;
    }

    public static PeerClient connect(InetSocketAddress address, String token, TransferConfig config)
            throws IOException {
        SocketChannel channel = SocketChannel.open();
        try {
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            if (config.socketReceiveBufferBytes() > 0) {
                channel.setOption(StandardSocketOptions.SO_RCVBUF, config.socketReceiveBufferBytes());
            }
            channel.socket().connect(address, (int) config.connectTimeout().toMillis());

            ByteBuffer hello = ByteBuffer.allocate(stringSize(token));
            putString(hello, token);
            hello.flip();
            writeFrame(channel, MSG_HELLO, hello);

            Frame frame = readFrameExpecting(channel, MSG_MANIFEST);
            return new PeerClient(channel, TransferManifest.decode(frame.payload()));
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    public TransferManifest manifest() {
        return manifest;
    }

    /**
     * Requests {@code [offset, offset+length)} ({@code length == -1} means to
     * EOF) and returns the byte count the sender will stream. After this call
     * exactly that many raw bytes follow on {@link #channel()}, then a
     * COMPLETE frame readable via {@link #readComplete()}.
     */
    public long requestRange(long offset, long length) throws IOException {
        ByteBuffer request = ByteBuffer.allocate(16);
        request.putLong(offset).putLong(length).flip();
        writeFrame(channel, MSG_RANGE_REQUEST, request);

        Frame start = readFrameExpecting(channel, MSG_DATA_START);
        long actualOffset = start.payload().getLong();
        long actualLength = start.payload().getLong();
        if (actualOffset != offset) {
            throw new IOException("Sender started at offset " + actualOffset + ", requested " + offset);
        }
        return actualLength;
    }

    public long readComplete() throws IOException {
        Frame complete = readFrameExpecting(channel, MSG_COMPLETE);
        return complete.payload().getLong();
    }

    /** CRC32C values for {@code count} chunks starting at {@code firstChunk}. */
    public int[] fetchChunkChecksums(long firstChunk, int count) throws IOException {
        ByteBuffer request = ByteBuffer.allocate(12);
        request.putLong(firstChunk).putInt(count).flip();
        writeFrame(channel, MSG_CHUNK_CHECKSUM_REQUEST, request);

        Frame response = readFrameExpecting(channel, MSG_CHUNK_CHECKSUMS);
        ByteBuffer payload = response.payload();
        long responseFirst = payload.getLong();
        int responseCount = payload.getInt();
        if (responseFirst != firstChunk || responseCount != count) {
            throw new IOException("Checksum response does not match request");
        }
        int[] checksums = new int[count];
        for (int i = 0; i < count; i++) {
            checksums[i] = payload.getInt();
        }
        return checksums;
    }

    public SocketChannel channel() {
        return channel;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
