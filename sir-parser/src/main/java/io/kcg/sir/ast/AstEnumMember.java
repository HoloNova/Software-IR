package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstEnumMember(
        AstNodeId id,
        SourceSpan span,
        AstName name) implements AstNode {

    public AstEnumMember {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
    }
}
