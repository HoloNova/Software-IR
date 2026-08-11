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
    public static boolean isValidForward(ConformanceStateMachine current,
                                         ConformanceStateMachine next) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(next, "next");
        if (current == next) {
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
        // Only the immediate next stage is permitted; no skipping.
        return nextIndex == currentIndex + 1;
    }
}