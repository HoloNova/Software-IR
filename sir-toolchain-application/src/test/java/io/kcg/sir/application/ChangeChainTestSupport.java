package io.kcg.sir.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ChangeApplyOutcome;
import io.kcg.sir.application.api.ChangeApplyRequest;
import io.kcg.sir.application.api.ChangeApplyResult;
import io.kcg.sir.application.api.ChangeBaselinePlanningRequest;
import io.kcg.sir.application.api.ChangeBaselinePlanningResult;
import io.kcg.sir.application.api.ChangeBaselineRegistrationRequest;
import io.kcg.sir.application.api.ChangeBaselineRegistrationResult;
import io.kcg.sir.application.api.ChangeExecutionApplication;
import io.kcg.sir.application.api.ConflictPolicy;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.application.internal.SirCompilation;
import io.kcg.sir.change.api.AddCapability;
import io.kcg.sir.change.api.ChangeAnalysis;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangePlan;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.change.api.ChangeTarget;
import io.kcg.sir.change.api.ModifyCapabilityWorkflow;
import io.kcg.sir.change.api.ModifyInputFieldConstraints;
import io.kcg.sir.change.api.RemoveCapability;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Test support for driving a SIR project through the real change pipeline: compile a base source
 * into a project root, register its baseline, then plan and apply candidate sources round by round.
 *
 * <p>Shared by Q13's change-loop contract test and Q14's projection-parity behaviour tests so both
 * speak to the same harness. Everything here goes through the public application API; nothing
 * reflects into production internals, and no database or Maven build is involved.
 */
final class ChangeChainTestSupport {

    private final Path root;
    private final SourceId sourceId;

    ChangeChainTestSupport(Path root, SourceId sourceId) {
        this.root = root;
        this.sourceId = sourceId;
    }

    // ------------------------------------------------------------------
    // 断言辅助
    // ------------------------------------------------------------------

    void assertPureCreation(ChangePlan plan, String what) {
        String shape = describePlan(plan);
        assertTrue(!plan.fileAdditions().isEmpty() || !plan.artifactAdditions().isEmpty(), shape);
        assertTrue(plan.fileChanges().isEmpty(), what + " must not change surviving files: " + shape);
        assertTrue(plan.fileDeletions().isEmpty(), what + " must not delete files: " + shape);
    }

    void assertPureUpdate(ChangePlan plan, String what) {
        String shape = describePlan(plan);
        assertTrue(!plan.fileChanges().isEmpty() || !plan.artifactChanges().isEmpty(), shape);
        assertTrue(plan.fileAdditions().isEmpty(), what + " must not add files: " + shape);
        assertTrue(plan.fileDeletions().isEmpty(), what + " must not delete files: " + shape);
    }

    void assertPureDeletion(ChangePlan plan, String what) {
        String shape = describePlan(plan);
        assertTrue(!plan.fileDeletions().isEmpty() || !plan.artifactDeletions().isEmpty(), shape);
        assertTrue(plan.fileAdditions().isEmpty(), what + " must not add files: " + shape);
        assertTrue(plan.fileChanges().isEmpty(), what + " must not change surviving files: " + shape);
    }

    ChangePlan planRound(Chain chain, Path candidate, ChangeSet changeSet) {
        ChangeBaselinePlanningResult result = chain.plan(candidate, changeSet);
        ChangeBaselinePlanningResult.Success success = assertInstanceOf(
                ChangeBaselinePlanningResult.Success.class, result, "planning must succeed: " + result);
        ChangeAnalysis.Planned planned = assertInstanceOf(
                ChangeAnalysis.Planned.class, success.analysis(),
                "planning must produce a plan: " + success.analysis());
        return planned.plan();
    }

    void applyRound(Chain chain, Path candidate, ChangeSet changeSet, String what) {
        ChangeApplyResult result = chain.apply(candidate, changeSet);
        ChangeApplyResult.Applied applied = assertInstanceOf(ChangeApplyResult.Applied.class, result,
                what + " must apply, got: " + result);
        assertEquals(ChangeApplyOutcome.FILES_AND_BASELINE, applied.outcome(),
                what + " must move files and baseline together");
        assertTrue(!applied.outputManifest().entries().isEmpty(),
                what + " must report the resulting output manifest");
    }

