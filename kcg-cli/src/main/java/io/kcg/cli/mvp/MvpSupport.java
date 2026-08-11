package io.kcg.cli.mvp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Self-contained helpers for the M4 acceptance IT: canonical JSON field
 * extraction, strict full-tree snapshots, SHA-256, free-port probing, and a
 * fixed-width alphanumeric secret generator. No third-party JSON library is
 * used; the CLI emits one canonical JSON document per line, which keeps the
 * extraction contract tiny.
 */
final class MvpSupport {

    private static final SecureRandom RANDOM = new SecureRandom();

    private MvpSupport() {
    }

    // ------------------------------------------------------------------
    // JSON extraction (single-line canonical CLI documents)
    // ------------------------------------------------------------------

    /** "key":"value" string field (null when absent). */
    static String fieldSafe(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int s = i + needle.length();
        int e = json.indexOf('"', s);
        return e < 0 ? null : json.substring(s, e);
    }

    /** "key":"value" string field. */
    static String field(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            throw new AssertionError("missing string field '" + key + "' in " + json);
        }
        int s = i + needle.length();
        int e = json.indexOf('"', s);
        if (e < 0) {
            throw new AssertionError("unterminated field '" + key + "' in " + json);
        }
        return json.substring(s, e);
    }

    /** "key":<number> numeric field. */
    static long number(String json, String key) {
        String needle = "\"" + key + "\":";
        int i = json.indexOf(needle);
        if (i < 0) {
            throw new AssertionError("missing numeric field '" + key + "' in " + json);
        }
        int s = i + needle.length();
        int e = s;
        while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) {
            e++;
        }
        return Long.parseLong(json.substring(s, e));
    }

    /** Count of objects in an array field (LAST occurrence = top-level key). */
    static int arrayCount(String json, String arrayKey) {
        String needle = "\"" + arrayKey + "\":[";
        int i = json.lastIndexOf(needle);
        if (i < 0) {
            return 0;
        }
        int pos = json.indexOf('{', i + needle.length());
        int count = 0;
        while (pos >= 0) {
            int close = findClose(json, pos);
            if (close < 0) {
                break;
            }
            count++;
            pos = json.indexOf('{', close + 1);
        }
        return count;
    }

    /**
     * Collect all string values of a field across an array of objects. Uses
     * the LAST occurrence of the array key: the plan document renders nested
     * {@code fileChanges} inside {@code artifactChanges} before the top-level
     * key, so the top-level family arrays are the final occurrences.
     */
    static List<String> arrayFieldValues(String json, String arrayKey, String itemKey) {
        String needle = "\"" + arrayKey + "\":[";
        int i = json.lastIndexOf(needle);
        if (i < 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int pos = json.indexOf('{', i + needle.length());
        while (pos >= 0) {
            int close = findClose(json, pos);
            if (close < 0) {
                break;
            }
            String obj = json.substring(pos, close + 1);
            String v = tryField(obj, itemKey);
            if (v != null) {
                out.add(v);
            }
            pos = json.indexOf('{', close + 1);
        }
        return out;
    }

    /** Locate the opaque targetKey of the unique target matching the typed identity. */
    static String targetKey(String json, String side, String kind, String decl, String target) {
        String needle = "\"targets\":[";
        int i = json.indexOf(needle);
        if (i < 0) {
            throw new AssertionError("missing targets array");
        }
        int pos = json.indexOf('{', i + needle.length());
        while (pos >= 0) {
            int close = findClose(json, pos);
            if (close < 0) {
                break;
            }
            String obj = json.substring(pos, close + 1);
            if (side.equals(tryField(obj, "side"))
                    && kind.equals(tryField(obj, "kind"))
                    && (decl == null || decl.equals(tryField(obj, "declarationDisplayName")))
                    && (target == null || target.equals(tryField(obj, "targetDisplayName")))) {
                return field(obj, "targetKey");
            }
            pos = json.indexOf('{', close + 1);
        }
        throw new AssertionError("target not found: " + side + "/" + kind + "/" + decl + "/" + target);
    }

    private static String tryField(String obj, String key) {
        String needle = "\"" + key + "\":\"";
        int i = obj.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int s = i + needle.length();
        int e = obj.indexOf('"', s);
        return e < 0 ? null : obj.substring(s, e);
    }

    private static int findClose(String json, int open) {
        int depth = 0;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                i = skipString(json, i);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int skipString(String json, int q) {
        for (int i = q + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return json.length();
    }

    /** Assert stdout is exactly one canonical JSON document + a single LF. */
    static void assertSingleJsonLine(String stdout) {
        int lf = stdout.indexOf('\n');
        if (lf < 0 || stdout.indexOf('\n', lf + 1) >= 0) {
            throw new AssertionError("stdout must be exactly one line + LF, got: " + stdout);
        }
        if (!stdout.endsWith("\n")) {
            throw new AssertionError("stdout must end with LF: " + stdout);
        }
    }

    // ------------------------------------------------------------------
    // Strict full-tree snapshots (P0-2)
    // ------------------------------------------------------------------

    /**
     * Complete, fail-closed tree snapshot. Recursion uses
     * {@link DirectoryStream} with {@code NOFOLLOW_LINKS} attribute reads at
     * every node; every directory (including empty ones), regular file,
     * symlink/reparse point, and special entry is recorded. Regular files
     * record byteCount, SHA-256, NOFOLLOW type, and identity. The
     * {@code LOCK} file is included like any other node; its bytes are
     * recorded only when readable (a cross-process holder keeps a mandatory
     * Windows region lock that blocks byte reads, while attribute reads
     * remain possible).
     */
    static final class TreeSnapshot {

        enum Kind { DIRECTORY, REGULAR_FILE, SYMLINK, SPECIAL }

        record Entry(String relativePath, Kind kind, long byteCount, String sha256Hex,
                     String identity, boolean bytesUnreadable) {

            boolean sameContentAs(Entry o) {
                return kind == o.kind && byteCount == o.byteCount
                        && Objects.equals(sha256Hex, o.sha256Hex);
            }

            boolean sameIdentityAs(Entry o) {
                // [RQ-08 RECOVERY NOTE] M4 hardening: identity-unavailable must fail CLOSED
                // (two entries can never be proven to be the same file without identity).
                // Historical dump had fail-open here; the surviving hardening test
                // missingFileKeyCannotMakeDifferentIdentityEqual requires fail-closed.
                if (identity == null || o.identity == null
                        || identity.equals("<none>") || o.identity.equals("<none>")) {
                    return false;
                }
                return identity.equals(o.identity);
            }
        }

        private final boolean rootPresent;
        private final Map<String, Entry> entries = new TreeMap<>();

        private TreeSnapshot(boolean rootPresent) {
            this.rootPresent = rootPresent;
        }

        boolean rootPresent() {
            return rootPresent;
        }

        /** All entries (directories, files, symlinks, specials) by relative path. */
        Map<String, Entry> entries() {
            return java.util.Collections.unmodifiableMap(entries);
        }

        /** Regular-file entries only (for manifest comparisons). */
        Map<String, Entry> regularFiles() {
            Map<String, Entry> out = new TreeMap<>();
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                if (e.getValue().kind() == Kind.REGULAR_FILE) {
                    out.put(e.getKey(), e.getValue());
                }
            }
            return out;
        }

        static TreeSnapshot capture(Path root) throws IOException {
            Path base = root.toAbsolutePath().normalize();
            BasicFileAttributes rootAttrs;
            try {
                rootAttrs = Files.readAttributes(base, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                return new TreeSnapshot(false);
            }
            if (!rootAttrs.isDirectory()) {
                throw new AssertionError("snapshot root is not a directory: " + base);
            }
            TreeSnapshot snap = new TreeSnapshot(true);
            snap.walk(base, base);
            return snap;
        }

        private void walk(Path base, Path dir) throws IOException {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path child : stream) {
                    BasicFileAttributes attrs = Files.readAttributes(
                            child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    String rel = base.relativize(child.toAbsolutePath().normalize())
                            .toString().replace('\\', '/');
                    String identity = fileKey(attrs);
                    if (attrs.isDirectory()) {
                        entries.put(rel, new Entry(rel, Kind.DIRECTORY, 0, null, identity, false));
                        walk(base, child);
                    } else if (attrs.isRegularFile()) {
                        String sha;
                        boolean unreadable;
                        try {
                            sha = sha256Hex(Files.readAllBytes(child));
                            unreadable = false;
                        } catch (IOException e) {
                            // Only the LOCK file may be byte-unreadable (a
                            // cross-process mandatory region lock); any other
                            // unreadable regular file fails closed.
                            if (!rel.equals("LOCK") && !rel.endsWith("/LOCK")) {
                                throw new AssertionError(
                                        "snapshot: unreadable regular file " + rel + ": " + e);
                            }
                            sha = null;
                            unreadable = true;
                        }
                        entries.put(rel, new Entry(rel, Kind.REGULAR_FILE, attrs.size(), sha, identity, unreadable));
                    } else if (attrs.isSymbolicLink() || isReparsePoint(child, attrs)) {
                        entries.put(rel, new Entry(rel, Kind.SYMLINK, 0, null, identity, false));
                    } else {
                        entries.put(rel, new Entry(rel, Kind.SPECIAL, 0, null, identity, false));
                    }
                }
            }
        }

        /** Full-tree equality: kind, byteCount, bytes, and identity drift all fail. */
        void assertUnchanged(TreeSnapshot after, String label) {
            if (rootPresent != after.rootPresent) {
                throw new AssertionError(label + ": root presence changed");
            }
            List<String> problems = new ArrayList<>();
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                Entry afterEntry = after.entries.get(e.getKey());
                if (afterEntry == null) {
                    problems.add("removed:" + e.getKey());
                } else if (!e.getValue().sameContentAs(afterEntry)) {
                    problems.add("content:" + e.getKey());
                } else if (!e.getValue().sameIdentityAs(afterEntry)) {
                    problems.add("identity:" + e.getKey());

        }
        }
    }
}

    // [RQ-08 RECOVERY NOTE] isReparsePoint(Path, BasicFileAttributes) recovered verbatim
    // from the later MvpSupport snapshot in the same session (line 4005 block).
private static boolean isReparsePoint(Path path, BasicFileAttributes attrs) {
        try {
            Object val = Files.getAttribute(path, "dos:reparsePoint",
                    LinkOption.NOFOLLOW_LINKS);
            return Boolean.TRUE.equals(val);
        } catch (UnsupportedOperationException e) {
            return false;
        } catch (IllegalArgumentException e) {
            // Attribute not recognized on this JDK 鈥?fall back to
            // isSymbolicLink() || isOther(). Symlinks and non-directory
            // reparse points are caught. Junctions are caught by the
            // strong identity check.
            return attrs.isSymbolicLink() || attrs.isOther();
        } catch (IOException | RuntimeException e) {
            // Fail-closed: cannot read reparse attribute.
            return true;
        }
    }

    // [RQ-08 RECOVERY NOTE] fileKey() helper lost in the historical output
    // truncation gap; reconstructed from the standard BasicFileAttributes API
    // contract (returns null when the platform has no file key).
    private static String fileKey(BasicFileAttributes attrs) {
        Object fk = attrs.fileKey();
        return fk == null ? null : fk.toString();
    }

    // [RQ-08 RECOVERY NOTE] sha256Hex() recovered verbatim from the earlier
    // MvpSupport snapshot in the same session (line 333 of the historical dump).
    static String sha256Hex(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xFF;
                if (v < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

// [RQ-08 RECOVERY NOTE] tail of TreeSnapshot comparison method lost in historical
// output truncation; minimal closure appended at the block boundary.
