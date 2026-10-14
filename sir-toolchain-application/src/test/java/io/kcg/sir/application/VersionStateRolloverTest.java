package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.ProjectBaselineApplication;
import io.kcg.sir.application.api.ProjectBaselineInspectionRequest;
import io.kcg.sir.application.api.ProjectBaselineRegistrationRequest;
import io.kcg.sir.application.api.ProjectBaselineResult;
import io.kcg.sir.application.api.ProjectChangeApplyRequest;
import io.kcg.sir.application.api.ProjectChangeApplyResult;
import io.kcg.sir.application.api.ProjectChangeContextRequest;
import io.kcg.sir.application.api.ProjectChangeContextResult;
import io.kcg.sir.application.api.ProjectChangeExecutionApplication;
import io.kcg.sir.application.api.ProjectChangePlanningApplication;
import io.kcg.sir.application.api.ProjectChangePlanningRequest;
import io.kcg.sir.application.api.ProjectChangePlanningResult;
import io.kcg.sir.application.api.ProjectToolchainRequest;
import io.kcg.sir.application.api.ProjectToolchainResult;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.VersionApplyApplication;
import io.kcg.sir.application.api.VersionApplyRequest;
import io.kcg.sir.application.api.VersionApplyResult;
import io.kcg.sir.application.api.VersionPlanningApplication;
import io.kcg.sir.application.api.VersionPlanningRequest;
import io.kcg.sir.application.api.VersionPlanningResult;
import io.kcg.sir.application.api.VersionRecoveryResult;
import io.kcg.sir.application.api.VersionStateRolloverApplication;
import io.kcg.sir.application.api.VersionStateRolloverRequest;
import io.kcg.sir.application.api.VersionStateRolloverResult;
import io.kcg.sir.application.api.VersionStateRolloverResult.NewStateTouched;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A state directory that has used up its history budget can be continued in a fresh one. What is checked: the project
 * keeps its version (same baseline ID) and keeps updating past the old limit, the new state is exactly one version, the
 * old state is changed by its retirement marker and nothing else, and every refusal leaves the disk untouched.
 */
class VersionStateRolloverTest {
    private static final VersionPlanningApplication PLANNER = new VersionPlanningApplication();
    private static final String CODE = "field code: String where notBlank, length(1, 32);";

    @TempDir Path temp;

    private Path output;
    private Path state;
    private SourceSnapshot v0;
    private String current;

