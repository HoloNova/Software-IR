package io.kcg.sir.application.api;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Continue a project in a fresh state directory because the old one has used up its history budget.
 *
 * <p>All three paths must be absolute and normalized. {@code expectedBaselineId} is the version the old state must be at
 * right now; the rollover never follows a different current version.
 */
public record VersionStateRolloverRequest(Path oldStateRoot, Path outputRoot, String expectedBaselineId, Path newStateRoot) {
    public VersionStateRolloverRequest {
        Objects.requireNonNull(oldStateRoot, "oldStateRoot");
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(newStateRoot, "newStateRoot");
        for (Path path : new Path[] {oldStateRoot, outputRoot, newStateRoot}) {
            if (!path.isAbsolute() || !path.equals(path.normalize())) {
                throw new IllegalArgumentException("every root must be an absolute normalized path: " + path);
            }
        }

        if (expectedBaselineId == null || !expectedBaselineId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedBaselineId must be lowercase SHA-256 hex");
        }
    }
}
