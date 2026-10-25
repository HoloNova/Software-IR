package io.kcg.sir.ast;

public enum AstBinaryOperator {
    OR,
    AND,
    EQ,
    NE,
    GT,
    GE,
    LT,
    LE,
    CONTAINS_LITERAL,
    MATCHES,
    ADD,
    SUB,
    MUL,
    /** {@code daysBetween(a, b)}: whole days from a to b, written as a call but carried as a binary node. */
    DAYS_BETWEEN,
    /** {@code max(a, b)}: the larger of two values of one comparable type. */
    MAX,
    /** {@code min(a, b)}: the smaller of two values of one comparable type. */
    MIN,
    /** {@code addDays(t, n)}: a Moment or Day moved by n whole days (a day is 24 hours for a Moment). */
    ADD_DAYS
}
