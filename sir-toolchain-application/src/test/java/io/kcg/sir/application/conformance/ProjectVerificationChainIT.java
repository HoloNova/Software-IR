package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.CandidateVerificationApplication;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.application.api.ResourceLimits;
import io.kcg.sir.application.api.StageStatus;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.VerificationReport;
import io.kcg.sir.application.api.VerificationRequest;
import io.kcg.sir.application.api.VerificationStage;
import io.kcg.sir.application.api.VerifiedWorkspaceApplication;
import io.kcg.sir.application.api.WorkspaceApplyResult;
import io.kcg.sir.application.api.WorkspacePlanningResult;
import io.kcg.sir.application.api.WorkspaceVersionApplication;
import io.kcg.sir.application.api.WorkspaceVersionRequest;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.execution.GitVersionControl;
import io.kcg.sir.application.execution.MavenProjectBuilder;
import io.kcg.sir.application.execution.SpringBootApplicationHost;
import io.kcg.sir.application.internal.ProjectSourceReader;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineVerification;
import io.kcg.sir.change.edit.Command;
import io.kcg.sir.change.edit.CommandBatch;
import io.kcg.sir.change.edit.EditResult;
import io.kcg.sir.change.edit.Selector;
import io.kcg.sir.change.edit.SourceEditor;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.StructNode;
import io.kcg.sir.semantic.schema.StructValue;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The whole path of the layered design for a project (SIR 0.2), against the real reference environment: commands edit
 * the sources on disk, the candidate is verified for real (a real Maven build, a real Spring Boot process, a real MySQL
 * schema owned by the run, an oracle written from the original requirement), and only candidates whose verification
 * covers the business level are published (written to the project and saved in Git).
 *
 * <p>Opt-in with the same switch as the other independent verification test. It shows that the gate lets a good
 * candidate through, that a candidate which is valid SIR and builds and starts but breaks the requirement is stopped
 * at the business level without touching the sources, the generated project or Git, and that the published version
 * is the one that was verified.
 */
class ProjectVerificationChainIT {
    private static final String ENABLED = "kcg.verification.enabled";
    private static final String RESOURCE = "/valid/multi-course/";
    private static final List<String> FILES = List.of("project.sir", "modules/course.sir", "modules/student.sir", "modules/enrollment.sir");
    private static final String DDL = "/conformance/mysql/change-loop-ddl.sql";
    private static final String SEED = "/conformance/mysql/change-loop-seed.sql";
    private static final SourceId ENTRY = SourceId.of("project.sir");
    private final StringBuilder reportText = new StringBuilder();

    private static ResourceLimits limits() {
        return new ResourceLimits(java.time.Duration.ofMinutes(20), java.time.Duration.ofMinutes(3), java.time.Duration.ofMinutes(5), 256, 256, 128, 8L * 1024 * 1024);
    }

