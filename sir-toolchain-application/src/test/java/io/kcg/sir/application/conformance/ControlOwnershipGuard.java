package io.kcg.sir.application.conformance;

import java.sql.SQLException;
import java.util.Objects;

/**
 * Single fail-closed guard that verifies control-connection ownership at
 * every run boundary (ADR-017 搂7.2 / 搂10.4).
 *
 * <p>The guard simultaneously verifies <em>all four</em> conditions:
 * <pre>
 *   original control connection (not closed, not replaced)
 *   AND unchanged CONNECTION_ID()
 *   AND expected server UUID matches observed
 *   AND IS_USED_LOCK(lockName) == captured control connection ID
 * </pre>
 *
 * <p>Any single failure (or any {@link SQLException} while querying) causes
 * the guard to fail closed. The guard never repairs the connection, never
 * re-acquires the lock, and never opens a new connection.
 *
 * <p>The {@link Boundary} enum lists every real run-path boundary at which
 * the guard must be invoked. The {@link #requireAtBoundary} method returns
 * a {@link GuardResult} that the orchestrator uses to choose between
 * {@code NOT_RUN(PRECONDITION_CONFLICT)} (pre-schema-create) and
 * {@code FAILED(CLEANUP_OWNERSHIP_UNPROVED)} (post-schema-create).
 */
public final class ControlOwnershipGuard {

    /**
     * Every real run-path boundary at which the guard must be invoked.
     * The orchestrator must call {@link #requireAtBoundary} before each
     * of these boundaries in the real execution path.
     */
    public enum Boundary {
        /** Before the parameterized schema-absence query. */
        SCHEMA_ABSENCE,
        /** Before CREATE DATABASE. */
        SCHEMA_CREATE,
        /** Before owner-marker creation. */
        MARKER_CREATE,
        /** Before fixture DDL execution. */
        FIXTURE_DDL,
        /** Before fixture seed execution. */
        FIXTURE_SEED,
        /** Before creation inventory query. */
        CREATION_INVENTORY,
        /** Before each SIR generation / materialization. */
        GENERATION,
        /** Before each Registration. */
        REGISTRATION,
        /** Before each Apply. */
        APPLY,
        /** Before each Spring process start. */
        SPRING_START,
        /** Before each schema inventory query (cleanup proof). */
        SCHEMA_INVENTORY,
        /** Before cleanup begins (MYSQL_LOCK_RECHECK / MYSQL_INVENTORY_PROOF). */
        CLEANUP_START,
        /** Before DROP DATABASE. */
        DROP,
        /** Before the post-DROP absence proof query. */
        POST_DROP_ABSENCE_PROOF
    }

    /**
     * Immutable result of a guard evaluation.
     *
     * @param passed        true iff all four conditions are satisfied
     * @param boundary      the boundary at which the guard was invoked
     * @param failureReason a sanitized reason when {@code passed == false}
     *                      (never null; empty when passed)
     */
    public record GuardResult(boolean passed, Boundary boundary, String failureReason) {
        public GuardResult {
            Objects.requireNonNull(boundary, "boundary");
            if (failureReason == null) {
                failureReason = "";
            }
        }
    }

    private ControlOwnershipGuard() {
    }

    /**
     * Evaluate the guard against a live {@link MysqlControlSession} at the
     * given boundary. Any {@link SQLException} while querying causes the
     * guard to fail closed 鈥?the connection is considered lost.
     *
     * @param control             the dedicated control session (must be non-null)
     * @param expectedServerUuid  the expected MySQL server UUID
     * @param boundary            the boundary at which the guard is invoked
     * @return the guard result (passed iff all four conditions hold)
     */
    public static GuardResult requireAtBoundary(MysqlControlSession control,
                                                String expectedServerUuid,
                                                Boundary boundary) {
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(expectedServerUuid, "expectedServerUuid");
        Objects.requireNonNull(boundary, "boundary");
        try {
            boolean connOriginal = control.isConnectionOriginal();
            boolean connIdOk = control.connectionIdUnchanged();
            boolean uuidOk = control.serverUuidMatches(expectedServerUuid);
            boolean lockOk = control.lockHeldByThisConnection();
            return evaluate(connOriginal, connIdOk, uuidOk, lockOk, boundary);
        } catch (SQLException e) {
            return new GuardResult(false, boundary,
                    "sql-exception: " + sanitizedSqlMessage(e));
        }
    }

    /**
     * Pure evaluation of the four ownership conditions. Exposed for unit
     * tests that cannot open a real MySQL connection.
     *
     * @param connOriginal   true iff the connection is the original (not closed/replaced)
     * @param connIdUnchanged true iff CONNECTION_ID() is unchanged
     * @param uuidMatches    true iff the observed server UUID matches expected
     * @param lockHeld       true iff IS_USED_LOCK(lockName) == captured connection ID
     * @param boundary       the boundary at which the guard is invoked
     * @return the guard result (passed iff all four are true)
     */
    public static GuardResult evaluate(boolean connOriginal,
                                       boolean connIdUnchanged,
                                       boolean uuidMatches,
                                       boolean lockHeld,
                                       Boundary boundary) {
        Objects.requireNonNull(boundary, "boundary");
        if (connOriginal && connIdUnchanged && uuidMatches && lockHeld) {
            return new GuardResult(true, boundary, "");
        }
        StringBuilder reason = new StringBuilder();
        if (!connOriginal) {
            reason.append("connection-not-original;");
        }
        if (!connIdUnchanged) {
            reason.append("connection-id-changed;");
        }
        if (!uuidMatches) {
            reason.append("server-uuid-mismatch;");
        }
        if (!lockHeld) {
            reason.append("lock-not-held-by-this-connection;");
        }
        return new GuardResult(false, boundary, reason.toString());
    }

    private static String sanitizedSqlMessage(SQLException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return "SQLState=" + e.getSQLState();
        }
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
