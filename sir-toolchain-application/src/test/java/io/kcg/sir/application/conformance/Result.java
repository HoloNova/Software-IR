package io.kcg.sir.application.conformance;

import java.util.Objects;

/**
 * Outcome of one structured manifest/transaction verification.
 *
 * <p>A failure always carries a stable message key so the orchestration can map it
 * onto a {@link ConformanceFailure} without parsing prose.
 */
public sealed interface Result permits Result.Success, Result.Failure {

    /** All assertions for this verification step passed. */
    record Success() implements Result {}

    /**
     * An assertion failed.
     *
     * @param key     stable machine-readable failure key
     * @param message short human-readable explanation (never carries a credential)
     */
    record Failure(String key, String message) implements Result {
        public Failure {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(message, "message");
        }
    }
}
