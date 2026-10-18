package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/** A {@code delete <variable>;} step: remove the stored row that a {@code load} read into the variable. */
public record AstDeleteStep(
        AstNodeId id,
        SourceSpan span,
        AstNameRef target) implements AstStep {
    public AstDeleteStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(target, "target");
    }
}
