package io.kcg.sir.application.conformance;

import java.util.Objects;

/**
 * A file's content identity: byte count plus lowercase SHA-256.
 *
 * <p>Used to compare a manifest entry against the other baseline's entry for the
 * same relative path, so the B0 to B1 delta is computed from content identity
 * rather than from file timestamps or ordering.
 *
 * @param byteCount the file size in bytes
 * @param sha256Hex the 64-character lowercase hex SHA-256 of the file content
 */
public record FileDigest(long byteCount, String sha256Hex) {

    public FileDigest {
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        if (byteCount < 0L) {
            throw new IllegalArgumentException("byteCount must not be negative: " + byteCount);
        }
        if (!sha256Hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "sha256Hex must be a 64-character lowercase hexadecimal string");
        }
    }
}
