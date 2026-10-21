package io.kcg.sir.lowering.springboot.model;

/** The target's explicit exact-decimal shape, shared by validation and physical storage. */
public record DecimalShape(int precision, int scale) {
    public DecimalShape {
        if (precision < 1 || scale < 0 || scale > precision) {
            throw new IllegalArgumentException("invalid decimal precision/scale");
        }
    }
}
