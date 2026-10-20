package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Export only. Does not acquire images, run Docker or mutate an existing deployment. {@code acceptanceRoot} is an
 * optional business acceptance Maven project, copied verbatim to {@code acceptance/} and never compiled here.
 * {@code basePackage} is the delivery an instance runs now: its frozen migration chain is inherited and only the
 * versions this source needs are appended, so {@code deploy.sh update} can move that instance here and keep its data.
 * {@code confirmBackup} says the person asking has a backup of that instance's data and accepts that the tables and
 * columns this source no longer has are dropped with their data; without it such a source is refused. It is a
 * declaration, nothing here checks it, and the update itself takes no backup.
 */
public record DeploymentPackageRequest(Path sourceRoot, SourceId entry, Path outputRoot, Optional<Path> acceptanceRoot,
        Optional<Path> basePackage, boolean confirmBackup) {
    public DeploymentPackageRequest {
        Objects.requireNonNull(sourceRoot); Objects.requireNonNull(entry); Objects.requireNonNull(outputRoot);
        Objects.requireNonNull(acceptanceRoot); Objects.requireNonNull(basePackage);
    }
    public DeploymentPackageRequest(Path sourceRoot, SourceId entry, Path outputRoot, Optional<Path> acceptanceRoot,
            Optional<Path> basePackage) {
        this(sourceRoot, entry, outputRoot, acceptanceRoot, basePackage, false);
    }
    public DeploymentPackageRequest(Path sourceRoot, SourceId entry, Path outputRoot, Optional<Path> acceptanceRoot) {
        this(sourceRoot, entry, outputRoot, acceptanceRoot, Optional.empty());
    }
    public DeploymentPackageRequest(Path sourceRoot, SourceId entry, Path outputRoot) { this(sourceRoot, entry, outputRoot, Optional.empty()); }
}
