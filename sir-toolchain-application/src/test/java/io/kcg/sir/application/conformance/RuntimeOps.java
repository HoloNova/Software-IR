package io.kcg.sir.application.conformance;

import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The per-scenario runtime operations the conformance orchestration performs.
 *
 * <p>{@link RealRuntimeOps} is the real implementation: it shells out to Maven,
 * starts the generated Spring application, issues HTTP assertions, and queries
 * MySQL through a read-only observer. Test fakes implement this seam so the
 * orchestration can be driven for scenarios that do not need a real server.
 *
 * <p>The method set and signatures are exactly those {@link RealRuntimeOps}
 * overrides; each returns {@code true} on success and records a
 * {@link ConformanceFailure} on the run before returning {@code false}, so the
 * orchestrator never has to reconstruct why a step failed.
 */
interface RuntimeOps {

    /**
     * Inspect the generated project's declared target dependency versions and the
     * harness JDBC driver version (ADR-017 P0-B2 evidence).
     *
     * @param control            the control session (its raw connection supplies the harness driver version)
     * @param run                the run under construction
     * @param scenario           the scenario being executed
     * @param loweredModel       the lowered model when the scenario produced one
     * @param loweredSourceKind  the source kind of the lowered model
     * @param candidateSirSha256 the candidate SIR fingerprint when applicable
     * @param b1BaselineId       the published B1 baseline id when applicable
     * @param outputRoot         the generated project root
     * @param evidenceWriter     the evidence writer for this run
     * @return true iff the inspection passed
     */
    boolean inspectTargetDependency(ControlSession control,
                                    ConformanceRun run,
                                    ConformanceScenario scenario,
                                    Optional<SpringBootLoweredModel> loweredModel,
                                    String loweredSourceKind,
                                    Optional<String> candidateSirSha256,
                                    Optional<String> b1BaselineId,
                                    Path outputRoot,
                                    EvidenceWriter evidenceWriter);

    /**
     * Build the generated project with the Maven runner, capturing both streams into
     * the scenario's evidence directory.
     *
     * @return true iff the build succeeded
     * @throws InterruptedException if the build process is interrupted
     */
    boolean buildProject(ConformanceSuiteContext ctx,
                         ConformanceRun run,
                         ConformanceScenario scenario,
                         Path outputRoot,
                         EvidenceWriter evidenceWriter) throws InterruptedException;

    /**
     * Start the generated application and wait for its business endpoint to become
     * ready.
     *
     * @return true iff the application became ready
     */
    boolean startSpringAndWait(ConformanceSuiteContext ctx,
                               ConformanceRun run,
                               ConformanceScenario scenario,
                               Path outputRoot,
                               EvidenceWriter evidenceWriter);

    /**
     * Issue the scenario's HTTP assertions (status, validation, removed route,
     * secondary route).
     *
     * @return true iff every expected status matched
     */
    boolean assertHttp(ConformanceSuiteContext ctx,
                       ConformanceRun run,
                       ConformanceScenario scenario,
                       EvidenceWriter evidenceWriter);

    /**
     * Count the scenario's observed table rows before the operation.
     *
     * @return the row count, 0 when the scenario has no observed table, or -1 when
     *         the count could not be read
     */
    int preQueryDbRowCount(ConformanceSuiteContext ctx,
                           ConformanceScenario scenario,
                           SchemaName schemaName);

    /**
     * Assert the observed table's row delta after the operation.
     *
     * @return true iff the delta matches
     */
    boolean assertDbRowCount(ConformanceSuiteContext ctx,
                             ConformanceRun run,
                             ConformanceScenario scenario,
                             SchemaName schemaName,
                             int expectedDelta,
                             int rowsBefore);
}
