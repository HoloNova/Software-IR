package io.kcg.sir.application.api;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import io.kcg.sir.application.internal.projectbaseline.*;
import io.kcg.sir.application.internal.state.*;
import io.kcg.sir.change.api.*;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.semantic.model.NormalizedCapability;
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
                var compilationDiagnostics=new ArrayList<ExecutionDiagnostic>();
                var base=ProjectBaselineVerification.read(state,request.expectedBaselineId(),request.outputRoot(),compilationDiagnostics);
                append(diagnostics,compilationDiagnostics);if(base==null)return rejected(diagnostics);
                checkpoint.accept("baseline-verified");
                var b=base.bundle();var sources=b.sources().manifest();var candidateSources=request.candidate().manifest();
                if(!sources.entry().equals(candidateSources.entry()) || !sources.files().stream().map(f->f.sourceId()).toList().equals(candidateSources.files().stream().map(f->f.sourceId()).toList()))
                    return reject(diagnostics,"SOURCE-001",ProjectChangeStage.PREFLIGHT,"candidate source entry/member paths must equal baseline");
                compilationDiagnostics.clear();var candidate=ProjectBaselineVerification.compile(request.candidate(),request.outputRoot(),compilationDiagnostics);
                append(diagnostics,compilationDiagnostics);if(candidate==null)return rejected(diagnostics);
                checkpoint.accept("candidate-compiled");
                var descriptor=b.descriptor();var receipt=new ProjectBaselineReceipt(b.baselineId(),descriptor.outputRoot(),descriptor.entry(),descriptor.sourceSetSha(),descriptor.graphDigest(),descriptor.manifestDigest());
                var baseRevision=revision(b);var candidateRevision=revision(candidate.bundle());
                var targets=base.compilation().semanticModel().declarations().stream().filter(d->d instanceof NormalizedCapability).map(d->(NormalizedCapability)d)
                    .map(cap->new ProjectWorkflowTarget(new ChangeTarget(cap.id(),cap.sourceNodeId(),cap.workflow().sourceNodeId()),cap.name(),cap.span(),cap.workflow().span()))
                    .sorted(Comparator.comparing(t->t.target().declarationSymbol().value())).toList();
                var context=new ProjectChangeContext(1,request.stateRoot(),receipt,baseRevision,candidateRevision,targets);
                // Force bounded canonical encoding before returning any evidence-bearing result.
                String contextId=context.contextId();checkpoint.accept("context-bound");
                if(planning!=null && !context.equals(planning.context()))return reject(diagnostics,"STALE-001",ProjectChangeStage.PREFLIGHT,"context no longer equals independently recompiled baseline/candidate/target catalog");
                ProjectChangePlanningResult result=null;
                if(planning!=null) {
                    var selected=targets.stream().filter(t->context.targetKey(t).equals(planning.targetKey())).findFirst().orElse(null);
                    if(selected==null)return reject(diagnostics,"TARGET-001",ProjectChangeStage.PLAN,"unknown or stale baseline workflow target key");
                    var input=new ProjectChangePlanningInput(baseRevision,candidateRevision,base.compilation().semanticModel(),candidate.compilation().semanticModel(),b.graph(),candidate.bundle().graph(),new ModifyCapabilityWorkflow(selected.target()));
                    var analysis=new ProjectChangePlanner().plan(input);
                    for(var d:analysis.diagnostics())diagnostics.add(new ProjectChangeDiagnostic(d.code(),ProjectChangeStage.PLAN,ExecutionSeverity.valueOf(d.severity().name()),d.message(),
                        d.sourceSpan().or(()->Optional.of(selected.workflowSpan())),d.artifactId(),d.relativePath(),Optional.of(d.stage())));
                    if(analysis instanceof ProjectChangeAnalysis.Failure)return rejected(diagnostics);
                    if(analysis instanceof ProjectChangeAnalysis.Planned p) {
                        var value=new ProjectChangePlanningResult.Planned(context,p.plan(),List.copyOf(diagnostics));value.sha256Hex();result=value;
                    } else result=new ProjectChangePlanningResult.NoChanges(context,((ProjectChangeAnalysis.NoChanges)analysis).reason(),List.copyOf(diagnostics));
                    if(expected!=null && (!(result instanceof ProjectChangePlanningResult.Planned p) || !p.context().equals(expected.context()) || !p.plan().equals(expected.plan())))
                        return reject(diagnostics,"VERIFY-001",ProjectChangeStage.PLAN,"expected plan differs from complete independent context/plan reconstruction");
                    checkpoint.accept("planned");
                }
                checkpoint.accept("before-return");ProjectBaselineVerification.revalidate(state,b);output.assertRootUnchanged();
                return planning==null?new Loaded(context,List.copyOf(diagnostics)):new Planned(result);
            }
        } catch(Problem e) {
            diagnostics.add(ProjectChangeDiagnostic.error(e.code.replace("PROJECT-BASELINE","PROJECT-CHANGE"),ProjectChangeStage.READ,e.getMessage()));return rejected(diagnostics);
        } catch(IOException|RuntimeException e) {
            return reject(diagnostics,"READ-001",ProjectChangeStage.READ,"project planning refused without disk mutation: "+e.getMessage());
        }
    }
    private static ProjectChangeRevision revision(Bundle bundle) { return new ProjectChangeRevision(1,bundle.sources().manifest(),bundle.graph().version(),bundle.graph().canonicalDigest(),ProjectGraphCanonicalFormatVersion.V2); }
    private static void append(List<ProjectChangeDiagnostic> to,List<ExecutionDiagnostic> from) { from.stream().map(ProjectChangeDiagnostic::from).forEach(to::add); }
    private static boolean hasErrors(List<ProjectChangeDiagnostic> diagnostics) { return diagnostics.stream().anyMatch(ProjectChangeDiagnostic::isError); }
    private static Rejected reject(List<ProjectChangeDiagnostic> diagnostics,String suffix,ProjectChangeStage stage,String message) {
        diagnostics.add(ProjectChangeDiagnostic.error("SIR-APP-PROJECT-CHANGE-"+suffix,stage,message));return rejected(diagnostics);
    }
    private static Rejected rejected(List<ProjectChangeDiagnostic> diagnostics) {
        var error=diagnostics.stream().filter(ProjectChangeDiagnostic::isError).reduce((a,b)->b).orElseThrow();return new Rejected(error.stage(),List.copyOf(diagnostics));
    }
}
