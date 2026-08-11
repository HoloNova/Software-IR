package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;

/**
 * Loads conformance fixture resources (SIR sources, DDL, seed SQL) and
 * computes stable digests. Existing SIR resources are reused by exact
 * resource path; new conformance resources (DDL, seed) are added under
 * {@code src/test/resources/conformance/}.
 */
public final class ConformanceFixtures {

    private ConformanceFixtures() {
    }

    /**
     * Read a classpath resource as UTF-8 text.
     */
    public static String readResource(String resourcePath) throws IOException {
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (var stream = ConformanceFixtures.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IOException("missing resource: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Compute the SHA-256 hex digest of a resource.
     */
    public static String resourceDigest(String resourcePath) throws IOException {
        return sha256Hex(readResource(resourcePath).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Compute the SHA-256 hex digest of a file.
     */
    public static String fileDigest(Path file) throws IOException {
        return sha256Hex(java.nio.file.Files.readAllBytes(file));
    }

    /**
     * Write a resource to a file (used to materialize SIR sources for
     * compilation).
     */
    public static Path writeResourceTo(String resourcePath, Path targetDir,
                                       String filename) throws IOException {
        Objects.requireNonNull(targetDir, "targetDir");
        Objects.requireNonNull(filename, "filename");
        String content = readResource(resourcePath);
        Path target = targetDir.resolve(filename);
        java.nio.file.Files.writeString(target, content, StandardCharsets.UTF_8);
        return target.toAbsolutePath();
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                int v = b & 0xFF;
                if (v < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}