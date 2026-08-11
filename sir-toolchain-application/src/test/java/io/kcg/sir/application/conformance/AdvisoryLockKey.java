package io.kcg.sir.application.conformance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/**
 * Derives the server-scoped MySQL advisory-lock name for a conformance run.
 *
 * <p>The lock name is the complete lowercase SHA-256 hex of an unambiguous
 * length-framed encoding of:
 *
 * <pre>
 * domain  = KCG-CONFORMANCE-SCHEMA-LOCK-V1
 * server  = verified server UUID (UTF-8 bytes)
 * schema  = exact SchemaName (ASCII bytes)
 * </pre>
 *
 * <p>Length-framing prevents concatenation ambiguity. Hostname, port, JDBC
 * URL spelling, Locale, and case conversion do not participate.
 */
public final class AdvisoryLockKey {

    static final String DOMAIN = "KCG-CONFORMANCE-SCHEMA-LOCK-V1";

    private final String lockName;

    private AdvisoryLockKey(String lockName) {
        this.lockName = lockName;
    }

    /**
     * Derive the advisory lock name from the verified server UUID and the
     * exact schema name.
     *
     * @param verifiedServerUuid the MySQL server UUID verified by the control
     *                           connection (must not be null or blank)
     * @param schemaName         the exact validated schema name
     * @return the lock key holder
     */
    public static AdvisoryLockKey derive(String verifiedServerUuid, SchemaName schemaName) {
        Objects.requireNonNull(verifiedServerUuid, "verifiedServerUuid");
        Objects.requireNonNull(schemaName, "schemaName");
        if (verifiedServerUuid.isBlank()) {
            throw new IllegalArgumentException("verifiedServerUuid must not be blank");
        }

        byte[] domainBytes = DOMAIN.getBytes(StandardCharsets.US_ASCII);
        byte[] uuidBytes = verifiedServerUuid.getBytes(StandardCharsets.UTF_8);
        byte[] schemaBytes = schemaName.asciiBytes();

        MessageDigest md = newSha256();
        updateLengthFramed(md, domainBytes);
        updateLengthFramed(md, uuidBytes);
        updateLengthFramed(md, schemaBytes);
        byte[] digest = md.digest();

        return new AdvisoryLockKey(toLowerHex(digest));
    }

    /**
     * @return the full lowercase SHA-256 hex lock name (64 chars)
     */
    public String lockName() {
        return lockName;
    }

    private static void updateLengthFramed(MessageDigest md, byte[] bytes) {
        md.update(intToAsciiBytes(bytes.length));
        md.update((byte) ':');
        md.update(bytes);
        md.update((byte) ';');
    }

    private static byte[] intToAsciiBytes(int value) {
        return Integer.toString(value, 10).getBytes(StandardCharsets.US_ASCII);
    }

    private static String toLowerHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            int v = b & 0xFF;
            if (v < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AdvisoryLockKey other)) {
            return false;
        }
        return lockName.equals(other.lockName);
    }

    @Override
    public int hashCode() {
        return lockName.hashCode();
    }

    @Override
    public String toString() {
        return lockName;
    }
}