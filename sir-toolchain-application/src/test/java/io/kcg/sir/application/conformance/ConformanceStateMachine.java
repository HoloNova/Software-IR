package io.kcg.sir.application.conformance;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Strict state machine for conformance runs. The only permitted order is:
 *
 * <pre>
 * STATIC_PRECONDITION
 *  -> EVIDENCE_ROOT_CREATE
 *  -> MYSQL_CONTROL_CONNECT
 *  -> MYSQL_SERVER_IDENTITY
 *  -> MYSQL_SCHEMA_LOCK
 *  -> MYSQL_SCHEMA_ABSENCE
 *  -> MYSQL_SCHEMA_CREATE
 *  -> MYSQL_MARKER_AND_FIXTURE
 *  -> WORK_ROOT_CREATE
 *  -> MATERIALIZE
 *  -> DETERMINISM
 *  -> [REGISTER -> APPLY]
 *  -> MAVEN_BUILD
 *  -> SPRING_START
 *  -> CONTEXT_PROOF
 *  -> HTTP_ASSERT
 *  -> VALIDATION_ASSERT
 *  -> DATABASE_ASSERT
 *  -> SPRING_STOP
 *  -> MYSQL_LOCK_RECHECK
 *  -> MYSQL_INVENTORY_PROOF
 *  -> MYSQL_SCHEMA_DROP
 *  -> MYSQL_SCHEMA_ABSENCE_PROOF
 *  -> MYSQL_LOCK_RELEASE
 *  -> WORK_ROOT_CLEANUP
 *  -> EVIDENCE_SECRET_SCAN
 *  -> EVIDENCE_FINALIZE
 *  -> QUALIFIED
 * </pre>
 *
 * <p>Only <em>adjacent</em> forward transitions are permitted. Skipping a
 * stage is forbidden. The only legal non-adjacent transition is
 * {@code current == next} (self-recheck), which is allowed exclusively for
 * lock revalidation, server-identity rechecks, and inventory rechecks
 * explicitly named by ADR-017 搂7.2/搂10.4.
 *
 * <p>{@code NOT_RUN} is available only while all of the following have not
 * begun: SIR generation, Registration, Apply, Spring process startup, schema
 * creation or modification. The side-effect boundary is crossed at
 * {@code MYSQL_SCHEMA_CREATE}. After that, any failure must be {@code FAILED}.
 */
public enum ConformanceStateMachine {
    STATIC_PRECONDITION,
    EVIDENCE_ROOT_CREATE,
    MYSQL_CONTROL_CONNECT,
    MYSQL_SERVER_IDENTITY,
    MYSQL_SCHEMA_LOCK,
    MYSQL_SCHEMA_ABSENCE,
    MYSQL_SCHEMA_CREATE,
    MYSQL_MARKER_AND_FIXTURE,
    WORK_ROOT_CREATE,
    MATERIALIZE,
    DETERMINISM,
    REGISTER,
    APPLY,
    MAVEN_BUILD,
    SPRING_START,
    CONTEXT_PROOF,
    HTTP_ASSERT,
    VALIDATION_ASSERT,
    DATABASE_ASSERT,
    SPRING_STOP,
    MYSQL_LOCK_RECHECK,
    MYSQL_INVENTORY_PROOF,
    MYSQL_SCHEMA_DROP,
    MYSQL_SCHEMA_ABSENCE_PROOF,
    MYSQL_LOCK_RELEASE,
    WORK_ROOT_CLEANUP,
    EVIDENCE_SECRET_SCAN,
    EVIDENCE_FINALIZE,
    QUALIFIED;

    /**
     * @return true if this state is in the repeatable per-scenario block
     */
    public boolean isInScenarioBlock() {
        return SCENARIO_BLOCK.contains(this);
    }

    /**
     * The repeatable per-scenario block, re-entered once per scenario in canonical order.
     *
     * <p>All five scenarios are run end to end, one at a time: each owns its own
     * generated project, its own Spring process on the run's loopback port, and its own
     * HTTP and database assertions. The block is therefore re-entered for each scenario
     * rather than executed once for all five.
     */
    private static final Set<ConformanceStateMachine> SCENARIO_BLOCK =
            EnumSet.of(
                    MATERIALIZE,
                    DETERMINISM,
                    REGISTER,
                    APPLY,
                    MAVEN_BUILD,
                    SPRING_START,
                    CONTEXT_PROOF,
                    HTTP_ASSERT,
                    VALIDATION_ASSERT,
                    DATABASE_ASSERT,
                    SPRING_STOP);

