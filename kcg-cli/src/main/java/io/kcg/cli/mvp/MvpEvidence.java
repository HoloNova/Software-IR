package io.kcg.cli.mvp;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sanitized evidence store for the M4 acceptance IT (ADR-019 section 14, P0-1).
 *
 * <p>Evidence contains only non-sensitive versions, outcomes, exit codes,
 * manifest/sentinel proofs, cleanup proofs, and a final credential scan
 * result. It never contains credentials, JDBC URLs with user-info, full
 * command lines, full environments, raw logs, candidate SIR text, or
 * generated file contents.
 *
 * <p>Cross-run normalization is STRUCTURED and allowlist-based: only the
 * explicitly documented root-bound fields are replaced ({@code baselineId}
 * family, {@code schemaName}, {@code runTag}, the {@code ports} list, and
 * absolute path values under the run roots). All other content—including candidate
 * digests, graph digests, context/target/transaction identity values,
 * protocols, diagnostics, relative paths, and Maven/HTTP/MySQL outcomes—must be
 * byte-identical across independent runs. No blanket hex or regex
 * replacement is performed.
 */
final class MvpEvidence {

    /** Fields proven root-bound: the Baseline Bundle ID is derived from the
     * bound outputRoot, so it legitimately differs between independent runs. */
    static final Set<String> BASELINE_ID_FIELDS = Set.of(
            "baselineId", "previousBaselineId", "currentAfterCrash", "postRecoveryBaselineId");

    private final Map<String, Object> doc = new LinkedHashMap<>();

    void put(String key, Object value) {
        doc.put(key, value);
    }

    /** Read-only view of the evidence document (for structured normalization). */
    Map<String, Object> doc() {
        return java.util.Collections.unmodifiableMap(doc);
    }

    /** Serialize deterministically (insertion order, no locale dependence). */
    String serialize() {
        return toJson(doc);
    }

    /** Plain write (caller-owned locations such as status.json). */
    void write(Path root, String fileName) throws IOException {
        Files.writeString(root.resolve(fileName), serialize() + "\n", StandardCharsets.UTF_8);
    }

    static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return quote(s);
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return String.valueOf(value);
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(toJson(list.get(i)));
            }
            return sb.append(']').toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(String.valueOf(e.getKey()))).append(':').append(toJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        throw new IllegalArgumentException("unsupported evidence value: " + value.getClass());
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // ------------------------------------------------------------------
    // Structured allowlist normalization (P0-1)
    // ------------------------------------------------------------------

    /**
     * Deep copy of the document with ONLY the documented root-bound fields
     * replaced: baseline-ID family fields, schema name, run tag, the ports
     * list, and absolute path values rooted under this run's roots. Every
     * other value stays verbatim so that any cross-run difference in
     * candidate/graph digests, identity tokens, or outcomes surfaces as a
     * comparison mismatch.
     */
    static Map<String, Object> normalizedDoc(MvpEvidence evidence, List<String> runRoots,
                                             String schema, String runTag, List<Integer> ports) {
        Map<String, Object> copy = deepCopy(evidence.doc());
        normalizeNode(copy, runRoots, schema, runTag, ports);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void normalizeNode(Object node, List<String> runRoots,
                                      String schema, String runTag, List<Integer> ports) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                String key = e.getKey();
                Object value = e.getValue();
                if (BASELINE_ID_FIELDS.contains(key) && value instanceof String s
                        && MvpSanitize.is64Hex(s)) {
                    e.setValue("<BASELINE_ID>");
                } else if ("schemaName".equals(key) && value instanceof String s
                        && s.equals(schema)) {
                    e.setValue("<SCHEMA>");
                } else if ("runTag".equals(key) && value instanceof String s
                        && s.equals(runTag)) {
                    e.setValue("<RUN>");
                } else if ("ports".equals(key) && value instanceof List<?> list) {
                    List<Object> portsList = new ArrayList<>();
                    for (Object p : list) {
                        portsList.add(ports != null && ports.contains(p) ? "<PORT>" : p);
                    }
                    e.setValue(portsList);
                } else if (value instanceof String s) {
                    String replaced = s;
                    for (String root : runRoots) {
                        if (root != null && !root.isEmpty() && replaced.startsWith(root)) {
                            replaced = "<ROOT>" + replaced.substring(root.length());
                            break;
                        }
                    }
                    if (!replaced.equals(s)) {
                        e.setValue(replaced);
                    }
                }
                normalizeNode(value, runRoots, schema, runTag, ports);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                normalizeNode(item, runRoots, schema, runTag, ports);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            copy.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return copy;
    }

    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                copy.put(String.valueOf(e.getKey()), deepCopyValue(e.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return value; // String/Number/Boolean immutable
    }

    // ------------------------------------------------------------------
    // Credential scan
    // ------------------------------------------------------------------

    /** Scan a captured string for credential/leakage markers. */
    static List<String> credentialHits(String text, String... secrets) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return hits;
        }
        for (String secret : secrets) {
            // Exact JSON-value match: the secret as a quoted string value.
            // Substring matching is intentionally NOT used: harness words
            // such as "wrong-output-root" legitimately contain "root".
            if (secret != null && !secret.isEmpty() && text.contains("\"" + secret + "\"")) {
                hits.add("secret-token");
            }
            // Percent-encoded registered secrets must be detected as well.
            // URL-encode the secret once (space -> +, non-ASCII -> %XX) and look for the
            // encoded form inside an opaque value.
            if (secret != null && !secret.isEmpty()) {
                String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
                if (text.contains(encoded)) {
                    hits.add("secret-token");
                }
            }
        }
        if (text.contains("jdbc:mysql://") && text.matches("(?s).*jdbc:mysql://[^/]*@.*")) {
            hits.add("jdbc-url-userinfo");
        }
        if (Pattern.compile("(?i)(user|username|password)=[^&\\s\"]+").matcher(text).find()) {
            hits.add("url-credential-param");
        }
        if (text.contains("\tat ")) {
            hits.add("stack-trace");
        }
        if (text.contains("Exception")) {
            hits.add("exception-text");
        }
        return hits;
    }

    /** Scan raw file bytes (UTF-8) for the same markers. */
    static List<String> scanBytes(byte[] bytes, String... secrets) {
        return credentialHits(new String(bytes, StandardCharsets.UTF_8), secrets);
    }
}
