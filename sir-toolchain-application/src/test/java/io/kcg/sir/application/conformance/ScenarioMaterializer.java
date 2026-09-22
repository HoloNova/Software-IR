package io.kcg.sir.application.conformance;

import io.kcg.sir.application.api.ChangeApplyRequest;
import io.kcg.sir.application.api.ChangeApplyResult;
import io.kcg.sir.application.api.ChangeBaselineRegistrationRequest;
import io.kcg.sir.application.api.ChangeBaselineRegistrationResult;
import io.kcg.sir.application.api.ChangeExecutionApplication;
import io.kcg.sir.application.api.ConflictPolicy;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.change.api.AddCapability;
import io.kcg.sir.change.api.ModifyInputFieldConstraints;
import io.kcg.sir.change.api.RemoveCapability;
import io.kcg.sir.change.api.ChangeTarget;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphSerialization;
import io.kcg.sir.projectgraph.api.ProjectGraphSerializer;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.model.NormalizedInput;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Materializes one conformance scenario by calling the real public
 * Application API. The materializer never reflects, copies, or bypasses
 * the production ToolchainApplication or ChangeExecutionApplication
 * logic 鈥?it constructs the same request shapes used by the existing
 * D/E-phase drivers.
 *
 * <p>For each scenario:
 * <ul>
 *   <li>{@code IG-ACTOR}: compile {@code campus-market.sir} via
 *       {@link ToolchainApplication#execute} into the scenario output root.</li>
 *   <li>{@code IG-READONLY}: compile {@code campus-market-actorless-readonly-base.sir}
 *       via {@link ToolchainApplication#execute}.</li>
 *   <li>{@code APPLY-UPDATE}: compile {@code campus-market.sir} as B0,
 *       register via {@link ChangeExecutionApplication#register}, then
 *       apply v0.4 {@link ModifyInputFieldConstraints} candidate.</li>
 *   <li>{@code APPLY-CREATE}: compile {@code campus-market-minimal.sir} as B0,
 *       register, then apply v0.2 {@link AddCapability} candidate.</li>
 *   <li>{@code APPLY-DELETE}: compile
 *       {@code campus-market-two-capabilities.sir} as B0, register, then
 *       apply v0.3 {@link RemoveCapability} candidate.</li>
 * </ul>
 *
 * <p>The materializer writes the generated project, the baseline state root,
 * and the candidate SIR into the scenario-specific subdirectory of the
 * owned workRoot. All materialization paths are recorded in the owned
 * directory inventory so cleanup can prove ownership.
 */
public final class ScenarioMaterializer {

    private static final SourceId SOURCE_ID = SourceId.of("campus-market.sir");

    /**
     * Materialize the given scenario.
     *
     * @param scenario  the scenario to materialize
     * @param workRoot  the owned workRoot; a scenario-specific subdirectory
     *                  is created under it
     * @return the materialization result
     * @throws ScenarioMaterializationException if materialization fails
     */
    public ScenarioMaterialization materialize(
            ConformanceScenario scenario,
            OwnedRunDirectory workRoot) throws ScenarioMaterializationException {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(workRoot, "workRoot");
        try {
            Path scenarioRoot = workRoot.root().resolve(scenarioDirName(scenario));
            Files.createDirectory(scenarioRoot);
            workRoot.recordCreation(scenarioRoot);
            return switch (scenario) {
                case IG_ACTOR -> materializeIgActor(scenarioRoot, workRoot);
                case IG_READONLY -> materializeIgReadonly(scenarioRoot, workRoot);
                case APPLY_UPDATE -> materializeApplyUpdate(scenarioRoot, workRoot);
                case APPLY_CREATE -> materializeApplyCreate(scenarioRoot, workRoot);
                case APPLY_DELETE -> materializeApplyDelete(scenarioRoot, workRoot);
            };
        } catch (ScenarioMaterializationException e) {
            throw e;
        } catch (Exception e) {
            throw new ScenarioMaterializationException(scenario,
                    "MATERIALIZE_FAILED", "materialization error: " + e.getMessage(), e);
        }
    }

    private ScenarioMaterialization materializeIgActor(Path scenarioRoot,
                                                       OwnedRunDirectory workRoot) throws Exception {
        Path outputRoot = scenarioRoot.resolve("output").toAbsolutePath();
        Files.createDirectory(outputRoot);
        workRoot.recordCreation(outputRoot);
        Path sirFile = writeResourceTo(scenarioRoot, workRoot,
                "campus-market.sir", "/valid/campus-market.sir");
        ToolchainResult.Success success = compileBase(sirFile, outputRoot);
        return new ScenarioMaterialization(
                ConformanceScenario.IG_ACTOR,
                outputRoot,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(success.graph()),
                Optional.empty(),
                Optional.empty(),
                lowerModel(sirFile),
                success.manifest());
    }

    private ScenarioMaterialization materializeIgReadonly(Path scenarioRoot,
                                                          OwnedRunDirectory workRoot) throws Exception {
        Path outputRoot = scenarioRoot.resolve("output").toAbsolutePath();
        Files.createDirectory(outputRoot);
        workRoot.recordCreation(outputRoot);
        Path sirFile = writeResourceTo(scenarioRoot, workRoot,
                "campus-market-actorless-readonly-base.sir",
                "/valid/campus-market-actorless-readonly-base.sir");
        ToolchainResult.Success success = compileBase(sirFile, outputRoot);
        return new ScenarioMaterialization(
                ConformanceScenario.IG_READONLY,
                outputRoot,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(success.graph()),
                Optional.empty(),
                Optional.empty(),
                lowerModel(sirFile),
                success.manifest());
    }

    private ScenarioMaterialization materializeApplyUpdate(Path scenarioRoot,
                                                           OwnedRunDirectory workRoot) throws Exception {
        Path outputRoot = scenarioRoot.resolve("output").toAbsolutePath();
        Files.createDirectory(outputRoot);
        workRoot.recordCreation(outputRoot);
        Path stateRoot = scenarioRoot.resolve("state-root").toAbsolutePath();
        Files.createDirectory(stateRoot);
        workRoot.recordCreation(stateRoot);
        Path baseSir = writeResourceTo(scenarioRoot, workRoot,
                "campus-market.sir", "/valid/campus-market.sir");
        Path candidateSir = writeResourceTo(scenarioRoot, workRoot,
                "campus-market-candidate-modify-input-field-constraints.sir",
                "/valid/campus-market-candidate-modify-input-field-constraints.sir");

        // 1. Compile B0.
        ToolchainResult.Success b0 = compileBase(baseSir, outputRoot);
        ProjectGraph b0Graph = b0.graph();
        // 2. Serialize snapshot.
        Path snapshotFile = scenarioRoot.resolve("baseline.kcg-psg").toAbsolutePath();
        Files.write(snapshotFile, serializeGraph(b0Graph));
        workRoot.recordCreation(snapshotFile);
        // 3. Build revision + ChangeSet.
        ChangeBaseRevision revision = buildRevision(baseSir, b0Graph);
        ChangeSet changeSet = buildV04ChangeSet(revision, baseSir);
        // 4. Register B0.
        ChangeExecutionApplication app = new ChangeExecutionApplication();
        ChangeBaselineRegistrationResult.Success registration =
                registerBaseline(app, baseSir, snapshotFile, outputRoot, stateRoot, revision);
        // 5. Apply v0.4 ModifyInputFieldConstraints.
        ChangeApplyRequest applyRequest = new ChangeApplyRequest(
                stateRoot, registration.receipt().baselineId(),
                candidateSir, outputRoot, changeSet);
        ChangeApplyResult applyResult = app.apply(applyRequest);
        if (!(applyResult instanceof ChangeApplyResult.Applied applied)) {
            throw new ScenarioMaterializationException(ConformanceScenario.APPLY_UPDATE,
                    "APPLY_FAILED", "v0.4 Apply did not produce Applied: " + applyResult);
        }
        return new ScenarioMaterialization(
                ConformanceScenario.APPLY_UPDATE,
                outputRoot,
                Optional.of(stateRoot),
                Optional.of(registration.receipt()),
                Optional.of(applied.newBaselineReceipt()),
                Optional.of(b0Graph),
                Optional.of(b0.manifest()),
                Optional.of(applied.outcome()),
                lowerModel(candidateSir),
                applied.outputManifest());
    }

    private ScenarioMaterialization materializeApplyCreate(Path scenarioRoot,
                                                           OwnedRunDirectory workRoot) throws Exception {
        Path outputRoot = scenarioRoot.resolve("output").toAbsolutePath();
        Files.createDirectory(outputRoot);
        workRoot.recordCreation(outputRoot);
        Path stateRoot = scenarioRoot.resolve("state-root").toAbsolutePath();
        Files.createDirectory(stateRoot);
        workRoot.recordCreation(stateRoot);
        Path baseSir = writeResourceTo(scenarioRoot, workRoot,
                "campus-market-minimal.sir", "/valid/campus-market-minimal.sir");
        Path candidateSir = writeResourceTo(scenarioRoot, workRoot,
                "campus-market-minimal-add-search-goods.sir",
                "/valid/campus-market-minimal-add-search-goods.sir");

        ToolchainResult.Success b0 = compileBase(baseSir, outputRoot);
        ProjectGraph b0Graph = b0.graph();
        Path snapshotFile = scenarioRoot.resolve("baseline.kcg-psg").toAbsolutePath();
        Files.write(snapshotFile, serializeGraph(b0Graph));
        workRoot.recordCreation(snapshotFile);
        ChangeBaseRevision revision = buildRevision(baseSir, b0Graph);
        ChangeSet changeSet = buildAddCapabilityChangeSet(revision, candidateSir);
        ChangeExecutionApplication app = new ChangeExecutionApplication();
        ChangeBaselineRegistrationResult.Success registration =
                registerBaseline(app, baseSir, snapshotFile, outputRoot, stateRoot, revision);
        ChangeApplyRequest applyRequest = new ChangeApplyRequest(
                stateRoot, registration.receipt().baselineId(),
                candidateSir, outputRoot, changeSet);
        ChangeApplyResult applyResult = app.apply(applyRequest);
        if (!(applyResult instanceof ChangeApplyResult.Applied applied)) {
            throw new ScenarioMaterializationException(ConformanceScenario.APPLY_CREATE,
                    "APPLY_FAILED", "v0.2 Apply did not produce Applied: " + applyResult);
        }
        return new ScenarioMaterialization(
                ConformanceScenario.APPLY_CREATE,
                outputRoot,
                Optional.of(stateRoot),
                Optional.of(registration.receipt()),
                Optional.of(applied.newBaselineReceipt()),
                Optional.of(b0Graph),
                Optional.of(b0.manifest()),
                Optional.of(applied.outcome()),
                lowerModel(candidateSir),
                applied.outputManifest());
    }

    private ScenarioMaterialization materializeApplyDelete(Path scenarioRoot,
                                                           OwnedRunDirectory workRoot) throws Exception {
        Path outputRoot = scenarioRoot.resolve("output").toAbsolutePath();
        Files.createDirectory(outputRoot);
        workRoot.recordCreation(outputRoot);
        Path stateRoot = scenarioRoot.resolve("state-root").toAbsolutePath();
        Files.createDirectory(stateRoot);
        workRoot.recordCreation(stateRoot);
        Path baseSir = writeResourceTo(scenarioRoot, workRoot,
                "campus-market-two-capabilities.sir",
                "/valid/campus-market-two-capabilities.sir");
        Path candidateSir = writeResourceTo(scenarioRoot, workRoot,
                "campus-market-two-capabilities-remove-publish-goods.sir",
                "/valid/campus-market-two-capabilities-remove-publish-goods.sir");

        ToolchainResult.Success b0 = compileBase(baseSir, outputRoot);
        ProjectGraph b0Graph = b0.graph();
        Path snapshotFile = scenarioRoot.resolve("baseline.kcg-psg").toAbsolutePath();
        Files.write(snapshotFile, serializeGraph(b0Graph));
        workRoot.recordCreation(snapshotFile);
        ChangeBaseRevision revision = buildRevision(baseSir, b0Graph);
        ChangeSet changeSet = buildRemoveCapabilityChangeSet(revision, baseSir);
        ChangeExecutionApplication app = new ChangeExecutionApplication();
        ChangeBaselineRegistrationResult.Success registration =
                registerBaseline(app, baseSir, snapshotFile, outputRoot, stateRoot, revision);
        ChangeApplyRequest applyRequest = new ChangeApplyRequest(
                stateRoot, registration.receipt().baselineId(),
                candidateSir, outputRoot, changeSet);
        ChangeApplyResult applyResult = app.apply(applyRequest);
        if (!(applyResult instanceof ChangeApplyResult.Applied applied)) {
            throw new ScenarioMaterializationException(ConformanceScenario.APPLY_DELETE,
                    "APPLY_FAILED", "v0.3 Apply did not produce Applied: " + applyResult);
        }
        return new ScenarioMaterialization(
                ConformanceScenario.APPLY_DELETE,
                outputRoot,
                Optional.of(stateRoot),
                Optional.of(registration.receipt()),
                Optional.of(applied.newBaselineReceipt()),
                Optional.of(b0Graph),
                Optional.of(b0.manifest()),
                Optional.of(applied.outcome()),
                lowerModel(candidateSir),
                applied.outputManifest());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static String scenarioDirName(ConformanceScenario scenario) {
        return scenario.displayName().toLowerCase(Locale.ROOT);
    }

    private ToolchainResult.Success compileBase(Path sourceFile, Path outputRoot) {
        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                sourceFile.toAbsolutePath(),
                SOURCE_ID,
                outputRoot.toAbsolutePath(),
                ConflictPolicy.FAIL_IF_EXISTS));
        if (!(result instanceof ToolchainResult.Success success)) {
            ToolchainResult.Failure failure = (ToolchainResult.Failure) result;
            throw new IllegalStateException("compileBase failed at "
                    + failure.failedStage() + ": " + failure.diagnostics());
        }
        return success;
    }

    private Path writeResourceTo(Path scenarioRoot, OwnedRunDirectory workRoot,
                                 String filename, String resourcePath) throws IOException {
        Path target = scenarioRoot.resolve(filename).toAbsolutePath();
        String content = ConformanceFixtures.readResource(resourcePath);
        Files.writeString(target, content, StandardCharsets.UTF_8);
        workRoot.recordCreation(target);
        return target;
    }

    private byte[] serializeGraph(ProjectGraph graph) {
        ProjectGraphSerialization serialization =
                new ProjectGraphSerializer().serialize(
                        graph, ProjectGraphCanonicalFormatVersion.V1);
        if (serialization instanceof ProjectGraphSerialization.Failure failure) {
            throw new IllegalStateException("serialization failed: " + failure.diagnostics());
        }
        return ((ProjectGraphSerialization.Success) serialization).document().bytes();
    }

    private ChangeBaseRevision buildRevision(Path baseSir, ProjectGraph graph) throws IOException {
        byte[] rawBytes = Files.readAllBytes(baseSir);
        return new ChangeBaseRevision(
                SOURCE_ID,
                sha256Hex(rawBytes),
                graph.version(),
                graph.canonicalDigest(),
                ProjectGraphCanonicalFormatVersion.V1);
    }

    private ChangeBaselineRegistrationResult.Success registerBaseline(
            ChangeExecutionApplication app,
            Path baseSir, Path snapshotFile, Path outputRoot, Path stateRoot,
            ChangeBaseRevision revision) {
        ChangeBaselineRegistrationRequest request = new ChangeBaselineRegistrationRequest(
                baseSir, snapshotFile, outputRoot, stateRoot, revision);
        ChangeBaselineRegistrationResult result = app.register(request);
        if (!(result instanceof ChangeBaselineRegistrationResult.Success success)) {
            throw new IllegalStateException("registerBaseline failed: " + result);
        }
        return success;
    }

    private ChangeSet buildV04ChangeSet(ChangeBaseRevision revision, Path baseSirFile)
            throws IOException {
        NormalizedSemanticModel model = compileModel(baseSirFile);
        NormalizedInput input = findInput(model, "PublishGoodsInput");
        NormalizedField field = findInputField(input, "title");
        ChangeTarget target = new ChangeTarget(input.id(), input.sourceNodeId(), field.sourceNodeId());
        return new ChangeSet(
                ChangeIrVersion.V0_4, revision,
                List.of(new ModifyInputFieldConstraints(target)));
    }

    private ChangeSet buildAddCapabilityChangeSet(ChangeBaseRevision revision, Path candidateSirFile)
            throws IOException {
        NormalizedSemanticModel model = compileModel(candidateSirFile);
        NormalizedCapability cap = findCapability(model, "SearchGoods");
        ChangeTarget target = new ChangeTarget(cap.id(), cap.sourceNodeId(), cap.workflow().sourceNodeId());
        return new ChangeSet(ChangeIrVersion.V0_2, revision, List.of(new AddCapability(target)));
    }

    private ChangeSet buildRemoveCapabilityChangeSet(ChangeBaseRevision revision, Path baseSirFile)
            throws IOException {
        NormalizedSemanticModel model = compileModel(baseSirFile);
        NormalizedCapability cap = findCapability(model, "PublishGoods");
        ChangeTarget target = new ChangeTarget(cap.id(), cap.sourceNodeId(), cap.workflow().sourceNodeId());
        return new ChangeSet(ChangeIrVersion.V0_3, revision, List.of(new RemoveCapability(target)));
    }

    private NormalizedSemanticModel compileModel(Path sourceFile) throws IOException {        String sourceText = Files.readString(sourceFile, StandardCharsets.UTF_8);
        List<io.kcg.sir.application.api.ExecutionDiagnostic> diagnostics = new java.util.ArrayList<>();
        Optional<io.kcg.sir.application.internal.SirCompilation.CompilationSnapshot> compiled =
                io.kcg.sir.application.internal.SirCompilation.compile(
                        sourceText, SOURCE_ID,
                        model -> new io.kcg.sir.generator.springboot.api.SpringBootGenerator().generate(model),
                        diagnostics);
        if (compiled.isEmpty()) {
            throw new IllegalStateException("SirCompilation.compile failed: " + diagnostics);
        }
        return compiled.get().semanticModel();
    }

    /**
     * Lower the given SIR source into its Spring Boot lowered model.
     *
     * <p>The orchestration needs the lowered model of the SIR that will actually run
     * so the target dependency inspection can compare the target runtime's Connector/J
     * against the generated POM. It is computed here, next to the other compilation
     * entry points, rather than re-derived in the orchestration.
     *
     * @param sourceFile the SIR source file
     * @return the lowered model
     * @throws IOException if the source cannot be read
     * @throws IllegalStateException if compilation or lowering fails
     */
    private io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel lowerModel(Path sourceFile)
            throws IOException {
        String sourceText = Files.readString(sourceFile, StandardCharsets.UTF_8);
        List<io.kcg.sir.application.api.ExecutionDiagnostic> diagnostics = new java.util.ArrayList<>();
        Optional<io.kcg.sir.application.internal.SirCompilation.CompilationSnapshot> compiled =
                io.kcg.sir.application.internal.SirCompilation.compile(
                        sourceText, SOURCE_ID,
                        model -> new io.kcg.sir.generator.springboot.api.SpringBootGenerator().generate(model),
                        diagnostics);
        if (compiled.isEmpty()) {
            throw new IllegalStateException("lowering failed: " + diagnostics);
        }
        return compiled.get().loweredModel();
    }

    private NormalizedInput findInput(NormalizedSemanticModel model, String name) {
        for (var decl : model.declarations()) {
            if (decl instanceof NormalizedInput input && input.name().equals(name)) {
                return input;
            }
        }
        throw new IllegalStateException("Input '" + name + "' not found in model");
    }

    private NormalizedField findInputField(NormalizedInput input, String fieldName) {
        for (var f : input.fields()) {
            if (f.name().equals(fieldName)) {
                return f;
            }
        }
        throw new IllegalStateException("Field '" + fieldName + "' not found in Input '" + input.name() + "'");
    }

    private NormalizedCapability findCapability(NormalizedSemanticModel model, String name) {
        NormalizedCapability found = null;
        for (var decl : model.declarations()) {
            if (decl instanceof NormalizedCapability cap && cap.name().equals(name)) {
                if (found != null) {
                    throw new IllegalStateException("multiple capabilities named '" + name + "'");
                }
                found = cap;
            }
        }
        if (found == null) {
            throw new IllegalStateException("Capability '" + name + "' not found in model");
        }
        return found;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                int v = b & 0xFF;
                if (v < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // Result type
    // ------------------------------------------------------------------

    /**
     * One scenario materialization result.
     *
     * <p>The {@code outputManifest} field is typed as {@link Object} because
     * the production API returns two distinct manifest types depending on
     * the scenario family: {@link io.kcg.sir.application.api.ExecutionManifest}
     * for IG-* scenarios (from {@link io.kcg.sir.application.api.ToolchainResult.Success})
     * and {@link io.kcg.sir.application.api.ChangeOutputManifest} for APPLY-*
     * scenarios (from {@link io.kcg.sir.application.api.ChangeApplyResult.Applied}).
     *
     * <p>The manifest, receipt, outcome, and lowered-model values the orchestration
     * needs are carried here rather than re-derived later, because they exist only
     * inside the materialization calls:
     * <ul>
     *   <li>{@code b0Receipt} / {@code b0Manifest}: the registered B0 baseline receipt
     *       and its {@link io.kcg.sir.application.api.ExecutionManifest} (APPLY-* only),
     *       required for the B0 to B1 delta;</li>
     *   <li>{@code outcome}: the {@link io.kcg.sir.application.api.ChangeApplyOutcome}
     *       the apply reported (APPLY-* only);</li>
     *   <li>{@code loweredModel}: the lowered model of the SIR that will actually run
     *       (the base SIR for IG-*, the candidate SIR for APPLY-*), required for the
     *       target dependency inspection.</li>
     * </ul>
     *
     * @param scenario       the scenario
     * @param outputRoot     the generated project output root (B0 for IG-*,
     *                       B1 for APPLY-*)
     * @param stateRoot      the Application state root (only for APPLY-*)
     * @param b0Receipt      the B0 baseline receipt (only for APPLY-*)
     * @param b1Receipt      the B1 baseline receipt (only for APPLY-*)
     * @param b0Graph        the B0 project graph
     * @param b0Manifest     the B0 ExecutionManifest (only for APPLY-*)
     * @param outcome        the apply outcome (only for APPLY-*)
     * @param loweredModel   the lowered model of the SIR that will run
     * @param outputManifest the final output manifest (ExecutionManifest for
     *                       IG-* scenarios, ChangeOutputManifest for APPLY-*
     *                       scenarios)
     */
    public record ScenarioMaterialization(
            ConformanceScenario scenario,
            Path outputRoot,
            Optional<Path> stateRoot,
            Optional<io.kcg.sir.application.api.ChangeBaselineReceipt> b0Receipt,
            Optional<io.kcg.sir.application.api.ChangeBaselineReceipt> b1Receipt,
            Optional<ProjectGraph> b0Graph,
            Optional<io.kcg.sir.application.api.ExecutionManifest> b0Manifest,
            Optional<io.kcg.sir.application.api.ChangeApplyOutcome> outcome,
            io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel loweredModel,
            Object outputManifest) {
        public ScenarioMaterialization {
            Objects.requireNonNull(scenario, "scenario");
            Objects.requireNonNull(outputRoot, "outputRoot");
            Objects.requireNonNull(stateRoot, "stateRoot");
            Objects.requireNonNull(b0Receipt, "b0Receipt");
            Objects.requireNonNull(b1Receipt, "b1Receipt");
            Objects.requireNonNull(b0Graph, "b0Graph");
            Objects.requireNonNull(b0Manifest, "b0Manifest");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(loweredModel, "loweredModel");
            Objects.requireNonNull(outputManifest, "outputManifest");
        }
    }

    /**
     * Thrown when scenario materialization fails.
     */
    public static final class ScenarioMaterializationException extends Exception {
        private final ConformanceScenario scenario;
        private final String messageKey;

        public ScenarioMaterializationException(ConformanceScenario scenario,
                                                String messageKey, String detail) {
            super(detail);
            this.scenario = scenario;
            this.messageKey = messageKey;
        }

        public ScenarioMaterializationException(ConformanceScenario scenario,
                                                String messageKey, String detail, Throwable cause) {
            super(detail, cause);
            this.scenario = scenario;
            this.messageKey = messageKey;
        }

        public ConformanceScenario scenario() {
            return scenario;
        }

        public String messageKey() {
            return messageKey;
        }
    }
}
