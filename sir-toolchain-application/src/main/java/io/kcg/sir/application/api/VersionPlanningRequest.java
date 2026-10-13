package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceSnapshot;
import java.nio.file.Path;
import java.util.Objects;

/** Plan moving the stored project at {@code expectedBaselineId} to the {@code candidate} sources. */
public record VersionPlanningRequest(Path stateRoot, Path outputRoot, String expectedBaselineId, SourceSnapshot candidate) {
    public VersionPlanningRequest {
        Objects.requireNonNull(stateRoot, "stateRoot");
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(candidate, "candidate");
        if (expectedBaselineId == null || !expectedBaselineId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedBaselineId must be lowercase SHA-256 hex");
        }
    }
}
