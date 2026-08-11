package io.kcg.sir.source;

import java.util.Objects;

/**
 * Stable, workspace-relative identity for a SIR source.
 */
public final class SourceId {
    private final String value;

    private SourceId(String value) {
        this.value = value;
    }

    public static SourceId of(String logicalPath) {
        Objects.requireNonNull(logicalPath, "logicalPath");
        if (logicalPath.isBlank()) {
            throw new IllegalArgumentException("logicalPath must not be blank");
        }

        String normalized = logicalPath.replace('\\', '/');
        if (isAbsolute(normalized)) {
            throw new IllegalArgumentException("logicalPath must be workspace-relative: " + logicalPath);
        }

        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("logicalPath must not contain empty path segments: " + logicalPath);
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("logicalPath must not contain '.' or '..' segments: " + logicalPath);
            }
        }

        return new SourceId(normalized);
    }

    private static boolean isAbsolute(String path) {
        if (path.startsWith("/") || path.startsWith("//")) {
            return true;
        }
        return path.length() >= 2
                && isAsciiLetter(path.charAt(0))
                && path.charAt(1) == ':';
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof SourceId sourceId && value.equals(sourceId.value));
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
