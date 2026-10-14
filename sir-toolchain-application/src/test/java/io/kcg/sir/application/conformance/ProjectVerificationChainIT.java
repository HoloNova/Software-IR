package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.CandidateVerificationApplication;
import io.kcg.sir.application.api.CompilationInput;
import io.kcg.sir.application.api.ProjectBaselineApplication;
import io.kcg.sir.application.api.ProjectBaselineInspectionRequest;
import io.kcg.sir.application.api.ProjectBaselineRegistrationRequest;
import io.kcg.sir.application.api.ProjectBaselineResult;
import io.kcg.sir.application.api.ProjectToolchainRequest;
import io.kcg.sir.application.api.ProjectToolchainResult;
import io.kcg.sir.application.api.ResourceLimits;
import io.kcg.sir.application.api.StageStatus;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.VerificationReport;
import io.kcg.sir.application.api.VerificationRequest;
import io.kcg.sir.application.api.VerificationStage;
import io.kcg.sir.application.api.VerifiedVersionApplication;
import io.kcg.sir.application.api.VersionApplyRequest;
import io.kcg.sir.application.api.VersionApplyResult;
import io.kcg.sir.application.api.VersionPlanningApplication;
import io.kcg.sir.application.api.VersionPlanningRequest;
import io.kcg.sir.application.execution.MavenProjectBuilder;
import io.kcg.sir.application.execution.SpringBootApplicationHost;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineVerification;
import io.kcg.sir.change.edit.Command;
import io.kcg.sir.change.edit.CommandBatch;
import io.kcg.sir.change.edit.ProjectEditResult;
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
 * the stored sources, the candidate is verified for real (a real Maven build, a real Spring Boot process, a real MySQL
 * schema owned by the run, an oracle written from the original requirement), and only candidates whose verification
 * covers the business level are published as the next stored version.
 *
 * <p>Opt-in with the same switch as the other independent verification test. It shows that the gate lets a good
 * candidate through, that a candidate which is valid SIR and builds and starts but breaks the requirement is stopped
 * at the business level without touching the stored version or the generated project, and that the published version
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
        try {
            run(env, work);
        } finally {
            BusinessSliceHarness.deleteRecursively(work);
            Files.createDirectories(env.evidenceParent());
            Files.writeString(env.evidenceParent().resolve("verification-chain-report.txt"), reportText.toString(), StandardCharsets.UTF_8);
            assertFalse(Files.exists(work), "the chain work area must be removed");
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
        Path state = work.resolve("state");
        var generated = assertInstanceOf(ProjectToolchainResult.Success.class, new ToolchainApplication().executeProject(new ProjectToolchainRequest(sources, ENTRY, output)));
        var stored = assertInstanceOf(ProjectBaselineResult.Success.class, new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(generated.sources(), output, state)));
        SourceSnapshot v0 = stored.sources();
        String b0 = stored.receipt().baselineId();

        var sandbox = new MysqlVerificationSandbox(env, DDL, SEED, "chain");
        var app = new CandidateVerificationApplication(sandbox, new MavenProjectBuilder(env.mavenExecutable(), env.mavenRepo(), env.mavenOffline(), Optional.empty(), sandbox.secrets()),
                new SpringBootApplicationHost(Path.of(System.getProperty("java.home"), "bin", "java"), env.serverPort(), ChangeLoopCourseOracle.ROUTE, sandbox.secrets()),
                new ChangeLoopCourseOracle(sandbox), limits());

        // 1. The stored version itself satisfies the requirement: the oracle accepts the starting point.
        var baseline = verify(app, "stored-v0", v0);
        assertTrue(baseline.passedThrough(VerificationStage.BUSINESS), baseline.canonicalText());

        // 2. A user's command edits the stored sources (a wider course-code limit, which changes generated files): requirement-preserving.
        SourceSnapshot candidate = widenCourseCode(v0, output);
        assertNotEquals(v0.sha256Hex(), candidate.sha256Hex());
        var good = verify(app, "command-edit", candidate);
        assertTrue(good.passedThrough(VerificationStage.BUSINESS), good.canonicalText());
        assertNotEquals(baseline.binding().orElseThrow().manifestDigest(), good.binding().orElseThrow().manifestDigest(), "the command changed the generated files");

        var planning = new VersionPlanningRequest(state, output, b0, candidate);
        var planned = new VersionPlanningApplication().plan(planning);
        var published = new VerifiedVersionApplication().apply(new VerifiedVersionApplication.Request(new VersionApplyRequest(planning, planned), good, VerificationStage.BUSINESS));
        var applied = assertInstanceOf(VersionApplyResult.Applied.class, assertInstanceOf(VerifiedVersionApplication.Result.Completed.class, published).result());
        String b1 = applied.candidate().baselineId();
        assertNotEquals(b0, b1);
        assertEquals(good.binding().orElseThrow().manifestDigest(), applied.candidate().manifestDigest(), "the published version is the verified one");
        var reopened = assertInstanceOf(ProjectBaselineResult.Success.class, new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(state, output, b1)));
        assertEquals(candidate.manifest(), reopened.sources().manifest(), "the stored version holds the edited sources, and the project on disk matches it");

        // 3. A candidate that is valid SIR, builds and starts, but widens the filter and so breaks the original requirement.
        SourceSnapshot wrong = replaceEntry(candidate, "status == EnrollmentStatus.ACTIVE", "(status == EnrollmentStatus.ACTIVE or status == EnrollmentStatus.CANCELLED)");
        var bad = verify(app, "wrong-filter", wrong);
        assertEquals(StageStatus.PASSED, bad.stage(VerificationStage.START).status(), bad.canonicalText());
        assertEquals(StageStatus.FAILED, bad.stage(VerificationStage.BUSINESS).status(), bad.canonicalText());
        assertTrue(bad.stage(VerificationStage.BUSINESS).outcome().diagnostics().stream().anyMatch(d -> d.contains("ART101")), bad.stage(VerificationStage.BUSINESS).outcome().diagnostics().toString());

        Map<String, String> stateBefore = new TreeMap<>(io.kcg.sir.application.MultiSourceTestSupport.tree(state));
        Map<String, String> outputBefore = new TreeMap<>(io.kcg.sir.application.MultiSourceTestSupport.tree(output));
        var wrongPlanning = new VersionPlanningRequest(state, output, b1, wrong);
        var refused = new VerifiedVersionApplication().apply(new VerifiedVersionApplication.Request(
                new VersionApplyRequest(wrongPlanning, new VersionPlanningApplication().plan(wrongPlanning)), bad, VerificationStage.BUSINESS));
        var no = assertInstanceOf(VerifiedVersionApplication.Result.Refused.class, refused);
        assertEquals("VERIFICATION_NOT_PASSED", no.code(), no.message());
        assertEquals(stateBefore, new TreeMap<>(io.kcg.sir.application.MultiSourceTestSupport.tree(state)), "the stored versions are untouched");
        assertEquals(outputBefore, new TreeMap<>(io.kcg.sir.application.MultiSourceTestSupport.tree(output)), "the generated project is untouched");
        assertInstanceOf(ProjectBaselineResult.Success.class, new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(state, output, b1)));

        // 4. Evidence for the wrong candidate does not unlock the good one, and the reverse: reports are bound to their candidate.
        var mismatch = new VerifiedVersionApplication().apply(new VerifiedVersionApplication.Request(
                new VersionApplyRequest(wrongPlanning, new VersionPlanningApplication().plan(wrongPlanning)), good, VerificationStage.BUSINESS));
        assertEquals("VERIFICATION_CANDIDATE_MISMATCH", assertInstanceOf(VerifiedVersionApplication.Result.Refused.class, mismatch).code());
        assertEquals(stateBefore, new TreeMap<>(io.kcg.sir.application.MultiSourceTestSupport.tree(state)));
    }

    private VerificationReport verify(CandidateVerificationApplication app, String id, SourceSnapshot candidate) {
        var report = app.verify(new VerificationRequest(id, CompilationInput.project(candidate), VerificationStage.BUSINESS));
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
        var edited = assertInstanceOf(ProjectEditResult.Edited.class, new SourceEditor().edit(v0, batch));
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
