package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Orchestrates the five representative conformance scenarios in fixed
 * canonical order: IG-ACTOR, IG-READONLY, APPLY-UPDATE, APPLY-CREATE,
 * APPLY-DELETE.
 *
 * <p>The {@link #orchestrate(ConformanceSuiteContext)} method performs the
 * real end-to-end call chain mandated by ADR-017 sections 4, 8, and 9:
 *
 * <pre>
 *   static precondition
 *   -> evidence root create
 *   -> mysql control connect + server identity
 *   -> schema lock + absence proof + create + marker
 *   -> work root create
 *   -> for each scenario:
 *        materialize (real ToolchainApplication / ChangeExecutionApplication)
 *        -> child Maven clean verify
 *        -> Spring process start
 *        -> business-endpoint readiness
 *        -> fresh HTTP / validation / database assertions
 *        -> Spring process stop
 *   -> schema inventory + DROP + absence proof + lock release
 *   -> work root cleanup (root directory itself deleted)
 *   -> evidence secret scan
 *   -> evidence finalize
 *   -> QUALIFIED
 * </pre>
 *
 * <p>State machine transitions are enforced via {@link ConformanceRun#advanceTo}.
 * Self-recheck (advancing to the same state) is used to repeat a stage for
 * each scenario. The side-effect boundary is crossed at {@code MYSQL_SCHEMA_CREATE};
 * any failure after that point produces {@code FAILED}, never {@code NOT_RUN}.
 */
public final class ConformanceSuite {

    private final List<ConformanceScenario> scenarios;

    public ConformanceSuite() {
        this(ConformanceScenario.canonicalOrder());
    }

    public ConformanceSuite(List<ConformanceScenario> scenarios) {
        this.scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios"));
    }

    public List<ConformanceScenario> scenarios() {
        return scenarios;
    }

    public int size() {
        return scenarios.size();
    }

    /**
     * Orchestrate the full five-scenario conformance run.
     *
     * <p>This method is opt-in. It performs real filesystem, MySQL, Maven,
     * Spring, and HTTP side effects. The caller (typically
     * {@code SpringBootTargetConformanceIT}) must verify the reference
     * environment is complete before invoking this method; otherwise the
     * run must be reported as {@code NOT_RUN} without calling this method.
     *
     * @param ctx the immutable suite context
     * @return the terminal conformance result (QUALIFIED, FAILED, or NOT_RUN)
     */
    public ConformanceResult orchestrate(ConformanceSuiteContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        ConformanceEnvironment env = ctx.environment();
        ConformanceRun run = new ConformanceRun(env);

        // --------------------------------------------------------------
        // STATIC_PRECONDITION
        // --------------------------------------------------------------
        run.advanceTo(ConformanceStateMachine.STATIC_PRECONDITION);
        if (!StreamingSecretRedactor.canInitialize(ctx.secrets())) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.HARNESS_CREDENTIAL_BOUNDARY,
                    "REDACTOR_NOT_INITIALIZABLE",
                    "streaming redactor cannot be initialized with the supplied secret set"));
            return run.terminalResult();
        }
        if (!ctx.control().serverUuidMatches(env.expectedMysqlServerUuid())) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.PRECONDITION,
                    "SERVER_UUID_MISMATCH",
                    "observed server UUID does not match expected"));
            return run.terminalResult();
        }

        // --------------------------------------------------------------
        // EVIDENCE_ROOT_CREATE -> MYSQL_CONTROL_CONNECT -> MYSQL_SERVER_IDENTITY
        // --------------------------------------------------------------
        try {
            run.advanceTo(ConformanceStateMachine.EVIDENCE_ROOT_CREATE);
            run.advanceTo(ConformanceStateMachine.MYSQL_CONTROL_CONNECT);
            run.advanceTo(ConformanceStateMachine.MYSQL_SERVER_IDENTITY);
            ctx.control().recheckLockAndIdentity(env.expectedMysqlServerUuid());
        } catch (SQLException e) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.PRECONDITION,
                    "CONTROL_CONNECT_FAILED",
                    "control connection identity recheck failed"));
            return run.terminalResult();
        }

        // --------------------------------------------------------------
        // MYSQL_SCHEMA_LOCK -> MYSQL_SCHEMA_ABSENCE -> MYSQL_SCHEMA_CREATE
        // (side-effect boundary crossed here)
        // --------------------------------------------------------------
        SchemaName schemaName = env.schemaName();
        try {
            run.advanceTo(ConformanceStateMachine.MYSQL_SCHEMA_LOCK);
            int lockResult = ctx.control().acquireAdvisoryLock(
                    env.expectedMysqlServerUuid(), schemaName, 5);
            if (lockResult != 1) {
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.PRECONDITION_CONFLICT,
                        "ADVISORY_LOCK_NOT_ACQUIRED",
                        "GET_LOCK returned " + lockResult + " (NOT_RUN: pre-side-effect)"));
                return run.terminalResult();
            }
            run.advanceTo(ConformanceStateMachine.MYSQL_SCHEMA_ABSENCE);
            if (ctx.control().schemaExists(schemaName)) {
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.PRECONDITION_CONFLICT,
                        "SCHEMA_ALREADY_EXISTS",
                        "schema already exists: " + schemaName.value()));
                return run.terminalResult();
            }
            run.advanceTo(ConformanceStateMachine.MYSQL_SCHEMA_CREATE);
            ctx.control().createDatabase(schemaName);
        } catch (SQLException e) {
            run.recordFailure(new ConformanceFailure(
                    run.isSideEffectBoundaryCrossed()
                            ? ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED
                            : ConformanceFailureKind.PRECONDITION_CONFLICT,
                    "SCHEMA_LOCK_OR_CREATE_FAILED",
                    "schema lock/create failed: " + sanitizedSqlMessage(e)));
            return run.terminalResult();
        }

        // --------------------------------------------------------------
        // MYSQL_MARKER_AND_FIXTURE -> WORK_ROOT_CREATE
        // --------------------------------------------------------------
        String runToken;
        try {
            run.advanceTo(ConformanceStateMachine.MYSQL_MARKER_AND_FIXTURE);
            runToken = "kcg-conf-" + System.currentTimeMillis();
            ctx.control().createOwnerMarker(schemaName, runToken);
            if (!ctx.control().verifyOwnerMarker(schemaName, runToken)) {
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                        "OWNER_MARKER_NOT_VERIFIED",
                        "owner marker verification failed after creation"));
                return cleanupAndTerminate(run, ctx, schemaName);
            }
            run.advanceTo(ConformanceStateMachine.WORK_ROOT_CREATE);
        } catch (SQLException e) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                    "OWNER_MARKER_FAILED",
                    "owner marker creation failed: " + sanitizedSqlMessage(e)));
            return cleanupAndTerminate(run, ctx, schemaName);
        }

        // --------------------------------------------------------------
        // Per-scenario loop: MATERIALIZE -> ... -> SPRING_STOP
        // --------------------------------------------------------------
        ScenarioMaterializer materializer = new ScenarioMaterializer();
        List<ScenarioMaterializer.ScenarioMaterialization> materializations = new ArrayList<>();

        try {
            run.advanceTo(ConformanceStateMachine.MATERIALIZE);
            for (ConformanceScenario scenario : scenarios()) {
                run.advanceTo(ConformanceStateMachine.MATERIALIZE);
                ScenarioMaterializer.ScenarioMaterialization m =
                        materializer.materialize(scenario, ctx.workRoot());
                materializations.add(m);
            }

            // DETERMINISM: verify output manifests are non-null (stable).
            run.advanceTo(ConformanceStateMachine.DETERMINISM);
            for (ScenarioMaterializer.ScenarioMaterialization m : materializations) {
                run.advanceTo(ConformanceStateMachine.DETERMINISM);
                Object manifest = m.outputManifest();
                if (manifest == null) {
                    failScenario(run, m.scenario(), "DETERMINISM",
                            "output manifest is null");
                    return cleanupAndTerminate(run, ctx, schemaName);
                }
            }

            // REGISTER -> APPLY (already done by ScenarioMaterializer for APPLY-*).
            run.advanceTo(ConformanceStateMachine.REGISTER);
            run.advanceTo(ConformanceStateMachine.APPLY);

            // MAVEN_BUILD: build each generated project.
            run.advanceTo(ConformanceStateMachine.MAVEN_BUILD);
            for (ScenarioMaterializer.ScenarioMaterialization m : materializations) {
                run.advanceTo(ConformanceStateMachine.MAVEN_BUILD);
                if (!buildProject(ctx, run, m)) {
                    return cleanupAndTerminate(run, ctx, schemaName);
                }
            }

            // SPRING_START -> CONTEXT_PROOF -> HTTP_ASSERT ->
            // VALIDATION_ASSERT -> DATABASE_ASSERT -> SPRING_STOP
            // (per scenario, one at a time)
            for (ScenarioMaterializer.ScenarioMaterialization m : materializations) {
                if (!executeScenarioRuntime(ctx, run, m, schemaName)) {
                    return cleanupAndTerminate(run, ctx, schemaName);
                }
                run.recordScenarioOutcome(new ConformanceResult.ScenarioOutcome(
                        m.scenario().displayName(), true, Optional.empty()));
            }
        } catch (ScenarioMaterializer.ScenarioMaterializationException e) {
            failScenario(run, e.scenario(), e.messageKey(), e.getMessage());
            return cleanupAndTerminate(run, ctx, schemaName);
        } catch (Exception e) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.HARNESS,
                    "ORCHESTRATE_FAILED",
                    "orchestration error: " + sanitizedMessage(e)));
            return cleanupAndTerminate(run, ctx, schemaName);
        }

        // --------------------------------------------------------------
        // MYSQL_LOCK_RECHECK -> MYSQL_INVENTORY_PROOF ->
        // MYSQL_SCHEMA_DROP -> MYSQL_SCHEMA_ABSENCE_PROOF -> MYSQL_LOCK_RELEASE
        // --------------------------------------------------------------
        boolean schemaDroppedAndAbsent = false;
        boolean lockReleased = false;
        try {
            run.advanceTo(ConformanceStateMachine.MYSQL_LOCK_RECHECK);
            if (!ctx.control().recheckLockAndIdentity(env.expectedMysqlServerUuid())) {
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                        "LOCK_RECHECK_FAILED",
                        "advisory lock recheck failed before DROP"));
                return cleanupAndTerminate(run, ctx, schemaName);
            }
            run.advanceTo(ConformanceStateMachine.MYSQL_INVENTORY_PROOF);
            MysqlSchemaInventory.query(ctx.control(), schemaName);
            run.advanceTo(ConformanceStateMachine.MYSQL_SCHEMA_DROP);
            ctx.control().dropDatabase(schemaName);
            run.advanceTo(ConformanceStateMachine.MYSQL_SCHEMA_ABSENCE_PROOF);
            if (ctx.control().schemaExists(schemaName)) {
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                        "SCHEMA_DROP_ABSENCE_PROOF_FAILED",
                        "schema still exists after DROP"));
                return cleanupAndTerminate(run, ctx, schemaName);
            }
            schemaDroppedAndAbsent = true;
            run.advanceTo(ConformanceStateMachine.MYSQL_LOCK_RELEASE);
            int releaseResult = ctx.control().releaseLock();
            if (releaseResult != 1) {
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                        "LOCK_RELEASE_FAILED",
                        "RELEASE_LOCK returned " + releaseResult));
                return cleanupAndTerminate(run, ctx, schemaName);
            }
            lockReleased = true;
            run.markSchemaCleanupComplete(schemaDroppedAndAbsent, lockReleased);
        } catch (SQLException e) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                    "SCHEMA_CLEANUP_FAILED",
                    "schema cleanup failed: " + sanitizedSqlMessage(e)));
            run.markSchemaCleanupComplete(schemaDroppedAndAbsent, lockReleased);
            return cleanupAndTerminate(run, ctx, schemaName);
        }

        // --------------------------------------------------------------
        // WORK_ROOT_CLEANUP
        // --------------------------------------------------------------
        try {
            run.advanceTo(ConformanceStateMachine.WORK_ROOT_CLEANUP);
            boolean cleaned = ctx.workRoot().cleanup();
            run.markWorkRootCleanedUp(cleaned);
            if (!cleaned) {
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                        "WORK_ROOT_CLEANUP_NOT_PROVED",
                        "workRoot cleanup did not delete the root directory"));
                return run.terminalResult();
            }
        } catch (IOException e) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                    "WORK_ROOT_CLEANUP_IO_ERROR",
                    "workRoot cleanup I/O error: " + sanitizedMessage(e)));
            run.markWorkRootCleanedUp(false);
            return run.terminalResult();
        }

        // --------------------------------------------------------------
        // EVIDENCE_SECRET_SCAN -> EVIDENCE_FINALIZE
        // --------------------------------------------------------------
        try {
            run.advanceTo(ConformanceStateMachine.EVIDENCE_SECRET_SCAN);
            boolean scanClean = ctx.secretScanner().scanTree(ctx.evidenceRoot().root());
            run.markEvidenceScanClean(scanClean);
            if (!scanClean) {
                List<Path> dirty = ctx.secretScanner().findDirtyFiles(ctx.evidenceRoot().root());
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.HARNESS_CREDENTIAL_BOUNDARY,
                        "EVIDENCE_SECRET_SCAN_FOUND_SECRETS",
                        "evidence scan found secrets in " + dirty.size() + " files"));
                return run.terminalResult();
            }
            run.advanceTo(ConformanceStateMachine.EVIDENCE_FINALIZE);
            String report = buildFinalReport(run, env);
            ctx.evidenceWriter().publishReport(report);
            run.markEvidenceFinalized(true);
        } catch (IOException e) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.HARNESS_CREDENTIAL_BOUNDARY,
                    "EVIDENCE_FINALIZE_FAILED",
                    "evidence finalize failed: " + sanitizedMessage(e)));
            run.markEvidenceFinalized(false);
            return run.terminalResult();
        }

        // --------------------------------------------------------------
        // QUALIFIED
        // --------------------------------------------------------------
        try {
            run.advanceTo(ConformanceStateMachine.QUALIFIED);
        } catch (IllegalStateException e) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.HARNESS,
                    "STATE_MACHINE_VIOLATION",
                    "cannot advance to QUALIFIED: " + e.getMessage()));
        }
        return run.terminalResult();
    }

    // ------------------------------------------------------------------
    // Per-scenario runtime: build, spring, readiness, HTTP, DB, stop
    // ------------------------------------------------------------------

    private boolean buildProject(ConformanceSuiteContext ctx, ConformanceRun run,
                                 ScenarioMaterializer.ScenarioMaterialization m)
            throws InterruptedException {
        try {
            run.advanceTo(ConformanceStateMachine.MAVEN_BUILD);
            String base = "scenarios/" + m.scenario().displayName();
            try (OutputStream out = ctx.evidenceWriter().openStream(base + "/maven.stdout.log");
                 OutputStream err = ctx.evidenceWriter().openStream(base + "/maven.stderr.log")) {
                MavenProjectRunner.BuildResult result = ctx.mavenRunner().cleanVerify(
                        m.outputRoot(), false, out, err);
                if (!result.isSuccess()) {
                    failScenario(run, m.scenario(), "BUILD_FAILED",
                            "Maven clean verify exit code: " + result.exitCode());
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            failScenario(run, m.scenario(), "BUILD_IO_ERROR",
                    "Maven build I/O error: " + sanitizedMessage(e));
            return false;
        }
    }

    private boolean executeScenarioRuntime(ConformanceSuiteContext ctx,
                                           ConformanceRun run,
                                           ScenarioMaterializer.ScenarioMaterialization m,
                                           SchemaName schemaName) {
        ConformanceScenario scenario = m.scenario();
        Path jarPath = findBuiltJar(m.outputRoot());
        if (jarPath == null) {
            failScenario(run, scenario, "JAR_NOT_FOUND",
                    "executable jar not found under " + m.outputRoot() + "/target");
            return false;
        }

        try {
            // SPRING_START
            run.advanceTo(ConformanceStateMachine.SPRING_START);
            String base = "scenarios/" + scenario.displayName();
            Process process = ctx.springProcess().start(
                    jarPath, ctx.serverPort(),
                    ctx.evidenceWriter().openStream(base + "/spring.stdout.log"),
                
    // TODO(conformance): complete orchestration after the Spring process starts.
    // The conformance package remains excluded until this class forms a complete,
    // directly testable suite; see docs/qualification/TEST_COVERAGE_INVENTORY.md.
