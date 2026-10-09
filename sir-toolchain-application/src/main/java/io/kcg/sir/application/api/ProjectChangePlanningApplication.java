package io.kcg.sir.application.api;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import io.kcg.sir.application.internal.projectbaseline.*;
import io.kcg.sir.application.internal.state.*;
import io.kcg.sir.change.api.*;
import io.kcg.sir.application.internal.projectupdate.LockedProjectPlanning;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/** Whole-project context and UPDATE-only planning. No publication, cleanup, sourceRoot access or apply. */
public final class ProjectChangePlanningApplication {
    private final Consumer<String> checkpoint;
    public ProjectChangePlanningApplication() { this(ignored->{}); }
    ProjectChangePlanningApplication(Consumer<String> checkpoint) { this.checkpoint=Objects.requireNonNull(checkpoint); }

    public ProjectChangeContextResult context(ProjectChangeContextRequest request) {
        Objects.requireNonNull(request);var result=execute(request,null,null);
        if(result instanceof Loaded l)return new ProjectChangeContextResult.Success(l.context(),l.diagnostics());
        var failure=(Rejected)result;return new ProjectChangeContextResult.Failure(failure.stage(),failure.diagnostics());
    }
    public ProjectChangePlanningResult plan(ProjectChangePlanningRequest request) {
        Objects.requireNonNull(request);var c=request.context();
        var result=execute(new ProjectChangeContextRequest(c.stateRoot(),c.baseline().boundOutputRoot(),c.baseline().baselineId(),request.candidate()),request,null);
        if(result instanceof Planned p)return p.result();
        var f=(Rejected)result;return new ProjectChangePlanningResult.Failure(f.stage(),f.diagnostics());
    }
    public ProjectChangePlanningResult verify(ProjectChangePlanningRequest request,ProjectChangePlanningResult.Planned expected) {
        Objects.requireNonNull(request);Objects.requireNonNull(expected);var c=request.context();
        var result=execute(new ProjectChangeContextRequest(c.stateRoot(),c.baseline().boundOutputRoot(),c.baseline().baselineId(),request.candidate()),request,expected);
        if(result instanceof Planned p)return p.result();
        var f=(Rejected)result;return new ProjectChangePlanningResult.Failure(f.stage(),f.diagnostics());
    }
    private sealed interface Result {}
    private record Loaded(ProjectChangeContext context,List<ProjectChangeDiagnostic> diagnostics) implements Result {}
    private record Planned(ProjectChangePlanningResult result) implements Result {}
    private record Rejected(ProjectChangeStage stage,List<ProjectChangeDiagnostic> diagnostics) implements Result {}

    private Result execute(ProjectChangeContextRequest request,ProjectChangePlanningRequest planning,ProjectChangePlanningResult.Planned expected) {
        var diagnostics=new ArrayList<ProjectChangeDiagnostic>();
        try {
            hex(request.expectedBaselineId());
            if(!request.stateRoot().isAbsolute() || !request.stateRoot().equals(request.stateRoot().normalize()) || !request.outputRoot().isAbsolute() || !request.outputRoot().equals(request.outputRoot().normalize()))
                return reject(diagnostics,"REQUEST-001",ProjectChangeStage.PREFLIGHT,"state/output roots must be absolute normalized");
            var paths=StateRootPathGuard.validate(request.stateRoot(),request.outputRoot(),true);
            for(var d:paths.errors())diagnostics.add(ProjectChangeDiagnostic.error(d.code(),ProjectChangeStage.PREFLIGHT,d.message()));
            if(hasErrors(diagnostics))return rejected(diagnostics);
            if(planning!=null && (planning.context().formatVersion()!=1 || !planning.context().candidate().sources().equals(request.candidate().manifest())))
                return reject(diagnostics,"STALE-001",ProjectChangeStage.PREFLIGHT,"unsupported context or candidate source evidence changed");
            var lock=new StateRootLock(request.stateRoot()).tryAcquireExisting();
            if(!(lock instanceof StateRootLock.HeldLock held)) {
                var failure=(StateRootLock.LockFailure)lock;return reject(diagnostics,"LOCK-001",ProjectChangeStage.PREFLIGHT,failure.code()+": "+failure.message());
            }
            try(held;var state=new SecureFileAccess(request.stateRoot());var output=new SecureFileAccess(request.outputRoot())) {
                var reconstructed=LockedProjectPlanning.reconstruct(request,planning,expected,state,output,checkpoint,diagnostics);
                if(reconstructed instanceof LockedProjectPlanning.Rejected failure)return new Rejected(failure.stage(),failure.diagnostics());
                var ready=(LockedProjectPlanning.Ready)reconstructed;
                return planning==null?new Loaded(ready.context(),ready.diagnostics()):new Planned(ready.decision().orElseThrow());
            }
        } catch(Problem e) {
            diagnostics.add(ProjectChangeDiagnostic.error(e.code.replace("PROJECT-BASELINE","PROJECT-CHANGE"),ProjectChangeStage.READ,e.getMessage()));return rejected(diagnostics);
        } catch(IOException|RuntimeException e) {
            return reject(diagnostics,"READ-001",ProjectChangeStage.READ,"project planning refused without disk mutation: "+e.getMessage());
        }
    }
    private static boolean hasErrors(List<ProjectChangeDiagnostic> diagnostics) { return diagnostics.stream().anyMatch(ProjectChangeDiagnostic::isError); }
    private static Rejected reject(List<ProjectChangeDiagnostic> diagnostics,String suffix,ProjectChangeStage stage,String message) {
        diagnostics.add(ProjectChangeDiagnostic.error("SIR-APP-PROJECT-CHANGE-"+suffix,stage,message));return rejected(diagnostics);
    }
    private static Rejected rejected(List<ProjectChangeDiagnostic> diagnostics) {
        var error=diagnostics.stream().filter(ProjectChangeDiagnostic::isError).reduce((a,b)->b).orElseThrow();return new Rejected(error.stage(),List.copyOf(diagnostics));
    }
}
