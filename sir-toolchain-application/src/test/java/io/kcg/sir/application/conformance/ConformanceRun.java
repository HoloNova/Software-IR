package io.kcg.sir.application.conformance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Context for one conformance run. Holds the environment, owned directories,
 * control session, evidence writer, and accumulates scenario outcomes and
 * failures. The run follows the strict {@link ConformanceStateMachine}
 * ordering.
 *
 * <p>Per ADR-017 搂3, {@code QUALIFIED} requires all of:
 * <ul>
 *   <li>all five canonical scenarios recorded as passed;</li>
 *   <li>no failures recorded;</li>
 *   <li>schema DROP + absence proof + lock release completed;</li>
 *   <li>workRoot cleanup completed (root directory itself deleted);</li>
 *   <li>evidence secret scan completed and clean;</li>
 *   <li>final sanitized evidence published.</li>
 * </ul>
 * Any warning, missing cleanup proof, missing evidence finalize, or zero
 * scenarios forbids {@code QUALIFIED}.
 */
public final class ConformanceRun {

    private final ConformanceEnvironment environment;
    private final List<ConformanceResult.ScenarioOutcome> scenarioOutcomes = new ArrayList<>();
    private final List<ConformanceFailure> failures = new ArrayList<>();
    private ConformanceStateMachine currentState = ConformanceStateMachine.STATIC_PRECONDITION;
    private boolean sideEffectBoundaryCrossed = false;
    private boolean schemaDroppedAndAbsent;
    private boolean lockReleased;
    private boolean workRootCleanedUp;
    private boolean evidenceScanClean;
    private boolean evidenceFinalized;

