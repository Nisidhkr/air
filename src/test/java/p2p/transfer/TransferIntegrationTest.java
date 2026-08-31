package p2p.transfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import p2p.protocol.TransferException;
import p2p.protocol.TransferManifest;
import p2p.util.Hashing;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferIntegrationTest {

    private static final int FILE_SIZE = 24 * 1024 * 1024 + 12345; // deliberately not chunk-aligned

    @TempDir
    Path sourceDir;
    @TempDir
    Path downloadDir;

    private Path sourceFile;
    private FileSender sender;
    private String token;
    private final TransferConfig config = new TransferConfig(
            0, 0, 3, 1 << 20, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(30), 2);

    @BeforeEach
    void setUp() throws IOException {
        sourceFile = sourceDir.resolve("big file.bin");
        byte[] data = new byte[FILE_SIZE];
        new Random(42).nextBytes(data);
        Files.write(sourceFile, data);
        token = p2p.security.TransferTokens.generate();
        sender = new FileSender(sourceFile, token, config, 0);
        sender.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        sender.close();
    }

    private FileReceiver receiver() throws IOException {
        return new FileReceiver("localhost", sender.port(), token, downloadDir, config);
    }

    @Test
    void parallelRoundTripPreservesContent() throws IOException {
        Path result = receiver().download(null);

        assertEquals("big file.bin", result.getFileName().toString());
        assertArrayEquals(Hashing.sha256(sourceFile), Hashing.sha256(result));
        assertTrue(Files.notExists(downloadDir.resolve("big file.bin.part")));
        assertTrue(Files.notExists(downloadDir.resolve("big file.bin.resume")));
    }

    @Test
    void resumesFromExactOffsetWithoutRefetchingBytes() throws IOException {
        // Simulate an interrupted download: first 10 MB already on disk,
        // resume metadata recording exactly that.
        TransferManifest manifest;
        try (PeerClient probe = PeerClient.connect(
                new InetSocketAddress("localhost", sender.port()), token, config)) {
            manifest = probe.manifest();
        }
        long alreadyReceived = 10L * 1024 * 1024;
        Path partFile = downloadDir.resolve("big file.bin.part");
        Path resumeFile = downloadDir.resolve("big file.bin.resume");
        byte[] source = Files.readAllBytes(sourceFile);
        try (FileChannel channel = FileChannel.open(partFile,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(source, 0, (int) alreadyReceived), 0);
            channel.write(ByteBuffer.allocate(1), FILE_SIZE - 1);
        }
        ResumeState state = ResumeState.forManifest(manifest, 1);
        state.segments().get(0).addReceived(alreadyReceived);
        state.save(resumeFile);

        FileReceiver receiver = new FileReceiver("localhost", sender.port(), token, downloadDir,
                config.withParallelConnections(1));
        long[] firstReportedTransferred = {-1};
        Path result = receiver.download(snapshot -> {
            if (firstReportedTransferred[0] == -1) {
                firstReportedTransferred[0] = snapshot.transferredBytes();
            }
        });

        assertArrayEquals(Hashing.sha256(sourceFile), Hashing.sha256(result));
        // Progress started at >= 10 MB: the first 10 MB were not re-downloaded.
        assertTrue(firstReportedTransferred[0] == -1 || firstReportedTransferred[0] >= alreadyReceived,
                "expected progress to start at the resume offset");
    }

    @Test
    void corruptedChunksAreDetectedAndRepaired() throws IOException {
        // A "complete" partial download whose middle was corrupted on disk.
        TransferManifest manifest;
        try (PeerClient probe = PeerClient.connect(
                new InetSocketAddress("localhost", sender.port()), token, config)) {
            manifest = probe.manifest();
        }
        Path partFile = downloadDir.resolve("big file.bin.part");
        Path resumeFile = downloadDir.resolve("big file.bin.resume");
        byte[] corrupted = Files.readAllBytes(sourceFile);
        for (int i = 5 * 1024 * 1024; i < 5 * 1024 * 1024 + 4096; i++) {
            corrupted[i] ^= 0x5A;
        }
        Files.write(partFile, corrupted);
        ResumeState state = ResumeState.forManifest(manifest, 1);
        state.segments().get(0).addReceived(FILE_SIZE);
        state.save(resumeFile);

        Path result = new FileReceiver("localhost", sender.port(), token, downloadDir,
                config.withParallelConnections(1)).download(null);

        assertArrayEquals(Hashing.sha256(sourceFile), Hashing.sha256(result));
    }

    @Test
    void invalidTokenIsRejectedBeforeAnyMetadata() {
        TransferException e = assertThrows(TransferException.class, () ->
                PeerClient.connect(new InetSocketAddress("localhost", sender.port()),
                        "wrong-token", config));
        assertEquals(p2p.protocol.PeerLinkProtocol.ERR_AUTH_FAILED, e.code());
    }

    @Test
    void filenameSanitizationBlocksPathTraversal() {
        assertEquals("passwd", FileReceiver.sanitizeFilename("../../etc/passwd"));
        assertEquals("evil.exe", FileReceiver.sanitizeFilename("..\\..\\windows\\evil.exe"));
        assertEquals("unnamed-file", FileReceiver.sanitizeFilename(".."));
        assertEquals("normal.txt", FileReceiver.sanitizeFilename("normal.txt"));
    }
}
