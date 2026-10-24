package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/** A local calendar conversion, not an SQL query or a fixed-duration shift. */
public record AstMonthStep(AstNodeId id, SourceSpan span, AstExpression month, AstExpression zone,
                           AstMonthBoundary since, AstMonthBoundary until, AstNameRef error) implements AstStep {
    public AstMonthStep {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(span, "span");
        Objects.requireNonNull(month, "month"); Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(since, "since"); Objects.requireNonNull(until, "until"); Objects.requireNonNull(error, "error");
    }
}
