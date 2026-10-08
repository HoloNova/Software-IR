package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceSnapshot;
import java.nio.file.Path;
import java.util.Objects;

public record ProjectChangeContextRequest(Path stateRoot,Path outputRoot,String expectedBaselineId,SourceSnapshot candidate) {
    public ProjectChangeContextRequest { Objects.requireNonNull(stateRoot);Objects.requireNonNull(outputRoot);Objects.requireNonNull(expectedBaselineId);Objects.requireNonNull(candidate); }
}
