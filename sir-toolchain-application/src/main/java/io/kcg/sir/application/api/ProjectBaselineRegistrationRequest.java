package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceSnapshot;
import java.nio.file.Path;
import java.util.Objects;

/** The exact generation snapshot, never a host source directory to be reread. */
public record ProjectBaselineRegistrationRequest(SourceSnapshot sources,Path outputRoot,Path stateRoot) {
    public ProjectBaselineRegistrationRequest { Objects.requireNonNull(sources);Objects.requireNonNull(outputRoot);Objects.requireNonNull(stateRoot); }
}
