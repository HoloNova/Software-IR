package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.math.BigInteger;
import java.util.Objects;

/**
 * A date or date-time moved forward or back by a whole number of units, written {@code base + 15 minutes}.
 *
 * <p>The duration is part of this expression, not a value of its own: nothing else can hold, compare or
 * pass one around, so a duration never has to be stored or lowered as a type.
 */
public record AstTimeShiftExpression(
        AstNodeId id,
        SourceSpan span,
        AstExpression base,
        AstShiftDirection direction,
        BigInteger amount,
        AstTimeUnit unit) implements AstExpression {

    public AstTimeShiftExpression {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(unit, "unit");
    }
}
