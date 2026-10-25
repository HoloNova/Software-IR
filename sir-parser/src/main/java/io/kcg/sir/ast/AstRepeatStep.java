package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * {@code repeat count times limit N else E as k { ... }}: the body runs for k = 0 .. count-1. A count above the
 * declared bound is refused before the first pass, the same way an each source over its limit is.
 */
public record AstRepeatStep(AstNodeId id, SourceSpan span, AstExpression count, BigInteger limit, AstNameRef error,
        AstName result, List<AstStep> body) implements AstStep {
    public AstRepeatStep {
        Objects.requireNonNull(id); Objects.requireNonNull(span); Objects.requireNonNull(count);
        Objects.requireNonNull(limit); Objects.requireNonNull(error); Objects.requireNonNull(result);
        body=List.copyOf(Objects.requireNonNull(body));
    }
}
