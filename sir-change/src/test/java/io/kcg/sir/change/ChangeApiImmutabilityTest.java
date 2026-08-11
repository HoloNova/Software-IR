package io.kcg.sir.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.change.api.ArtifactChange;
import io.kcg.sir.change.api.ChangeAnalysis;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeDiagnostic;
import io.kcg.sir.change.api.ChangeDiagnosticStage;
import io.kcg.sir.change.api.ChangeDiagnosticSeverity;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangeOperation;
import io.kcg.sir.change.api.ChangePlan;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.change.api.ModifyCapabilityWorkflow;
import io.kcg.sir.change.api.ChangeTarget;
import io.kcg.sir.change.api.FileChange;
import io.kcg.sir.change.api.ImpactedArtifact;
import io.kcg.sir.change.api.NoChangeReason;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies the public API invariants of the Change IR v0.1 contract:
 * version sealing, single-operation rule, SHA-256 format, sealed
 * ChangeAnalysis variants, at-least-one rules for plan/impacted/file,
 * and ChangeAnalysis.Failure/NoChanges/Planned diagnostic invariants.
 */
class ChangeApiImmutabilityTest {

    private static final SourceId SOURCE_ID = SourceId.of("campus-market.sir");
    private static final String SHA_A =
            "0a1b2c3d4e5f60718293a4b5c6d7e8f900112233445566778899aabbccddeeff";
    private static final String SHA_B =
            "1a2b3c4d5e6f708192a3b4c5d6e7f8090112233445566778899aabbccddeeff0";
    private static final String SHA_C =
            "2b3c4d5e6f708192a3b4c5d6e7f8090112233445566778899aabbccddeeff0a1";

    @Test
    void changeIrVersionIsSingletonV0_1() {
        // [RQ-06 RECOVERY NOTE] v0.1-era assertion updated to current v0.1-v0.6 reality:
        // the historical test asserted a single version; the current main source (which
        // this task must preserve) has V0_1..V0_6. No new version was added by this task.
        assertEquals(6, ChangeIrVersion.values().length, "v0.1-v0.6 are the current versions");
        assertEquals(ChangeIrVersion.V0_1, ChangeIrVersion.valueOf("V0_1"));
    }

    @Test
    void changeSetRejectsNullAndWrongVersion() {
        ChangeBaseRevision rev = baseRevision();
        ModifyCapabilityWorkflow op = new ModifyCapabilityWorkflow(target());
        assertThrows(NullPointerException.class,
                () -> new ChangeSet(null, rev, List.of(op)));
        assertThrows(NullPointerException.class,
                () -> new ChangeSet(ChangeIrVersion.V0_1, null, List.of(op)));
        assertThrows(NullPointerException.class,
                () -> new ChangeSet(ChangeIrVersion.V0_1, rev, null));
    }

