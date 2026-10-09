package io.kcg.sir.application.api;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import io.kcg.sir.application.internal.projectbaseline.*;
import io.kcg.sir.application.internal.projectupdate.*;
import io.kcg.sir.application.internal.state.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Explicit multi-source UPDATE and recovery. Does not broaden any single-source execution entrance. */
public final class ProjectChangeExecutionApplication {
    private final Consumer<String> checkpoint;
    public ProjectChangeExecutionApplication() {this(ignored->{});}
    ProjectChangeExecutionApplication(Consumer<String> checkpoint) {this.checkpoint=Objects.requireNonNull(checkpoint);}
    public ProjectChangeApplyResult apply(ProjectChangeApplyRequest request) {
        Objects.requireNonNull(request);var planning=request.planning();var context=planning.context();var diagnostics=new ArrayList<ExecutionDiagnostic>();
        ProjectUpdateTransaction transaction=null;Bundle base=null,candidate=null;
        try {
            if(!context.stateRoot().isAbsolute() || !context.stateRoot().equals(context.stateRoot().normalize()) || !context.baseline().boundOutputRoot().isAbsolute() || !context.baseline().boundOutputRoot().equals(context.baseline().boundOutputRoot().normalize()))return failure(diagnostics,"REQUEST-001",ExecutionStage.PREFLIGHT,"state/output roots must be absolute normalized");
            var paths=StateRootPathGuard.validate(context.stateRoot(),context.baseline().boundOutputRoot(),true);appendPaths(diagnostics,paths.errors());
            if(diagnostics.stream().anyMatch(ExecutionDiagnostic::isError))return new ProjectChangeApplyResult.Failure(ExecutionStage.PREFLIGHT,diagnostics);
            if(context.formatVersion()!=1 || !context.candidate().sources().equals(planning.candidate().manifest()))return failure(diagnostics,"STALE-001",ExecutionStage.PREFLIGHT,"context/candidate source evidence changed");
            var lock=new StateRootLock(context.stateRoot()).tryAcquireExisting();if(!(lock instanceof StateRootLock.HeldLock held))return failure(diagnostics,"LOCK-001",ExecutionStage.PREFLIGHT,((StateRootLock.LockFailure)lock).message());
            try(held;var state=new SecureFileAccess(context.stateRoot());var output=new SecureFileAccess(context.baseline().boundOutputRoot())) {
                var original=new ArrayList<ProjectChangeDiagnostic>();var expected=request.expected() instanceof ProjectChangePlanningResult.Planned p?p:null;
                var reconstruction=LockedProjectPlanning.reconstruct(new ProjectChangeContextRequest(context.stateRoot(),context.baseline().boundOutputRoot(),context.baseline().baselineId(),planning.candidate()),planning,expected,state,output,checkpoint,original);
                original.stream().map(ProjectChangeExecutionApplication::convert).forEach(diagnostics::add);
                if(reconstruction instanceof LockedProjectPlanning.Rejected rejected)return new ProjectChangeApplyResult.Failure(stage(rejected.stage()),diagnostics);
                var ready=(LockedProjectPlanning.Ready)reconstruction;var decision=ready.decision().orElseThrow();
                if(decision instanceof ProjectChangePlanningResult.NoChanges none) {
                    if(!(request.expected() instanceof ProjectChangePlanningResult.NoChanges expectedNone) || !none.context().equals(expectedNone.context()) || none.reason()!=expectedNone.reason())return failure(diagnostics,"VERIFY-001",ExecutionStage.PREFLIGHT,"NoChanges evidence differs from independent complete reconstruction");
                    return new ProjectChangeApplyResult.NoChanges(none.context(),none.reason(),diagnostics);
                }
                if(!(request.expected() instanceof ProjectChangePlanningResult.Planned))return failure(diagnostics,"VERIFY-001",ExecutionStage.PREFLIGHT,"expected NoChanges but reconstruction requires UPDATE");
                base=ready.base().bundle();candidate=ready.candidate().bundle();transaction=new ProjectUpdateTransaction(state,output,request.transactionId(),checkpoint);
                transaction.apply(ready.base(),ready.candidate(),(ProjectChangePlanningResult.Planned)decision);
                return new ProjectChangeApplyResult.Applied(receipt(base),receipt(candidate),diagnostics);
            }
        } catch(IOException|RuntimeException e) {
            boolean changed=transaction!=null && transaction.started();diagnostics.add(error("APPLY-001",changed?ExecutionStage.WRITE:ExecutionStage.PREFLIGHT,"project update refused: "+e.getMessage()));
            if(!changed)return new ProjectChangeApplyResult.Failure(ExecutionStage.PREFLIGHT,diagnostics);
            var digest=transaction.binding().map(ProjectUpdateBinding::digest);var handle=new ProjectRecoveryHandle(context.stateRoot(),context.baseline().boundOutputRoot(),request.transactionId(),base.baselineId(),candidate.baselineId(),digest);
            return new ProjectChangeApplyResult.RecoveryRequired(handle,publication(handle),diagnostics);
        }
    }
    public ProjectChangeRecoveryResult recover(ProjectRecoveryHandle handle) {
        Objects.requireNonNull(handle);var diagnostics=new ArrayList<ExecutionDiagnostic>();
        try {
            var paths=StateRootPathGuard.validate(handle.stateRoot(),handle.outputRoot(),true);appendPaths(diagnostics,paths.errors());
            if(diagnostics.stream().anyMatch(ExecutionDiagnostic::isError))return new ProjectChangeRecoveryResult.Failure(handle,diagnostics);
            var lock=new StateRootLock(handle.stateRoot()).tryAcquireExisting();if(!(lock instanceof StateRootLock.HeldLock held))throw new IOException("existing state lock unavailable: "+((StateRootLock.LockFailure)lock).message());
            try(held;var state=new SecureFileAccess(handle.stateRoot());var output=new SecureFileAccess(handle.outputRoot())) {
                var transaction=new ProjectUpdateTransaction(state,output,handle.transactionId(),checkpoint);String prefix=ProjectUpdateTransaction.ACTIVE+"/"+handle.transactionId();boolean active=state.exists(prefix);
                var binding=active?ProjectUpdateTransaction.loadBinding(state,handle.transactionId()):ProjectUpdateReceipt.read(state,handle.transactionId()).binding();
                require(binding.publication().b0().equals(handle.baselineId()) && binding.publication().b1().equals(handle.candidateId()),"recovery handle baseline/candidate differ");
                boolean published=transaction.recover(handle.bindingDigest());String id=published?handle.candidateId():handle.baselineId();var current=new ProjectBaselineStore(state).load(id);
                var outcome=active?(published?ProjectChangeRecoveryResult.Outcome.COMMITTED_AND_CLEANED:ProjectChangeRecoveryResult.Outcome.ROLLED_BACK):ProjectChangeRecoveryResult.Outcome.ALREADY_CLEAN;
                return new ProjectChangeRecoveryResult.Recovered(outcome,receipt(current),diagnostics);
            }
        } catch(IOException|RuntimeException e) {diagnostics.add(error("RECOVER-001",ExecutionStage.ROLLBACK,"project recovery retained unresolved evidence: "+e.getMessage()));return new ProjectChangeRecoveryResult.Failure(handle,diagnostics);}
    }
    private static ProjectChangeApplyResult.Publication publication(ProjectRecoveryHandle handle) {
        try(var state=new SecureFileAccess(handle.stateRoot())) {
            var store=new ProjectBaselineStore(state);var current=store.current();if(current.isEmpty())return ProjectChangeApplyResult.Publication.UNKNOWN;
            store.verifyPointerIdentity(current.get(),false);
            if(current.get().equals(handle.candidateId()))return ProjectChangeApplyResult.Publication.PUBLISHED;
            if(current.get().equals(handle.baselineId()))return ProjectChangeApplyResult.Publication.NOT_PUBLISHED;
        }catch(IOException|RuntimeException ignored) {}return ProjectChangeApplyResult.Publication.UNKNOWN;
    }
    private static ProjectBaselineReceipt receipt(Bundle b) {var d=b.descriptor();return new ProjectBaselineReceipt(b.baselineId(),d.outputRoot(),d.entry(),d.sourceSetSha(),d.graphDigest(),d.manifestDigest());}
    private static void appendPaths(List<ExecutionDiagnostic> to,List<ChangeExecutionDiagnostic> from) {from.stream().map(d->new ExecutionDiagnostic(d.code(),ExecutionStage.PREFLIGHT,d.severity(),d.message(),Optional.empty(),Optional.empty(),d.relativePath())).forEach(to::add);}
    private static ExecutionStage stage(ProjectChangeStage s) {return s==ProjectChangeStage.PLAN?ExecutionStage.PREFLIGHT:ExecutionStage.valueOf(s.name());}
    private static ExecutionDiagnostic convert(ProjectChangeDiagnostic d) {return new ExecutionDiagnostic(d.code(),stage(d.stage()),d.severity(),d.message(),d.sourceSpan(),d.artifactId(),d.relativePath());}
    private static ExecutionDiagnostic error(String suffix,ExecutionStage stage,String message) {return ExecutionDiagnostic.error("SIR-APP-PROJECT-EXECUTE-"+suffix,stage,message);}
    private static ProjectChangeApplyResult.Failure failure(List<ExecutionDiagnostic> diagnostics,String suffix,ExecutionStage stage,String message) {diagnostics.add(error(suffix,stage,message));return new ProjectChangeApplyResult.Failure(stage,diagnostics);}
}