    /** Asserts the plan is an UPDATE (files change) and not {@code NoChanges}. */
    void assertPlannedUpdate(ChangeBaselinePlanningResult result, String what) {
        ChangeBaselinePlanningResult.Success success = assertInstanceOf(
                ChangeBaselinePlanningResult.Success.class, result, "planning must succeed: " + result);
        ChangeAnalysis.Planned planned = assertInstanceOf(ChangeAnalysis.Planned.class, success.analysis(),
                "changing " + what + " must be planned, not reported as "
                        + describeOutcome(result) + "; a projection that cannot see the fact would call it unchanged");
        assertTrue(!planned.plan().fileChanges().isEmpty() || !planned.plan().artifactChanges().isEmpty(),
                "changing " + what + " must reach the generated files: " + describePlan(planned.plan()));
    }

    /** Asserts the plan reported no change and returns the reason. */
    String assertNoChanges(ChangeBaselinePlanningResult result, String what) {
        ChangeBaselinePlanningResult.Success success = assertInstanceOf(
                ChangeBaselinePlanningResult.Success.class, result, "planning must succeed: " + result);
        ChangeAnalysis.NoChanges noChanges = assertInstanceOf(ChangeAnalysis.NoChanges.class, success.analysis(),
                what + " must report no change, got: " + describeOutcome(result));
        return noChanges.reason().name();
    }

    String describeOutcome(ChangeBaselinePlanningResult result) {
        if (result instanceof ChangeBaselinePlanningResult.Failure failure) {
            return "Failure[" + failure.failedStage() + " " + failure.diagnostics() + "]";
        }

        ChangeBaselinePlanningResult.Success success = (ChangeBaselinePlanningResult.Success) result;
        if (success.analysis() instanceof ChangeAnalysis.Planned planned) {
            return "Planned[" + describePlan(planned.plan()) + "]";
        }

        if (success.analysis() instanceof ChangeAnalysis.NoChanges noChanges) {
            return "NoChanges[" + noChanges.reason() + "]";
        }

        return "Failure[" + ((ChangeAnalysis.Failure) success.analysis()).diagnostics() + "]";
    }

    boolean isFailed(ChangeBaselinePlanningResult result) {
        if (result instanceof ChangeBaselinePlanningResult.Failure) {
            return true;
        }

        return result instanceof ChangeBaselinePlanningResult.Success success
                && success.analysis() instanceof ChangeAnalysis.Failure;
    }

    String codesOf(ChangeBaselinePlanningResult result) {
        List<String> codes = new ArrayList<>();
        if (result instanceof ChangeBaselinePlanningResult.Failure failure) {
            failure.diagnostics().forEach(diagnostic -> codes.add(diagnostic.code()));
        } else if (result instanceof ChangeBaselinePlanningResult.Success success
                && success.analysis() instanceof ChangeAnalysis.Failure failure) {
            failure.diagnostics().forEach(diagnostic -> codes.add(diagnostic.code()));
        }

        return codes.isEmpty() ? "(no diagnostics)" : String.join(",", codes);
    }

    String describePlan(ChangePlan plan) {
        return "additions=" + relativePaths(plan.fileAdditions().stream()
                    .map(addition -> addition.relativePath()).toList())
                + " changes=" + relativePaths(plan.fileChanges().stream()
                    .map(change -> change.relativePath()).toList())
                + " deletions=" + relativePaths(plan.fileDeletions().stream()
                    .map(deletion -> deletion.relativePath()).toList());
    }

    List<String> relativePaths(List<String> raw) {
        List<String> sorted = new ArrayList<>(raw);
        sorted.sort(String::compareTo);
        return sorted;
    }

