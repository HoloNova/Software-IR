package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code expose command put at "/notes/{id}";}: the kind of capability, and optionally the HTTP method ({@code put} or
 * {@code delete}) and the path under {@code /api} it is reached at.
 */
public record AstExposeClause(
        AstNodeId id,
        SourceSpan span,
        AstExposureKind exposure,
        Optional<AstName> method,
        Optional<AstStringLiteral> path) implements AstCapabilityClause {

    public AstExposeClause {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(exposure, "exposure");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
    }

    public AstExposeClause(AstNodeId id, SourceSpan span, AstExposureKind exposure) {
        this(id, span, exposure, Optional.empty(), Optional.empty());
    }
}
