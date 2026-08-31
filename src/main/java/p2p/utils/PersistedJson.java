package p2p.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The ONE ObjectMapper configuration for file-backed repositories
 * (java.time support, ISO-8601 timestamps) plus the shared atomic-write
 * helper (temp file + move) they all use.
 */
public final class PersistedJson {

    private PersistedJson() {
    }

    public static ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Atomically replaces {@code file} with the serialized {@code value}. */
    public static void writeAtomic(ObjectMapper mapper, Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Could not persist " + file.getFileName() + ": " + e.getMessage());
        }
    }
}
