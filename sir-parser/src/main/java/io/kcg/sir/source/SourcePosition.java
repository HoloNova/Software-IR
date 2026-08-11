package io.kcg.sir.source;

/**
 * A position in normalized SIR text. Offsets are zero-based Unicode code-point
 * offsets; line and column are one-based.
 */
public record SourcePosition(int codePointOffset, int line, int column) {
    public SourcePosition {
        if (codePointOffset < 0) {
            throw new IllegalArgumentException("codePointOffset must be non-negative");
        }
        if (line < 1) {
            throw new IllegalArgumentException("line must be at least 1");
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be at least 1");
        }
    }
}
