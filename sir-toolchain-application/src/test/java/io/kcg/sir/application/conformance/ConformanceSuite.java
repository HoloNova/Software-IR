package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

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
 *
 * <p><strong>INTERIM SCAFFOLD (Q4+Q5 merged work order)</strong>: only the
 * side-effect-free static preconditions are implemented. The orchestration above
 * is implemented by the same work order; this file currently reports
 * {@code ORCHESTRATION_NOT_IMPLEMENTED} instead of fabricating a verdict.
 */
public final class ConformanceSuite {

    /** Advisory-lock acquisition timeout, in seconds. */
    private static final int LOCK_TIMEOUT_SECONDS = 10;

    /** Fixture DDL resource applied into the owned schema. */
    private static final String FIXTURE_DDL_RESOURCE = "/conformance/mysql/campus-market-ddl.sql";

    /** Report file name inside the evidence root. */
    private static final String REPORT_FILE = "report.txt";

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
     * <p>Failure handling is fail-closed at every phase: a failure is recorded with a
     * stable key and the run stops advancing, but cleanup (Spring stop, lock recheck,
     * schema DROP, lock release, work-root cleanup) is still attempted so a failed run
     * leaves nothing behind. {@link ConformanceRun#terminalResult()} decides whether the
     * outcome is {@code NOT_RUN} (nothing had been created yet) or {@code FAILED}.
     *
     * @param ctx the immutable suite context
     * @return the terminal conformance result (QUALIFIED, FAILED, or NOT_RUN)
     */
    public ConformanceResult orchestrate(ConformanceSuiteContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        ConformanceEnvironment env = ctx.environment();
        SchemaName schemaName = env.schemaName();
        String expectedUuid = env.expectedMysqlServerUuid();
        ConformanceRun run = new ConformanceRun(env);
        RuntimeOps ops = new RealRuntimeOps();
        ScenarioMaterializer materializer = new ScenarioMaterializer();

        // --------------------------------------------------------------
        // STATIC_PRECONDITION (no side effects)
        // --------------------------------------------------------------
        run.advanceTo(ConformanceStateMachine.STATIC_PRECONDITION);
        if (!StreamingSecretRedactor.canInitialize(ctx.secrets())) {
            return fail(run, ConformanceFailureKind.HARNESS_CREDENTIAL_BOUNDARY,
                    "REDACTOR_NOT_INITIALIZABLE",
                    "streaming redactor cannot be initialized with the supplied secret set");
        }
        if (!ctx.control().serverUuidMatches(expectedUuid)) {
            return fail(run, ConformanceFailureKind.PRECONDITION,
                    "SERVER_UUID_MISMATCH",
                    "observed server UUID does not match expected");
        }

        // --------------------------------------------------------------
        // EVIDENCE_ROOT_CREATE
        // --------------------------------------------------------------
        run.advanceTo(ConformanceStateMachine.EVIDENCE_ROOT_CREATE);
        EvidenceDirectory evidenceRoot;
        EvidenceOwnershipInventory inventory;
        EvidenceWriter evidenceWriter;
        try {
            Path normalizedParent = ctx.evidenceParent().toAbsolutePath().normalize();
            evidenceRoot = EvidenceDirectory.create(ctx.evidenceParent(), "evidence-" + ctx.runToken());
            EvidenceRootProof rootProof = EvidenceRootProof.capture(
                    normalizedParent, evidenceRoot.root(), evidenceRoot.root());
            evidenceRoot.attachRootProof(rootProof);
            inventory = new EvidenceOwnershipInventory(evidenceRoot.root(), rootProof);
            evidenceWriter = EvidenceWriter.create(evidenceRoot, inventory);
        } catch (IOException e) {
            return fail(run, ConformanceFailureKind.PRECONDITION,
                    "EVIDENCE_ROOT_CREATE_FAILED",
                    "cannot create the evidence root: " + sanitized(e));
        }

        // --------------------------------------------------------------
        // MYSQL_CONTROL_CONNECT / MYSQL_SERVER_IDENTITY
        // --------------------------------------------------------------
        run.advanceTo(ConformanceStateMachine.MYSQL_CONTROL_CONNECT);
        run.advanceTo(ConformanceStateMachine.MYSQL_SERVER_IDENTITY);
        if (ctx.control().observedServerUuid() == null
                || ctx.control().observedServerVersion() == null) {
            return fail(run, ConformanceFailureKind.PRECONDITION,
                    "MYSQL_SERVER_IDENTITY_UNREADABLE",
                    "the control connection did not record the server identity");
        }

        // --------------------------------------------------------------
        // MYSQL_SCHEMA_LOCK
        // --------------------------------------------------------------
        run.advanceTo(ConformanceStateMachine.MYSQL_SCHEMA_LOCK);
        try {
            int acquired = ctx.control().acquireAdvisoryLock(expectedUuid, schemaName,
                    LOCK_TIMEOUT_SECONDS);
            if (acquired != 1) {
                return fail(run, ConformanceFailureKind.PRECONDITION_CONFLICT,
                        "SCHEMA_LOCK_NOT_ACQUIRED",
                        "GET_LOCK did not return 1 for schema " + schemaName.value()
                                + " (another conformance run may hold it)");
            }
        } catch (Exception e) {
            return fail(run, ConformanceFailureKind.PRECONDITION,
                    "SCHEMA_LOCK_FAILED", "advisory lock acquisition failed: " + sanitized(e));
        }

        // --------------------------------------------------------------
        // MYSQL_SCHEMA_ABSENCE (the schema must not already exist)
        // --------------------------------------------------------------
        run.advanceTo(ConformanceStateMachine.MYSQL_SCHEMA_ABSENCE);
        // The ownership guard verifies the lock it is about to rely on, so it runs after
        // the lock is acquired and before anything is created.
        if (guardFails(run, ctx, expectedUuid, ControlOwnershipGuard.Boundary.SCHEMA_ABSENCE)) {
            releaseLockQuietly(ctx);
            return run.terminalResult();
        }
        try {
            if (ctx.control().schemaExists(schemaName)) {
                // Nothing has been created yet, so this is a precondition conflict: the
                // run must not adopt, reuse, or drop a schema it did not create.
                releaseLockQuietly(ctx);
                return fail(run, ConformanceFailureKind.PRECONDITION_CONFLICT,
                        "SCHEMA_ALREADY_EXISTS",
                        "schema " + schemaName.value() + " already exists; refusing to adopt it");
            }
        } catch (Exception e) {
            releaseLockQuietly(ctx);
            return fail(run, ConformanceFailureKind.PRECONDITION,
                    "SCHEMA_ABSENCE_QUERY_FAILED",
                    "parameterized absence query failed: " + sanitized(e));
        }

        // --------------------------------------------------------------
        // MYSQL_SCHEMA_CREATE (side-effect boundary)
        // --------------------------------------------------------------
        run.advanceTo(ConformanceStateMachine.MYSQL_SCHEMA_CREATE);
        if (guardFails(run, ctx, expectedUuid, ControlOwnershipGuard.Boundary.SCHEMA_CREATE)) {
            releaseLockQuietly(ctx);
            return run.terminalResult();
        }
        try {
            ctx.control().createDatabase(schemaName);
        } catch (Exception e) {
            releaseLockQuietly(ctx);
            return fail(run, ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                    "SCHEMA_CREATE_FAILED", "CREATE DATABASE failed: " + sanitized(e));
        }

        // --------------------------------------------------------------
        // MYSQL_MARKER_AND_FIXTURE
        // --------------------------------------------------------------
        run.advanceTo(ConformanceStateMachine.MYSQL_MARKER_AND_FIXTURE);
        if (guardFails(run, ctx, expectedUuid, ControlOwnershipGuard.Boundary.MARKER_CREATE)) {
            return cleanupAfterFailure(ctx, run, evidenceWriter, null, schemaName, expectedUuid);
        }
        try {
            ctx.control().createOwnerMarker(schemaName, ctx.runToken());
            if (!ctx.control().verifyOwnerMarker(schemaName, ctx.runToken())) {
                return fail(run, ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                        "OWNER_MARKER_NOT_VERIFIED",
                        "the owner marker did not carry this run token");
            }
        } catch (Exception e) {
            return failCleanup(ctx, run, evidenceWriter, null, schemaName, expectedUuid,
                    ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                    "OWNER_MARKER_CREATE_FAILED",
                    "owner marker could not be created or verified: " + sanitized(e));
        }
        if (guardFails(run, ctx, expectedUuid, ControlOwnershipGuard.Boundary.FIXTURE_DDL)) {
            return cleanupAfterFailure(ctx, run, evidenceWriter, null, schemaName, expectedUuid);
        }
        try {
            applyFixtureDdl(ctx, schemaName);
        } catch (Exception e) {
            return failCleanup(ctx, run, evidenceWriter, null, schemaName, expectedUuid,
                    ConformanceFailureKind.HARNESS,
                    "FIXTURE_DDL_FAILED", "fixture DDL failed: " + sanitized(e));
        }

        // --------------------------------------------------------------
        // WORK_ROOT_CREATE
        // --------------------------------------------------------------
        run.advanceTo(ConformanceStateMachine.WORK_ROOT_CREATE);
        OwnedRunDirectory workRoot;
        try {
            workRoot = OwnedRunDirectory.create(ctx.workParent(), "work-" + ctx.runToken());
        } catch (IOException e) {
            return failCleanup(ctx, run, evidenceWriter, null, schemaName, expectedUuid,
                    ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                    "WORK_ROOT_CREATE_FAILED",
                    "cannot create the owned work root: " + sanitized(e));
        }

        // --------------------------------------------------------------
        // One scenario at a time, in canonical order.
        // --------------------------------------------------------------
        for (ConformanceScenario scenario : scenarios) {
            ConformanceFailure failure = runScenario(ctx, run, ops, materializer, evidenceWriter,
                    workRoot, scenario);
            if (failure == null) {
                run.recordScenarioOutcome(new ConformanceResult.ScenarioOutcome(
                        scenario.displayName(), true, java.util.Optional.empty()));
                continue;
            }
            run.recordScenarioOutcome(new ConformanceResult.ScenarioOutcome(
                    scenario.displayName(), false, java.util.Optional.of(failure)));
            run.recordFailure(failure);
            // Stop after the first failing scenario: later scenarios would run against a
            // state the failing one left behind, and their results would not be evidence.
            break;
        }

        // --------------------------------------------------------------
        // Cleanup and proofs (always attempted, pass or fail)
        // --------------------------------------------------------------
        return finishRun(ctx, run, evidenceWriter, evidenceRoot, inventory, workRoot,
                schemaName, expectedUuid);
    }

    // ------------------------------------------------------------------
    // Per-scenario phases
    // ------------------------------------------------------------------

    /**
     * Run one scenario end to end.
     *
     * @return {@code null} when the scenario passed, otherwise the failure to record
     */
    private ConformanceFailure runScenario(ConformanceSuiteContext ctx, ConformanceRun run,
                                           RuntimeOps ops, ScenarioMaterializer materializer,
                                           EvidenceWriter evidenceWriter,
                                           OwnedRunDirectory workRoot,
                                           ConformanceScenario scenario) {
        // MATERIALIZE
        run.advanceTo(ConformanceStateMachine.MATERIALIZE);
        ScenarioMaterializer.ScenarioMaterialization materialization;
        try {
            materialization = materializer.materialize(scenario, workRoot);
        } catch (ScenarioMaterializer.ScenarioMaterializationException e) {
            return new ConformanceFailure(ConformanceFailureKind.MATERIALIZE,
                    e.messageKey(), e.getMessage());
        } catch (Exception e) {
            return new ConformanceFailure(ConformanceFailureKind.MATERIALIZE,
                    "MATERIALIZE_FAILED", "materialization error: " + sanitized(e));
        }

        // DETERMINISM: the structured manifest/transaction verification.
        run.advanceTo(ConformanceStateMachine.DETERMINISM);
        MaterializationEvidence evidence = toEvidence(materialization);
        if (evidence == null) {
            return new ConformanceFailure(ConformanceFailureKind.DETERMINISM,
                    "MANIFEST_EVIDENCE_INCOMPLETE",
                    "materialization did not carry the receipts and manifests "
                            + "verification needs for " + scenario.displayName());
        }
        Result verification = ManifestTransactionVerifier.verify(scenario, evidence,
                materialization.outputRoot(),
                materialization.stateRoot().orElse(null), evidenceWriter);
        if (verification instanceof Result.Failure f) {
            return new ConformanceFailure(ConformanceFailureKind.DETERMINISM, f.key(), f.message());
        }

        // Target dependency inspection (P0-B2) is part of the materialization evidence.
        if (!ops.inspectTargetDependency(ctx.control(), run, scenario,
                java.util.Optional.of(materialization.loweredModel()),
                sourceKindFor(scenario),
                java.util.Optional.empty(), materialization.b1Receipt().map(r -> r.baselineId()),
                materialization.outputRoot(), evidenceWriter)) {
            return lastFailure(run);
        }

        // REGISTER / APPLY: both already happened inside materialization against the real
        // Change execution API; the states are advanced so the machine records them.
        run.advanceTo(ConformanceStateMachine.REGISTER);
        run.advanceTo(ConformanceStateMachine.APPLY);

        // MAVEN_BUILD
        run.advanceTo(ConformanceStateMachine.MAVEN_BUILD);
        try {
            if (!ops.buildProject(ctx, run, scenario, materialization.outputRoot(), evidenceWriter)) {
                return lastFailure(run);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ConformanceFailure(ConformanceFailureKind.BUILD, "BUILD_INTERRUPTED",
                    "the Maven build was interrupted");
        }

        // SPRING_START + CONTEXT_PROOF
        run.advanceTo(ConformanceStateMachine.SPRING_START);
        boolean started = ops.startSpringAndWait(ctx, run, scenario,
                materialization.outputRoot(), evidenceWriter);
        run.advanceTo(ConformanceStateMachine.CONTEXT_PROOF);
        if (!started) {
            stopSpring(ctx);
            return lastFailure(run);
        }

        SchemaName schemaName = ctx.environment().schemaName();
        int rowsBefore = ops.preQueryDbRowCount(ctx, scenario, schemaName);
        if (rowsBefore < 0) {
            stopSpring(ctx);
            return new ConformanceFailure(ConformanceFailureKind.DATABASE,
                    "DB_PRE_QUERY_FAILED", "the pre-operation row count could not be read");
        }

        // HTTP_ASSERT + VALIDATION_ASSERT
        run.advanceTo(ConformanceStateMachine.HTTP_ASSERT);
        boolean httpOk = ops.assertHttp(ctx, run, scenario, evidenceWriter);
        run.advanceTo(ConformanceStateMachine.VALIDATION_ASSERT);
        if (!httpOk) {
            stopSpring(ctx);
            return lastFailure(run);
        }

        // DATABASE_ASSERT
        run.advanceTo(ConformanceStateMachine.DATABASE_ASSERT);
        ScenarioEndpoint endpoint = new ScenarioEndpoint(scenario, ctx.serverPort());
        boolean dbOk = ops.assertDbRowCount(ctx, run, scenario, schemaName,
                endpoint.expectedDbDelta(), rowsBefore);

        // SPRING_STOP must prove exit regardless of the assertion outcome.
        run.advanceTo(ConformanceStateMachine.SPRING_STOP);
        boolean stopped = stopSpring(ctx);
        if (!dbOk) {
            return lastFailure(run);
        }
        if (!stopped) {
            return new ConformanceFailure(ConformanceFailureKind.CLEANUP_PROCESS_REMAINS,
                    "SPRING_PROCESS_REMAINS",
                    "the generated application process did not exit within the grace period");
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Cleanup, proofs, evidence
    // ------------------------------------------------------------------

    private ConformanceResult finishRun(ConformanceSuiteContext ctx, ConformanceRun run,
                                        EvidenceWriter evidenceWriter,
                                        EvidenceDirectory evidenceRoot,
                                        EvidenceOwnershipInventory inventory,
                                        OwnedRunDirectory workRoot, SchemaName schemaName,
                                        String expectedUuid) {
        boolean droppedAndAbsent = false;
        boolean lockReleased = false;

        // MYSQL_LOCK_RECHECK
        run.advanceTo(ConformanceStateMachine.MYSQL_LOCK_RECHECK);
        if (!guardFails(run, ctx, expectedUuid, ControlOwnershipGuard.Boundary.CLEANUP_START)) {
            try {
                ctx.control().recheckLockAndIdentity(expectedUuid);
            } catch (Exception e) {
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                        "CLEANUP_OWNERSHIP_LOST",
                        "lock/identity recheck failed before cleanup: " + sanitized(e)));
            }
        }

        // MYSQL_INVENTORY_PROOF: record the complete schema object inventory as evidence.
        run.advanceTo(ConformanceStateMachine.MYSQL_INVENTORY_PROOF);
        if (!guardFails(run, ctx, expectedUuid, ControlOwnershipGuard.Boundary.SCHEMA_INVENTORY)) {
            try {
                MysqlSchemaInventory schemaInventory = MysqlSchemaInventory.query(ctx.control(), schemaName);
                StringBuilder sb = new StringBuilder();
                for (MysqlSchemaInventory.InventoryItem item : schemaInventory.items()) {
                    sb.append(item.objectType()).append(' ').append(item.objectName()).append('\n');
                }
                try (java.io.OutputStream out = evidenceWriter.openStream(
                        "schema-inventory.txt")) {
                    out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                run.recordFailure(new ConformanceFailure(ConformanceFailureKind.HARNESS,
                        "SCHEMA_INVENTORY_FAILED",
                        "schema inventory could not be recorded: " + sanitized(e)));
            }
        }

        // MYSQL_SCHEMA_DROP: only drop the schema this run created and proved ownership of.
        run.advanceTo(ConformanceStateMachine.MYSQL_SCHEMA_DROP);
        if (!guardFails(run, ctx, expectedUuid, ControlOwnershipGuard.Boundary.DROP)) {
            try {
                ctx.control().dropDatabase(schemaName);
            } catch (Exception e) {
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                        "SCHEMA_DROP_FAILED", "DROP DATABASE failed: " + sanitized(e)));
            }
        }

        // MYSQL_SCHEMA_ABSENCE_PROOF
        run.advanceTo(ConformanceStateMachine.MYSQL_SCHEMA_ABSENCE_PROOF);
        if (!guardFails(run, ctx, expectedUuid, ControlOwnershipGuard.Boundary.POST_DROP_ABSENCE_PROOF)) {
            try {
                boolean stillPresent = ctx.control().schemaExists(schemaName);
                boolean markerGone = !ctx.control().verifyOwnerMarker(schemaName, ctx.runToken());
                droppedAndAbsent = !stillPresent && markerGone;
                if (!droppedAndAbsent) {
                    run.recordFailure(new ConformanceFailure(
                            ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                            "SCHEMA_DROP_NOT_PROVED",
                            "the owned schema or its owner marker is still present after DROP"));
                }
            } catch (Exception e) {
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                        "SCHEMA_ABSENCE_PROOF_FAILED",
                        "post-DROP absence proof failed: " + sanitized(e)));
            }
        }

