package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstExposeClause(
        AstNodeId id,
        SourceSpan span,
        AstExposureKind exposure) implements AstCapabilityClause {

    public AstExposeClause {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(exposure, "exposure");
    }
}