    /** Every file under {@code root}, keyed by slash-separated relative path with its SHA-256. */
    Map<String, String> fingerprint(Path path) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(path.relativize(file).toString().replace('\\', '/'),
                        sha256Hex(Files.readAllBytes(file)));
            }
        }

        return files;
    }

    List<String> difference(Set<String> left, Set<String> right) {
        List<String> only = new ArrayList<>();
        for (String path : left) {
            if (!right.contains(path)) {
                only.add(path);
            }
        }

        only.sort(String::compareTo);
        return only;
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    Chain startChain(String baseResource) throws Exception {
        return startChainFromSource(readResource(baseResource), "baseline.sir");
    }

    /** Starts a chain from an inline source, for tests whose baseline is generated in the test. */
    Chain startChainFromSource(String source, String filename) throws Exception {
        Path workspace = Files.createTempDirectory(this.root, "chain-");
        Path outputRoot = Files.createDirectory(workspace.resolve("output"));
        Path stateRoot = Files.createDirectory(workspace.resolve("state"));
        Path baseSir = writeText(filename, source);

        ToolchainResult.Success compiled = compile(baseSir, outputRoot);
        Path snapshot = workspace.resolve("baseline.kcg-psg");
        Files.write(snapshot, serialize(compiled.graph()));
        ChangeExecutionApplication application = new ChangeExecutionApplication();
        ChangeBaselineRegistrationResult registration = application.register(
                new ChangeBaselineRegistrationRequest(baseSir, snapshot, outputRoot, stateRoot,
                        revisionOf(baseSir, compiled.graph())));
        ChangeBaselineRegistrationResult.Success registered = assertInstanceOf(
                ChangeBaselineRegistrationResult.Success.class, registration,
                "baseline registration must succeed: " + registration);
        return new Chain(application, outputRoot, stateRoot, baseSir, registered.receipt().baselineId());
    }

    /** Convenience for tests that use the write-slice-plus-enrollment baseline. */
    Chain startChain() throws Exception {
        return startChain("valid/course-admin-enrollment.sir");
    }

    ChangeBaseRevision revisionOf(Path source, ProjectGraph graph) throws IOException {
        return new ChangeBaseRevision(this.sourceId, sha256Hex(Files.readAllBytes(source)),
                graph.version(), graph.canonicalDigest(), ProjectGraphCanonicalFormatVersion.V1);
    }

    ToolchainResult.Success compile(Path source, Path outputRoot) {
        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                source.toAbsolutePath(), this.sourceId, outputRoot.toAbsolutePath(), ConflictPolicy.FAIL_IF_EXISTS));
        if (result instanceof ToolchainResult.Success success) {
            return success;
        }

        ToolchainResult.Failure failure = (ToolchainResult.Failure) result;
        throw new IllegalStateException("compiling " + source + " failed at " + failure.failedStage()
                + ": " + failure.diagnostics());
    }

    byte[] serialize(ProjectGraph graph) {
        ProjectGraphSerialization serialization = new ProjectGraphSerializer()
                .serialize(graph, ProjectGraphCanonicalFormatVersion.V1);
        if (serialization instanceof ProjectGraphSerialization.Success success) {
            return success.document().bytes();
        }

        throw new IllegalStateException("graph serialization failed: "
                + ((ProjectGraphSerialization.Failure) serialization).diagnostics());
    }

    Path writeSource(String resource, String filename) throws IOException {
        return writeText(filename, readResource(resource));
    }

    Path writeText(String filename, String content) throws IOException {
        Path sources = Files.createDirectories(this.root.resolve("sources"));
        Path target = sources.resolve(filename).toAbsolutePath();
        Files.writeString(target, content, UTF_8);
        return target;
    }

    String readResource(String resource) throws IOException {
        try (InputStream stream = ChangeChainTestSupport.class.getResourceAsStream("/" + resource)) {
            assertNotNull(stream, "missing test resource: " + resource);
            return new String(stream.readAllBytes(), UTF_8);
        }
    }

    NormalizedSemanticModel compileModel(Path source) throws IOException {
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        Optional<SirCompilation.CompilationSnapshot> compiled = SirCompilation.compile(
                Files.readString(source, UTF_8), this.sourceId,
                model -> new SpringBootGenerator().generate(model), diagnostics);
        if (compiled.isEmpty()) {
            throw new IllegalStateException("compiling " + source + " failed: " + diagnostics);
        }

        return compiled.get().semanticModel();
    }

    NormalizedCapability findCapability(NormalizedSemanticModel model, String name) {
        for (var declaration : model.declarations()) {
            if (declaration instanceof NormalizedCapability capability && capability.name().equals(name)) {
                return capability;
            }
        }

        throw new IllegalStateException("capability '" + name + "' not found");
    }

    NormalizedInput findInput(NormalizedSemanticModel model, String name) {
        for (var declaration : model.declarations()) {
            if (declaration instanceof NormalizedInput input && input.name().equals(name)) {
                return input;
            }
        }

        throw new IllegalStateException("input '" + name + "' not found");
    }

    NormalizedField findInputField(NormalizedInput input, String name) {
        for (NormalizedField field : input.fields()) {
            if (field.name().equals(name)) {
                return field;
            }
        }

        throw new IllegalStateException("field '" + name + "' not found in input '" + input.name() + "'");
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                int unsigned = value & 0xFF;
                if (unsigned < 0x10) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(unsigned));
            }

            return builder.toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A materialized project plus its registered baseline; every apply advances the baseline. */
    final class Chain {

        private final ChangeExecutionApplication application;
        private final Path outputRoot;
        private final Path stateRoot;
        private Path currentSir;
        private String baselineId;

        Chain(ChangeExecutionApplication application, Path outputRoot, Path stateRoot,
              Path currentSir, String baselineId) {
            this.application = application;
            this.outputRoot = outputRoot;
            this.stateRoot = stateRoot;
            this.currentSir = currentSir;
            this.baselineId = baselineId;
        }

        ChangeExecutionApplication application() {
            return this.application;
        }

        Path outputRoot() {
            return this.outputRoot;
        }

        Path stateRoot() {
            return this.stateRoot;
        }

        Path currentSir() {
            return this.currentSir;
        }

        String baselineId() {
            return this.baselineId;
        }

        /** The revision of the source the tree currently holds. */
        ChangeBaseRevision currentRevision() throws IOException {
            Path scratch = Files.createTempDirectory("graph-of-current");
            ProjectGraph graph = compile(this.currentSir, scratch).graph();
            return revisionOf(this.currentSir, graph);
        }

        ChangeSet addCapability(Path candidate, String capability) throws IOException {
            NormalizedCapability found = findCapability(compileModel(candidate), capability);
            return new ChangeSet(ChangeIrVersion.V0_2, currentRevision(),
                    List.of(new AddCapability(target(found))));
        }

        ChangeSet modifyCapabilityWorkflow(Path current, String capability) throws IOException {
            NormalizedCapability found = findCapability(compileModel(current), capability);
            return new ChangeSet(ChangeIrVersion.V0_1, currentRevision(),
                    List.of(new ModifyCapabilityWorkflow(target(found))));
        }

        ChangeSet modifyInputFieldConstraints(Path current, String input, String field) throws IOException {
            NormalizedInput found = findInput(compileModel(current), input);
            NormalizedField foundField = findInputField(found, field);
            return new ChangeSet(ChangeIrVersion.V0_4, currentRevision(),
                    List.of(new ModifyInputFieldConstraints(
                            new ChangeTarget(found.id(), found.sourceNodeId(), foundField.sourceNodeId()))));
        }

        ChangeSet removeCapability(Path current, String capability) throws IOException {
            NormalizedCapability found = findCapability(compileModel(current), capability);
            return new ChangeSet(ChangeIrVersion.V0_3, currentRevision(),
                    List.of(new RemoveCapability(target(found))));
        }

        ChangeBaselinePlanningResult plan(Path candidate, ChangeSet changeSet) {
            return this.application.plan(new ChangeBaselinePlanningRequest(
                    this.stateRoot, this.baselineId, candidate, this.outputRoot, changeSet));
        }

        ChangeApplyResult apply(Path candidate, ChangeSet changeSet) {
            ChangeApplyResult result = this.application.apply(new ChangeApplyRequest(
                    this.stateRoot, this.baselineId, candidate, this.outputRoot, changeSet));
            if (result instanceof ChangeApplyResult.Applied applied) {
                this.baselineId = applied.newBaselineReceipt().baselineId();
                this.currentSir = candidate;
            }

            return result;
        }

        private ChangeTarget target(NormalizedCapability capability) {
            return new ChangeTarget(capability.id(), capability.sourceNodeId(),
                    capability.workflow().sourceNodeId());
        }
    }
}
