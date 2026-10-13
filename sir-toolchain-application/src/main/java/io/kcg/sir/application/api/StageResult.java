package io.kcg.sir.application.api;

import java.util.Objects;

/** One stage of a report. The duration is a measurement, not evidence, and is left out of the report digest. */
public record StageResult(VerificationStage stage, StageOutcome outcome, long elapsedMillis) {
    public StageResult {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(outcome, "outcome");
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must not be negative");
        }
    }

    public StageStatus status() {
        return outcome.status();
    }
}