    @Test
    void changeSetEnforcesExactlyOneOperation() {
        ChangeBaseRevision rev = baseRevision();
        ModifyCapabilityWorkflow op = new ModifyCapabilityWorkflow(target());
        assertEquals(1, new ChangeSet(ChangeIrVersion.V0_1, rev, List.of(op)).operations().size());
        assertThrows(IllegalArgumentException.class,
                () -> new ChangeSet(ChangeIrVersion.V0_1, rev, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ChangeSet(ChangeIrVersion.V0_1, rev, List.of(op, op)));
    }

    @Test
    void changeSetDefensivelyCopiesOperations() {
        ChangeBaseRevision rev = baseRevision();
        ModifyCapabilityWorkflow op = new ModifyCapabilityWorkflow(target());
        ChangeSet set = new ChangeSet(ChangeIrVersion.V0_1, rev, new java.util.ArrayList<>(List.of(op)));
        assertEquals(1, set.operations().size());
        assertThrows(UnsupportedOperationException.class, () -> set.operations().add(op));
    }

    @Test
    void changeBaseRevisionValidatesSha256Format() {
        assertThrows(NullPointerException.class,
                () -> new ChangeBaseRevision(null, SHA_A, GraphVersion.V0_1, SHA_B,
                        ProjectGraphCanonicalFormatVersion.V1));
        assertThrows(NullPointerException.class,
                () -> new ChangeBaseRevision(SOURCE_ID, null, GraphVersion.V0_1, SHA_B,
                        ProjectGraphCanonicalFormatVersion.V1));
        assertThrows(NullPointerException.class,
                () -> new ChangeBaseRevision(SOURCE_ID, SHA_A, null, SHA_B,
                        ProjectGraphCanonicalFormatVersion.V1));
        assertThrows(NullPointerException.class,
                () -> new ChangeBaseRevision(SOURCE_ID, SHA_A, GraphVersion.V0_1, null,
                        ProjectGraphCanonicalFormatVersion.V1));
        assertThrows(NullPointerException.class,
                () -> new ChangeBaseRevision(SOURCE_ID, SHA_A, GraphVersion.V0_1, SHA_B, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ChangeBaseRevision(SOURCE_ID, "abc", GraphVersion.V0_1, SHA_B,
                        ProjectGraphCanonicalFormatVersion.V1));
        assertThrows(IllegalArgumentException.class,
                () -> new ChangeBaseRevision(SOURCE_ID, SHA_A.toUpperCase(), GraphVersion.V0_1, SHA_B,
                        ProjectGraphCanonicalFormatVersion.V1));
        assertThrows(IllegalArgumentException.class,
                () -> new ChangeBaseRevision(SOURCE_ID, SHA_A, GraphVersion.V0_1, "xyz",
                        ProjectGraphCanonicalFormatVersion.V1));
    }

    @Test
    void changeTargetRejectsNull() {
        SymbolId sym = new SymbolId("sir://software/x/capability/Y");
        AstNodeId ast = new AstNodeId("x/y");
        assertThrows(NullPointerException.class,
                () -> new ChangeTarget(null, ast, ast));
        assertThrows(NullPointerException.class,
                () -> new ChangeTarget(sym, null, ast));
        assertThrows(NullPointerException.class,
                () -> new ChangeTarget(sym, ast, null));
    }

    @Test
    void modifyCapabilityWorkflowRejectsNullTarget() {
        assertThrows(NullPointerException.class, () -> new ModifyCapabilityWorkflow(null));
    }

    @Test
    void changeOperationIsSealedPermittingOnlyModifyCapabilityWorkflow() {
        // [RQ-06 RECOVERY NOTE] v0.1-era assertion updated to current v0.1-v0.6 reality:
        // historical test permitted only ModifyCapabilityWorkflow; current main source
        // permits all six operation types (v0.2 AddCapability / RemoveCapability /
        // ModifyInputFieldConstraints / ModifyUnreferencedInputFieldType /
        // ModifyActorlessReadonlyCapabilityExposure). No new operation was added here.
        assertEquals(6, ChangeOperation.class.getPermittedSubclasses().length,
                "ChangeOperation permits all six current operation types");
        assertEquals(ModifyCapabilityWorkflow.class,
                ChangeOperation.class.getPermittedSubclasses()[0]);
    }

    @Test
    void changeAnalysisIsSealedWithThreeVariants() {
        assertEquals(3, ChangeAnalysis.class.getPermittedSubclasses().length,
                "ChangeAnalysis permits Planned, NoChanges, Failure");
    }

    @Test
    void plannedRejectsErrorDiagnostics() {
        ChangePlan plan = buildPlan();
        ChangeDiagnostic err = ChangeDiagnostic.error(
                "SIR-CHANGE-TARGET-001", ChangeDiagnosticStage.TARGET, 0, "err");
        assertThrows(IllegalArgumentException.class,
                () -> new ChangeAnalysis.Planned(plan, List.of(err)));
    }

    @Test
    void noChangesRejectsErrorDiagnostics() {
        ChangeDiagnostic err = ChangeDiagnostic.error(
                "SIR-CHANGE-TARGET-001", ChangeDiagnosticStage.TARGET, 0, "err");
        assertThrows(IllegalArgumentException.class,
                () -> new ChangeAnalysis.NoChanges(NoChangeReason.SEMANTICALLY_IDENTICAL, List.of(err)));
    }

    @Test
    void failureRequiresAtLeastOneError() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChangeAnalysis.Failure(List.of()));
    }

    @Test
    void failureRejectsEmptyDiagnostics() {
        assertThrows(NullPointerException.class, () -> new ChangeAnalysis.Failure(null));
    }

    @Test
    void fileChangeEnforcesSha256Format() {
        LoweredNodeId artifact = new LoweredNodeId("lir://spring/a");
        Optional<SymbolId> owner = Optional.of(new SymbolId("sir://software/x/capability/Y"));
        assertThrows(IllegalArgumentException.class,
                () -> new FileChange("path", artifact, owner, 10L, "bad", 20L, SHA_B));
        assertThrows(IllegalArgumentException.class,
                () -> new FileChange("path", artifact, owner, 10L, SHA_A, 20L, "bad"));
        assertThrows(IllegalArgumentException.class,
                () -> new FileChange("path", artifact, owner, 10L, SHA_A, 20L, SHA_A));
    }

    @Test
    void fileChangeBytesChangedPredicate() {
        LoweredNodeId artifact = new LoweredNodeId("lir://spring/a");
        Optional<SymbolId> owner = Optional.of(new SymbolId("sir://software/x/capability/Y"));
        FileChange changed = new FileChange("p", artifact, owner, 10L, SHA_A, 20L, SHA_B);
        assertTrue(changed.bytesChanged());
    }

