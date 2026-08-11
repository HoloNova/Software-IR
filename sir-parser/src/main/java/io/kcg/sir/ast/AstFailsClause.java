package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstFailsClause(
        AstNodeId id,
        SourceSpan span,
        AstNameRef error) implements AstCapabilityClause {

    public AstFailsClause {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(error, "error");
    }
}
