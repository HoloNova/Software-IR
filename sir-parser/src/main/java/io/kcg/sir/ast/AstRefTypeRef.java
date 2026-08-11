package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstRefTypeRef(
        AstNodeId id,
        SourceSpan span,
        AstNameRef targetName) implements AstTypeRef {

    public AstRefTypeRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(targetName, "targetName");
    }
}
