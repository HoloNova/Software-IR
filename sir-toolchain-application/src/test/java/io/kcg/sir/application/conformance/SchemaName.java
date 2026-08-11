package io.kcg.sir.application.conformance;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, strictly-validated MySQL schema name for the conformance harness.
 *
 * <p>Accepts only a complete match of {@code \Akcg_conf_[a-z0-9_]{1,55}\z}.
 * The constructor is not public; the only entry point is {@link #parse(String)}.
 *
 * <p>Parsing performs no trim, Unicode normalization, URL decoding, case
 * folding, lowercasing, or automatic prefix repair. Java character length,
 * UTF-8 byte length, and ASCII byte length must all agree (the accepted
 * character set is pure ASCII so all three are identical).
 *
 * <p>Total length is therefore 10 to 64 ASCII bytes: the 9-byte prefix
 * {@code kcg_conf_} plus a 1-to-55 byte suffix.
 *
 * <p>This value object is independent from SQL rendering ({@link SchemaSqlRenderer})
 * and from JDBC URL construction. A JDBC URL is never parsed back into schema
 * authority.
 */
public final class SchemaName {

    private static final Pattern PATTERN =
            Pattern.compile("\\Akcg_conf_[a-z0-9_]{1,55}\\z");

    private final String value;

    private SchemaName(String value) {
        this.value = value;
    }

    /**
     * Parse a raw input string into a {@link SchemaName}.
     *
     * @param raw the raw input; must not be null
     * @return the immutable schema name
     * @throws NullPointerException if raw is null
     * @throws IllegalArgumentException if raw does not completely match the
     *         required pattern (after no transformation whatsoever)
     */
    public static SchemaName parse(String raw) {
        Objects.requireNonNull(raw, "raw schema name must not be null");
        if (!PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException(
                    "schema name must match kcg_conf_[a-z0-9_]{1,55}: rejected");
        }
        // The accepted charset is pure ASCII lowercase letters, digits,
        // underscore, and the fixed prefix. Java char length, UTF-8 byte
        // length, and ASCII byte length are all identical for this input.
        // No further normalization is performed.
        return new SchemaName(raw);
    }

    /**
     * @return the exact validated schema name string (never null, never modified)
     */
    public String value() {
        return value;
    }

    /**
     * @return the exact ASCII bytes of the schema name
     */
    public byte[] asciiBytes() {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SchemaName other)) {
            return false;
        }
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}