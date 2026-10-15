package io.kcg.sir.application.api;

import io.kcg.sir.change.edit.CommandBatch;
import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.Objects;

/** Produce a new candidate source directory, never change the baseline or generated project. */
public record SourceEditRequest(Path sourceRoot, SourceId entry, Path candidateRoot, CommandBatch batch) {
    public SourceEditRequest {
        Objects.requireNonNull(sourceRoot); Objects.requireNonNull(entry);
        Objects.requireNonNull(candidateRoot); Objects.requireNonNull(batch);
    }
}
