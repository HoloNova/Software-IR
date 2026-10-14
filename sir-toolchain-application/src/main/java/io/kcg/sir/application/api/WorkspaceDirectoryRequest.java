package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The same change as {@link WorkspaceVersionRequest}, with the candidate given as a directory of SIR sources
 * instead of bytes in memory.
 *
 * <p>Why it exists: the command line does no file I/O of its own (its boundary test enforces that), so it
 * cannot read a candidate. It hands the application a directory and the application reads it, with the same
 * strict reader that reads the current sources.
 *
 * @param entry the entry source of the project, relative to both {@code sourceRoot} and {@code candidateRoot}
 */
public record WorkspaceDirectoryRequest(Path repositoryRoot, Path sourceRoot, SourceId entry, Path outputRoot, Path candidateRoot) {
    public WorkspaceDirectoryRequest {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(candidateRoot, "candidateRoot");
    }
}
