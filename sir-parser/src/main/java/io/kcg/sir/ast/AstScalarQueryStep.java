package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/** An explicit database read whose result is a Boolean or a 64-bit row count, not a row collection. */
public record AstScalarQueryStep(AstNodeId id, SourceSpan span, Kind kind, AstNameRef entity,
                                 AstExpression predicate, AstName result) implements AstStep {
    public enum Kind { EXISTS, COUNT }

    public AstScalarQueryStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(result, "result");
    }
}
