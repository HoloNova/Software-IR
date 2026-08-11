package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstNameExpression(
        AstNodeId id,
        SourceSpan span,
        AstNameRef name) implements AstExpression {

    public AstNameExpression {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
    }
}
