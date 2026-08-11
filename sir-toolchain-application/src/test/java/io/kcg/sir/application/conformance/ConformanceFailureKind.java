package io.kcg.sir.application.conformance;

/**
 * Stable failure taxonomy for conformance runs. The first causal failure is
 * primary; cleanup failures are additional unless cleanup is the first failure.
 *
 * <p>Every non-precondition terminal path is {@code FAILED}.
 */
public enum ConformanceFailureKind {
    /** Tool, path, credential shape, port, reference tuple, or server prerequisite. */
    PRECONDITION,
    /** work/evidence/schema name exists or advisory lock cannot be acquired. */
    PRECONDITION_CONFLICT,
    /** Initial generation, Registration, or Apply. */
    MATERIALIZE,
    /** File/graph/Snapshot or normalized evidence drift. */
    DETERMINISM,
    /** Generated Maven project build. */
    BUILD,
    /** Positive Context or expected negative startup. */
    STARTUP,
    /** Route, method, status, binding, or response. */
    HTTP,
    /** Invalid input does not stop at 400. */
    VALIDATION,
    /** Seed, query, insert, actor ownership, or zero-write assertion. */
    DATABASE,
    /** Spring process exit unproved. */
    CLEANUP_PROCESS_REMAINS,
    /** workRoot/schema ownership, identity, lock, inventory, DROP, or release. */
    CLEANUP_OWNERSHIP_UNPROVED,
    /** Redaction or secret scan. */
    HARNESS_CREDENTIAL_BOUNDARY,
    /** Internal fixture/state/evidence contract. */
    HARNESS
}