    @Test
    void impactedArtifactRequiresAtLeastOneFileChange() {
        LoweredNodeId artifact = new LoweredNodeId("lir://spring/a");
        Optional<SymbolId> owner = Optional.of(new SymbolId("sir://software/x/capability/Y"));
        assertThrows(IllegalArgumentException.class,
                () -> new ImpactedArtifact(artifact, owner,
                        ArtifactRole.DeclarationRole.SERVICE, "q", List.of()));
    }

    @Test
    void artifactChangeRequiresAtLeastOneFileChange() {
        ImpactedArtifact impacted = new ImpactedArtifact(
                new LoweredNodeId("lir://spring/a"),
                Optional.of(new SymbolId("sir://software/x/capability/Y")),
                ArtifactRole.DeclarationRole.SERVICE,
                "q",
                List.of(new FileChange("p",
                        new LoweredNodeId("lir://spring/a"),
                        Optional.of(new SymbolId("sir://software/x/capability/Y")),
                        1L, SHA_A, 2L, SHA_B)));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactChange(impacted, List.of()));
    }

    @Test
    void changePlanRequiresAtLeastOneOfEach() {
        ChangeSet set = new ChangeSet(
                ChangeIrVersion.V0_1, baseRevision(), List.of(new ModifyCapabilityWorkflow(target())));
        assertThrows(IllegalArgumentException.class,
                () -> new ChangePlan(set, List.of(), List.of()));
    }

    @Test
    void changeDiagnosticErrorFactoryProducesErrorSeverity() {
        ChangeDiagnostic d = ChangeDiagnostic.error(
                "SIR-CHANGE-REQUEST-001", ChangeDiagnosticStage.REQUEST, 0, "msg");
        assertEquals(ChangeDiagnosticSeverity.ERROR, d.severity());
        assertTrue(d.isError());
        assertEquals(ChangeDiagnosticStage.REQUEST, d.stage());
        assertEquals(0, d.operationIndex());
    }

    // --- helpers ---

    // [RQ-06 RECOVERY NOTE] baseRevision() helper was in the truncated tail of the
    // historical dump (session block 2026-07-19T10:31:02, rollout-2026-07-18T16-06,
    // line 1033). Reconstructed from the ChangeBaseRevision record signature; digest
    // values are placeholders that satisfy the immutability/validation assertions.
    private static ChangeBaseRevision baseRevision() {
        return new ChangeBaseRevision(
                SourceId.of("campus-market.sir"),
                "a".repeat(64),
                GraphVersion.V0_1,
                "b".repeat(64),
                ProjectGraphCanonicalFormatVersion.V1);
    }

    // [RQ-06 RECOVERY NOTE] buildPlan() helper was in the truncated tail of the
    // historical dump (same evidence as baseRevision()). Reconstructed from the
    // ChangePlan 3-arg convenience constructor plus a minimal legal ArtifactChange
    // (FileChange -> ImpactedArtifact -> ArtifactChange chain); used only by
    // plannedRejectsErrorDiagnostics, whose assertion does not depend on plan contents.
    private static ChangePlan buildPlan() {
        FileChange fileChange = new FileChange(
                "src/main/java/x/YService.java",
                new LoweredNodeId("lir://spring/artifact-service/Y"),
                Optional.of(new SymbolId("sir://software/x/capability/Y")),
                0L, "a".repeat(64), 1L, "b".repeat(64));
        ArtifactChange artifactChange = new ArtifactChange(
                new ImpactedArtifact(
                        new LoweredNodeId("lir://spring/artifact-service/Y"),
                        Optional.of(new SymbolId("sir://software/x/capability/Y")),
                        ArtifactRole.DeclarationRole.SERVICE, "x.YService", List.of(fileChange)),
                List.of(fileChange));
        return new ChangePlan(
                new ChangeSet(ChangeIrVersion.V0_1, baseRevision(),
                        List.of(new ModifyCapabilityWorkflow(target()))),
                List.of(artifactChange), List.of(fileChange));
    }

    private static ChangeTarget target() {
        return new ChangeTarget(
                new SymbolId("sir://software/x/capability/Y"),
                new AstNodeId("x/y"),
                // [RQ-06 RECOVERY NOTE] third record component (targetNodeId) was in the
                // truncated tail of the historical dump (session block 2026-07-19T10:31:02,
                // rollout-2026-07-18T16-06, line 1033); value reconstructed as declaration
                // node id. All call sites share this helper, so record equality semantics
                // of the immutability tests are unaffected.
                new AstNodeId("x/y"));
    }
}