    @Test void commandsVerifiedForRealThenPublishedAndAWrongCandidateIsStoppedBeforeAnythingChanges() throws Exception {
        var env = BusinessSliceHarness.Environment.read(System::getProperty, System::getenv, ENABLED);
        if (!env.enabled() || !env.complete()) {
            System.out.println("[PROJECT-VERIFICATION-CHAIN] NOT_RUN: " + env.missingPrerequisites());
            return;
        }

        Path work = env.workParent().resolve("chain-" + Long.toHexString(System.currentTimeMillis()));
        Throwable failure = null;
        try {
            run(env, work);
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            try {
                Files.createDirectories(env.evidenceParent());
                Files.writeString(env.evidenceParent().resolve("verification-chain-report.txt"), reportText.toString(), StandardCharsets.UTF_8);
                if (failure == null) {
                    // Kept when the test failed, like the other conformance tests, so the sources, the project and the Git history can be inspected.
                    BusinessSliceHarness.deleteRecursively(work);
                    assertFalse(Files.exists(work), "the chain work area must be removed");
                }
            } catch (Exception | AssertionError cleanup) {
                if (failure != null) {
                    failure.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }

    private void run(BusinessSliceHarness.Environment env, Path work) throws Exception {
        Path sources = Files.createDirectories(work.resolve("sources"));
        for (String name : FILES) {
            Path path = sources.resolve(name);
            Files.createDirectories(path.getParent());
            try (var stream = getClass().getResourceAsStream(RESOURCE + name)) {
                Files.write(path, stream.readAllBytes());
            }
        }

        Path output = work.resolve("output");
        var generated = assertInstanceOf(ToolchainResult.Success.class, new ToolchainApplication().execute(new ToolchainRequest(sources, ENTRY, output)));
        SourceSnapshot v0 = generated.sources();

        var sandbox = new MysqlVerificationSandbox(env, DDL, SEED, "chain");
        var app = new CandidateVerificationApplication(sandbox, new MavenProjectBuilder(env.mavenExecutable(), env.mavenRepo(), env.mavenOffline(), Optional.empty(), sandbox.secrets()),
                new SpringBootApplicationHost(Path.of(System.getProperty("java.home"), "bin", "java"), env.serverPort(), ChangeLoopCourseOracle.ROUTE, sandbox.secrets()),
                new ChangeLoopCourseOracle(sandbox), limits());

        // 1. The version on disk itself satisfies the requirement: the oracle accepts the starting point.
        var baseline = verify(app, "version-0", v0);
        assertTrue(baseline.passedThrough(VerificationStage.BUSINESS), baseline.canonicalText());

        // 2. A user's command edits the sources (a wider course-code limit, which changes generated files): requirement-preserving.
        SourceSnapshot candidate = widenCourseCode(v0, output);
        assertNotEquals(v0.sha256Hex(), candidate.sha256Hex());
        var good = verify(app, "command-edit", candidate);
        assertTrue(good.passedThrough(VerificationStage.BUSINESS), good.canonicalText());
        assertNotEquals(baseline.binding().orElseThrow().manifestDigest(), good.binding().orElseThrow().manifestDigest(), "the command changed the generated files");

        // The project is a Git working tree: the SIR sources and the generated files side by side, history in Git.
        Path repository = Files.createDirectories(work.resolve("repository"));
        Files.move(sources, repository.resolve("sources"));
        Files.move(output, repository.resolve("generated"));
        Path liveSources = repository.resolve("sources");
        Path liveOutput = repository.resolve("generated");
        var git = new GitVersionControl();
        // The work area sits under the checkout this test runs in, so Git would find that outer repository and the application would refuse a
        // root that is not its own top level. The project is made its own repository here, as a user's project would be.
        assertEquals(Optional.empty(), git.initialize(repository));
        var versions = new WorkspaceVersionApplication(git);
        var gate = new VerifiedWorkspaceApplication(git);

        var request = new WorkspaceVersionRequest(repository, liveSources, liveOutput, candidate);
        var planned = versions.plan(request);
        var published = gate.apply(new VerifiedWorkspaceApplication.Request(request, planned, good, VerificationStage.BUSINESS));
        var outcome = assertInstanceOf(VerifiedWorkspaceApplication.Result.Completed.class, published).result();
        var applied = assertInstanceOf(WorkspaceApplyResult.Applied.class, outcome, () -> String.valueOf(outcome.diagnostics()));
        assertEquals(good.binding().orElseThrow().manifestDigest(), applied.candidate().manifestDigest(), "the published version is the verified one");
        assertTrue(applied.beforeCommit().isPresent() && applied.afterCommit().isPresent(), "both versions are saved in Git");
        assertEquals("2", git(repository, "rev-list", "--count", "HEAD").strip());
        var onDisk = ProjectSourceReader.read(new io.kcg.sir.application.api.ToolchainRequest(liveSources, ENTRY, liveOutput), new ArrayList<ExecutionDiagnostic>()).orElseThrow();
        assertEquals(candidate.manifest(), onDisk.snapshot().manifest(), "the sources on disk are the edited ones, and the project matches them");
        String publishedCommit = git(repository, "rev-parse", "HEAD").strip();

        // 3. A candidate that is valid SIR, builds and starts, but widens the filter and so breaks the original requirement.
        SourceSnapshot wrong = replaceEntry(candidate, "status == EnrollmentStatus.ACTIVE", "(status == EnrollmentStatus.ACTIVE or status == EnrollmentStatus.CANCELLED)");
        var bad = verify(app, "wrong-filter", wrong);
        assertEquals(StageStatus.PASSED, bad.stage(VerificationStage.START).status(), bad.canonicalText());
        assertEquals(StageStatus.FAILED, bad.stage(VerificationStage.BUSINESS).status(), bad.canonicalText());
        assertTrue(bad.stage(VerificationStage.BUSINESS).outcome().diagnostics().stream().anyMatch(d -> d.contains("ART101")), bad.stage(VerificationStage.BUSINESS).outcome().diagnostics().toString());

        Map<String, String> sourcesBefore = new TreeMap<>(io.kcg.sir.application.MultiSourceTestSupport.tree(liveSources));
        Map<String, String> outputBefore = new TreeMap<>(io.kcg.sir.application.MultiSourceTestSupport.tree(liveOutput));
        var wrongRequest = new WorkspaceVersionRequest(repository, liveSources, liveOutput, wrong);
        var wrongPlan = assertInstanceOf(WorkspacePlanningResult.Planned.class, versions.plan(wrongRequest));
        var refused = gate.apply(new VerifiedWorkspaceApplication.Request(wrongRequest, wrongPlan, bad, VerificationStage.BUSINESS));
        var no = assertInstanceOf(VerifiedWorkspaceApplication.Result.Refused.class, refused);
        assertEquals("VERIFICATION_NOT_PASSED", no.code(), no.message());
        assertEquals(sourcesBefore, new TreeMap<>(io.kcg.sir.application.MultiSourceTestSupport.tree(liveSources)), "the sources are untouched");
        assertEquals(outputBefore, new TreeMap<>(io.kcg.sir.application.MultiSourceTestSupport.tree(liveOutput)), "the generated project is untouched");
        assertEquals(publishedCommit, git(repository, "rev-parse", "HEAD").strip(), "nothing new was saved in Git");

        // 4. Evidence for the good candidate does not unlock the wrong one: reports are bound to their candidate.
        var mismatch = gate.apply(new VerifiedWorkspaceApplication.Request(wrongRequest, wrongPlan, good, VerificationStage.BUSINESS));
        assertEquals("VERIFICATION_CANDIDATE_MISMATCH", assertInstanceOf(VerifiedWorkspaceApplication.Result.Refused.class, mismatch).code());
        assertEquals(sourcesBefore, new TreeMap<>(io.kcg.sir.application.MultiSourceTestSupport.tree(liveSources)));
        assertEquals(publishedCommit, git(repository, "rev-parse", "HEAD").strip());
    }

    private static String git(Path repository, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), String.join(" ", command) + ": " + text);
        return text;
    }

    private VerificationReport verify(CandidateVerificationApplication app, String id, SourceSnapshot candidate) {
        var report = app.verify(new VerificationRequest(id, candidate, VerificationStage.BUSINESS));
        reportText.append(report.canonicalText()).append("reportSha256=").append(report.sha256Hex()).append("\n\n");
        System.out.println(report.canonicalText());
        assertTrue(report.cleanup().proved(), "cleanup must be proved: " + report.cleanup().checks());
        return report;
    }

    /** Edits through the command editor, exactly as a user's update command would. */
    private static SourceSnapshot widenCourseCode(SourceSnapshot v0, Path output) throws Exception {
        var model = ProjectBaselineVerification.compile(v0, output, new ArrayList<>()).compilation().semanticModel();
        StructNode course = SoftwareDraft.of(model).declaration("NormalizedEntity", "Course").orElseThrow();
        StructNode wider = replaceText(course, "32", "40");
        assertNotEquals(course, wider);
        var batch = new CommandBatch(CommandBatch.PROTOCOL, "CourseAdmin", SourceEditor.sha256(v0),
                List.of(new Command.Update(Selector.root().named("declarations", "Course"), new StructValue.Child(wider))));
        var edited = assertInstanceOf(EditResult.Edited.class, new SourceEditor().edit(v0, batch));
        return edited.sources();
    }

    private static SourceSnapshot replaceEntry(SourceSnapshot base, String from, String to) {
        Map<SourceId, byte[]> inputs = new LinkedHashMap<>();
        for (var file : base.manifest().files()) {
            inputs.put(file.sourceId(), base.bytes(file.sourceId()));
        }

        String text = new String(inputs.get(ENTRY), StandardCharsets.UTF_8);
        assertTrue(text.contains(from), "fixture condition is reachable");
        inputs.put(ENTRY, text.replace(from, to).getBytes(StandardCharsets.UTF_8));
        return new SourceSnapshot(base.entry(), inputs);
    }

    private static StructNode replaceText(StructNode node, String from, String to) {
        List<StructNode.Slot> slots = new ArrayList<>();
        for (StructNode.Slot slot : node.slots()) {
            slots.add(new StructNode.Slot(slot.key(), slot.kind(), replaceText(slot.value(), from, to)));
        }

        return new StructNode(node.kind(), slots);
    }

    private static StructValue replaceText(StructValue value, String from, String to) {
        return switch (value) {
            case StructValue.Text text -> new StructValue.Text(text.value().equals(from) ? to : text.value());
            case StructValue.None none -> none;
            case StructValue.Child child -> new StructValue.Child(replaceText(child.node(), from, to));
            case StructValue.Seq seq -> new StructValue.Seq(seq.elements().stream().map(e -> replaceText(e, from, to)).toList());
        };
    }
}