    private void store(String name) throws Exception {
        Path root = this.temp.resolve(name);
        Files.createDirectories(root);
        Path source = MultiSourceTestSupport.fixture(root);
        this.output = root.resolve("output");
        this.state = root.resolve("state");
        var generated = MultiSourceTestSupport.generate(source, this.output);
        var saved = assertInstanceOf(ProjectBaselineResult.Success.class,
            new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(generated.sources(), this.output, this.state)));
        this.v0 = saved.sources();
        this.current = saved.receipt().baselineId();
    }

    /** The first version with its code length limit changed to {@code 32 + n}: every n is a distinct version. */
    private SourceSnapshot version(int n) {
        Map<SourceId, byte[]> inputs = new LinkedHashMap<>();
        for (var file : this.v0.manifest().files()) {
            String text = new String(this.v0.bytes(file.sourceId()), StandardCharsets.UTF_8);
            if (text.contains(CODE)) {
                text = text.replace(CODE, "field code: String where notBlank, length(1, " + (32 + n) + ");");
            }

            inputs.put(file.sourceId(), text.getBytes(StandardCharsets.UTF_8));
        }

        return new SourceSnapshot(this.v0.entry(), inputs);
    }

    private VersionPlanningRequest request(Path stateRoot, String baseline, SourceSnapshot candidate) {
        return new VersionPlanningRequest(stateRoot, this.output, baseline, candidate);
    }

    /** Applies {@code candidate} to {@code stateRoot}; returns the result without asserting success. */
    private VersionApplyResult apply(Path stateRoot, String baseline, SourceSnapshot candidate) {
        var request = request(stateRoot, baseline, candidate);
        return new VersionApplyApplication().apply(new VersionApplyRequest(request, PLANNER.plan(request)));
    }

    private void update(Path stateRoot, SourceSnapshot candidate) {
        var applied = assertInstanceOf(VersionApplyResult.Applied.class, this.apply(stateRoot, this.current, candidate));
        this.current = applied.candidate().baselineId();
    }

    private Map<String, String> fromScratch(SourceSnapshot candidate, String name) throws Exception {
        Path sources = this.temp.resolve(name + "-sources");
        for (var file : candidate.manifest().files()) {
            Path path = sources.resolve(file.sourceId().value());
            Files.createDirectories(path.getParent());
            Files.write(path, candidate.bytes(file.sourceId()));
        }

        Path scratch = this.temp.resolve(name + "-output");
        assertInstanceOf(ProjectToolchainResult.Success.class, new ToolchainApplication().executeProject(new ProjectToolchainRequest(sources, candidate.entry(), scratch)));
        return MultiSourceTestSupport.files(scratch);
    }

    private VersionStateRolloverResult rollover(Path oldRoot, Path newRoot) {
        return new VersionStateRolloverApplication().rollover(new VersionStateRolloverRequest(oldRoot, this.output, this.current, newRoot));
    }

    private static VersionStateRolloverResult.RolledOver rolledOver(VersionStateRolloverResult result) {
        return assertInstanceOf(VersionStateRolloverResult.RolledOver.class, result, String.valueOf(result));
    }

    private static VersionStateRolloverResult.Refused refused(VersionStateRolloverResult result, String code) {
        var refused = assertInstanceOf(VersionStateRolloverResult.Refused.class, result, String.valueOf(result));
        assertEquals(code, refused.code(), refused.message());
        return refused;
    }

    private Map<String, String> snapshot() throws Exception {
        return new TreeMap<>(MultiSourceTestSupport.tree(this.temp));
    }

    // ------------------------------------------------------------------ the main path

    @Test
    void aProjectThatUsedUpItsHistoryContinuesInAFreshStateAtTheSameVersionAndKeepsUpdating() throws Exception {
        this.store("exhausted");
        for (int n = 1; n <= 7; n++) {
            this.update(this.state, this.version(n));
        }

        var blocked = assertInstanceOf(VersionApplyResult.Failure.class, this.apply(this.state, this.current, this.version(8)));
        assertTrue(blocked.diagnostics().toString().contains("baseline history count exhausted"), blocked.diagnostics().toString());
        Map<String, String> oldBefore = new TreeMap<>(MultiSourceTestSupport.tree(this.state));
        Map<String, String> outputBefore = new TreeMap<>(MultiSourceTestSupport.tree(this.output));
        String versionBefore = this.current;

        // The new directory shares a string prefix with the old one on purpose: only real nesting is refused.
        Path fresh = this.temp.resolve("exhausted").resolve("state-next");
        var done = rolledOver(this.rollover(this.state, fresh));

        assertEquals(versionBefore, done.receipt().baselineId(), "the project stays at the same version");
        assertEquals(8, done.archive().versions());
        assertEquals(7, done.archive().relations());
        assertEquals(7, done.archive().receipts());
        assertFalse(done.resumed());
        assertEquals(fresh, done.newStateRoot());
        Map<String, String> oldAfter = new TreeMap<>(MultiSourceTestSupport.tree(this.state));
        assertEquals("RETIRED", onlyAdded(oldBefore, oldAfter), "the retirement marker is the only change to the old state");
        assertEquals(outputBefore, new TreeMap<>(MultiSourceTestSupport.tree(this.output)), "the generated project is not touched");
        try (var list = Files.list(fresh)) {
            assertEquals(List.of("CURRENT", "LOCK", "baselines"), list.map(p -> p.getFileName().toString()).sorted().toList());
        }

        // Retired for real: while the output still equals the old version (so nothing but the marker can be the reason),
        // the toolchain refuses every entry on the old state.
        assertRetired(this.state, versionBefore);

        // The new state opens, carries the same sources, and goes on past the old limit.
        var inspected = assertInstanceOf(ProjectBaselineResult.Success.class,
            new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(fresh, this.output, versionBefore)));
        assertEquals(this.version(7).manifest(), inspected.sources().manifest());
        for (int n = 8; n <= 12; n++) {
            this.update(fresh, this.version(n));
            assertEquals(this.fromScratch(this.version(n), "after-" + n), MultiSourceTestSupport.files(this.output), "version " + n + " equals a fresh generation");
        }

        // The old state is retired: the toolchain refuses it, even for a plan that would otherwise be valid.
        var viaOld = PLANNER.plan(this.request(this.state, versionBefore, this.version(20)));
        assertInstanceOf(VersionPlanningResult.Failure.class, viaOld);
        var inspectOld = new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(this.state, this.output, versionBefore));
        assertInstanceOf(ProjectBaselineResult.Failure.class, inspectOld);

        // And a second rollover of the retired state names where it went instead of doing anything.
        var again = refused(new VersionStateRolloverApplication().rollover(new VersionStateRolloverRequest(this.state, this.output, versionBefore, this.temp.resolve("third"))), "OLD_STATE_RETIRED");
        assertTrue(again.message().contains("state-next"), again.message());
        assertFalse(Files.exists(this.temp.resolve("third")));
    }


    /** Planning, inspecting and applying through a retired state all fail because of the unknown marker object. */
    private void assertRetired(Path retired, String baseline) {
        var planning = this.request(retired, baseline, this.version(40));
        var plan = PLANNER.plan(planning);
        var failure = assertInstanceOf(VersionPlanningResult.Failure.class, plan, String.valueOf(plan));
        assertTrue(failure.diagnostics().toString().contains("unknown objects"), failure.diagnostics().toString());
        var inspect = assertInstanceOf(ProjectBaselineResult.Failure.class,
            new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(retired, this.output, baseline)));
        assertTrue(inspect.diagnostics().toString().contains("unknown objects"), inspect.diagnostics().toString());
    }

    private static String onlyAdded(Map<String, String> before, Map<String, String> after) {
        Map<String, String> added = new TreeMap<>(after);
        before.forEach((key, value) -> assertEquals(value, after.get(key), "changed in the old state: " + key));
        added.keySet().removeAll(before.keySet());
        assertEquals(1, added.size(), added.toString());
        return added.keySet().iterator().next();
    }

    @Test
    void aStateWithoutAnyHistoryCanBeRolledOverIntoAnEmptyExistingDirectory() throws Exception {
        this.store("plain");
        Path fresh = Files.createDirectories(this.temp.resolve("plain").resolve("new"));

        var done = rolledOver(this.rollover(this.state, fresh));

        assertEquals(this.current, done.receipt().baselineId());
        assertEquals(1, done.archive().versions());
        assertEquals(0, done.archive().relations());
        this.update(fresh, this.version(1));
    }

    @Test
    void theNewStateIsAlsoTheRightStartAfterTheOlderKindOfUpdateWasUsed() throws Exception {
        this.store("older-kind");
        // One update through the plan-and-apply entries of the earlier family: widen a workflow filter of one query.
        SourceSnapshot widened = this.widenedFilter();
        var context = assertInstanceOf(ProjectChangeContextResult.Success.class, new ProjectChangePlanningApplication().context(
            new ProjectChangeContextRequest(this.state, this.output, this.current, widened))).context();
        var target = context.targets().stream().filter(t -> t.displayName().equals("SearchCourseEnrollments")).findFirst().orElseThrow();
        var planning = new ProjectChangePlanningRequest(context, widened, context.targetKey(target));
        var planned = assertInstanceOf(ProjectChangePlanningResult.Planned.class, new ProjectChangePlanningApplication().plan(planning));
        var older = assertInstanceOf(ProjectChangeApplyResult.Applied.class, new ProjectChangeExecutionApplication().apply(new ProjectChangeApplyRequest(planning, planned)));
        this.current = older.candidate().baselineId();
        // Then one of the newer family on top of it.
        this.update(this.state, this.withConstraint(widened, 40));

        Path next = this.temp.resolve("older-kind").resolve("next");
        var done = rolledOver(this.rollover(this.state, next));

        assertEquals(this.current, done.receipt().baselineId());
        assertEquals(3, done.archive().versions());
        assertEquals(2, done.archive().relations());
        this.update(next, this.withConstraint(widened, 41));
        assertEquals(this.fromScratch(this.withConstraint(widened, 41), "older-kind"), MultiSourceTestSupport.files(this.output));
    }

    private SourceSnapshot widenedFilter() {
        return this.replaceInEntry(this.v0, "status == EnrollmentStatus.ACTIVE", "(status == EnrollmentStatus.ACTIVE or status == EnrollmentStatus.CANCELLED)");
    }

    private SourceSnapshot withConstraint(SourceSnapshot base, int length) {
        return this.replaceInEntry(base, CODE, "field code: String where notBlank, length(1, " + length + ");");
    }

    private SourceSnapshot replaceInEntry(SourceSnapshot base, String from, String to) {
        Map<SourceId, byte[]> inputs = new LinkedHashMap<>();
        boolean replaced = false;
        for (var file : base.manifest().files()) {
            String text = new String(base.bytes(file.sourceId()), StandardCharsets.UTF_8);
            if (!replaced && text.contains(from)) {
                text = text.replace(from, to);
                replaced = true;
            }

            inputs.put(file.sourceId(), text.getBytes(StandardCharsets.UTF_8));
        }

        assertTrue(replaced, "fixture changed: " + from);
        return new SourceSnapshot(base.entry(), inputs);
    }

    // ------------------------------------------------------------------ refusals leave the disk alone

    @Test
    void relatedPathsAreRefusedBeforeAnythingIsCreated() throws Exception {
        this.store("paths");
        Path root = this.temp.resolve("paths");
        Map<String, String> before = this.snapshot();
        List<Path> badNewRoots = List.of(this.state, this.state.resolve("next"), root, this.output, this.output.resolve("state"), root.resolve("output/../state"));

        for (Path newRoot : badNewRoots) {
            if (!newRoot.equals(newRoot.normalize())) {
                assertThrows(IllegalArgumentException.class, () -> new VersionStateRolloverRequest(this.state, this.output, this.current, newRoot));
                continue;
            }

            refused(this.rollover(this.state, newRoot), "REQUEST_PATHS");
            assertEquals(before, this.snapshot(), "refusing " + newRoot + " changed the disk");
        }

        assertThrows(IllegalArgumentException.class, () -> new VersionStateRolloverRequest(Path.of("relative"), this.output, this.current, root.resolve("n")));
        assertThrows(IllegalArgumentException.class, () -> new VersionStateRolloverRequest(this.state, this.output, "not-hex", root.resolve("n")));
    }

    @Test
    void aLinkToTheOldStateIsTheSameDirectoryAndIsRefused() throws Exception {
        this.store("link");
        Path alias = this.temp.resolve("link").resolve("alias");
        Files.createSymbolicLink(alias, this.state);
        Map<String, String> before = this.snapshot();

        assertInstanceOf(VersionStateRolloverResult.Refused.class, this.rollover(this.state, alias));
        assertEquals(before, this.snapshot());

        // A new root reached through a symlink that points at the old state's own parent is inside the old state's tree.
        Path parentAlias = this.temp.resolve("parent-alias");
        Files.createSymbolicLink(parentAlias, this.state.getParent());
        Map<String, String> beforeParent = this.snapshot();
        refused(this.rollover(this.state, parentAlias.resolve("state")), "REQUEST_PATHS");
        refused(this.rollover(this.state, parentAlias.resolve("state").resolve("inside")), "REQUEST_PATHS");
        assertEquals(beforeParent, this.snapshot());

        // The old state inside the output, and the output inside the old state, are refused too.
        Path nested = this.output.resolve("nested-state");
        assertThrows(IllegalArgumentException.class, () -> new VersionStateRolloverRequest(nested, this.output.resolve("x").resolve(".."), this.current, this.temp.resolve("n")));
        refused(new VersionStateRolloverApplication().rollover(new VersionStateRolloverRequest(this.state, this.state.resolve("out"), this.current, this.temp.resolve("n2"))), "REQUEST_PATHS");
    }

    @Test
    void aNewRootThatIsNotEmptyIsNeverUsed() throws Exception {
        this.store("occupied");
        Path root = this.temp.resolve("occupied");
        Path withFile = Files.createDirectories(root.resolve("foreign"));
        Files.writeString(withFile.resolve("keep.txt"), "mine");
        Path regular = root.resolve("a-file");
        Files.writeString(regular, "not a directory");
        Path dangling = root.resolve("dangling");
        Files.createSymbolicLink(dangling, root.resolve("nowhere"));
        Map<String, String> before = this.snapshot();

        for (Path newRoot : List.of(withFile, regular, dangling)) {
            var refused = assertInstanceOf(VersionStateRolloverResult.Refused.class, this.rollover(this.state, newRoot));
            assertEquals(NewStateTouched.NO, refused.newState(), newRoot + ": " + refused.message());
            assertEquals(before, this.snapshot(), newRoot + " was modified");
        }
    }

    @Test
    void aCopyOfTheOldStateOrAStateWithItsOwnHistoryIsNotAFreshState() throws Exception {
        this.store("copy");
        this.update(this.state, this.version(1));
        Path root = this.temp.resolve("copy");

        // A first rollover, then an update in the new state: the new state now has history of its own.
        Path next = root.resolve("next");
        rolledOver(this.rollover(this.state, next));
        this.update(next, this.version(2));

        // A different project directory pretending to be the target is refused for the same reason.
        Path other = Files.createDirectories(root.resolve("other-state"));
        Files.writeString(other.resolve("LOCK"), "");
        Map<String, String> before = this.snapshot();
        var refused = assertInstanceOf(VersionStateRolloverResult.Refused.class, this.rollover(next, other));
        assertEquals("NEW_ROOT_NOT_EMPTY", refused.code());
        assertEquals(before, this.snapshot());
        // Rolling the grown new state over again into its own predecessor is refused as well.
        assertInstanceOf(VersionStateRolloverResult.Refused.class, this.rollover(next, this.state));
    }

    @Test
    void anOldStateThatIsNotExactlyTheExpectedVersionOrWasChangedIsRefusedAndNothingIsCreated() throws Exception {
        this.store("old-refused");
        this.update(this.state, this.version(1));
        Path next = this.temp.resolve("old-refused").resolve("next");
        Map<String, String> before = this.snapshot();

        var wrong = new VersionStateRolloverApplication().rollover(new VersionStateRolloverRequest(this.state, this.output, "a".repeat(64), next));
        refused(wrong, "OLD_STATE_REFUSED");
        assertEquals(before, this.snapshot());

        Path tracked = this.output.resolve(MultiSourceTestSupport.files(this.output).keySet().iterator().next());
        Files.writeString(tracked, Files.readString(tracked) + "\n// edited by hand\n");
        Map<String, String> edited = this.snapshot();
        var modified = refused(this.rollover(this.state, next), "OLD_STATE_REFUSED");
        assertEquals(NewStateTouched.NO, modified.newState());
        assertEquals(edited, this.snapshot(), "the hand-edited project and the state are left as they were");
        assertFalse(Files.exists(next));

        Path missing = this.temp.resolve("old-refused").resolve("no-such-state");
        assertInstanceOf(VersionStateRolloverResult.Refused.class, this.rollover(missing, next));
        assertFalse(Files.exists(missing));
        assertFalse(Files.exists(next));
    }

    @Test
    void anUpdateThatStoppedHalfWayMustBeRecoveredBeforeTheStateCanBeRolledOver() throws Exception {
        this.store("active");
        SourceSnapshot candidate = this.version(1);
        var request = this.request(this.state, this.current, candidate);
        var stopped = assertInstanceOf(VersionApplyResult.RecoveryRequired.class, hooked(VersionApplyApplication.class, point -> {
            if (point.equals("current-published")) {
                throw new IllegalStateException("process stopped");
            }
        }).apply(new VersionApplyRequest(request, PLANNER.plan(request))));
        Path next = this.temp.resolve("active").resolve("next");
        Map<String, String> before = this.snapshot();

        var refusedNow = new VersionStateRolloverApplication().rollover(new VersionStateRolloverRequest(this.state, this.output, stopped.handle().baselineId(), next));
        assertInstanceOf(VersionStateRolloverResult.Refused.class, refusedNow);
        assertEquals(before, this.snapshot());

        assertInstanceOf(VersionRecoveryResult.Recovered.class, new VersionApplyApplication().recover(stopped.handle()));
        this.current = stopped.handle().candidateId();
        rolledOver(this.rollover(this.state, next));
    }

    @Test
    void aLockHeldElsewhereIsReportedAndNothingIsWritten() throws Exception {
        this.store("locked");
        Path next = this.temp.resolve("locked").resolve("next");
        Map<String, String> before;
        try (var held = new io.kcg.sir.application.internal.state.StateRootLock(this.state).tryAcquireExisting() instanceof
                io.kcg.sir.application.internal.state.StateRootLock.HeldLock lock ? lock : null) {
            assertNotNull(held);
            before = this.snapshot();
            refused(this.rollover(this.state, next), "OLD_STATE_LOCKED");
            assertEquals(before, this.snapshot());
            assertTrue(held.isHeld(), "a refused rollover does not release the other holder's lock");
        }

        rolledOver(this.rollover(this.state, next));
    }

    // ------------------------------------------------------------------ stopping in the middle

    private VersionStateRolloverResult runStoppedAt(String point, Path newRoot) throws Exception {
        var app = hooked(VersionStateRolloverApplication.class, step -> {
            if (step.equals(point)) {
                throw new IllegalStateException("process stopped at " + step);
            }
        });
        return app.rollover(new VersionStateRolloverRequest(this.state, this.output, this.current, newRoot));
    }


    @Test
    void aNewStateThatGrewItsOwnHistoryIsNeverTakenForTheFreshOne() throws Exception {
        this.store("grown");
        Path grown = this.temp.resolve("grown").resolve("next");
        // First job: stop after the new state is verified but before the old one is retired. The old state stays live.
        assertInstanceOf(VersionStateRolloverResult.Refused.class, this.runStoppedAt("new-verified", grown));
        String v0Id = this.current;
        // Then the new directory is used and grows: v0 -> v1 -> v0, so its CURRENT equals the old one's again.
        this.update(grown, this.version(1));
        this.update(grown, this.v0);
        assertEquals(v0Id, this.current, "returning to the first version reuses its ID");
        Map<String, String> before = this.snapshot();

        var refused = assertInstanceOf(VersionStateRolloverResult.Refused.class, this.rollover(this.state, grown));

        assertEquals("NEW_ROOT_NOT_EMPTY", refused.code());
        assertEquals(NewStateTouched.NO, refused.newState());
        assertEquals(before, this.snapshot());
        assertFalse(Files.exists(this.state.resolve("RETIRED")), "the old state was not retired against a state with history of its own");
    }

    @Test
    void theOldStateIsRevalidatedAfterTheNewOneIsBuiltAndItsLockIsHeldUntilTheEnd() throws Exception {
        this.store("changed-meanwhile");
        Path next = this.temp.resolve("changed-meanwhile").resolve("next");
        Path tracked = this.output.resolve(MultiSourceTestSupport.files(this.output).keySet().iterator().next());
        boolean[] lockWasHeld = new boolean[1];
        var app = hooked(VersionStateRolloverApplication.class, step -> {
            if (step.equals("new-verified")) {
                lockWasHeld[0] = new io.kcg.sir.application.internal.state.StateRootLock(this.state).tryAcquireExisting()
                    instanceof io.kcg.sir.application.internal.state.StateRootLock.LockFailure;
                try {
                    Files.writeString(tracked, Files.readString(tracked) + "\n// edited while the rollover ran\n");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        var result = app.rollover(new VersionStateRolloverRequest(this.state, this.output, this.current, next));

        assertTrue(lockWasHeld[0], "the old state stays locked for the whole rollover");
        var refused = assertInstanceOf(VersionStateRolloverResult.Refused.class, result);
        assertEquals("OLD_STATE_REFUSED", refused.code(), refused.message());
        assertFalse(Files.exists(this.state.resolve("RETIRED")), "a project that changed under the rollover is not retired");
        assertTrue(Files.exists(next), "the new state was already built and is reported as left behind");
        assertEquals(NewStateTouched.PARTIAL_STATE, refused.newState());
    }

    @Test
    void aFailureAfterTheRetirementDoesNotTurnADoneRolloverIntoARefusal() throws Exception {
        this.store("after-retired");
        Path next = this.temp.resolve("after-retired").resolve("next");

        var result = this.runStoppedAt("retired", next);

        var done = rolledOver(result);
        assertEquals(next, done.newStateRoot());
        assertTrue(Files.exists(this.state.resolve("RETIRED")));
        this.update(next, this.version(1));
    }

    @Test
    void aRegistrationThatWritesNothingIsNotReportedAsAPartialState() throws Exception {
        this.store("nothing-written");
        // The output disagrees with the stored version only inside the new registration's own check: make the new root's
        // parent unusable instead, which the path guard refuses before anything is created.
        Path blocker = this.temp.resolve("nothing-written").resolve("a-file");
        Files.writeString(blocker, "x");
        Path next = blocker.resolve("next");
        Map<String, String> before = this.snapshot();

        var refused = assertInstanceOf(VersionStateRolloverResult.Refused.class, this.rollover(this.state, next));

        assertEquals(NewStateTouched.NO, refused.newState(), refused.message());
        assertEquals(before, this.snapshot());
    }

    @Test
    void aStopAtAnyStepLeavesTheOldStateLiveUntilTheLastStepAndTheSameRequestFinishesTheJob() throws Exception {
        for (String point : List.of("old-verified", "new-created", "new-verified", "old-revalidated")) {
            this.store("stop-" + point);
            this.update(this.state, this.version(1));
            Path next = this.temp.resolve("stop-" + point).resolve("next");
            Map<String, String> oldBefore = new TreeMap<>(MultiSourceTestSupport.tree(this.state));

            var stopped = assertInstanceOf(VersionStateRolloverResult.Refused.class, this.runStoppedAt(point, next), point);
            assertEquals("ROLLOVER_INTERRUPTED", stopped.code(), point);
            assertEquals(oldBefore, new TreeMap<>(MultiSourceTestSupport.tree(this.state)), point + ": the old state is unchanged before the last step");
            // The old state is still live: it plans exactly as before.
            assertInstanceOf(VersionPlanningResult.Planned.class, PLANNER.plan(this.request(this.state, this.current, this.version(2))), point);
            if (point.equals("old-verified")) {
                assertFalse(Files.exists(next), "nothing was created yet");
            }

            if (!point.equals("old-verified")) {
                assertTrue(Files.exists(next), point);
            }

            Path scratchNext = next;
            if (!point.equals("old-verified") && !point.equals("new-created")) {
                // The new state is complete; running the same request again only retires the old one.
                var finished = rolledOver(this.rollover(this.state, scratchNext));
                assertTrue(finished.resumed(), point);
            } else if (point.equals("new-created")) {
                var finished = rolledOver(this.rollover(this.state, scratchNext));
                assertTrue(finished.resumed(), "a complete new state is recognised even if the first call did not verify it");
            } else {
                var finished = rolledOver(this.rollover(this.state, scratchNext));
                assertFalse(finished.resumed());
            }

            this.update(next, this.version(2));
            assertTrue(Files.exists(this.state.resolve("RETIRED")), point);
        }
    }

    @Test
    void aTornNewStateIsNotMistakenForAFinishedOneAndTheOldStateStaysLive() throws Exception {
        this.store("torn");
        Path next = this.temp.resolve("torn").resolve("next");
        assertInstanceOf(VersionStateRolloverResult.Refused.class, this.runStoppedAt("new-created", next));
        // What a crash during registration could leave: the Bundle without the pointer.
        Files.delete(next.resolve("CURRENT"));
        Map<String, String> before = this.snapshot();

        var refused = assertInstanceOf(VersionStateRolloverResult.Refused.class, this.rollover(this.state, next));

        assertEquals("NEW_ROOT_NOT_EMPTY", refused.code());
        assertEquals(NewStateTouched.NO, refused.newState());
        assertEquals(before, this.snapshot(), "a state that is not exactly the fresh one is left alone for the owner to discard");
        assertFalse(Files.exists(this.state.resolve("RETIRED")), "and the old state is still live");
        this.update(this.state, this.version(1));
    }

    // ------------------------------------------------------------------ plans do not cross states

    @Test
    void aPlanMadeForTheOldStateIsRefusedByTheNewState() throws Exception {
        this.store("cross");
        SourceSnapshot candidate = this.version(1);
        var oldRequest = this.request(this.state, this.current, candidate);
        var oldPlan = PLANNER.plan(oldRequest);
        Path next = this.temp.resolve("cross").resolve("next");
        rolledOver(this.rollover(this.state, next));

        var viaNew = new VersionApplyApplication().apply(new VersionApplyRequest(this.request(next, this.current, candidate), oldPlan));

        assertInstanceOf(VersionApplyResult.Failure.class, viaNew, "a plan carries the state it was made for");
        assertEquals(this.fromScratch(this.v0, "cross-v0"), MultiSourceTestSupport.files(this.output), "nothing was applied");
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static <T> T hooked(Class<T> type, Consumer<String> hook) throws Exception {
        var constructor = type.getDeclaredConstructor(Consumer.class);
        constructor.setAccessible(true);
        return constructor.newInstance(hook);
    }

}
