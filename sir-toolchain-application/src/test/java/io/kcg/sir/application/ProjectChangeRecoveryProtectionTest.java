package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.projectupdate.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectChangeRecoveryProtectionTest {
    @TempDir Path temp;
    ProjectChangeExecutionApplicationTest.Case scenario(Path root) throws Exception {return new ProjectChangeExecutionApplicationTest().scenario(root,false);}
    ProjectChangeApplyResult.RecoveryRequired interrupt(ProjectChangeExecutionApplicationTest.Case c,boolean published) throws Exception {
        String checkpoint=published?"current-published":"output-replaced:0";var hit=new java.util.concurrent.atomic.AtomicBoolean();var result=assertInstanceOf(ProjectChangeApplyResult.RecoveryRequired.class,ProjectChangeExecutionApplicationTest.hookExecution(p->{if(p.equals(checkpoint)){hit.set(true);throw new IllegalStateException("process stop");}}).apply(c.apply()));assertTrue(hit.get());return result;
    }
    @Test void currentDirectionMustBeRealAnchoredB0OrB1AndCannotBeInferredFromOutputBytes() throws Exception {
        for(String kind:List.of("missing","third","same-bytes")) {
            var c=scenario(temp.resolve(kind));var stopped=interrupt(c,false);var pointer=c.s().state().resolve("CURRENT");byte[] bytes=Files.readAllBytes(pointer);Files.delete(pointer);
            if(kind.equals("third"))Files.writeString(pointer,"0".repeat(64)+"\n");if(kind.equals("same-bytes"))Files.write(pointer,bytes);var before=tree(c.s().root());assertInstanceOf(ProjectChangeRecoveryResult.Failure.class,new ProjectChangeExecutionApplication().recover(stopped.handle()));assertEquals(before,tree(c.s().root()),kind);
        }
    }
    @Test void unknownOrReplacedCleanupMaterialIsRetainedBeforeAnyCompensationWrite() throws Exception {
        for(String kind:List.of("unknown-staging","unknown-candidate","backup-replaced","backup-missing","binding-replaced","slot-replaced","journal-corrupt","candidate-graph-missing","base-corrupt")) {
            var c=scenario(temp.resolve(kind));var stopped=interrupt(c,false);var state=c.s().state();Path transaction=state.resolve(ProjectUpdateTransaction.ACTIVE+"/"+stopped.handle().transactionId());
            switch(kind) {
                case "unknown-staging" -> Files.writeString(transaction.resolve("staging/user.txt"),"external keep");
                case "unknown-candidate" -> Files.writeString(state.resolve("baselines/"+stopped.handle().candidateId()+"/user.txt"),"external keep");
                case "backup-replaced" -> {var p=transaction.resolve("backups/0");byte[] b=Files.readAllBytes(p);Files.delete(p);Files.write(p,b);}
                case "backup-missing" -> Files.delete(transaction.resolve("backups/0"));
                case "binding-replaced" -> {var p=transaction.resolve("binding");byte[] b=Files.readAllBytes(p);Files.delete(p);Files.write(p,b);}
                case "slot-replaced" -> {var p=transaction.resolve("journal.A");byte[] b=Files.readAllBytes(p);Files.delete(p);Files.write(p,b);}
                case "journal-corrupt" -> Files.write(transaction.resolve("journal.A"),new byte[]{0},StandardOpenOption.APPEND);
                case "candidate-graph-missing" -> Files.delete(state.resolve("baselines/"+stopped.handle().candidateId()+"/graph.kcg-psg"));
                case "base-corrupt" -> Files.write(state.resolve("baselines/"+stopped.handle().baselineId()+"/sources.kcg-source-set"),new byte[]{0},StandardOpenOption.APPEND);
            }
            var before=tree(c.s().root());assertInstanceOf(ProjectChangeRecoveryResult.Failure.class,new ProjectChangeExecutionApplication().recover(stopped.handle()));assertEquals(before,tree(c.s().root()),kind);
        }
    }
    @Test void sameByteExternalOutputReplacementAndWrongHandleHaveNoRecoverySideEffects() throws Exception {
        for(boolean published:List.of(false,true)) {
            var c=scenario(temp.resolve(published?"published":"rollback"));var stopped=interrupt(c,published);var h=stopped.handle();var wrong=new ProjectRecoveryHandle(h.stateRoot(),h.outputRoot(),h.transactionId(),h.baselineId(),h.candidateId(),Optional.of("0".repeat(64)));var before=tree(c.s().root());assertInstanceOf(ProjectChangeRecoveryResult.Failure.class,new ProjectChangeExecutionApplication().recover(wrong));assertEquals(before,tree(c.s().root()));
            var file=c.s().output().resolve(c.planned().plan().fileChanges().getFirst().relativePath());byte[] content=Files.readAllBytes(file);Files.delete(file);Files.write(file,content);before=tree(c.s().root());assertInstanceOf(ProjectChangeRecoveryResult.Failure.class,new ProjectChangeExecutionApplication().recover(h));assertEquals(before,tree(c.s().root()));
        }
    }
    @Test void noReadEntranceRecoversOrCleansAnActiveProjectTransactionAndLegacyFamiliesDoNotConsumeIt() throws Exception {
        var c=scenario(temp);var stopped=interrupt(c,false);var before=tree(c.s().root());
        assertInstanceOf(ProjectChangeContextResult.Failure.class,new ProjectChangePlanningApplication().context(c.s().request()));assertInstanceOf(ProjectChangePlanningResult.Failure.class,new ProjectChangePlanningApplication().plan(c.planning()));assertInstanceOf(ProjectChangePlanningResult.Failure.class,new ProjectChangePlanningApplication().verify(c.planning(),c.planned()));assertInstanceOf(ProjectBaselineResult.Failure.class,new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(c.s().state(),c.s().output(),stopped.handle().baselineId())));assertEquals(before,tree(c.s().root()));
    }
}
