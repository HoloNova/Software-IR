package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.bundle.BaselineBuilder;
import io.kcg.sir.change.api.*;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.semantic.model.NormalizedCapability;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiSourceCompatibilityTest {
    @TempDir Path temp;

    @Test void realV2GraphsAndMatchingRequestVersionsAreRejectedBeforeAnyDeclarationPlanning() throws Exception {
        Path source = fixture(temp); var generated = generate(source, temp.resolve("generated"));
        var semantic = model(generated.sources());
        var capability = semantic.declarations().stream().filter(d -> d instanceof NormalizedCapability && d.name().equals("SearchCourseEnrollments"))
                .map(d -> (NormalizedCapability)d).findFirst().orElseThrow();
        var sourceEntry = generated.sources().manifest().files().stream().filter(f -> f.sourceId().equals(ENTRY)).findFirst().orElseThrow();
        var snapshot = new RenameSourceSnapshot(ENTRY, sourceEntry.byteCount(), sourceEntry.sha256Hex());
        var revision = new RenameRevisionSnapshot(generated.graph(), snapshot);
        var before = tree(temp);
        for (var format : List.of(ProjectGraphCanonicalFormatVersion.V1, ProjectGraphCanonicalFormatVersion.V2)) {
            // All three graph versions match. V1 is the deliberate attempt to disguise new data as an old snapshot.
            var basedOn = new ChangeBaseRevision(ENTRY, snapshot.sha256Hex(), GraphVersion.V0_2, generated.graph().canonicalDigest(), format);
            var target = new ChangeTarget(capability.id(), capability.sourceNodeId(), capability.sourceNodeId());
            var change = new ChangeSet(ChangeIrVersion.V0_1, basedOn, List.of(new ModifyCapabilityWorkflow(target)));
            var failed = assertInstanceOf(ChangeAnalysis.Failure.class, new ChangePlanner().plan(new ChangePlanningInput(semantic, semantic, generated.graph(), generated.graph(), change)));
            assertEquals(List.of("SIR-CHANGE-COMPAT-001"), failed.diagnostics().stream().map(ChangeDiagnostic::code).toList());
            assertEquals(ChangeDiagnosticStage.COMPAT, failed.diagnostics().getFirst().stage());
            var rename = assertInstanceOf(RenameAnalysis.Rejected.class, new RenamePlanner().plan(new RenamePlanningInput(semantic, semantic, revision, revision,
                    new RenamePlanRequest(basedOn, capability.id()))));
            assertTrue(rename.diagnostics().stream().anyMatch(d -> d.code().equals("SIR-RENAME-REQUEST-001") && d.stage() == RenameDiagnosticStage.REQUEST
                    && d.message().contains("single-source")), rename.diagnostics().toString());
        }
        assertEquals(before, tree(temp));
        assertNull(BaselineBuilder.serializeGraph(generated.graph()), "old Bundle cannot serialize a V2 graph into its V1 snapshot slot");
    }

    @Test void verifyCannotBypassTheVersionGateWithAnExternallyConstructedV2Plan() throws Exception {
        Path source = fixture(temp); var generated = generate(source, temp.resolve("generated"));
        var capability = model(generated.sources()).declarations().stream().filter(d -> d.name().equals("SearchCourseEnrollments")).findFirst().orElseThrow();
        var sourceEntry = generated.sources().manifest().files().stream().filter(f -> f.sourceId().equals(ENTRY)).findFirst().orElseThrow();
        var snapshot = new RenameSourceSnapshot(ENTRY, sourceEntry.byteCount(), sourceEntry.sha256Hex());
        var revision = new RenameRevisionSnapshot(generated.graph(), snapshot);
        var basedOn = new ChangeBaseRevision(ENTRY, snapshot.sha256Hex(), GraphVersion.V0_2, generated.graph().canonicalDigest(), ProjectGraphCanonicalFormatVersion.V2);
        var subject = new RenameSubject(capability.id(), RenameSubjectKind.CAPABILITY, capability.name(), "RenamedSearch");
        var provisional = new RenamePlan(basedOn, subject, List.of(), List.of(), List.of(), generated.graph().canonicalDigest(), generated.graph().canonicalDigest(),
                snapshot.sha256Hex(), snapshot.sha256Hex(), "0".repeat(64));
        var plan = new RenamePlan(basedOn, subject, List.of(), List.of(), List.of(), provisional.baseGraphCanonicalDigest(), provisional.candidateGraphCanonicalDigest(),
                snapshot.sha256Hex(), snapshot.sha256Hex(), io.kcg.sir.change.internal.RenamePlanDigest.of(provisional));
        var before = tree(temp);
        var errors = new RenamePlanner().verify(plan, revision, revision);
        assertTrue(errors.stream().anyMatch(d -> d.code().equals("SIR-RENAME-REQUEST-001") && d.message().contains("single-source")), errors.toString());
        assertEquals(before, tree(temp));
    }

    @Test void oldSingleSourceRegistrationDoesNotPublishAnyMultiSourceBundleOrCurrent() throws Exception {
        Path source = fixture(temp), output = temp.resolve("generated"), state = temp.resolve("state");
        generate(source, output); var files = tree(output);
        var result = assertInstanceOf(ChangeBaselineRegistrationResult.Failure.class, new ChangeExecutionApplication().registerGeneratedBaseline(
                new GeneratedBaselineRegistrationRequest(source.resolve("project.sir"), ENTRY, output, state)));
        assertEquals(ChangeExecutionStage.RECOMPILE, result.failedStage()); assertFalse(result.diagnostics().isEmpty());
        assertEquals(files, tree(output));
        assertFalse(Files.exists(state.resolve("CURRENT"))); assertFalse(Files.exists(state.resolve("CURRENT.new")));
        assertFalse(Files.exists(state.resolve("baselines"))); assertFalse(Files.exists(state.resolve("transactions")));
        // Creating/acquiring the existing state's lock is not publication; no candidate Bundle is installed.
    }
}
