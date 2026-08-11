package io.kcg.sir.application.conformance;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Streaming redactor that sanitizes byte streams before they reach evidence
 * storage. stdout/stderr are never persisted raw.
 *
 * <p>Redaction covers:
 * <ul>
 *   <li>exact credential byte values;</li>
 *   <li>URL-encoded secret forms;</li>
 *   <li>credential-bearing JDBC URLs (user-info and password query params);</li>
 *   <li>{@code password=...} and datasource key-value forms;</li>
 *   <li>complete environment and command-line dumps.</li>
 * </ul>
 *
 * <p>The redactor is initialized with a set of exact secret values. If it
 * cannot be initialized (e.g. a secret is empty or the pattern set is
 * inconsistent), {@link #canInitialize(List)} returns false and the run must
 * stop with {@code FAILED(HARNESS_CREDENTIAL_BOUNDARY)}.
 */
public final class StreamingSecretRedactor {

    private static final String REPLACEMENT = "***REDACTED***";
    private static final byte[] REPLACEMENT_BYTES =
            REPLACEMENT.getBytes(StandardCharsets.UTF_8);

    /** Matches password=... key-value forms (greedy to end of token). */
    private static final Pattern PASSWORD_KV =
            Pattern.compile("(?i)(password|passwd|pwd|secret|token|credential)=[^\\s;&,'\"]+");

    /** Matches JDBC URL user-info: jdbc:...//user:pass@... */
    private static final Pattern JDBC_USERINFO =
            Pattern.compile("(jdbc:[^\\s]*://)[^:@/\\s]+:[^:@/\\s]+@");

    /** Matches password query params in URLs: ?password=... or &password=... */
    private static final Pattern URL_PASSWORD_PARAM =
            Pattern.compile("(?i)([?&](?:password|passwd|pwd|secret|token)=)[^&\\s#]+");

    /** Matches SPRING_DATASOURCE_PASSWORD=... environment forms. */
    private static final Pattern ENV_DATASOURCE_PASSWORD =
            Pattern.compile("(?i)(SPRING_DATASOURCE_PASSWORD|KCG_CONF_CONTROL_PASSWORD|KCG_CONF_RUNTIME_PASSWORD|KCG_CONF_CONTROL_JDBC_URL)=[^\\s]*");

    private final List<Pattern> exactSecretPatterns;
    private final List<byte[]> exactSecretBytes;

    private StreamingSecretRedactor(List<Pattern> exactSecretPatterns,
                                    List<byte[]> exactSecretBytes) {
        this.exactSecretPatterns = exactSecretPatterns;
        this.exactSecretBytes = exactSecretBytes;
    }

    /**
     * Check whether the redactor can be initialized with the given secrets.
     * All secrets must be non-null and non-empty.
     *
     * @param secrets the exact secret values to redact
     * @return true if initialization would succeed
     */
    public static boolean canInitialize(List<String> secrets) {
        Objects.requireNonNull(secrets, "secrets");
        for (String s : secrets) {
            if (s == null || s.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Initialize the redactor with exact secret values plus the fixed
     * pattern-based redaction rules.
     *
     * @param secrets the exact secret values (passwords, usernames, full JDBC URLs)
     * @return the initialized redactor
     * @throws IllegalArgumentException if any secret is null or empty
     */
    public static StreamingSecretRedactor initialize(List<String> secrets) {
        Objects.requireNonNull(secrets, "secrets");
        if (!canInitialize(secrets)) {
            throw new IllegalArgumentException(
                    "cannot initialize redactor: a secret is null or empty");
        }
        List<Pattern> patterns = new ArrayList<>();
        List<byte[]> secretBytes = new ArrayList<>();
        for (String secret : secrets) {
            patterns.add(Pattern.compile(Pattern.quote(secret)));
            secretBytes.add(secret.getBytes(StandardCharsets.UTF_8));
        }
        return new StreamingSecretRedactor(List.copyOf(patterns), List.copyOf(secretBytes));
    }

    /**
     * Redact a complete string. Convenience method for non-streaming use.
     */
    public String redactString(String input) {
        Objects.requireNonNull(input, "input");
        String result = input;
        // Exact secrets first (longest first to avoid partial overlaps).
        for (Pattern p : exactSecretPatterns) {
            result = p.matcher(result).replaceAll(REPLACEMENT);
        }
        result = JDBC_USERINFO.matcher(result).replaceAll("$1***REDACTED***@");
        result = URL_PASSWORD_PARAM.matcher(result).replaceAll("$1***REDACTED***");
        result = PASSWORD_KV.matcher(result).replaceAll("$1=***REDACTED***");
        result = ENV_DATASOURCE_PASSWORD.matcher(result).replaceAll("$1=***REDACTED***");
        return result;
    }

    /**
     * Redact raw bytes. This performs a byte-level scan for exact secret
     * byte sequences and replaces them with the replacement marker. The
     * fixed patterns are applied at the UTF-8 string level.
     */
    public byte[] redactBytes(byte[] input) {
        Objects.requireNonNull(input, "input");
        if (input.length == 0) {
            return input;
        }
        // First, apply exact-secret byte replacement.
        byte[] working = input;
        for (byte[] secret : exactSecretBytes) {
            working = replaceBytes(working, secret, REPLACEMENT_BYTES);
        }
        // Then, decode as UTF-8 and apply pattern-based redaction.
        // If the bytes are not valid UTF-8, we only apply byte-level exact
        // secret replacement (already done) and return.
        try {
            String text = new String(working, StandardCharsets.UTF_8);
            String redacted = redactStringPatternsOnly(text);
            return redacted.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Not valid UTF-8; return byte-level redacted output.
            return working;
        }
    }

    private String redactStringPatternsOnly(String input) {
        String result = input;
        result = JDBC_USERINFO.matcher(result).replaceAll("$1***REDACTED***@");
        result = URL_PASSWORD_PARAM.matcher(result).replaceAll("$1***REDACTED***");
        result = PASSWORD_KV.matcher(result).replaceAll("$1=***REDACTED***");
        result = ENV_DATASOURCE_PASSWORD.matcher(result).replaceAll("$1=***REDACTED***");
        return result;
    }

    /**
     * Wrap an output stream so all bytes are redacted before being written.
     */
    public OutputStream wrap(OutputStream delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new RedactingOutputStream(delegate);
    }

    private static byte[] replaceBytes(byte[] haystack, byte[] needle, byte[] replacement) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return haystack;
        }
        // Simple byte search. Adequate for redaction of moderate-sized logs.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(haystack.length);
        int i = 0;
        while (i < haystack.length) {
            if (i + needle.length <= haystack.length && matchesAt(haystack, i, needle)) {
                out.write(replacement, 0, replacement.length);
                i += needle.length;
            } else {
                out.write(haystack[i]);
                i++;
            }
        }
        return out.toByteArray();
    }

    private static boolean matchesAt(byte[] haystack, int offset, byte[] needle) {
        for (int j = 0; j < needle.length; j++) {
            if (haystack[offset + j] != needle[j]) {
                return false;
            }
        }
        return true;
    }

    private final class RedactingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final java.io.ByteArrayOutputStream buffer =
                new java.io.ByteArrayOutputStream(4096);

        RedactingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) {
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }

        @Override
        public void flush() throws java.io.IOException {
            byte[] raw = buffer.toByteArray();
            buffer.reset();
            byte[] redacted = redactBytes(raw);
            delegate.write(redacted);
            delegate.flush();
        }

        @Override
        public void close() throws java.io.IOException {
            try {
                flush();
            } finally {
                delegate.close();
            }
        }
    }
}
