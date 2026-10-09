package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import static io.kcg.sir.application.ProjectChangePrerequisiteTest.*;
import static io.kcg.sir.application.ProjectChangePlanningApplicationTest.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.projectbaseline.*;
import io.kcg.sir.application.internal.projectupdate.*;
import io.kcg.sir.change.api.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectChangeExecutionApplicationTest {
    @TempDir Path temp;
    record Case(ProjectChangePlanningApplicationTest.Scenario s,ProjectChangePlanningRequest planning,ProjectChangePlanningResult.Planned planned) {ProjectChangeApplyRequest apply(){return new ProjectChangeApplyRequest(planning,planned);}}
    Case scenario(Path root,boolean fragment) throws Exception {var s=new ProjectChangePlanningApplicationTest().scenario(root,fragment);var api=new ProjectChangePlanningApplication();var ctx=context(api.context(s.request()));var req=request(ctx,s.candidate());var plan=assertInstanceOf(ProjectChangePlanningResult.Planned.class,api.plan(req));return new Case(s,req,plan);}
    static ProjectChangeExecutionApplication hookExecution(java.util.function.Consumer<String> hook) throws Exception {var ctor=ProjectChangeExecutionApplication.class.getDeclaredConstructor(java.util.function.Consumer.class);ctor.setAccessible(true);return ctor.newInstance(hook);}
    @Test void publicApplyReopensRealRootAndFragmentAndEqualsCandidateFromZeroByEveryPathAndByte() throws Exception {
        for(boolean fragment:List.of(false,true)) {
            var c=scenario(temp.resolve(fragment?"fragment":"root"),fragment);Files.move(c.s.source(),c.s.root().resolve("unavailable"));Files.writeString(c.s.output().resolve("user.txt"),"keep");
            var compiled=ProjectBaselineVerification.compile(c.s.candidate(),c.s.output(),new ArrayList<>());var expected=new TreeMap<String,String>();compiled.compilation().generatedFiles().forEach(f->expected.put(f.relativePath(),f.content()));
            Path freshSource=c.s.root().resolve("fresh-sources"),freshOutput=c.s.root().resolve("fresh-output");for(var source:c.s.candidate().manifest().files()) {Path path=freshSource.resolve(source.sourceId().value());Files.createDirectories(path.getParent());Files.write(path,c.s.candidate().bytes(source.sourceId()));}
            assertInstanceOf(ProjectToolchainResult.Success.class,new ToolchainApplication().executeProject(new ProjectToolchainRequest(freshSource,ENTRY,freshOutput)));
            var before=files(c.s.output());var result=assertInstanceOf(ProjectChangeApplyResult.Applied.class,new ProjectChangeExecutionApplication().apply(c.apply()));assertEquals(compiled.bundle().baselineId(),result.candidate().baselineId());
            var actual=files(c.s.output());assertEquals(expected.size()+1,actual.size());for(var e:expected.entrySet())assertEquals(e.getValue(),Files.readString(c.s.output().resolve(e.getKey())),e.getKey());
            var managed=new TreeMap<>(actual);managed.remove("user.txt");assertEquals(files(freshOutput),managed);
            assertEquals("keep",Files.readString(c.s.output().resolve("user.txt")));for(var e:before.entrySet())if(!c.planned.plan().fileChanges().stream().anyMatch(f->f.relativePath().equals(e.getKey())))assertEquals(e.getValue(),actual.get(e.getKey()));
            var saved=assertInstanceOf(ProjectBaselineResult.Success.class,new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(c.s.state(),c.s.output(),result.candidate().baselineId())));assertEquals(c.s.candidate().manifest(),saved.sources().manifest());for(var source:c.s.candidate().manifest().files())assertArrayEquals(c.s.candidate().bytes(source.sourceId()),saved.sources().bytes(source.sourceId()));
        }
    }
    @Test void staleRequestRejectsAndFreshEquivalentCandidateNoChangesLeavesWholeStateUntouched() throws Exception {
        var c=scenario(temp,false);var api=new ProjectChangeExecutionApplication();var applied=assertInstanceOf(ProjectChangeApplyResult.Applied.class,api.apply(c.apply()));var before=tree(temp);
        assertEquals(FailureDisposition.NO_CHANGES,assertInstanceOf(ProjectChangeApplyResult.Failure.class,new ProjectChangeExecutionApplication().apply(c.apply())).disposition());assertEquals(before,tree(temp));
        var planner=new ProjectChangePlanningApplication();var ctx=context(planner.context(new ProjectChangeContextRequest(c.s.state(),c.s.output(),applied.candidate().baselineId(),c.s.candidate())));var req=request(ctx,c.s.candidate());var none=assertInstanceOf(ProjectChangePlanningResult.NoChanges.class,planner.plan(req));before=tree(temp);
        assertInstanceOf(ProjectChangeApplyResult.NoChanges.class,new ProjectChangeExecutionApplication().apply(new ProjectChangeApplyRequest(req,none)));assertEquals(before,tree(temp));
    }
    @Test void commentsOnlyCandidateDoesNotPublishNewSourceBytes() throws Exception {
        var c=scenario(temp,false);var source=replace(c.s.saved().sources(),ENTRY,"sir 0.2","sir 0.2\n// comment only");var planner=new ProjectChangePlanningApplication();var ctx=context(planner.context(new ProjectChangeContextRequest(c.s.state(),c.s.output(),c.s.saved().receipt().baselineId(),source)));var req=request(ctx,source);var none=assertInstanceOf(ProjectChangePlanningResult.NoChanges.class,planner.plan(req));var before=tree(temp);
        assertInstanceOf(ProjectChangeApplyResult.NoChanges.class,new ProjectChangeExecutionApplication().apply(new ProjectChangeApplyRequest(req,none)));assertEquals(before,tree(temp));
    }
    @Test void twoSuccessivePublicUpdatesAndReversionKeepEveryPublishedBaseline() throws Exception {
        var c=scenario(temp,false);var first=assertInstanceOf(ProjectChangeApplyResult.Applied.class,new ProjectChangeExecutionApplication().apply(c.apply()));
        var source=replace(c.s.candidate(),ENTRY,"order by code ascending","order by code descending");var planner=new ProjectChangePlanningApplication();var ctx=context(planner.context(new ProjectChangeContextRequest(c.s.state(),c.s.output(),first.candidate().baselineId(),source)));var req=request(ctx,source);var p=assertInstanceOf(ProjectChangePlanningResult.Planned.class,planner.plan(req));
        var second=assertInstanceOf(ProjectChangeApplyResult.Applied.class,new ProjectChangeExecutionApplication().apply(new ProjectChangeApplyRequest(req,p)));assertInstanceOf(ProjectBaselineResult.Success.class,new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(c.s.state(),c.s.output(),second.candidate().baselineId())));
        ctx=context(planner.context(new ProjectChangeContextRequest(c.s.state(),c.s.output(),second.candidate().baselineId(),c.s.saved().sources())));req=request(ctx,c.s.saved().sources());p=assertInstanceOf(ProjectChangePlanningResult.Planned.class,planner.plan(req));assertEquals(c.s.saved().receipt().baselineId(),assertInstanceOf(ProjectChangeApplyResult.Applied.class,new ProjectChangeExecutionApplication().apply(new ProjectChangeApplyRequest(req,p))).candidate().baselineId());
        for(String id:List.of(c.s.saved().receipt().baselineId(),first.candidate().baselineId(),second.candidate().baselineId()))assertTrue(Files.isDirectory(c.s.state().resolve("baselines/"+id)));
    }
    @Test void publicInterruptedApplyReportsDirectionAndExplicitRecoveryReopensNewInstance() throws Exception {
        for(String point:List.of("output-replaced:0","current-published")) {
            var c=scenario(temp.resolve(point.replace(':','-')),false);var hit=new java.util.concurrent.atomic.AtomicBoolean();
            var result=assertInstanceOf(ProjectChangeApplyResult.RecoveryRequired.class,hookExecution(p->{if(p.equals(point)){hit.set(true);throw new IllegalStateException("process interruption");}}).apply(c.apply()));assertTrue(hit.get());assertEquals(FailureDisposition.RECOVERY_REQUIRED,result.disposition());boolean published=point.equals("current-published");assertEquals(published?ProjectChangeApplyResult.Publication.PUBLISHED:ProjectChangeApplyResult.Publication.NOT_PUBLISHED,result.publication());
            var before=tree(c.s.root());assertInstanceOf(ChangeRecoveryResult.Failure.class,new ChangeExecutionApplication().recover(new ChangeRecoveryRequest(c.s.state(),c.s.output(),RecoveryHandle.any())));assertEquals(before,tree(c.s.root()));assertInstanceOf(ProjectBaselineResult.Failure.class,new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(c.s.state(),c.s.output(),published?result.handle().candidateId():result.handle().baselineId())));assertEquals(before,tree(c.s.root()));
            var recovered=assertInstanceOf(ProjectChangeRecoveryResult.Recovered.class,new ProjectChangeExecutionApplication().recover(result.handle()));assertEquals(published?ProjectChangeRecoveryResult.Outcome.COMMITTED_AND_CLEANED:ProjectChangeRecoveryResult.Outcome.ROLLED_BACK,recovered.outcome());before=tree(c.s.root());assertEquals(ProjectChangeRecoveryResult.Outcome.ALREADY_CLEAN,assertInstanceOf(ProjectChangeRecoveryResult.Recovered.class,new ProjectChangeExecutionApplication().recover(result.handle())).outcome());assertEquals(before,tree(c.s.root()));
        }
    }
    @Test void malformedExpectedPlanAndChangedCandidateRejectBeforeAnyNewStateFiles() throws Exception {
        var c=scenario(temp,false);var plan=c.planned.plan();var fake=new ProjectChangePlan(plan.formatVersion(),plan.basedOn(),plan.candidate(),plan.operation(),List.of(),plan.fileChanges());var expected=new ProjectChangePlanningResult.Planned(c.planned.context(),fake,List.of());var before=tree(temp);
        assertInstanceOf(ProjectChangeApplyResult.Failure.class,new ProjectChangeExecutionApplication().apply(new ProjectChangeApplyRequest(c.planning,expected)));assertEquals(before,tree(temp));
        var changed=replace(c.s.candidate(),ENTRY,"order by code ascending","order by code descending");var altered=new ProjectChangePlanningRequest(c.planning.context(),changed,c.planning.targetKey());assertInstanceOf(ProjectChangeApplyResult.Failure.class,new ProjectChangeExecutionApplication().apply(new ProjectChangeApplyRequest(altered,c.planned)));assertEquals(before,tree(temp));
    }
    @Test void terminalReceiptBudgetRefusesNextWriteButStillPermitsFreshNoChanges() throws Exception {
        var c=scenario(temp,false);String current=c.s.saved().receipt().baselineId();var planner=new ProjectChangePlanningApplication();
        for(int i=0;i<ProjectUpdateReceipt.MAX_RECEIPTS;i++) {var source=i%2==0?c.s.candidate():c.s.saved().sources();var ctx=context(planner.context(new ProjectChangeContextRequest(c.s.state(),c.s.output(),current,source)));var req=request(ctx,source);var p=assertInstanceOf(ProjectChangePlanningResult.Planned.class,planner.plan(req));current=assertInstanceOf(ProjectChangeApplyResult.Applied.class,new ProjectChangeExecutionApplication().apply(new ProjectChangeApplyRequest(req,p))).candidate().baselineId();}
        var ctx=context(planner.context(new ProjectChangeContextRequest(c.s.state(),c.s.output(),current,c.s.candidate())));var req=request(ctx,c.s.candidate());var p=assertInstanceOf(ProjectChangePlanningResult.Planned.class,planner.plan(req));var before=tree(temp);assertInstanceOf(ProjectChangeApplyResult.Failure.class,new ProjectChangeExecutionApplication().apply(new ProjectChangeApplyRequest(req,p)));assertEquals(before,tree(temp));
        ctx=context(planner.context(new ProjectChangeContextRequest(c.s.state(),c.s.output(),current,c.s.saved().sources())));req=request(ctx,c.s.saved().sources());var none=assertInstanceOf(ProjectChangePlanningResult.NoChanges.class,planner.plan(req));before=tree(temp);assertInstanceOf(ProjectChangeApplyResult.NoChanges.class,new ProjectChangeExecutionApplication().apply(new ProjectChangeApplyRequest(req,none)));assertEquals(before,tree(temp));
    }
    @Test void latePhysicalParentReplacementRejectsBeforePreparationWithoutDeletingExternalBytes() throws Exception {
        var c=scenario(temp,false);Path file=c.s.output().resolve(c.planned.plan().fileChanges().getFirst().relativePath());var parent=file.getParent();var retained=parent.resolveSibling("retained-parent");var captured=new java.util.concurrent.atomic.AtomicReference<Map<String,String>>();
        var result=hookExecution(p->{if(p.equals("before-prepare"))try {Files.move(parent,retained);Files.createDirectory(parent);try(var paths=Files.list(retained)){for(Path path:paths.toList())Files.copy(path,parent.resolve(path.getFileName()));}captured.set(tree(temp));}catch(Exception e){throw new RuntimeException(e);}}).apply(c.apply());
        assertInstanceOf(ProjectChangeApplyResult.Failure.class,result);assertNotNull(captured.get());assertEquals(captured.get(),tree(temp));assertFalse(Files.exists(c.s.state().resolve(ProjectUpdateTransaction.ACTIVE)));
    }
}
