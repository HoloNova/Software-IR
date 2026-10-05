package io.kcg.sir.application.api;

import java.nio.file.Path;
import java.util.Objects;

/** Explicit expected identity; inspection cannot silently follow a different CURRENT. */
public record ProjectBaselineInspectionRequest(Path stateRoot,Path outputRoot,String expectedBaselineId) {
    public ProjectBaselineInspectionRequest { Objects.requireNonNull(stateRoot);Objects.requireNonNull(outputRoot);Objects.requireNonNull(expectedBaselineId); }
}
