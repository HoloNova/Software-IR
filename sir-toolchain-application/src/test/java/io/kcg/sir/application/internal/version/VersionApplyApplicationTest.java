package io.kcg.sir.application.internal.version;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.MultiSourceTestSupport;
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
import io.kcg.sir.source.SourceSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The public entries: plan, apply, recover - and what survives across several of them. */
class VersionApplyApplicationTest {
    private static final VersionPlanningApplication PLANNER = new VersionPlanningApplication();

    @TempDir Path temp;

    /** A stored project that successive versions are applied to. */
    private final class Flow {
        final Path root;
        final Path output;
        final Path state;
        SourceSnapshot sources;
        String current;

        Flow(String name) throws Exception {
            this.root = VersionApplyApplicationTest.this.temp.resolve(name);
            Files.createDirectories(this.root);
            Path source = MultiSourceTestSupport.fixture(this.root);
            this.output = this.root.resolve("output");
            this.state = this.root.resolve("state");
            var generated = MultiSourceTestSupport.generate(source, this.output);
            var saved = assertInstanceOf(ProjectBaselineResult.Success.class,
                new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(generated.sources(), this.output, this.state)));
            this.sources = saved.sources();
            this.current = saved.receipt().baselineId();
            Files.move(source, this.root.resolve("unavailable"));
        }

        VersionPlanningRequest request(SourceSnapshot candidate) {
            return new VersionPlanningRequest(this.state, this.output, this.current, candidate);
        }

        /** Plans and applies through the public entries; the version becomes current. */
        void update(SourceSnapshot candidate, String label) throws Exception {
            var planned = PLANNER.plan(this.request(candidate));
            var result = new VersionApplyApplication().apply(new VersionApplyRequest(this.request(candidate), planned));
            var applied = assertInstanceOf(VersionApplyResult.Applied.class, result, label + ": " + result.diagnostics());
            assertEquals(this.current, applied.base().baselineId());
            this.current = applied.candidate().baselineId();
            this.sources = candidate;
            this.assertIs(candidate, label);
        }

