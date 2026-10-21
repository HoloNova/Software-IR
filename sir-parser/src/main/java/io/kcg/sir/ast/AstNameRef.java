package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstNameRef(AstNodeId id, SourceSpan span, String text, java.util.Optional<AstNodeId> argumentId) implements AstNode {
    public AstNameRef(AstNodeId id, SourceSpan span, String text) { this(id, span, text, java.util.Optional.empty()); }
    public AstNameRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(argumentId, "argumentId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(span, "span");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
