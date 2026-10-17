package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.Objects;

/** Export only. Does not acquire images, run Docker or mutate an existing deployment. */
public record DeploymentPackageRequest(Path sourceRoot, SourceId entry, Path outputRoot) {
    public DeploymentPackageRequest { Objects.requireNonNull(sourceRoot); Objects.requireNonNull(entry); Objects.requireNonNull(outputRoot); }
}
