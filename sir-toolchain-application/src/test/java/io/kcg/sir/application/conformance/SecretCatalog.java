package io.kcg.sir.application.conformance;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Single canonical secret-representation mechanism shared by
 * {@link StreamingSecretRedactor} and {@link EvidenceSecretScanner}.
 *
 * <p>Both the streaming redactor and the final scanner consume the same
 * byte-level and string-level representations from this catalog, ensuring
 * that no secret form is missed by one component but caught by the other.
 *
 * <p>The catalog includes:
 * <ul>
 *   <li>control username, control password;</li>
 *   <li>runtime username, runtime password;</li>
 *   <li>complete control JDBC URL;</li>
 *   <li>typed runtime JDBC URL (rendered form);</li>
 *   <li>raw UTF-8 byte forms of every secret string;</li>
 *   <li>correctly percent-encoded forms (via {@link URLEncoder});</li>
 *   <li>JDBC URL user-info forms ({@code user:pass@}) and their encoded forms;</li>
 *   <li>sensitive config key-value forms ({@code password=...},
 *       {@code SPRING_DATASOURCE_PASSWORD=...},
 *       {@code KCG_CONF_*_PASSWORD=...}, etc.).</li>
 * </ul>
 *
 * <p>If initialization cannot be stably completed (e.g. a required
 * credential is null or empty), {@link #canBuild} returns false and the
 * run must fail-closed with
 * {@code FAILED(HARNESS_CREDENTIAL_BOUNDARY)} 鈥?no subprocess is started.
 */
public final class SecretCatalog {

    private final List<String> allSecretStrings;
    private final List<byte[]> allSecretBytes;
    private final List<Pattern> allSecretPatterns;

    private SecretCatalog(List<String> strings, List<byte[]> bytes,
                          List<Pattern> patterns) {
        this.allSecretStrings = List.copyOf(strings);
        this.allSecretBytes = List.copyOf(bytes);
        this.allSecretPatterns = List.copyOf(patterns);
    }

    /**
     * Check whether the catalog can be built from the given credentials.
     * All six inputs must be non-null and non-empty.
     */
    public static boolean canBuild(String controlUsername, String controlPassword,
                                   String runtimeUsername, String runtimePassword,
                                   String controlJdbcUrl, String runtimeJdbcUrl) {
        return controlUsername != null && !controlUsername.isEmpty()
                && controlPassword != null && !controlPassword.isEmpty()
                && runtimeUsername != null && !runtimeUsername.isEmpty()
                && runtimePassword != null && !runtimePassword.isEmpty()
                && controlJdbcUrl != null && !controlJdbcUrl.isEmpty()
                && runtimeJdbcUrl != null && !runtimeJdbcUrl.isEmpty();
    }

    /**
     * Build the canonical catalog from all credential inputs. Expands each
     * credential into its raw, percent-encoded, user-info, and config
     * key-value forms.
     *
     * @throws IllegalStateException if any required credential is null or empty
     */
    public static SecretCatalog build(String controlUsername, String controlPassword,
                                      String runtimeUsername, String runtimePassword,
                                      String controlJdbcUrl, String runtimeJdbcUrl) {
        if (!canBuild(controlUsername, controlPassword, runtimeUsername, runtimePassword,
                controlJdbcUrl, runtimeJdbcUrl)) {
            throw new IllegalStateException(
                    "cannot build SecretCatalog: a required credential is null or empty");
        }
        List<String> strings = new ArrayList<>();
        List<byte[]> bytes = new ArrayList<>();
        List<Pattern> patterns = new ArrayList<>();

        // --- Exact secret values ---
        List<String> exactSecrets = new ArrayList<>();
        exactSecrets.add(controlUsername);
        exactSecrets.add(controlPassword);
        exactSecrets.add(runtimeUsername);
        exactSecrets.add(runtimePassword);
        exactSecrets.add(controlJdbcUrl);
        exactSecrets.add(runtimeJdbcUrl);
        for (String s : exactSecrets) {
            addSecret(strings, bytes, patterns, s);
        }

        // --- Percent-encoded forms ---
        for (String s : exactSecrets) {
            String encoded = percentEncode(s);
            if (!encoded.equals(s)) {
                addSecret(strings, bytes, patterns, encoded);
            }
        }

        // --- JDBC URL user-info forms: user:pass@ ---
        addSecret(strings, bytes, patterns,
                controlUsername + ":" + controlPassword + "@");
        addSecret(strings, bytes, patterns,
                runtimeUsername + ":" + runtimePassword + "@");

        // --- Encoded user-info forms ---
        String encodedControlUser = percentEncode(controlUsername);
        String encodedControlPass = percentEncode(controlPassword);
        String encodedRuntimeUser = percentEncode(runtimeUsername);
        String encodedRuntimePass = percentEncode(runtimePassword);
        addSecret(strings, bytes, patterns,
                encodedControlUser + ":" + encodedControlPass + "@");
        addSecret(strings, bytes, patterns,
                encodedRuntimeUser + ":" + encodedRuntimePass + "@");

        // --- Config key-value forms ---
        // password=<value>
        addSecret(strings, bytes, patterns, "password=" + controlPassword);
        addSecret(strings, bytes, patterns, "password=" + runtimePassword);
        // passwd=<value>
        addSecret(strings, bytes, patterns, "passwd=" + controlPassword);
        addSecret(strings, bytes, patterns, "passwd=" + runtimePassword);
        // pwd=<value>
        addSecret(strings, bytes, patterns, "pwd=" + controlPassword);
        addSecret(strings, bytes, patterns, "pwd=" + runtimePassword);
        // SPRING_DATASOURCE_PASSWORD=<value>
        addSecret(strings, bytes, patterns, "SPRING_DATASOURCE_PASSWORD=" + runtimePassword);
        // SPRING_DATASOURCE_USERNAME=<value>
        addSecret(strings, bytes, patterns, "SPRING_DATASOURCE_USERNAME=" + runtimeUsername);
        // KCG_CONF_CONTROL_PASSWORD=<value>
        addSecret(strings, bytes, patterns, "KCG_CONF_CONTROL_PASSWORD=" + controlPassword);
        // KCG_CONF_CONTROL_USERNAME=<value>
        addSecret(strings, bytes, patterns, "KCG_CONF_CONTROL_USERNAME=" + controlUsername);
        // KCG_CONF_RUNTIME_PASSWORD=<value>
        addSecret(strings, bytes, patterns, "KCG_CONF_RUNTIME_PASSWORD=" + runtimePassword);
        // KCG_CONF_RUNTIME_USERNAME=<value>
        addSecret(strings, bytes, patterns, "KCG_CONF_RUNTIME_USERNAME=" + runtimeUsername);
        // KCG_CONF_CONTROL_JDBC_URL=<value>
        addSecret(strings, bytes, patterns, "KCG_CONF_CONTROL_JDBC_URL=" + controlJdbcUrl);

        // --- Username:password pair (without @, for general config) ---
        addSecret(strings, bytes, patterns,
                controlUsername + ":" + controlPassword);
        addSecret(strings, bytes, patterns,
                runtimeUsername + ":" + runtimePassword);

        return new SecretCatalog(strings, bytes, patterns);
    }

    /**
     * Build a catalog from a simple list of secret strings (for backward
     * compatibility with existing tests and code paths that pass a flat
     * list). Each string is added as-is plus its percent-encoded form.
     */
    public static SecretCatalog fromList(List<String> secrets) {
        Objects.requireNonNull(secrets, "secrets");
        if (secrets.isEmpty()) {
            throw new IllegalStateException("cannot build SecretCatalog: empty secret list");
        }
        List<String> strings = new ArrayList<>();
        List<byte[]> bytes = new ArrayList<>();
        List<Pattern> patterns = new ArrayList<>();
        for (String s : secrets) {
            if (s == null || s.isEmpty()) {
                throw new IllegalStateException(
                        "cannot build SecretCatalog: null or empty secret entry");
            }
            addSecret(strings, bytes, patterns, s);
            String encoded = percentEncode(s);
            if (!encoded.equals(s)) {
                addSecret(strings, bytes, patterns, encoded);
            }
        }
        return new SecretCatalog(strings, bytes, patterns);
    }

    /**
     * @return all secret string forms (exact, encoded, user-info, config).
     *         This list is also suitable for passing to
     *         {@link ChildEnvironmentBuilder#commandLineIsSafe(String[], java.util.Set)}.
     */
    public List<String> allSecretStrings() {
        return allSecretStrings;
    }

    /**
     * @return all secret byte forms (UTF-8 bytes of every string form).
     *         Used by the scanner for byte-level scanning.
     */
    public List<byte[]> allSecretBytes() {
        return allSecretBytes;
    }

    /**
     * @return all secret regex patterns (quoted literals of every string
     *         form). Used by both the redactor and the scanner.
     */
    public List<Pattern> allSecretPatterns() {
        return allSecretPatterns;
    }

    /**
     * @return the secret strings as an unmodifiable Set for use with
     *         {@link ChildEnvironmentBuilder#commandLineIsSafe}.
     */
    public java.util.Set<String> secretValueSet() {
        return java.util.Collections.unmodifiableSet(new java.util.HashSet<>(allSecretStrings));
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private static void addSecret(List<String> strings, List<byte[]> bytes,
                                  List<Pattern> patterns, String secret) {
        strings.add(secret);
        bytes.add(secret.getBytes(StandardCharsets.UTF_8));
        patterns.add(Pattern.compile(Pattern.quote(secret)));
    }

    /**
     * Percent-encode a string using UTF-8, the same encoding used by JDBC
     * URLs and HTML forms. This produces the standard
     * {@code URLEncoder.encode} output.
     */
    private static String percentEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is always supported.
            throw new IllegalStateException("UTF-8 not supported", e);
        }
    }
}
