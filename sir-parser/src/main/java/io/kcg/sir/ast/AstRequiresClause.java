package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstRequiresClause(
        AstNodeId id,
        SourceSpan span,
        AstRequirementKind requirement) implements AstCapabilityClause {

    public AstRequiresClause {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(requirement, "requirement");
    }
}