    public ConformanceRun(ConformanceEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public ConformanceEnvironment environment() {
        return environment;
    }

    /**
     * Advance to the next state. Validates forward ordering.
     *
     * @throws IllegalStateException if the transition skips a stage or moves
     *         backward. The only legal non-adjacent transition is
     *         {@code next == current} (self-recheck).
     */
    public synchronized void advanceTo(ConformanceStateMachine next) {
        Objects.requireNonNull(next, "next");
        if (!ConformanceStateMachine.isValidForward(currentState, next)) {
            throw new IllegalStateException(
                    "invalid state transition: " + currentState + " -> " + next
                            + " (only adjacent forward or self-recheck is permitted)");
        }
        currentState = next;
        if (currentState.isAtOrAfterSideEffectBoundary()) {
            sideEffectBoundaryCrossed = true;
        }
    }

    public synchronized ConformanceStateMachine currentState() {
        return currentState;
    }

    /**
     * Record a scenario outcome.
     */
    public synchronized void recordScenarioOutcome(ConformanceResult.ScenarioOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        scenarioOutcomes.add(outcome);
    }

    /**
     * Record a failure. The first failure is primary.
     */
    public synchronized void recordFailure(ConformanceFailure failure) {
        Objects.requireNonNull(failure, "failure");
        failures.add(failure);
    }

    /**
     * Mark schema DROP, absence proof, and lock release as completed.
     */
    public synchronized void markSchemaCleanupComplete(boolean droppedAndAbsent,
                                                       boolean lockReleased) {
        this.schemaDroppedAndAbsent = droppedAndAbsent;
        this.lockReleased = lockReleased;
    }

    /**
     * Mark workRoot cleanup (including root directory deletion) as completed.
     */
    public synchronized void markWorkRootCleanedUp(boolean cleaned) {
        this.workRootCleanedUp = cleaned;
    }

    /**
     * Mark evidence secret scan as completed (true only if scan found no
     * registered secret representation).
     */
    public synchronized void markEvidenceScanClean(boolean clean) {
        this.evidenceScanClean = clean;
    }

    /**
     * Mark final sanitized evidence as atomically published.
     */
    public synchronized void markEvidenceFinalized(boolean finalized) {
        this.evidenceFinalized = finalized;
    }

    /**
     * @return true if the side-effect boundary (schema creation) has been
     *         crossed. If so, any failure must be FAILED, not NOT_RUN.
     */
    public synchronized boolean isSideEffectBoundaryCrossed() {
        return sideEffectBoundaryCrossed;
    }

    /**
     * @return true iff all five canonical scenarios have been recorded as
     *         passed. Used by {@link #terminalResult()}.
     */
    private synchronized boolean allFiveScenariosPassed() {
        if (scenarioOutcomes.size() != ConformanceScenario.canonicalOrder().size()) {
            return false;
        }
        for (ConformanceResult.ScenarioOutcome outcome : scenarioOutcomes) {
            if (!outcome.passed()) {
                return false;
            }
        }
        // Verify canonical names match exactly.
        List<ConformanceScenario> canonical = ConformanceScenario.canonicalOrder();
        for (int i = 0; i < canonical.size(); i++) {
            if (!canonical.get(i).displayName().equals(scenarioOutcomes.get(i).scenarioName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compute the terminal result from the accumulated state.
     *
     * <p>{@code QUALIFIED} is allowed only when <em>all</em> of:
     * <ul>
     *   <li>all five canonical scenarios passed;</li>
     *   <li>no failures recorded;</li>
     *   <li>schema DROP + absence proof + lock release completed;</li>
     *   <li>workRoot cleanup completed;</li>
     *   <li>evidence secret scan completed and clean;</li>
     *   <li>final sanitized evidence published.</li>
     * </ul>
     * Any missing proof, warning, or failure forbids {@code QUALIFIED}.
     */
    public synchronized ConformanceResult terminalResult() {
        if (!failures.isEmpty()) {
            ConformanceFailure primary = failures.get(0);
            List<ConformanceFailure> additional = failures.size() > 1
                    ? List.copyOf(failures.subList(1, failures.size()))
                    : List.of();
            if (!sideEffectBoundaryCrossed
                    && (primary.kind() == ConformanceFailureKind.PRECONDITION
                    || primary.kind() == ConformanceFailureKind.PRECONDITION_CONFLICT)) {
                return new ConformanceResult.NotRun(primary);
            }
            return new ConformanceResult.Failed(primary, additional,
                    scenarioOutcomes.isEmpty()
                            ? java.util.Optional.empty()
                            : java.util.Optional.of(List.copyOf(scenarioOutcomes)));
        }
        // No failures recorded 鈥?but QUALIFIED requires all proofs.
        if (!allFiveScenariosPassed()) {
            // Insufficient scenarios 鈥?NOT_RUN if pre-boundary, else FAILED.
            ConformanceFailure insufficient = new ConformanceFailure(
                    sideEffectBoundaryCrossed
                            ? ConformanceFailureKind.HARNESS
                            : ConformanceFailureKind.PRECONDITION,
                    "SCENARIOS_INCOMPLETE",
                    "expected " + ConformanceScenario.canonicalOrder().size()
                            + " passed scenarios, got " + scenarioOutcomes.size());
            if (sideEffectBoundaryCrossed) {
                return new ConformanceResult.Failed(insufficient, List.of(),
                        java.util.Optional.empty());
            }
            return new ConformanceResult.NotRun(insufficient);
        }
        if (!schemaDroppedAndAbsent) {
            ConformanceFailure f = new ConformanceFailure(
                    ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                    "SCHEMA_DROP_NOT_PROVED",
                    "schema DROP + absence proof not completed");
            return new ConformanceResult.Failed(f, List.of(),
                    java.util.Optional.of(List.copyOf(scenarioOutcomes)));
        }
        if (!lockReleased) {
            ConformanceFailure f = new ConformanceFailure(
                    ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                    "LOCK_RELEASE_NOT_PROVED",
                    "advisory lock RELEASE_LOCK(?) did not return 1");
            return new ConformanceResult.Failed(f, List.of(),
                    java.util.Optional.of(List.copyOf(scenarioOutcomes)));
        }
        if (!workRootCleanedUp) {
            ConformanceFailure f = new ConformanceFailure(
                    ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                    "WORK_ROOT_CLEANUP_NOT_PROVED",
                    "workRoot cleanup did not delete the root directory");
            return new ConformanceResult.Failed(f, List.of(),
                    java.util.Optional.of(List.copyOf(scenarioOutcomes)));
        }
        if (!evidenceScanClean) {
            ConformanceFailure f = new ConformanceFailure(
                    ConformanceFailureKind.HARNESS_CREDENTIAL_BOUNDARY,
                    "EVIDENCE_SECRET_SCAN_NOT_CLEAN",
                    "evidence secret scan did not complete clean");
            return new ConformanceResult.Failed(f, List.of(),
                    java.util.Optional.of(List.copyOf(scenarioOutcomes)));
        }
        if (!evidenceFinalized) {
            ConformanceFailure f = new ConformanceFailure(
                    ConformanceFailureKind.HARNESS,
                    "EVIDENCE_NOT_PUBLISHED",
                    "final sanitized evidence report was not atomically published");
            return new ConformanceResult.Failed(f, List.of(),
                    java.util.Optional.of(List.copyOf(scenarioOutcomes)));
        }
        return new ConformanceResult.Qualified(
                java.util.Optional.of(List.copyOf(scenarioOutcomes)));
    }

    public synchronized List<ConformanceFailure> failures() {
        return List.copyOf(failures);
    }

    public synchronized List<ConformanceResult.ScenarioOutcome> scenarioOutcomes() {
        return List.copyOf(scenarioOutcomes);
    }
}