        /** The generated project equals a from-scratch generation of {@code snapshot}, and the stored state agrees. */
        void assertIs(SourceSnapshot snapshot, String label) throws Exception {
            Path sources = this.root.resolve("fresh-" + label + "-sources");
            for (var file : snapshot.manifest().files()) {
                Path path = sources.resolve(file.sourceId().value());
                Files.createDirectories(path.getParent());
                Files.write(path, snapshot.bytes(file.sourceId()));
            }

            Path fresh = this.root.resolve("fresh-" + label + "-output");
            assertInstanceOf(ProjectToolchainResult.Success.class, new ToolchainApplication().executeProject(new ProjectToolchainRequest(sources, snapshot.entry(), fresh)));
            assertEquals(MultiSourceTestSupport.tree(fresh), MultiSourceTestSupport.tree(this.output), label + ": generated project");
            var inspected = assertInstanceOf(ProjectBaselineResult.Success.class,
                new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(this.state, this.output, this.current)), label);
            assertEquals(snapshot.manifest(), inspected.sources().manifest(), label + ": stored sources");
            assertEquals(this.current, Files.readString(this.state.resolve("CURRENT")).trim());
        }
    }

    private static SourceSnapshot edit(SourceSnapshot base, String[]... edits) {
        return VersionScenarios.edit(base, edits);
    }

    private static VersionApplyApplication hooked(Consumer<String> hook) throws Exception {
        var constructor = VersionApplyApplication.class.getDeclaredConstructor(Consumer.class);
        constructor.setAccessible(true);
        return constructor.newInstance(hook);
    }

    @Test
    void severalVersionsInARowEachEqualAFreshGenerationAndTheStoredStateStaysValid() throws Exception {
        Flow flow = new Flow("chain");
        SourceSnapshot v0 = flow.sources;
        SourceSnapshot v1 = edit(v0, new String[] {"project.sir", "field code: String where notBlank, length(1, 32);", "field code: String where notBlank, length(1, 40);"});
        SourceSnapshot v2 = edit(v1, new String[] {"modules/student.sir", "sir 0.2", "sir 0.2\n// reviewed"});
        SourceSnapshot v3 = edit(v2, new String[] {"project.sir", "namespace \"com.example.courseadmin\"", "namespace \"com.example.coursemanager\""});

        flow.update(v1, "v1 update");
        flow.update(v2, "v2 source only");
        flow.update(v3, "v3 new package");
        flow.update(v0, "back to v0");

        long baselines;
        try (var list = Files.list(flow.state.resolve("baselines"))) {
            baselines = list.count();
        }

        assertEquals(4, baselines, "four distinct versions were ever stored; going back reuses the first");
    }

    @Test
    void theOlderAndTheNewerKindOfUpdateCanFollowEachOtherInOneStateDirectory() throws Exception {
        Flow flow = new Flow("mixed-families");
        SourceSnapshot v1 = edit(flow.sources, new String[] {"project.sir", "field code: String where notBlank, length(1, 32);", "field code: String where notBlank, length(1, 40);"});
        flow.update(v1, "version update");

        // an update of the older kind: one workflow changes, the set of source files stays
        SourceSnapshot v2 = edit(v1, new String[] {"project.sir", "order by code ascending", "order by code descending"});
        var planner = new ProjectChangePlanningApplication();
        var context = ((ProjectChangeContextResult.Success)planner.context(new ProjectChangeContextRequest(flow.state, flow.output, flow.current, v2))).context();
        var target = context.targets().stream().filter(t -> t.displayName().equals("SearchCourseEnrollments")).findFirst().orElseThrow();
        var planning = new ProjectChangePlanningRequest(context, v2, context.targetKey(target));
        var planned = assertInstanceOf(ProjectChangePlanningResult.Planned.class, planner.plan(planning));
        var old = assertInstanceOf(ProjectChangeApplyResult.Applied.class, new ProjectChangeExecutionApplication().apply(new ProjectChangeApplyRequest(planning, planned)));
        flow.current = old.candidate().baselineId();
        flow.sources = v2;
        flow.assertIs(v2, "after the older update");

        // and a version update on top of it
        SourceSnapshot v3 = edit(v2, new String[] {"modules/student.sir", "sir 0.2", "sir 0.2\n// after both"});
        flow.update(v3, "version update after the older one");

        // and the older kind again on top of the newer one
        SourceSnapshot v4 = edit(v3, new String[] {"project.sir", "order by code descending", "order by code ascending"});
        context = ((ProjectChangeContextResult.Success)planner.context(new ProjectChangeContextRequest(flow.state, flow.output, flow.current, v4))).context();
        target = context.targets().stream().filter(t -> t.displayName().equals("SearchCourseEnrollments")).findFirst().orElseThrow();
        planning = new ProjectChangePlanningRequest(context, v4, context.targetKey(target));
        planned = assertInstanceOf(ProjectChangePlanningResult.Planned.class, planner.plan(planning));
        old = assertInstanceOf(ProjectChangeApplyResult.Applied.class, new ProjectChangeExecutionApplication().apply(new ProjectChangeApplyRequest(planning, planned)));
        flow.current = old.candidate().baselineId();
        flow.assertIs(v4, "after the older update again");
    }

    @Test
    void aPlanForAnOlderVersionIsRefusedAfterTheProjectMovedOn() throws Exception {
        Flow flow = new Flow("stale");
        SourceSnapshot candidate = edit(flow.sources, new String[] {"project.sir", "field code: String where notBlank, length(1, 32);", "field code: String where notBlank, length(1, 40);"});
        var request = flow.request(candidate);
        var planned = PLANNER.plan(request);
        flow.update(edit(flow.sources, new String[] {"modules/student.sir", "sir 0.2", "sir 0.2\n// someone else was first"}), "someone else");
        Map<String, String> state = MultiSourceTestSupport.tree(flow.state);
        Map<String, String> output = MultiSourceTestSupport.tree(flow.output);

        var result = new VersionApplyApplication().apply(new VersionApplyRequest(request, planned));

        assertInstanceOf(VersionApplyResult.Failure.class, result);
        assertEquals(state, MultiSourceTestSupport.tree(flow.state));
        assertEquals(output, MultiSourceTestSupport.tree(flow.output));
    }

    @Test
    void anExpectedPlanThatDiffersFromTheRebuiltOneIsRefusedAndNothingChanges() throws Exception {
        Flow flow = new Flow("verify");
        SourceSnapshot candidate = edit(flow.sources, new String[] {"project.sir", "field code: String where notBlank, length(1, 32);", "field code: String where notBlank, length(1, 40);"});
        SourceSnapshot other = edit(flow.sources, new String[] {"modules/student.sir", "sir 0.2", "sir 0.2\n// other"});
        var otherPlan = PLANNER.plan(flow.request(other));
        Map<String, String> state = MultiSourceTestSupport.tree(flow.state);
        Map<String, String> output = MultiSourceTestSupport.tree(flow.output);

        var result = new VersionApplyApplication().apply(new VersionApplyRequest(flow.request(candidate), otherPlan));

        var failure = assertInstanceOf(VersionApplyResult.Failure.class, result);
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().equals("SIR-APP-VERSION-VERIFY-001")), failure.diagnostics().toString());
        assertEquals(state, MultiSourceTestSupport.tree(flow.state));
        assertEquals(output, MultiSourceTestSupport.tree(flow.output));
    }

    @Test
    void theStoredVersionItselfIsNoChange() throws Exception {
        Flow flow = new Flow("same");
        var planned = PLANNER.plan(flow.request(flow.sources));
        assertInstanceOf(VersionPlanningResult.NoChanges.class, planned);
        Map<String, String> state = MultiSourceTestSupport.tree(flow.state);

        var result = new VersionApplyApplication().apply(new VersionApplyRequest(flow.request(flow.sources), planned));

        assertInstanceOf(VersionApplyResult.NoChanges.class, result);
        assertEquals(state, MultiSourceTestSupport.tree(flow.state));
    }

    @Test
    void aStoppedChangeIsReportedWithAHandleAndOnlyAnExplicitRecoveryResolvesItEitherWay() throws Exception {
        for (boolean published : new boolean[] {false, true}) {
            Flow flow = new Flow(published ? "stopped-after" : "stopped-before");
            SourceSnapshot candidate = edit(flow.sources, new String[] {"project.sir", "field code: String where notBlank, length(1, 32);", "field code: String where notBlank, length(1, 40);"});
            SourceSnapshot before = flow.sources;
            String baseId = flow.current;
            var planned = PLANNER.plan(flow.request(candidate));
            String stop = published ? "current-published" : "output-applied:0";

            var stopped = hooked(point -> {
                if (point.equals(stop)) {
                    throw new IllegalStateException("process stopped");
                }
            }).apply(new VersionApplyRequest(flow.request(candidate), planned));

            var required = assertInstanceOf(VersionApplyResult.RecoveryRequired.class, stopped);
            assertEquals(published ? VersionApplyResult.Publication.PUBLISHED : VersionApplyResult.Publication.NOT_PUBLISHED, required.publication());
            assertTrue(required.handle().bindingDigest().isPresent());

            var recovered = assertInstanceOf(VersionRecoveryResult.Recovered.class, new VersionApplyApplication().recover(required.handle()));
            assertEquals(published ? VersionRecoveryResult.Outcome.COMMITTED_AND_CLEANED : VersionRecoveryResult.Outcome.ROLLED_BACK, recovered.outcome());
            if (published) {
                flow.current = required.handle().candidateId();
                flow.assertIs(candidate, "recovered forward");
            } else {
                flow.current = baseId;
                flow.assertIs(before, "recovered back");
            }

            var again = assertInstanceOf(VersionRecoveryResult.Recovered.class, new VersionApplyApplication().recover(required.handle()));
            assertEquals(VersionRecoveryResult.Outcome.ALREADY_CLEAN, again.outcome());
        }
    }

    @Test
    void aFinishedHandleIsRefusedOnceTheProjectHasMovedOn() throws Exception {
        Flow flow = new Flow("moved-on");
        SourceSnapshot candidate = edit(flow.sources, new String[] {"project.sir", "field code: String where notBlank, length(1, 32);", "field code: String where notBlank, length(1, 40);"});
        var planned = PLANNER.plan(flow.request(candidate));
        var stopped = assertInstanceOf(VersionApplyResult.RecoveryRequired.class, hooked(point -> {
            if (point.equals("current-published")) {
                throw new IllegalStateException("process stopped");
            }
        }).apply(new VersionApplyRequest(flow.request(candidate), planned)));
        assertInstanceOf(VersionRecoveryResult.Recovered.class, new VersionApplyApplication().recover(stopped.handle()));
        flow.current = stopped.handle().candidateId();
        flow.sources = candidate;
        flow.update(edit(candidate, new String[] {"modules/student.sir", "sir 0.2", "sir 0.2\n// later"}), "a later update");

        var late = new VersionApplyApplication().recover(stopped.handle());

        assertInstanceOf(VersionRecoveryResult.Failure.class, late);
    }

    @Test
    void anUnreadableRequestIsRefusedBeforeAnyWork() throws Exception {
        Flow flow = new Flow("bad-root");
        var request = new VersionPlanningRequest(Path.of("relative-state"), flow.output, flow.current, flow.sources);
        assertThrows(IllegalArgumentException.class, () -> new VersionApplyRequest(request, PLANNER.plan(request), "short"));
        var failure = assertInstanceOf(VersionPlanningResult.Failure.class, PLANNER.plan(request));
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().equals("SIR-APP-VERSION-REQUEST-001")));
        assertNotNull(new ArrayList<>(failure.diagnostics()));
    }
}
