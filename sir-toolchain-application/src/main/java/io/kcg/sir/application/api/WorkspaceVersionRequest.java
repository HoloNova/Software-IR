package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceSnapshot;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Move the project whose SIR sources are in {@code sourceRoot} (and whose generated files are in
 * {@code outputRoot}) to the {@code candidate} sources.
 *
 * <p>All three roots are inside one Git working tree, {@code repositoryRoot}: the SIR sources and the
 * generated project are saved together (ADR-027). The two roots must be separate directories, so that
 * no generated file can ever be a source file or the other way round.
 */
public record WorkspaceVersionRequest(Path repositoryRoot, Path sourceRoot, Path outputRoot, SourceSnapshot candidate) {
    public WorkspaceVersionRequest {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(candidate, "candidate");
        for (Path path : new Path[] {repositoryRoot, sourceRoot, outputRoot}) {
            if (!path.isAbsolute() || !path.equals(path.normalize())) {
                throw new IllegalArgumentException("roots must be absolute normalized paths: " + path);
            }
        }

        if (!sourceRoot.startsWith(repositoryRoot) || !outputRoot.startsWith(repositoryRoot)) {
            throw new IllegalArgumentException("the source root and the output root must be inside the repository root");
        }

        if (sourceRoot.startsWith(outputRoot) || outputRoot.startsWith(sourceRoot)) {
            throw new IllegalArgumentException("the source root and the output root must be separate directories");
        }
    }
}
