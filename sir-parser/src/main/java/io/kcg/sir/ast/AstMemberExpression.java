package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstMemberExpression(
        AstNodeId id,
        SourceSpan span,
        AstExpression receiver,
        AstNameRef member) implements AstExpression {

    public AstMemberExpression {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(member, "member");
    }
}
