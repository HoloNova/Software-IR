package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record AstConstraint(
        AstNodeId id,
        SourceSpan span,
        AstNameRef name,
        List<AstExpression> arguments) implements AstNode {

    public AstConstraint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }
}
