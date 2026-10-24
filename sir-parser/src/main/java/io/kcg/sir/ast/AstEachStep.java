package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** A bounded database source and a lexical body, not an unbounded client-controlled loop. */
public record AstEachStep(AstNodeId id, SourceSpan span, AstNameRef entity, AstExpression predicate,
        AstFindOrder order, BigInteger limit, AstNameRef error, AstName result, List<AstStep> body) implements AstStep {
    public AstEachStep {
        Objects.requireNonNull(id); Objects.requireNonNull(span); Objects.requireNonNull(entity);
        Objects.requireNonNull(predicate); Objects.requireNonNull(order); Objects.requireNonNull(limit);
        Objects.requireNonNull(error); Objects.requireNonNull(result);
        body=List.copyOf(Objects.requireNonNull(body));
    }
}
