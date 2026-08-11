package io.kcg.sir.application.conformance;

import java.util.List;

/**
 * The five representative conformance scenarios in fixed canonical order.
 *
 * <p>The matrix proves one representative operation for each Apply family
 * instead of multiplying every Change SIR version, actor mode, operating
 * system, and operation.
 */
public enum ConformanceScenario {
    IG_ACTOR("IG-ACTOR"),
    IG_READONLY("IG-READONLY"),
    APPLY_UPDATE("APPLY-UPDATE"),
    APPLY_CREATE("APPLY-CREATE"),
    APPLY_DELETE("APPLY-DELETE");

    private final String displayName;

    ConformanceScenario(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * @return the five scenarios in fixed canonical order.
     */
    public static List<ConformanceScenario> canonicalOrder() {
        return List.of(values());
    }
}