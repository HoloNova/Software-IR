package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.PathGuard;
import java.util.Objects;

/**
 * Points at one log file kept as evidence. Build, application and business logs are kept apart, so a
 * download or startup message can never be mistaken for a compile diagnostic or a business verdict.
 */
public record LogRef(Kind kind, String relativePath, long bytes, String sha256) {
    public enum Kind { BUILD, APPLICATION, BUSINESS }

    public LogRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(sha256, "sha256");
        if (PathGuard.validateRelativePath(relativePath) != null) {
            throw new IllegalArgumentException("unsafe log path: " + relativePath);
        }

        if (bytes < 0 || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid log evidence for " + relativePath);
        }
    }
}
