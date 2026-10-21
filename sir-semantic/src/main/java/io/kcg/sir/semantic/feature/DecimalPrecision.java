package io.kcg.sir.semantic.feature;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Evaluates a declared precision rule without adding a node to the normalized model.
 * The rule's facts remain in NormalizedConstraint; this helper never rounds or sets storage limits.
 */
public record DecimalPrecision(int precision, int scale) {
    public static final int MONEY_PRECISION = 19;
    public static final int MONEY_SCALE = 2;

    public DecimalPrecision {
        if (precision < 1 || scale < 0 || scale > precision) {
            throw new IllegalArgumentException("precision requires p >= 1 and 0 <= s <= p");
        }
    }

    public static Optional<DecimalPrecision> parse(List<String> arguments) {
        if (arguments.size() != 2 || arguments.stream().anyMatch(a -> !a.matches("[0-9]+"))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new DecimalPrecision(Integer.parseInt(arguments.get(0)), Integer.parseInt(arguments.get(1))));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    /** Trailing zeros do not change a number's value. Absence is governed by the field's nullability. */
    public boolean accepts(BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return true;
        }
        long integerDigits = Math.max(0L, (long) value.precision() - value.scale());
        if (integerDigits > (long) precision - scale) {
            return false;
        }
        return value.scale() <= scale || value.stripTrailingZeros().scale() <= scale;
    }
}
