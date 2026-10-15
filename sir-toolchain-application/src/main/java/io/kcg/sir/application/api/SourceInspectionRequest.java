package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.Objects;

/** Read-only inspection of an explicit SIR source set; no target generation or process execution. */
public record SourceInspectionRequest(Path sourceRoot, SourceId entry) {
    public SourceInspectionRequest { Objects.requireNonNull(sourceRoot); Objects.requireNonNull(entry); }
}