    /**
     * The states at or after which the side-effect boundary is crossed.
     * Once any of these has begun, failures must be {@code FAILED}, not
     * {@code NOT_RUN}.
     */
    private static final Set<ConformanceStateMachine> SIDE_EFFECT_BOUNDARY =
            EnumSet.of(
                    MYSQL_SCHEMA_CREATE,
                    MYSQL_MARKER_AND_FIXTURE,
                    WORK_ROOT_CREATE,
                    MATERIALIZE,
                    DETERMINISM,
                    REGISTER,
                    APPLY,
                    MAVEN_BUILD,
                    SPRING_START,
                    CONTEXT_PROOF,
                    HTTP_ASSERT,
                    VALIDATION_ASSERT,
                    DATABASE_ASSERT,
                    SPRING_STOP,
                    MYSQL_LOCK_RECHECK,
                    MYSQL_INVENTORY_PROOF,
                    MYSQL_SCHEMA_DROP,
                    MYSQL_SCHEMA_ABSENCE_PROOF,
                    MYSQL_LOCK_RELEASE,
                    WORK_ROOT_CLEANUP,
                    EVIDENCE_SECRET_SCAN,
                    EVIDENCE_FINALIZE,
                    QUALIFIED);

    /**
     * The pre-side-effect states where {@code NOT_RUN} is still available.
     */
    private static final Set<ConformanceStateMachine> PRE_BOUNDARY =
            EnumSet.of(
                    STATIC_PRECONDITION,
                    EVIDENCE_ROOT_CREATE,
                    MYSQL_CONTROL_CONNECT,
                    MYSQL_SERVER_IDENTITY,
                    MYSQL_SCHEMA_LOCK,
                    MYSQL_SCHEMA_ABSENCE);

    /**
     * @return true if this state is at or after the side-effect boundary
     *         (schema creation). A failure at or after this point must be
     *         {@code FAILED}, not {@code NOT_RUN}.
     */
    public boolean isAtOrAfterSideEffectBoundary() {
        return SIDE_EFFECT_BOUNDARY.contains(this);
    }

    /**
     * @return true if this state is strictly before the side-effect boundary.
     *         A failure here may be {@code NOT_RUN}.
     */
    public boolean isBeforeSideEffectBoundary() {
        return PRE_BOUNDARY.contains(this);
    }

    /**
     * @return true if {@code next} is a valid forward transition from
     *         {@code current}. {@code current == next} is allowed (self-recheck
     *         for lock revalidation). Any other non-adjacent forward jump is
     *         forbidden 鈥?skipping a stage is not permitted.
     */
    /**
     * Validate a state transition.
     *
     * <p>Three transitions are permitted, and nothing else:
     * <ol>
     *   <li><b>Self-recheck</b> ({@code next == current}): repeat the current stage, which
     *       is how one stage processes several items (for example one artifact per
     *       scenario).</li>
     *   <li><b>Adjacent forward</b> ({@code next} is the immediately following stage):
     *       never skipping.</li>
     *   <li>The two block transitions the real run path needs:
     *     <ul>
     *       <li>re-entering the repeatable per-scenario block at {@code MATERIALIZE} from
     *           any state inside that block — the second and later scenarios run the whole
     *           block again, because each scenario starts its own Spring process on the
     *           run's single loopback port and therefore cannot overlap with another;</li>
     *       <li>jumping to the cleanup phase start ({@code MYSQL_LOCK_RECHECK}) from any
     *           state inside the scenario block — a failing scenario must still reach the
     *           cleanup and proof phases, and the states it did not execute must not be
     *           reported as visited.</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>Run-level phases before and after the scenario block stay strictly adjacent, so
     * a skipped or reordered schema, cleanup, or evidence stage is still rejected.
     *
     * @param current the current state
     * @param next    the requested next state
     * @return true if the transition is permitted
     */
    public static boolean isValidForward(ConformanceStateMachine current,
                                         ConformanceStateMachine next) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(next, "next");
        if (current == next) {
            return true;
        }
        // Re-enter the repeatable per-scenario block for the next scenario.
        if (next == MATERIALIZE && current.isInScenarioBlock()) {
            return true;
        }
        // A failure inside the block still has to reach cleanup and proofs.
        if (next == MYSQL_LOCK_RECHECK && current.isInScenarioBlock()) {
            return true;
        }
        ConformanceStateMachine[] order = values();
        int currentIndex = -1;
        int nextIndex = -1;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == current) {
                currentIndex = i;
            }
            if (order[i] == next) {
                nextIndex = i;
            }
        }
        // Otherwise only the immediate next stage is permitted; no skipping.
        return nextIndex == currentIndex + 1;
    }
}