        // MYSQL_LOCK_RELEASE
        run.advanceTo(ConformanceStateMachine.MYSQL_LOCK_RELEASE);
        try {
            ctx.control().releaseLock();
            lockReleased = !ctx.control().lockHeldByThisConnection();
            if (!lockReleased) {
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                        "LOCK_RELEASE_NOT_PROVED",
                        "the advisory lock is still held after RELEASE_LOCK"));
            }
        } catch (Exception e) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                    "LOCK_RELEASE_FAILED", "RELEASE_LOCK failed: " + sanitized(e)));
        }
        run.markSchemaCleanupComplete(droppedAndAbsent, lockReleased);

        // WORK_ROOT_CLEANUP
        run.advanceTo(ConformanceStateMachine.WORK_ROOT_CLEANUP);
        boolean workRootCleaned = false;
        try {
            workRootCleaned = workRoot.cleanup() && workRootRootIsGone(workRoot);
        } catch (IOException e) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                    "WORK_ROOT_CLEANUP_FAILED",
                    "work-root cleanup failed: " + sanitized(e)));
        }
        if (!workRootCleaned && run.failures().stream()
                .noneMatch(f -> "WORK_ROOT_CLEANUP_FAILED".equals(f.messageKey()))) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                    "WORK_ROOT_CLEANUP_NOT_PROVED",
                    "the work root directory was not deleted"));
        }
        run.markWorkRootCleanedUp(workRootCleaned);
        closeQuietly(workRoot);

        // EVIDENCE_SECRET_SCAN: the first scan covers every scenario artifact written so far.
        run.advanceTo(ConformanceStateMachine.EVIDENCE_SECRET_SCAN);
        boolean firstScanClean = scanEvidence(ctx, run, evidenceRoot, inventory);
        if (!firstScanClean) {
            run.markEvidenceScanClean(false);
            run.markEvidenceFinalized(false);
            return run.terminalResult();
        }

        // EVIDENCE_FINALIZE: publish and seal the report, then scan the sealed tree. The
        // report is written here (after the scenario work it summarizes) and the second
        // scan is the one whose verdict counts, so the report is itself scanned.
        run.advanceTo(ConformanceStateMachine.EVIDENCE_FINALIZE);
        boolean reportPublished = publishReport(ctx, run, evidenceWriter);
        boolean sealed = false;
        if (reportPublished) {
            try {
                evidenceWriter.sealReport(REPORT_FILE);
                sealed = true;
            } catch (IOException e) {
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.HARNESS_CREDENTIAL_BOUNDARY,
                        "EVIDENCE_REPORT_SEAL_FAILED",
                        "the report could not be sealed: " + sanitized(e)));
            }
        }
        boolean finalScanClean = sealed && scanEvidence(ctx, run, evidenceRoot, inventory);
        run.markEvidenceScanClean(finalScanClean);
        run.markEvidenceFinalized(finalScanClean);
        return run.terminalResult();
    }

    /**
     * Run the evidence scan and record the outcome.
     *
     * @return true iff the scan completed and reconciled clean
     */
    private static boolean scanEvidence(ConformanceSuiteContext ctx, ConformanceRun run,
                                        EvidenceDirectory evidenceRoot,
                                        EvidenceOwnershipInventory inventory) {
        try {
            EvidenceSecretScanner.ScanResult scanResult =
                    ctx.secretScanner().scanAndReconcile(evidenceRoot, inventory);
            if (scanResult instanceof EvidenceSecretScanner.ScanResult.Clean) {
                return true;
            }
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.HARNESS_CREDENTIAL_BOUNDARY,
                    ((EvidenceSecretScanner.ScanResult.Dirty) scanResult).failureKey(),
                    "the evidence tree failed the final scan"));
            return false;
        } catch (Exception e) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.HARNESS_CREDENTIAL_BOUNDARY,
                    "EVIDENCE_SCAN_FAILED", "the evidence scan could not run: " + sanitized(e)));
            return false;
        }
    }

    /**
     * Prove the work root directory itself is gone after cleanup.
     *
     * <p>Uses {@link OwnedRunDirectory#proveAbsent}, which fails when the path still
     * exists; {@code Files.notExists} is deliberately not used, because it cannot
     * distinguish "absent" from "cannot tell".
     *
     * @param workRoot the owned work root
     * @return true iff the root is proven absent
     */
    private static boolean workRootRootIsGone(OwnedRunDirectory workRoot) {
        try {
            OwnedRunDirectory.proveAbsent(workRoot.root());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean publishReport(ConformanceSuiteContext ctx, ConformanceRun run,
                                  EvidenceWriter evidenceWriter) {
        StringBuilder sb = new StringBuilder();
        sb.append("conformance-run\n");
        sb.append("scenarios=").append(scenarios.size()).append('\n');
        for (ConformanceResult.ScenarioOutcome outcome : run.scenarioOutcomes()) {
            sb.append("scenario ").append(outcome.scenarioName())
                    .append(" passed=").append(outcome.passed()).append('\n');
        }
        for (ConformanceFailure failure : run.failures()) {
            sb.append("failure ").append(failure.kind()).append(' ')
                    .append(failure.messageKey()).append('\n');
        }
        sb.append("terminal=determined-after-evidence-finalization\n");
        sb.append("scenario-outcomes-recorded=").append(run.scenarioOutcomes().size())
                .append('/').append(scenarios.size()).append('\n');
        try {
            // The report is redacted like every other artifact: it names failures and
            // outcomes, and must never carry a credential even by accident.
            evidenceWriter.publishReport(REPORT_FILE, ctx.redactor().redactString(sb.toString()));
            return true;
        } catch (IOException e) {
            run.recordFailure(new ConformanceFailure(ConformanceFailureKind.HARNESS,
                    "EVIDENCE_REPORT_WRITE_FAILED",
                    "the report could not be published: " + sanitized(e)));
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static MaterializationEvidence toEvidence(
            ScenarioMaterializer.ScenarioMaterialization materialization) {
        if (materialization.outputManifest()
                instanceof io.kcg.sir.application.api.ExecutionManifest manifest) {
            return new MaterializationEvidence.IgEvidence(manifest);
        }
        if (materialization.outputManifest()
                instanceof io.kcg.sir.application.api.ChangeOutputManifest manifest
                && materialization.b0Receipt().isPresent()
                && materialization.b0Manifest().isPresent()
                && materialization.b1Receipt().isPresent()
                && materialization.outcome().isPresent()) {
            return new MaterializationEvidence.ApplyEvidence(
                    materialization.b0Receipt().get(),
                    materialization.b0Manifest().get(),
                    materialization.b1Receipt().get(),
                    manifest,
                    materialization.outcome().get());
        }
        return null;
    }

    private static String sourceKindFor(ConformanceScenario scenario) {
        return switch (scenario) {
            case IG_ACTOR, IG_READONLY -> "BASE_SIR";
            case APPLY_UPDATE, APPLY_CREATE, APPLY_DELETE -> "CANDIDATE_SIR";
        };
    }

    private static void applyFixtureDdl(ConformanceSuiteContext ctx, SchemaName schemaName)
            throws Exception {
        // Comments are stripped before the statements are split: a `--` comment may contain a
        // semicolon (the fixture explains itself in prose), and splitting first would turn the
        // remainder of such a comment into a fragment that is then executed as SQL.
        String ddl = stripSqlComments(ConformanceFixtures.readResource(FIXTURE_DDL_RESOURCE));
        java.sql.Connection connection = ctx.control().rawConnection();
        try (java.sql.Statement statement = connection.createStatement()) {
            // The fixture statements are not schema-qualified in the file, so this is the one
            // place that must select the schema. An explicit USE is used rather than
            // Connection.setCatalog: against MySQL 8.4 the catalog hint can leave a
            // statement with no database selected (ERROR 1046), and a silently mis-targeted
            // CREATE TABLE would create schema objects the run does not own. The schema name
            // is a validated SchemaName and is backquoted, so there is no injection surface.
            statement.execute("USE `" + schemaName.value() + "`");
            for (String raw : ddl.split(";")) {
                String sql = raw.trim();
                if (sql.isEmpty()) {
                    continue;
                }
                statement.executeUpdate(sql);
            }
        }
    }

    /**
     * Remove {@code --} comments from fixture SQL so it can be documented in place.
     *
     * <p>Removal is per line and keeps the line's remaining text, so a statement may carry a
     * trailing comment. Blank lines are dropped. This runs before statement splitting, because
     * comment prose may contain a semicolon.
     *
     * @param ddl the raw fixture text
     * @return the SQL with comments removed
     */
    private static String stripSqlComments(String ddl) {
        StringBuilder sb = new StringBuilder();
        for (String line : ddl.split("\\R")) {
            int commentAt = line.indexOf("--");
            String withoutComment = commentAt >= 0 ? line.substring(0, commentAt) : line;
            String trimmed = withoutComment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(trimmed);
        }
        return sb.toString().trim();
    }

    /**
     * Evaluate the ownership guard at a boundary and record a failure when it fails.
     *
     * <p>The kind depends on whether the run has crossed the side-effect boundary: before
     * {@code MYSQL_SCHEMA_CREATE} a guard failure is a precondition conflict (nothing was
     * created), after it the run has lost the proof that it still owns the schema.
     *
     * @return true iff a failure was recorded
     */
    private static boolean guardFails(ConformanceRun run, ConformanceSuiteContext ctx,
                                      String expectedUuid,
                                      ControlOwnershipGuard.Boundary boundary) {
        ControlOwnershipGuard.GuardResult result = ControlOwnershipGuard.requireAtBoundary(
                ctx.control(), expectedUuid, boundary);
        if (result.passed()) {
            return false;
        }
        run.recordFailure(new ConformanceFailure(
                run.isSideEffectBoundaryCrossed()
                        ? ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED
                        : ConformanceFailureKind.PRECONDITION_CONFLICT,
                "CONTROL_OWNERSHIP_NOT_PROVED_" + boundary.name(),
                "control ownership guard failed at " + boundary.name() + ": "
                        + result.failureReason()));
        return true;
    }

    private static ConformanceResult fail(ConformanceRun run, ConformanceFailureKind kind,
                                          String key, String message) {
        run.recordFailure(new ConformanceFailure(kind, key, message));
        return run.terminalResult();
    }

    private static ConformanceFailure lastFailure(ConformanceRun run) {
        List<ConformanceFailure> failures = run.failures();
        if (failures.isEmpty()) {
            return new ConformanceFailure(ConformanceFailureKind.HARNESS,
                    "SCENARIO_FAILED_WITHOUT_CAUSE",
                    "a scenario step failed without recording a cause");
        }
        return failures.get(failures.size() - 1);
    }

    private ConformanceResult failCleanup(ConformanceSuiteContext ctx, ConformanceRun run,
                                          EvidenceWriter evidenceWriter, OwnedRunDirectory workRoot,
                                          SchemaName schemaName, String expectedUuid,
                                          ConformanceFailureKind kind, String key, String message) {
        run.recordFailure(new ConformanceFailure(kind, key, message));
        return cleanupAfterFailure(ctx, run, evidenceWriter, workRoot, schemaName, expectedUuid);
    }

    /**
     * Attempt schema/lock cleanup after a failure that happened once the schema existed.
     *
     * <p>Cleanup failures are recorded as additional failures; they never replace the
     * original cause, which stays the primary failure of the terminal result.
     */
    private ConformanceResult cleanupAfterFailure(ConformanceSuiteContext ctx, ConformanceRun run,
                                                  EvidenceWriter evidenceWriter,
                                                  OwnedRunDirectory workRoot,
                                                  SchemaName schemaName, String expectedUuid) {
        boolean droppedAndAbsent = false;
        boolean lockReleased = false;
        try {
            ControlOwnershipGuard.GuardResult guard = ControlOwnershipGuard.requireAtBoundary(
                    ctx.control(), expectedUuid, ControlOwnershipGuard.Boundary.DROP);
            if (guard.passed()) {
                ctx.control().dropDatabase(schemaName);
                droppedAndAbsent = !ctx.control().schemaExists(schemaName)
                        && !ctx.control().verifyOwnerMarker(schemaName, ctx.runToken());
            } else {
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                        "CLEANUP_OWNERSHIP_NOT_PROVED_DROP",
                        "ownership guard refused the cleanup DROP: " + guard.failureReason()));
            }
        } catch (Exception e) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                    "SCHEMA_DROP_FAILED", "cleanup DROP failed: " + sanitized(e)));
        }
        try {
            ctx.control().releaseLock();
            lockReleased = !ctx.control().lockHeldByThisConnection();
        } catch (Exception e) {
            run.recordFailure(new ConformanceFailure(
                    ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                    "LOCK_RELEASE_FAILED", "cleanup RELEASE_LOCK failed: " + sanitized(e)));
        }
        run.markSchemaCleanupComplete(droppedAndAbsent, lockReleased);
        if (workRoot != null) {
            try {
                boolean cleaned = workRoot.cleanup() && workRootRootIsGone(workRoot);
                run.markWorkRootCleanedUp(cleaned);
            } catch (IOException e) {
                run.recordFailure(new ConformanceFailure(
                        ConformanceFailureKind.CLEANUP_OWNERSHIP_UNPROVED,
                        "WORK_ROOT_CLEANUP_FAILED",
                        "cleanup of the work root failed: " + sanitized(e)));
            }
            closeQuietly(workRoot);
        }
        stopSpring(ctx);
        run.markEvidenceScanClean(false);
        run.markEvidenceFinalized(false);
        return run.terminalResult();
    }

    private static boolean stopSpring(ConformanceSuiteContext ctx) {
        try {
            return ctx.springProcess().stop(ctx.springGraceMillis())
                    && ctx.springProcess().isStopped();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void releaseLockQuietly(ConformanceSuiteContext ctx) {
        try {
            ctx.control().releaseLock();
        } catch (Exception ignored) {
            // Best effort on the pre-boundary failure paths.
        }
    }

    private static void closeQuietly(OwnedRunDirectory workRoot) {
        try {
            workRoot.close();
        } catch (IOException ignored) {
            // Cleanup already reported through the inventory and cleanup results.
        }
    }

    private static String sanitized(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return t.getClass().getSimpleName();
        }
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }

    // ------------------------------------------------------------------
    // Per-scenario endpoint configuration
    // ------------------------------------------------------------------

    /**
     * The business endpoints one scenario exercises, resolved against the loopback
     * port the generated application was started on.
     *
     * <p>Routes are the real generated routes (/{@code /api/publish-goods},
     * {@code /api/search-goods}), which are derived from the campus-market
     * capabilities by the lowering pass — the same route the lowering integration
     * test pins. The expected database table is {@code goods}, the table the
     * campus-market goods entity is persisted to.
     *
     * <p>Optional members are {@code null} (or 0 for a status) when the scenario does
     * not exercise them: only APPLY-CREATE has a secondary route, and only
     * APPLY-DELETE has a removed route.
     */
    static final class ScenarioEndpoint {
        private final String readinessUrl;
        private final String readinessMethod;
        private final String readinessBody;
        private final int expectedReadinessStatus;
        private final String assertionMethod;
        private final String assertionUrl;
        private final String assertionBody;
        private final int expectedAssertionStatus;
        private final String invalidMethod;
        private final String invalidUrl;
        private final String invalidBody;
        private final int expectedInvalidStatus;
        private final String secondaryMethod;
        private final String secondaryUrl;
        private final String secondaryBody;
        private final int expectedSecondaryStatus;
        private final String removedUrl;
        private final int expectedRemovedStatus;
        private final String dbTable;
        private final int expectedDbDelta;

        ScenarioEndpoint(ConformanceScenario scenario, int serverPort) {
            String base = "http://127.0.0.1:" + serverPort;
            switch (scenario) {
                case IG_ACTOR -> {
                    this.readinessUrl = base + "/api/publish-goods";
                    this.readinessMethod = "POST";
                    this.readinessBody = "{\"title\":\"\",\"price\":0}";
                    this.expectedReadinessStatus = 400;
                    this.assertionMethod = "POST";
                    this.assertionUrl = base + "/api/publish-goods";
                    this.assertionBody = "{\"title\":\"Conf-Actor-Good\",\"price\":1.00}";
                    this.expectedAssertionStatus = 200;
                    this.invalidMethod = "POST";
                    this.invalidUrl = base + "/api/publish-goods";
                    this.invalidBody = "{\"title\":\"\",\"price\":0}";
                    this.expectedInvalidStatus = 400;
                    this.secondaryMethod = null;
                    this.secondaryUrl = null;
                    this.secondaryBody = null;
                    this.expectedSecondaryStatus = 0;
                    this.removedUrl = null;
                    this.expectedRemovedStatus = 0;
                    this.dbTable = "goods";
                    this.expectedDbDelta = 1;
                }
                case IG_READONLY -> {
                    this.readinessUrl = base + "/api/search-goods?title=Intro%20to%20Algorithms&price=59.90";
                    this.readinessMethod = "GET";
                    this.readinessBody = null;
                    this.expectedReadinessStatus = 200;
                    this.assertionMethod = "GET";
                    this.assertionUrl = base + "/api/search-goods?title=Intro%20to%20Algorithms&price=59.90";
                    this.assertionBody = null;
                    this.expectedAssertionStatus = 200;
                    this.invalidMethod = "GET";
                    this.invalidUrl = base + "/api/search-goods?price=-invalid";
                    this.invalidBody = null;
                    this.expectedInvalidStatus = 400;
                    this.secondaryMethod = null;
                    this.secondaryUrl = null;
                    this.secondaryBody = null;
                    this.expectedSecondaryStatus = 0;
                    this.removedUrl = null;
                    this.expectedRemovedStatus = 0;
                    this.dbTable = "goods";
                    this.expectedDbDelta = 0;
                }
                case APPLY_UPDATE -> {
                    this.readinessUrl = base + "/api/publish-goods";
                    this.readinessMethod = "POST";
                    this.readinessBody = "{\"title\":\"" + "x".repeat(201)
                            + "\",\"price\":1.00}";
                    this.expectedReadinessStatus = 400;
                    this.assertionMethod = "POST";
                    this.assertionUrl = base + "/api/publish-goods";
                    this.assertionBody = "{\"title\":\"Conf-Update-Good\",\"price\":1.00}";
                    this.expectedAssertionStatus = 200;
                    this.invalidMethod = "POST";
                    this.invalidUrl = base + "/api/publish-goods";
                    this.invalidBody = "{\"title\":\"" + "x".repeat(201)
                            + "\",\"price\":1.00}";
                    this.expectedInvalidStatus = 400;
                    this.secondaryMethod = null;
                    this.secondaryUrl = null;
                    this.secondaryBody = null;
                    this.expectedSecondaryStatus = 0;
                    this.removedUrl = null;
                    this.expectedRemovedStatus = 0;
                    this.dbTable = "goods";
                    this.expectedDbDelta = 1;
                }
                case APPLY_CREATE -> {
                    this.readinessUrl = base + "/api/search-goods?title=Intro%20to%20Algorithms&price=59.90";
                    this.readinessMethod = "GET";
                    this.readinessBody = null;
                    this.expectedReadinessStatus = 200;
                    this.assertionMethod = "POST";
                    this.assertionUrl = base + "/api/publish-goods";
                    this.assertionBody = "{\"title\":\"Conf-Create-Good\",\"price\":1.00}";
                    this.expectedAssertionStatus = 200;
                    this.invalidMethod = "GET";
                    this.invalidUrl = base + "/api/search-goods?price=-invalid";
                    this.invalidBody = null;
                    this.expectedInvalidStatus = 400;
                    this.secondaryMethod = "GET";
                    this.secondaryUrl = base + "/api/search-goods?title=Conf-Create-Good&price=1.00";
                    this.secondaryBody = null;
                    this.expectedSecondaryStatus = 200;
                    this.removedUrl = null;
                    this.expectedRemovedStatus = 0;
                    this.dbTable = "goods";
                    this.expectedDbDelta = 1;
                }
                case APPLY_DELETE -> {
                    this.readinessUrl = base + "/api/search-goods?title=Intro%20to%20Algorithms&price=59.90";
                    this.readinessMethod = "GET";
                    this.readinessBody = null;
                    this.expectedReadinessStatus = 200;
                    this.assertionMethod = "GET";
                    this.assertionUrl = base + "/api/search-goods?title=Intro%20to%20Algorithms&price=59.90";
                    this.assertionBody = null;
                    this.expectedAssertionStatus = 200;
                    this.invalidMethod = "GET";
                    this.invalidUrl = base + "/api/search-goods?price=-invalid";
                    this.invalidBody = null;
                    this.expectedInvalidStatus = 400;
                    this.secondaryMethod = null;
                    this.secondaryUrl = null;
                    this.secondaryBody = null;
                    this.expectedSecondaryStatus = 0;
                    this.removedUrl = base + "/api/publish-goods";
                    this.expectedRemovedStatus = 404;
                    this.dbTable = "goods";
                    this.expectedDbDelta = 0;
                }
                default -> throw new IllegalArgumentException("unknown scenario: " + scenario);
            }
        }

        String readinessUrl() { return readinessUrl; }
        String readinessMethod() { return readinessMethod; }
        String readinessBody() { return readinessBody; }
        int expectedReadinessStatus() { return expectedReadinessStatus; }
        String assertionMethod() { return assertionMethod; }
        String assertionUrl() { return assertionUrl; }
        String assertionBody() { return assertionBody; }
        int expectedAssertionStatus() { return expectedAssertionStatus; }
        String invalidMethod() { return invalidMethod; }
        String invalidUrl() { return invalidUrl; }
        String invalidBody() { return invalidBody; }
        int expectedInvalidStatus() { return expectedInvalidStatus; }
        String secondaryMethod() { return secondaryMethod; }
        String secondaryUrl() { return secondaryUrl; }
        String secondaryBody() { return secondaryBody; }
        int expectedSecondaryStatus() { return expectedSecondaryStatus; }
        String removedUrl() { return removedUrl; }
        int expectedRemovedStatus() { return expectedRemovedStatus; }
        String dbTable() { return dbTable; }
        int expectedDbDelta() { return expectedDbDelta; }
    }
}
