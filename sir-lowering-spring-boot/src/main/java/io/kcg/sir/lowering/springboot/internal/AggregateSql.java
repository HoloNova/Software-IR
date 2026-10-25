package io.kcg.sir.lowering.springboot.internal;

/**
 * The one place the measurement expression of a total or an average is written, so the lowerer that plans it and the
 * validators that check it can never disagree. A total of nothing is 0. An average is rounded half up to the scale of
 * the measured column, because the database would otherwise answer with extra digits the stored values never had; an
 * average of nothing is 0 as well (a caller that must tell "none" from "zero" counts first).
 */
final class AggregateSql {
    private AggregateSql() {}

    static String measurement(String quotedColumn, boolean average, int scale) {
        return average ? "COALESCE(ROUND(AVG(" + quotedColumn + "), " + scale + "), 0)"
                : "COALESCE(SUM(" + quotedColumn + "), 0)";
    }
}
