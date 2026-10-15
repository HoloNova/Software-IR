package io.kcg.sir.ast;

/** The units a time shift may be written in. Months and years are left out: their length is not fixed. */
public enum AstTimeUnit {
    SECONDS,
    MINUTES,
    HOURS,
    DAYS
}
