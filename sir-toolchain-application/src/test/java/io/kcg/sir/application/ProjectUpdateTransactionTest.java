package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import static io.kcg.sir.application.ProjectChangePrerequisiteTest.*;
import static io.kcg.sir.application.ProjectChangePlanningApplicationTest.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.projectbaseline.*;
import io.kcg.sir.application.internal.projectupdate.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectUpdateTransactionTest {
    @TempDir Path temp;
    record Case(ProjectChangePlanningApplicationTest.Scenario s,ProjectBaselineVerification.Verified base,ProjectBaselineVerification.Verified candidate,ProjectChangePlanningResult.Planned planned) {}
    Case scenario(Path root,boolean fragment) throws Exception {
        var s=new ProjectChangePlanningApplicationTest().scenario(root,fragment);var api=new ProjectChangePlanningApplication();var ctx=context(api.context(s.request()));var plan=assertInstanceOf(ProjectChangePlanningResult.Planned.class,api.plan(request(ctx,s.candidate())));
        var ds=new ArrayList<ExecutionDiagnostic>();var base=ProjectBaselineVerification.compile(s.saved().sources(),s.output(),ds);var cand=ProjectBaselineVerification.compile(s.candidate(),s.output(),ds);return new Case(s,base,cand,plan);
    }
    void apply(Case c,String id,Consumer<String> hook) throws Exception {try(var state=new SecureFileAccess(c.s.state());var output=new SecureFileAccess(c.s.output())) {new ProjectUpdateTransaction(state,output,id,hook).apply(c.base,c.candidate,c.planned);}}
    void assertOutput(Case c,ProjectBaselineVerification.Verified expected) throws Exception {
        for(var f:expected.compilation().generatedFiles())assertEquals(f.content(),Files.readString(c.s.output().resolve(f.relativePath())),f.relativePath());
    }
    @Test void successfulRootAndFragmentTransactionsReopenAndKeepNonmanagedFiles() throws Exception {
        for(boolean fragment:List.of(false,true)) {
            var c=scenario(temp.resolve(fragment?"fragment":"root"),fragment);Files.writeString(c.s.output().resolve("user.txt"),"keep");Files.move(c.s.source(),c.s.root().resolve("unavailable"));apply(c,"a".repeat(64),x->{});assertOutput(c,c.candidate);assertEquals("keep",Files.readString(c.s.output().resolve("user.txt")));
            assertInstanceOf(ProjectBaselineResult.Success.class,new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(c.s.state(),c.s.output(),c.candidate.bundle().baselineId())));
            assertTrue(Files.isDirectory(c.s.state().resolve("baselines/"+c.base.bundle().baselineId())));
            try(var state=new SecureFileAccess(c.s.state())) {assertEquals(Set.of(),state.list(ProjectUpdateTransaction.ACTIVE,1));assertEquals(ProjectUpdateTrail.Phase.COMPLETED,ProjectUpdateReceipt.read(state,"a".repeat(64)).trail().state().phase());}
        }
    }
    @Test void updateCanContinueThenRevertToOriginalContentAddressedId() throws Exception {
        var c=scenario(temp,false);apply(c,"a".repeat(64),x->{});
        var secondSources=replace(c.s.candidate(),ENTRY,"order by code ascending","order by code descending");
        var ds=new ArrayList<ExecutionDiagnostic>();var second=ProjectBaselineVerification.compile(secondSources,c.s.output(),ds);var api=new ProjectChangePlanningApplication();
        var ctx=context(api.context(new ProjectChangeContextRequest(c.s.state(),c.s.output(),c.candidate.bundle().baselineId(),secondSources)));var plan=assertInstanceOf(ProjectChangePlanningResult.Planned.class,api.plan(request(ctx,secondSources)));
        try(var state=new SecureFileAccess(c.s.state());var output=new SecureFileAccess(c.s.output())) {new ProjectUpdateTransaction(state,output,"b".repeat(64),x->{}).apply(c.candidate,second,plan);}
        assertOutput(c,second);ctx=context(api.context(new ProjectChangeContextRequest(c.s.state(),c.s.output(),second.bundle().baselineId(),c.base.bundle().sources())));plan=assertInstanceOf(ProjectChangePlanningResult.Planned.class,api.plan(request(ctx,c.base.bundle().sources())));
        try(var state=new SecureFileAccess(c.s.state());var output=new SecureFileAccess(c.s.output())) {new ProjectUpdateTransaction(state,output,"c".repeat(64),x->{}).apply(second,c.base,plan);}
        assertOutput(c,c.base);assertInstanceOf(ProjectBaselineResult.Success.class,new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(c.s.state(),c.s.output(),c.base.bundle().baselineId())));
    }
    @Test void outputReplacementAndPublicationCheckpointsRecoverInOppositeDirections() throws Exception {
        for(String point:List.of("binding-complete","replace-intent:0","output-replaced:0","before-publication","current-pending","current-published","before-cleanup","before-receipt-relocation")) {
            var c=scenario(temp.resolve(point.replace(':','-')),false);var hit=new java.util.concurrent.atomic.AtomicBoolean();
            assertThrows(IllegalStateException.class,()->apply(c,"a".repeat(64),p->{if(p.equals(point)){hit.set(true);throw new IllegalStateException("simulated process interruption");}}));assertTrue(hit.get(),point);
            boolean published=Files.readString(c.s.state().resolve("CURRENT")).trim().equals(c.candidate.bundle().baselineId());
            try(var state=new SecureFileAccess(c.s.state());var output=new SecureFileAccess(c.s.output())) {assertEquals(published,new ProjectUpdateTransaction(state,output,"a".repeat(64),x->{}).recover(Optional.empty()),point);}
            assertOutput(c,published?c.candidate:c.base);String current=published?c.candidate.bundle().baselineId():c.base.bundle().baselineId();assertInstanceOf(ProjectBaselineResult.Success.class,new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(c.s.state(),c.s.output(),current)),point);
        }
    }
    @Test void cleanupRejectsUnknownOrSameByteExternalObjectsWithoutDeletingThem() throws Exception {
        var c=scenario(temp,false);assertThrows(IllegalStateException.class,()->apply(c,"a".repeat(64),p->{if(p.equals("current-published"))throw new IllegalStateException("stop");}));
        var dir=c.s.state().resolve("project-transactions/"+"a".repeat(64)+"/backups");Files.writeString(dir.resolve("external"),"retain");
        try(var state=new SecureFileAccess(c.s.state());var output=new SecureFileAccess(c.s.output())) {assertThrows(java.io.IOException.class,()->new ProjectUpdateTransaction(state,output,"a".repeat(64),x->{}).recover(Optional.empty()));}
        assertEquals("retain",Files.readString(dir.resolve("external")));assertOutput(c,c.candidate); // Recovery may append its own legal progress but must not delete anything unowned.
    }
}
