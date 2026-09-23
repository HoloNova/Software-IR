package io.kcg.sir.application;



/**
 * Planning- and apply-level contract for the Q13 change loop on the course slice.
 *
 * <p>This class carries the three measurements the Q13 work order requires before the business
 * scenario is written (P1, P2, P3), plus the idempotent replay (R4) and the two negative rounds
 * (N1, N2). It deliberately stays at the public application API and never starts a database or a
 * Maven build, so the whole chain can be measured cheaply before the real-environment IT runs
 * once, at the end of the batch.
 *
 * <ul>
 *   <li><b>P1</b>: can an {@link AddCapability} candidate bring new declarations (entities, enum,
 *       views, input, error, capability) and still be a pure creation?</li>
 *   <li><b>P2</b>: does removing the last write capability apply at all — i.e. does the C7
 *       structural constraint (support artifacts are unconditional targets) hold for a real
 *       DELETE, and do the support artifacts survive it?</li>
 *   <li><b>P3</b>: does the incrementally applied project converge, path by path and byte by
 *       byte, with a from-scratch generation of the same candidate?</li>
 *   <li><b>R4</b>: does replaying an already applied candidate report {@code NoChanges}?</li>
 *   <li><b>N1</b>/<b>N2</b>: do an invalid candidate and a stale baseline leave the project
 *       untouched?</li>
 * </ul>
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.kcg.sir.application.api.ChangeApplyRequest;
import io.kcg.sir.application.api.ChangeApplyResult;
import io.kcg.sir.application.api.ChangeBaselinePlanningResult;
import io.kcg.sir.change.api.AddCapability;
import io.kcg.sir.change.api.ChangePlan;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.source.SourceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ChangeLoopPlanningContractTest {

    private static final SourceId SOURCE_ID = SourceId.of("course-admin.sir");
    /** Q10's write slice alone: the base a change must not be able to grow with new declarations. */
    private static final String WRITE_SLICE = "valid/course-admin.sir";
    /** The chain base: the write slice plus the Q11 enrollment slice. */
    private static final String BASE = "valid/course-admin-enrollment.sir";
    private static final String FILTER_ANY = "valid/course-admin-enrollment-filter-any.sir";
    private static final String TIGHTEN_NAME = "valid/course-admin-enrollment-tighten.sir";
    private static final String REMOVE_UPDATE = "valid/course-admin-enrollment-remove-update.sir";
    private static final String REMOVE_LAST_WRITER = "valid/course-admin-enrollment-readonly.sir";
    private static final String ADD_LIST_REFS = "valid/course-admin-enrollment-add-list-refs.sir";

    /** The shared artifacts that must survive deleting the last write capability (Q10 C7). */
    private static final List<String> SUPPORT_ARTIFACT_SUFFIXES = List.of(
            "ApiErrorResponse.java",
            "ApiException.java",
            "ApiExceptionAdvice.java",
            "ValidationSupport.java",
            "application.yml");

    @TempDir
    Path temporaryDirectory;

    private ChangeChainTestSupport support;

    @org.junit.jupiter.api.BeforeEach
    void setUpSupport() {
        support = new ChangeChainTestSupport(temporaryDirectory, SOURCE_ID);
    }

    // ------------------------------------------------------------------
    // P1：变更层能否带新声明（实测为"不能"，这里把边界钉成回归）
    // ------------------------------------------------------------------

    @Test
    void aChangeCannotBringNewDeclarationsWithACapability() throws Exception {
        // Measured 2026-09-23 on the untouched write slice: handing the enrollment slice to
        // AddCapability fails with SIR-CHANGE-SCOPE-101, "candidate must add exactly one new
        // declaration; got 9" — the operation may add the capability itself, not its entities,
        // enum, views, input and error. The slice therefore belongs to the chain base, and this
        // test pins the boundary so a later widening of the change vocabulary is noticed.
        ChangeChainTestSupport.Chain chain = support.startChain(WRITE_SLICE);
        Map<String, String> before = support.fingerprint(chain.outputRoot());
        Path candidate = support.writeSource(BASE, "course-admin-enrollment.sir");

        ChangeBaselinePlanningResult result = chain.plan(candidate,
                chain.addCapability(candidate, "SearchCourseEnrollments"));

        assertEquals("SIR-CHANGE-SCOPE-101", support.codesOf(result),
                "the boundary must be the scope rule, not another stage");
        assertEquals(before, support.fingerprint(chain.outputRoot()),
                "a refused candidate must leave the project untouched");
    }

    // ------------------------------------------------------------------
    // 链：R1 -> R2 -> R3a -> R3b（P2 在最后两轮）
    // ------------------------------------------------------------------

    @Test
    void theChainAppliesOneFamilyPerRoundAndKeepsTheSupportArtifacts() throws Exception {
        ChainRun run = applyFullChain();

        // P2: the deletion that removes the last write capability must not delete a support artifact.
        List<String> deleted = support.relativePaths(run.lastWriterDeletion().fileDeletions().stream()
                .map(deletion -> deletion.relativePath()).toList());
        for (String suffix : SUPPORT_ARTIFACT_SUFFIXES) {
            assertTrue(deleted.stream().noneMatch(path -> path.endsWith(suffix)),
                    "the delete round must not delete the support artifact " + suffix + "; deleted=" + deleted);
        }

        // ... and the support artifacts are still on disk after the last writer is gone.
        Map<String, String> tree = support.fingerprint(run.chain().outputRoot());
        for (String suffix : SUPPORT_ARTIFACT_SUFFIXES) {
            assertTrue(tree.keySet().stream().anyMatch(path -> path.endsWith(suffix)),
                    "support artifact must survive the delete round: " + suffix + " in " + tree.keySet());
        }
    }

    /** The whole chain, so the replay and convergence cases start from the same applied state. */
    private ChainRun applyFullChain() throws Exception {
        ChangeChainTestSupport.Chain chain = support.startChain();

        Path filterAny = support.writeSource(FILTER_ANY, "course-admin-enrollment-filter-any.sir");
        ChangeSet widenFilter = chain.modifyCapabilityWorkflow(chain.currentSir(), "SearchCourseEnrollments");
        support.assertPureUpdate(support.planRound(chain, filterAny, widenFilter), "R1 widen the enrollment filter");
        support.applyRound(chain, filterAny, widenFilter, "R1 widen the enrollment filter");

        Path tightened = support.writeSource(TIGHTEN_NAME, "course-admin-enrollment-tighten.sir");
        ChangeSet tightenConstraints = chain.modifyInputFieldConstraints(chain.currentSir(), "CreateCourseInput", "name");
        support.assertPureUpdate(support.planRound(chain, tightened, tightenConstraints), "R2 tighten CreateCourseInput.name");
        support.applyRound(chain, tightened, tightenConstraints, "R2 tighten CreateCourseInput.name");

        Path withoutUpdate = support.writeSource(REMOVE_UPDATE, "course-admin-enrollment-remove-update.sir");
        ChangeSet removeUpdate = chain.removeCapability(chain.currentSir(), "UpdateCourse");
        support.assertPureDeletion(support.planRound(chain, withoutUpdate, removeUpdate), "R3a remove UpdateCourse");
        support.applyRound(chain, withoutUpdate, removeUpdate, "R3a remove UpdateCourse");

        Path readOnly = support.writeSource(REMOVE_LAST_WRITER, "course-admin-enrollment-readonly.sir");
        ChangeSet removeLastWriter = chain.removeCapability(chain.currentSir(), "CreateCourse");
        ChangePlan lastWriterDeletion = support.planRound(chain, readOnly, removeLastWriter);
        support.assertPureDeletion(lastWriterDeletion, "R3b remove CreateCourse (the last write capability)");
        support.applyRound(chain, readOnly, removeLastWriter, "R3b remove CreateCourse (the last write capability)");

        Path addRefs = support.writeSource(ADD_LIST_REFS, "course-admin-enrollment-add-list-refs.sir");
        ChangeSet addCapability = chain.addCapability(addRefs, "ListCourseRefs");
        support.assertPureCreation(support.planRound(chain, addRefs, addCapability), "R4 add the list endpoint");
        support.applyRound(chain, addRefs, addCapability, "R4 add the list endpoint");

        return new ChainRun(chain, addRefs, addCapability, lastWriterDeletion);
    }

    /** The state the chain ends in, plus the plans the individual assertions need. */
    private record ChainRun(
            ChangeChainTestSupport.Chain chain,
            Path listEndpointCandidate,
            ChangeSet listEndpointChangeSet,
            ChangePlan lastWriterDeletion) {
    }

    // ------------------------------------------------------------------
    // P3
    // ------------------------------------------------------------------

    @Test
    void anAppliedCandidateConvergesWithFromScratchGeneration() throws Exception {
        ChainRun run = applyFullChain();

        Path scratch = Files.createDirectory(temporaryDirectory.resolve("from-scratch"));
        Path scratchRoot = Files.createDirectory(scratch.resolve("output"));
        support.compile(run.listEndpointCandidate(), scratchRoot);

        Map<String, String> applied = support.fingerprint(run.chain().outputRoot());
        Map<String, String> fromScratch = support.fingerprint(scratchRoot);
        assertEquals(fromScratch.keySet(), applied.keySet(),
                () -> "the incrementally applied project must hold exactly the files a from-scratch "
                        + "generation holds; missing=" + support.difference(fromScratch.keySet(), applied.keySet())
                        + " extra=" + support.difference(applied.keySet(), fromScratch.keySet()));
        assertEquals(fromScratch, applied, "file contents must match byte for byte");
    }

    // ------------------------------------------------------------------
    // R4 / N1 / N2
    // ------------------------------------------------------------------

    @Test
    void anAppliedAdditionCannotBeDeclaredTwiceAndUnchangedContentIsNoChanges() throws Exception {
        ChainRun run = applyFullChain();
        Map<String, String> afterFullChain = support.fingerprint(run.chain().outputRoot());

        // C5: idempotence has two distinct faces, and neither is "apply the same candidate twice".
        // (a) Re-declaring the addition now targets a declaration that already exists, so the plan is
        // refused at the target stage — applying it again must never be possible.
        ChangeBaselinePlanningResult readded = run.chain().plan(run.listEndpointCandidate(),
                run.chain().addCapability(run.listEndpointCandidate(), "ListCourseRefs"));
        assertEquals("SIR-CHANGE-TARGET-101", support.codesOf(readded),
                "re-declaring an applied addition must fail at the target stage");
        assertEquals(afterFullChain, support.fingerprint(run.chain().outputRoot()),
                "a refused plan must not touch the project");

        // (b) Re-planning unchanged content is the actual idempotence claim: NoChanges, no rewrite.
        ChangeBaselinePlanningResult replanned = run.chain().plan(run.listEndpointCandidate(),
                run.chain().modifyCapabilityWorkflow(run.chain().currentSir(), "SearchCourseEnrollments"));
        support.assertNoChanges(replanned, "re-planning unchanged content");
        assertEquals(afterFullChain, support.fingerprint(run.chain().outputRoot()),
                "re-planning unchanged content must not rewrite the project");
    }

    @Test
    void aCandidateTheTargetRefusesFailsPlanningAndLeavesTheProjectAlone() throws Exception {
        ChangeChainTestSupport.Chain chain = support.startChain();
        Map<String, String> before = support.fingerprint(chain.outputRoot());

        // Q11 C5: a nested relation projection is only read through a paged find. Returning the
        // nested view from the create capability keeps the source semantically valid and reaches
        // the target boundary, which is what the public SIR path can actually express: a non-paged
        // find returning a view is already refused by the semantic layer as a return-type mismatch,
        // so it never gets this far.
        // Swap the response view of the create capability only: locate the block, then the first
        // "output" line after it, so the get capability's own use of the same view is untouched.
        String base = support.readResource(BASE);
        int createCapability = base.indexOf("capability CreateCourse {");
        int output = base.indexOf("output CourseDetail;", createCapability);
        String nestedResponse = base.substring(0, output) + "output CourseEnrollmentItem;"
                + base.substring(output + "output CourseDetail;".length());
        assertTrue(nestedResponse.contains("output CourseEnrollmentItem;"),
                "the negative candidate must really change the create capability's response view");
        Path candidate = support.writeText("course-admin-enrollment-nested-response.sir", nestedResponse);
        ChangeSet changeSet = chain.modifyCapabilityWorkflow(chain.currentSir(), "CreateCourse");

        ChangeBaselinePlanningResult result = chain.plan(candidate, changeSet);

        String codes = support.codesOf(result);
        assertTrue(support.isFailed(result),
                "a capability the target refuses must fail planning; diagnostics=" + codes);
        assertEquals(before, support.fingerprint(chain.outputRoot()),
                "a rejected candidate must leave the project untouched");
    }

    @Test
    void aStaleBaselineIsRejectedAndLeavesTheProjectAlone() throws Exception {
        ChangeChainTestSupport.Chain chain = support.startChain();
        String supersededBaselineId = chain.baselineId();
        Path filterAny = support.writeSource(FILTER_ANY, "course-admin-enrollment-filter-any.sir");
        support.applyRound(chain, filterAny, chain.modifyCapabilityWorkflow(chain.currentSir(), "SearchCourseEnrollments"),
                "R1 widen the enrollment filter");
        Map<String, String> afterFirstApply = support.fingerprint(chain.outputRoot());

        Path tightened = support.writeSource(TIGHTEN_NAME, "course-admin-enrollment-tighten.sir");
        ChangeSet tightenConstraints = chain.modifyInputFieldConstraints(filterAny, "CreateCourseInput", "name");
        ChangeApplyResult stale = chain.application().apply(new ChangeApplyRequest(
                chain.stateRoot(), supersededBaselineId, tightened, chain.outputRoot(), tightenConstraints));

        assertInstanceOf(ChangeApplyResult.Failure.class, stale,
                "applying against a superseded baseline must fail, got: " + stale);
        assertEquals(afterFirstApply, support.fingerprint(chain.outputRoot()),
                "a stale apply must not touch the project");
    }
}
