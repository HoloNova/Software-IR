package io.kcg.sir.application.conformance;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Terminal result of one conformance run. Exactly one of:
 *
 * <pre>
 * QUALIFIED
 * FAILED
 * NOT_RUN
 * </pre>
 *
 * <p>{@code QUALIFIED} is allowed only when every required scenario and
 * independent assertion passed, every spawned Spring process is proved
 * terminated, every schema created by the run is safely dropped and proved
 * absent, every ephemeral workRoot is safely cleaned, no residue remains,
 * and sanitized evidence is completely published. {@code QUALIFIED} and any
 * cleanup warning are mutually exclusive.
 *
 * <p>{@code FAILED} is any failure after the run crosses a side-effect
 * boundary. The primary {@link ConformanceFailure} and any additional
 * cleanup failures are recorded.
 *
 * <p>{@code NOT_RUN} is permitted only before SIR generation, Registration,
 * Apply, Spring process startup, or schema creation/modification. The
 * {@code failure} carries a {@link ConformanceFailureKind#PRECONDITION} or
 * {@link ConformanceFailureKind#PRECONDITION_CONFLICT} kind.
 */
public sealed interface ConformanceResult
        permits ConformanceResult.Qualified,
                ConformanceResult.Failed,
                ConformanceResult.NotRun {

    /**
     * @return the scenario results in canonical order, if any scenarios ran.
     */
    Optional<List<ScenarioOutcome>> scenarioOutcomes();

    record Qualified(Optional<List<ScenarioOutcome>> scenarioOutcomes) implements ConformanceResult {
        public Qualified {
            Objects.requireNonNull(scenarioOutcomes, "scenarioOutcomes");
        }
    }

    record Failed(
            ConformanceFailure primaryFailure,
            List<ConformanceFailure> additionalFailures,
            Optional<List<ScenarioOutcome>> scenarioOutcomes
    ) implements ConformanceResult {
        public Failed {
            Objects.requireNonNull(primaryFailure, "primaryFailure");
            additionalFailures = List.copyOf(Objects.requireNonNull(additionalFailures, "additionalFailures"));
            Objects.requireNonNull(scenarioOutcomes, "scenarioOutcomes");
        }

        public static Failed of(ConformanceFailure primary) {
            return new Failed(primary, List.of(), Optional.empty());
        }

        public static Failed of(ConformanceFailure primary, List<ConformanceFailure> additional) {
            return new Failed(primary, additional, Optional.empty());
        }
    }

    record NotRun(ConformanceFailure failure) implements ConformanceResult {
        public NotRun {
            Objects.requireNonNull(failure, "failure");
        }

        @Override
        public Optional<List<ScenarioOutcome>> scenarioOutcomes() {
            return Optional.empty();
        }
    }

    /**
     * Per-scenario outcome recorded in canonical scenario order.
     *
     * @param scenarioName  the scenario name (IG-ACTOR, etc.)
     * @param passed        whether the scenario passed all its assertions
     * @param failure       the failure if not passed, else empty
     */
    record ScenarioOutcome(String scenarioName, boolean passed, Optional<ConformanceFailure> failure) {
        public ScenarioOutcome {
            Objects.requireNonNull(scenarioName, "scenarioName");
            Objects.requireNonNull(failure, "failure");
        }
    }
}