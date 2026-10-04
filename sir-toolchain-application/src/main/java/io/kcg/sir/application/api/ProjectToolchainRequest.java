package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.Objects;

/** First generation only; no state root, Bundle, baseline registration or replace policy. */
public record ProjectToolchainRequest(Path sourceRoot, SourceId entry, Path outputRoot) {
    public ProjectToolchainRequest { Objects.requireNonNull(sourceRoot); Objects.requireNonNull(entry); Objects.requireNonNull(outputRoot); }
}
