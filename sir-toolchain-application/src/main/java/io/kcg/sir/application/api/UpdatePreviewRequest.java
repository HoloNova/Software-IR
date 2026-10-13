package io.kcg.sir.application.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Compare two revisions of one software. {@code outputRoot}, when given, is the directory the base
 * revision was generated into; it is only read, to detect files changed by hand.
 */
public record UpdatePreviewRequest(CompilationInput base, CompilationInput candidate, Optional<Path> outputRoot) {
    public UpdatePreviewRequest {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(candidate, "candidate");
        outputRoot = Objects.requireNonNull(outputRoot, "outputRoot");
        outputRoot.ifPresent(root -> {
            if (!root.isAbsolute() || !root.equals(root.normalize())) {
                throw new IllegalArgumentException("outputRoot must be an absolute normalized path");
            }
        });
    }
}
