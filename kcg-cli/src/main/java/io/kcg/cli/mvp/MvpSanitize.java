package io.kcg.cli.mvp;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic sanitization for the M4 acceptance IT (ADR-019 搂14, P0-5).
 *
 * <p>Every child stdout/stderr that may reach a message, log, evidence, or
 * exception is sanitized BEFORE entering those sinks. Credentials, JDBC
 * URLs with user-info, absolute drive paths, and control characters are
 * replaced with stable placeholders; multi-line content is collapsed to a
 * bounded first line for message keys. Sanitization never changes the
 * byte-level content used for assertions (assertion logic operates on the
 * raw captured streams).
 */
final class MvpSanitize {

    private static final Pattern DRIVE_PATH = Pattern.compile(
            "(?i)([a-z]:\\\\(?:[^\\\\\"\\s,}]+\\\\)*[^\\\\\"\\s,}]*|\\\\\\\\\\?\\\\[^\\\\\"\\s,}]+)");
    private static final Pattern JDBC_URL = Pattern.compile(
            "(?i)jdbc:mysql://[^\\s\",}]+");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");

    private MvpSanitize() {
    }

    /**
     * Sanitize a text for embedding into messages/logs/evidence: replace
     * secrets, absolute drive paths, JDBC URLs, and control characters.
     * Line structure is preserved (CLI outputs are single-line canonical
     * JSON and must stay parseable when re-embedded only in messages).
     */
    static String forMessage(String text, String... secrets) {
        if (text == null) {
            return "";
        }
        String s = text;
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) {
                s = s.replace(secret, "<SECRET>");
            }
        }
        s = DRIVE_PATH.matcher(s).replaceAll("<PATH>");
        s = JDBC_URL.matcher(s).replaceAll("<JDBC_URL>");
        s = CONTROL_CHARS.matcher(s).replaceAll(" ");
        return s;
    }

    /** Stable failure category derived from the exception type. */
    static String category(Throwable t) {
        if (t == null) {
            return "UNKNOWN";
        }
        if (t instanceof AssertionError) {
            return "ASSERTION";
        }
        if (t instanceof java.io.IOException) {
            return "IO";
        }
        if (t instanceof java.sql.SQLException) {
            return "SQL";
        }
        if (t instanceof IllegalStateException) {
            return "STATE";
        }
        if (t instanceof IllegalArgumentException) {
            return "ARGUMENT";
        }
        String name = t.getClass().getSimpleName();
        return name == null || name.isBlank() ? "UNKNOWN" : name;
    }

    /**
     * Stable, bounded, non-sensitive message key for failure reports: the
     * exception category plus the first line of its message with secrets,
     * paths, and JDBC URLs replaced and truncated to 120 chars. Never
     * includes stack frames, raw streams, or environment content.
     */
    static String messageKey(Throwable t, String... secrets) {
        String msg = t == null ? null : t.getMessage();
        if (msg == null || msg.isBlank()) {
            return category(t);
        }
        String firstLine = msg.split("\n", 2)[0];
        String s = forMessage(firstLine, secrets);
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > 120) {
            s = s.substring(0, 120) + "...";
        }
        return category(t) + ":" + s;
    }

    /** Locale-independent lowercase hex check for opaque identity tokens. */
    static boolean is64Hex(String s) {
        if (s == null || s.length() != 64) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}

