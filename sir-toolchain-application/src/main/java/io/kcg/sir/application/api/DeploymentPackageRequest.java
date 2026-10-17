package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Export only. Does not acquire images, run Docker or mutate an existing deployment. {@code acceptanceRoot} is an
 * optional business acceptance Maven project, copied verbatim to {@code acceptance/} and never compiled here.
 */
public record DeploymentPackageRequest(Path sourceRoot, SourceId entry, Path outputRoot, Optional<Path> acceptanceRoot) {
    public DeploymentPackageRequest {
        Objects.requireNonNull(sourceRoot); Objects.requireNonNull(entry); Objects.requireNonNull(outputRoot); Objects.requireNonNull(acceptanceRoot);
    }
    public DeploymentPackageRequest(Path sourceRoot, SourceId entry, Path outputRoot) { this(sourceRoot, entry, outputRoot, Optional.empty()); }
}
