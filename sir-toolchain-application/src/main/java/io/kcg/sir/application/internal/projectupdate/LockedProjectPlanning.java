package io.kcg.sir.application.internal.projectupdate;

import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.projectbaseline.*;
import io.kcg.sir.change.api.*;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.semantic.model.NormalizedCapability;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Shared full reconstruction. Caller must hold the existing state lock for this call and its subsequent use. */
public final class LockedProjectPlanning {
    private LockedProjectPlanning() {}
    public sealed interface Result {}
    public record Ready(ProjectChangeContext context,Optional<ProjectChangePlanningResult> decision,
                        ProjectBaselineVerification.Verified base,ProjectBaselineVerification.Verified candidate,
                        List<ProjectChangeDiagnostic> diagnostics) implements Result {public Ready {diagnostics=List.copyOf(diagnostics);}}
    public record Rejected(ProjectChangeStage stage,List<ProjectChangeDiagnostic> diagnostics) implements Result {public Rejected {diagnostics=List.copyOf(diagnostics);}}
    public static Result reconstruct(ProjectChangeContextRequest request,ProjectChangePlanningRequest planning,ProjectChangePlanningResult.Planned expected,
                                     SecureFileAccess state,SecureFileAccess output,Consumer<String> checkpoint,List<ProjectChangeDiagnostic> diagnostics) throws IOException {
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
        var context=describe(request.stateRoot(),base,candidate);var targets=context.targets();
        // Force bounded canonical encoding before returning any evidence-bearing result.
        context.contextId();checkpoint.accept("context-bound");
        if(planning!=null && !context.equals(planning.context()))return reject(diagnostics,"STALE-001",ProjectChangeStage.PREFLIGHT,"context no longer equals independently recompiled baseline/candidate/target catalog");
        ProjectChangePlanningResult result=null;
        if(planning!=null) {
            var selected=targets.stream().filter(t->context.targetKey(t).equals(planning.targetKey())).findFirst().orElse(null);
            if(selected==null)return reject(diagnostics,"TARGET-001",ProjectChangeStage.PLAN,"unknown or stale baseline workflow target key");
            var input=input(context,base,candidate,selected.target());
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
        return new Ready(context,Optional.ofNullable(result),base,candidate,diagnostics);
    }
    /** Pure assembly also used to authenticate a recovery binding against saved source-byte replay. */
    public static ProjectChangeContext describe(Path stateRoot,ProjectBaselineVerification.Verified base,ProjectBaselineVerification.Verified candidate) {
        var b=base.bundle();var d=b.descriptor();var receipt=new ProjectBaselineReceipt(b.baselineId(),d.outputRoot(),d.entry(),d.sourceSetSha(),d.graphDigest(),d.manifestDigest());
        var targets=base.compilation().semanticModel().declarations().stream().filter(v->v instanceof NormalizedCapability).map(v->(NormalizedCapability)v)
            .map(cap->new ProjectWorkflowTarget(new ChangeTarget(cap.id(),cap.sourceNodeId(),cap.workflow().sourceNodeId()),cap.name(),cap.span(),cap.workflow().span()))
            .sorted(Comparator.comparing(t->t.target().declarationSymbol().value())).toList();
        return new ProjectChangeContext(1,stateRoot,receipt,revision(b),revision(candidate.bundle()),targets);
    }
    public static ProjectChangePlanningInput input(ProjectChangeContext context,ProjectBaselineVerification.Verified base,ProjectBaselineVerification.Verified candidate,ChangeTarget target) {
        return new ProjectChangePlanningInput(context.basedOn(),context.candidate(),base.compilation().semanticModel(),candidate.compilation().semanticModel(),base.bundle().graph(),candidate.bundle().graph(),new ModifyCapabilityWorkflow(target));
    }
    private static ProjectChangeRevision revision(ProjectBaselineCodec.Bundle bundle) {return new ProjectChangeRevision(1,bundle.sources().manifest(),bundle.graph().version(),bundle.graph().canonicalDigest(),ProjectGraphCanonicalFormatVersion.V2);}
    private static void append(List<ProjectChangeDiagnostic> to,List<ExecutionDiagnostic> from) {from.stream().map(ProjectChangeDiagnostic::from).forEach(to::add);}
    private static Rejected reject(List<ProjectChangeDiagnostic> diagnostics,String suffix,ProjectChangeStage stage,String message) {diagnostics.add(ProjectChangeDiagnostic.error("SIR-APP-PROJECT-CHANGE-"+suffix,stage,message));return rejected(diagnostics);}
    private static Rejected rejected(List<ProjectChangeDiagnostic> diagnostics) {var error=diagnostics.stream().filter(ProjectChangeDiagnostic::isError).reduce((a,b)->b).orElseThrow();return new Rejected(error.stage(),diagnostics);}
}
