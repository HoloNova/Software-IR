package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.projectbaseline.*;
import io.kcg.sir.application.internal.bundle.*;
import io.kcg.sir.source.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBaselineContractTest {
    @TempDir Path temp;
    private record Scenario(Path source,Path output,Path state,ProjectToolchainResult.Success generated) {
        ProjectBaselineRegistrationRequest request() { return new ProjectBaselineRegistrationRequest(generated.sources(),output,state); }
    }
    private Scenario scenario(Path root) throws Exception {
        Files.createDirectories(root);var source=fixture(root);var output=root.resolve("output");return new Scenario(source,output,root.resolve("state"),generate(source,output));
    }
    private static ProjectBaselineResult.Success ok(ProjectBaselineResult r) { return assertInstanceOf(ProjectBaselineResult.Success.class,r,r.diagnostics().toString()); }
    private static ProjectBaselineResult.Failure rejected(ProjectBaselineResult r) { return assertInstanceOf(ProjectBaselineResult.Failure.class,r); }
    private static ProjectBaselineInspectionRequest inspection(Scenario s,String id) { return new ProjectBaselineInspectionRequest(s.state,s.output,id); }
    private static ProjectBaselineApplication hooked(Consumer<String> hook) throws Exception {
        var ctor=ProjectBaselineApplication.class.getDeclaredConstructor(Consumer.class);ctor.setAccessible(true);return ctor.newInstance(hook);
    }

    @Test void realFourSourceBaselineReopensInNewInstanceWithoutOriginalDirectoryAndDoesNotWrite() throws Exception {
        var s=scenario(temp);Files.writeString(s.output.resolve("user.txt"),"not managed");var beforeOutput=tree(s.output);
        var registered=ok(new ProjectBaselineApplication().register(s.request()));assertEquals(ProjectBaselineResult.Outcome.REGISTERED,registered.outcome());
        assertEquals(2,registered.receipt().formatVersion());assertEquals(4,registered.sources().manifest().files().size());
        for (var f:s.generated.sources().manifest().files()) assertArrayEquals(s.generated.sources().bytes(f.sourceId()),registered.sources().bytes(f.sourceId()));
        Files.move(s.source,temp.resolve("original-unavailable"));var all=tree(temp);
        var inspected=ok(new ProjectBaselineApplication().inspect(inspection(s,registered.receipt().baselineId())));
        assertEquals(ProjectBaselineResult.Outcome.VERIFIED,inspected.outcome());assertEquals(registered.receipt(),inspected.receipt());
        assertEquals(s.generated.graph().canonicalDigest(),inspected.graph().canonicalDigest());assertEquals(all,tree(temp));assertEquals(beforeOutput,tree(s.output));
        var again=ok(new ProjectBaselineApplication().register(s.request()));assertEquals(ProjectBaselineResult.Outcome.ALREADY_REGISTERED,again.outcome());assertEquals(all,tree(temp));
        assertFalse(Files.exists(s.state.resolve("CURRENT.new")));assertFalse(Files.exists(s.state.resolve("transactions")));
        try (var access=new SecureFileAccess(s.state)) { assertEquals(ProjectBaselineStore.MEMBERS,access.list("baselines/"+registered.receipt().baselineId())); }
    }

    @Test void commentOnlySourceChangeChangesIdentityWithoutChangingJavaAndCannotReplaceCurrent() throws Exception {
        var s=scenario(temp);var originalOutput=tree(s.output);var first=ok(new ProjectBaselineApplication().register(s.request()));var before=tree(temp);
        Map<SourceId,byte[]> bytes=new LinkedHashMap<>();s.generated.sources().manifest().files().forEach(e->bytes.put(e.sourceId(),s.generated.sources().bytes(e.sourceId())));
        var course=SourceId.of("modules/course.sir");bytes.put(course,(new String(bytes.get(course),StandardCharsets.UTF_8)+"\n// comment\n").getBytes(StandardCharsets.UTF_8));
        var changed=new SourceSnapshot(ENTRY,bytes);assertNotEquals(s.generated.sources().sha256Hex(),changed.sha256Hex());
        rejected(new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(changed,s.output,s.state)));assertEquals(before,tree(temp));
        var second=ok(new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(changed,s.output,temp.resolve("another-state"))));
        assertNotEquals(first.receipt().baselineId(),second.receipt().baselineId());assertEquals(originalOutput,tree(s.output));
    }

    @Test void diskChangesWrongExpectedIdentityAndWrongOutputBindingAreRejectedWithoutRepair() throws Exception {
        var s=scenario(temp);var id=ok(new ProjectBaselineApplication().register(s.request())).receipt().baselineId();
        var before=tree(temp);rejected(new ProjectBaselineApplication().inspect(inspection(s,"0".repeat(64))));assertEquals(before,tree(temp));
        var other=temp.resolve("other-output");Files.createDirectory(other);
        var otherBefore=tree(temp);rejected(new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(s.state,other,id)));assertEquals(otherBefore,tree(temp));
        var tracked=s.output.resolve("pom.xml");Files.writeString(tracked,Files.readString(tracked)+"\nchanged");var modified=tree(temp);
        rejected(new ProjectBaselineApplication().inspect(inspection(s,id)));rejected(new ProjectBaselineApplication().register(s.request()));assertEquals(modified,tree(temp));
    }

    @Test void inspectionNeverCreatesLockOrCleansTransactionsPendingPointersOrUnknownFiles() throws Exception {
        var s=scenario(temp);var id=ok(new ProjectBaselineApplication().register(s.request())).receipt().baselineId();
        for (String dirty:List.of("transactions","CURRENT.new","foreign")) {
            var path=s.state.resolve(dirty);
            if (dirty.equals("transactions")) Files.createDirectory(path);else Files.writeString(path,"keep");
            var before=tree(temp);rejected(new ProjectBaselineApplication().inspect(inspection(s,id)));assertEquals(before,tree(temp));
            if (Files.isDirectory(path)) Files.delete(path);else Files.delete(path); // test-owned dirty fixture only
        }
        Files.delete(s.state.resolve("LOCK"));var before=tree(temp);
        rejected(new ProjectBaselineApplication().inspect(inspection(s,id)));assertEquals(before,tree(temp));assertFalse(Files.exists(s.state.resolve("LOCK")));
    }

    @Test void missingExtraTamperedAndExternalBundleMembersRemainUntouched() throws Exception {
        for (String kind:List.of("extra","missing","comment","link")) {
            var s=scenario(temp.resolve(kind));var id=ok(new ProjectBaselineApplication().register(s.request())).receipt().baselineId();var dir=s.state.resolve("baselines/"+id);
            if (kind.equals("extra")) Files.writeString(dir.resolve("user"),"keep");
            if (kind.equals("missing")) Files.delete(dir.resolve(ProjectBaselineStore.GRAPH));
            if (kind.equals("comment")) Files.write(dir.resolve(ProjectBaselineStore.SOURCES),new byte[]{1},StandardOpenOption.APPEND);
            if (kind.equals("link")) { var original=dir.resolve(ProjectBaselineStore.GRAPH);var user=s.source.resolve("user");Files.move(original,user);Files.createSymbolicLink(original,user); }
            var before=tree(s.source.getParent());rejected(new ProjectBaselineApplication().inspect(inspection(s,id)));rejected(new ProjectBaselineApplication().register(s.request()));assertEquals(before,tree(s.source.getParent()));
        }
    }

    @Test void legacyDescriptorAndStoreRemainStrictAndNewInspectorExplicitlyRejectsLegacy() throws Exception {
        var s=scenario(temp.resolve("multi"));var id=ok(new ProjectBaselineApplication().register(s.request())).receipt().baselineId();
        var raw=Files.readAllBytes(s.state.resolve("baselines/"+id+"/"+ProjectBaselineStore.DESCRIPTOR));
        assertInstanceOf(BaselineDescriptorCodec.DecodeFailure.class,BaselineDescriptorCodec.decode(raw));
        assertInstanceOf(BaselineBundleStore.LoadResult.Failure.class,new BaselineBundleStore(s.state).loadBundle(id));
        var single=temp.resolve("single.sir");Files.writeString(single,resource("valid/course-admin-enrollment.sir"));var output=temp.resolve("single-output");
        assertInstanceOf(ToolchainResult.Success.class,new ToolchainApplication().execute(new ToolchainRequest(single,ENTRY,output,ConflictPolicy.FAIL_IF_EXISTS)));
        var state=temp.resolve("single-state");var receipt=assertInstanceOf(ChangeBaselineRegistrationResult.Success.class,new ChangeExecutionApplication().registerGeneratedBaseline(new GeneratedBaselineRegistrationRequest(single,ENTRY,output,state))).receipt();
        var before=tree(state);var failure=rejected(new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(state,output,receipt.baselineId())));
        assertTrue(failure.diagnostics().stream().anyMatch(d->d.code().endsWith("VERSION-001")),failure.diagnostics().toString());assertEquals(before,tree(state));
        assertInstanceOf(ChangeBaselineRegistrationResult.AlreadyRegistered.class,new ChangeExecutionApplication().registerGeneratedBaseline(new GeneratedBaselineRegistrationRequest(single,ENTRY,output,state)));
    }

    @Test void oldContextPlanApplyRenameAndRecoverCannotConsumeAProjectBaseline() throws Exception {
        var s=scenario(temp);var id=ok(new ProjectBaselineApplication().register(s.request())).receipt().baselineId();
        var model=model(s.generated.sources());var declaration=model.declarations().stream().filter(d->d.name().equals("SearchCourseEnrollments")).findFirst().orElseThrow();
        // An intentionally old-shaped request, not a claimed multi-source ChangeBaseRevision.
        var rootEvidence=s.generated.sources().manifest().files().stream().filter(e->e.sourceId().equals(ENTRY)).findFirst().orElseThrow();
        var basedOn=new io.kcg.sir.change.api.ChangeBaseRevision(ENTRY,rootEvidence.sha256Hex(),io.kcg.sir.projectgraph.api.GraphVersion.V0_1,"0".repeat(64),io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion.V1);
        var target=new io.kcg.sir.change.api.ChangeTarget(declaration.id(),declaration.sourceNodeId(),declaration.sourceNodeId());
        var change=new io.kcg.sir.change.api.ChangeSet(io.kcg.sir.change.api.ChangeIrVersion.V0_1,basedOn,List.of(new io.kcg.sir.change.api.ModifyCapabilityWorkflow(target)));
        var api=new ChangeExecutionApplication();var candidate=s.source.resolve("project.sir");var before=tree(temp);
        assertInstanceOf(ChangePlanningContextResult.Failure.class,api.inspectChangePlanningContext(new ChangePlanningContextRequest(s.state,s.output,candidate)));
        assertInstanceOf(ChangeBaselinePlanningResult.Failure.class,api.plan(new ChangeBaselinePlanningRequest(s.state,id,candidate,s.output,change)));
        assertInstanceOf(ChangeApplyResult.Failure.class,api.apply(new ChangeApplyRequest(s.state,id,candidate,s.output,change)));
        assertInstanceOf(RenameApplyResult.Failure.class,api.applyRename(new RenameApplyRequest(s.state,id,candidate,s.output,declaration.id())));
        assertInstanceOf(ChangeRecoveryResult.Failure.class,api.recover(new ChangeRecoveryRequest(s.state,s.output,RecoveryHandle.any())));
        assertEquals(before,tree(temp));ok(new ProjectBaselineApplication().inspect(inspection(s,id)));
    }

    @Test void completeAndPartialInterruptionMatrixIsPinnedAndRetryNeverDeletesUnprovenEvidence() throws Exception {
        var points=List.of("before-save","candidate-directory","partial:sources.kcg-source-set","written:sources.kcg-source-set","partial:graph.kcg-psg","written:graph.kcg-psg",
                "partial:descriptor.kcg-baseline","written:descriptor.kcg-baseline","partial:baseline-id.kcg-pointer","written:baseline-id.kcg-pointer","bundle-verified","pending-pointer","current-published","before-pointer-cleanup","pointer-cleaned");
        assertEquals(15,points.size());var incomplete=Set.of("candidate-directory","partial:sources.kcg-source-set","written:sources.kcg-source-set","partial:graph.kcg-psg","written:graph.kcg-psg","partial:descriptor.kcg-baseline","written:descriptor.kcg-baseline");
        int reached=0;
        for (int i=0;i<points.size();i++) {
            var point=points.get(i);var s=scenario(temp.resolve("case"+i));var output=tree(s.output);var hit=new boolean[1];
            var failure=rejected(hooked(event->{ boolean match=event.equals(point) || (point.contains(":") && event.startsWith(point.substring(0,point.indexOf(':')+1)) && event.endsWith("/"+point.substring(point.indexOf(':')+1)));
                if (match && !hit[0]) { hit[0]=true;throw new IllegalStateException("simulated crash "+point); } }).register(s.request()));
            assertTrue(hit[0],point);reached++;assertEquals(output,tree(s.output),point);var stateBefore=tree(s.state);
            var retry=new ProjectBaselineApplication().register(s.request());
            if (incomplete.contains(point)) { rejected(retry);assertEquals(stateBefore,tree(s.state),point);assertFalse(Files.exists(s.state.resolve("CURRENT")),point); }
            else { var complete=ok(retry);assertFalse(Files.exists(s.state.resolve("CURRENT.new")));ok(new ProjectBaselineApplication().inspect(inspection(s,complete.receipt().baselineId())));var stable=tree(s.state);ok(new ProjectBaselineApplication().register(s.request()));assertEquals(stable,tree(s.state)); }
            assertEquals(output,tree(s.output));
        }
        assertEquals(15,reached);
    }

    @Test void malformedClosureAndLegacySourceSnapshotsReturnDiagnosticsWithoutAnyPublication() throws Exception {
        var s=scenario(temp);var original=tree(temp);
        for (String kind:List.of("missing","unlisted","legacy")) {
            var values=new LinkedHashMap<SourceId,byte[]>();s.generated.sources().manifest().files().forEach(e->values.put(e.sourceId(),s.generated.sources().bytes(e.sourceId())));
            if(kind.equals("missing"))values.remove(SourceId.of("modules/student.sir"));
            if(kind.equals("unlisted"))values.put(SourceId.of("extra.sir"),"sir 0.2\nimports {}\ndeclarations {}".getBytes(StandardCharsets.UTF_8));
            if(kind.equals("legacy")){values.clear();values.put(ENTRY,resource("valid/course-admin-enrollment.sir").getBytes(StandardCharsets.UTF_8));}
            var failure=rejected(new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(new SourceSnapshot(ENTRY,values),s.output,s.state)));
            assertFalse(failure.diagnostics().isEmpty());assertEquals(original,tree(temp));assertFalse(Files.exists(s.state));
        }
    }

    @Test void outputMutationAtPublishBoundaryIsDetectedAndNeverRepairedByRegistration() throws Exception {
        var s=scenario(temp);var pom=s.output.resolve("pom.xml");var original=Files.readAllBytes(pom);
        rejected(hooked(e->{if(e.equals("bundle-verified")){try{Files.writeString(pom,"external change");}catch(IOException ex){throw new IllegalStateException(ex);}}}).register(s.request()));
        assertEquals("external change",Files.readString(pom));assertFalse(Files.exists(s.state.resolve("CURRENT")));
        var before=tree(temp);rejected(new ProjectBaselineApplication().register(s.request()));assertEquals(before,tree(temp));
        Files.write(pom,original);ok(new ProjectBaselineApplication().register(s.request()));assertArrayEquals(original,Files.readAllBytes(pom));
    }

    @Test void aForeignCurrentAppearingAtPublishBoundaryCannotBeOverwritten() throws Exception {
        var s=scenario(temp);var foreign="b".repeat(64)+"\n";
        rejected(hooked(e->{if(e.equals("pending-pointer")){try{Files.writeString(s.state.resolve("CURRENT"),foreign,StandardOpenOption.CREATE_NEW);}catch(IOException ex){throw new IllegalStateException(ex);}}}).register(s.request()));
        assertEquals(foreign,Files.readString(s.state.resolve("CURRENT")));assertTrue(Files.exists(s.state.resolve("CURRENT.new")));
        var before=tree(s.state);rejected(new ProjectBaselineApplication().register(s.request()));assertEquals(before,tree(s.state));
    }

    @Test void sameByteExternalPendingPointerCannotBeConsumedOrDeletedInEitherCurrentDirection() throws Exception {
        for (String point:List.of("pending-pointer","current-published")) {
            var s=scenario(temp.resolve(point));rejected(hooked(e->{if(e.equals(point))throw new IllegalStateException("stop");}).register(s.request()));
            var pending=s.state.resolve("CURRENT.new");var bytes=Files.readAllBytes(pending);Files.delete(pending);Files.write(pending,bytes,StandardOpenOption.CREATE_NEW);
            var before=tree(s.state);rejected(new ProjectBaselineApplication().register(s.request()));assertEquals(before,tree(s.state));assertArrayEquals(bytes,Files.readAllBytes(pending));
        }
    }
}
