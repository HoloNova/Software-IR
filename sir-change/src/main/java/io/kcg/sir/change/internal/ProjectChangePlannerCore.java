package io.kcg.sir.change.internal;

import io.kcg.sir.change.api.*;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceId;
import java.util.*;

/** New revision admission around the same workflow comparison used by the legacy planner. No I/O. */
public final class ProjectChangePlannerCore {
    private ProjectChangePlannerCore() {}
    public static ProjectChangeAnalysis plan(ProjectChangePlanningInput input) {
        var diagnostics=new ArrayList<ChangeDiagnostic>();
        check(input.basedOn(),input.baseGraph(),input.baseSemanticModel(),"base",diagnostics);
        check(input.candidate(),input.candidateGraph(),input.candidateSemanticModel(),"candidate",diagnostics);
        if(!diagnostics.isEmpty())return new ProjectChangeAnalysis.Failure(List.copyOf(diagnostics));
        var base=input.basedOn().sources();var candidate=input.candidate().sources();
        if(!base.entry().equals(candidate.entry()) || !base.files().stream().map(f->f.sourceId()).toList().equals(candidate.files().stream().map(f->f.sourceId()).toList()))
            return failure("SIR-PROJECT-CHANGE-SOURCE-001",ChangeDiagnosticStage.SCOPE,"source entry/member paths changed");
        var old=new LinkedHashMap<SymbolId,NormalizedDeclaration>();input.baseSemanticModel().declarations().forEach(d->old.put(d.id(),d));
        for(var d:input.candidateSemanticModel().declarations()) {
            var b=old.get(d.id());
            if(b!=null && (!b.span().source().equals(d.span().source()) || !b.sourceNodeId().equals(d.sourceNodeId())))
                return failure("SIR-PROJECT-CHANGE-SOURCE-001",ChangeDiagnosticStage.SCOPE,"declaration moved or structural identity changed: "+d.id().value());
        }
        if(!sourceOrder(input.baseSemanticModel()).equals(sourceOrder(input.candidateSemanticModel())))
            return failure("SIR-PROJECT-CHANGE-SOURCE-001",ChangeDiagnosticStage.SCOPE,"declaration order/member placement changed");
        var decision=PlannerCore.compareWorkflow(new WorkflowInput(input.baseSemanticModel(),input.candidateSemanticModel(),input.baseGraph(),input.candidateGraph()),input.operation().target(),diagnostics);
        if(decision instanceof WorkflowDecision.Failure f)return new ProjectChangeAnalysis.Failure(f.diagnostics());
        if(decision instanceof WorkflowDecision.NoChanges n)return new ProjectChangeAnalysis.NoChanges(input.basedOn(),input.candidate(),n.reason(),n.diagnostics());
        var p=(WorkflowDecision.Planned)decision;
        return new ProjectChangeAnalysis.Planned(new ProjectChangePlan(1,input.basedOn(),input.candidate(),input.operation(),p.artifacts(),p.files()),p.diagnostics());
    }
    private static Map<SourceId,List<SymbolId>> sourceOrder(NormalizedSemanticModel model) {
        var result=new TreeMap<SourceId,List<SymbolId>>(Comparator.comparing(SourceId::value));
        model.declarations().stream().sorted(Comparator.comparing((NormalizedDeclaration d)->d.span().source().value()).thenComparingInt(d->d.span().start().codePointOffset()))
            .forEach(d->result.computeIfAbsent(d.span().source(),s->new ArrayList<>()).add(d.id()));return result;
    }
    private static void check(ProjectChangeRevision revision,ProjectGraph graph,NormalizedSemanticModel model,String side,List<ChangeDiagnostic> diagnostics) {
        if(revision.formatVersion()!=1 || revision.graphVersion()!=GraphVersion.V0_2 || graph.version()!=GraphVersion.V0_2 || revision.snapshotFormatVersion()!=ProjectGraphCanonicalFormatVersion.V2) {
            error(diagnostics,"SIR-PROJECT-CHANGE-COMPAT-001",ChangeDiagnosticStage.COMPAT,side+" supports only project revision 1 / Graph V0_2 / Snapshot V2");return;
        }
        var validation=new ProjectGraphValidator().validate(graph.version(),graph.nodes(),graph.edges());
        if(validation.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
            error(diagnostics,"SIR-PROJECT-CHANGE-SOURCE-001",ChangeDiagnosticStage.COMPAT,side+" invalid graph: "+validation);return;
        }
        var root=graph.node(GraphNodeId.ProjectNodeId.INSTANCE).filter(n->n instanceof ProjectGraphNode.Project).map(n->(ProjectGraphNode.Project)n).orElse(null);
        if(root==null || !root.provenance().sourceSet().equals(Optional.of(revision.sources())) || !revision.graphCanonicalDigest().equals(graph.canonicalDigest())) {
            error(diagnostics,"SIR-PROJECT-CHANGE-SOURCE-001",ChangeDiagnosticStage.COMPAT,side+" graph does not match complete source/revision evidence");return;
        }
        if(!root.displayName().equals(model.metadata().displayName())) {
            error(diagnostics,"SIR-PROJECT-CHANGE-MODEL-001",ChangeDiagnosticStage.TARGET,side+" model metadata displayName differs from graph root");return;
        }
        var encoded=new ProjectGraphSerializer().serialize(graph,ProjectGraphCanonicalFormatVersion.V2);
        if(!(encoded instanceof ProjectGraphSerialization.Success ok)) {
            error(diagnostics,"SIR-PROJECT-CHANGE-SOURCE-001",ChangeDiagnosticStage.COMPAT,side+" graph cannot be canonically encoded");return;
        }
        var decoded=new ProjectGraphLoader().load(ok.document().bytes());
        if(!(decoded instanceof ProjectGraphAnalysis.Success loaded) || !loaded.graph().canonicalDigest().equals(graph.canonicalDigest())) {
            error(diagnostics,"SIR-PROJECT-CHANGE-SOURCE-001",ChangeDiagnosticStage.COMPAT,side+" graph digest does not match canonical reconstruction");return;
        }
        var semantic=new HashMap<SymbolId,ProjectGraphNode.SemanticDeclaration>();
        graph.nodes().stream().filter(n->n instanceof ProjectGraphNode.SemanticDeclaration).map(n->(ProjectGraphNode.SemanticDeclaration)n).forEach(n->semantic.put(n.id().symbolId(),n));
        if(semantic.size()!=model.declarations().size()) { error(diagnostics,"SIR-PROJECT-CHANGE-MODEL-001",ChangeDiagnosticStage.TARGET,side+" model/graph declaration count differs");return; }
        var seen=new HashSet<SymbolId>();
        for(var d:model.declarations()) {
            var n=semantic.get(d.id());
            if(!seen.add(d.id()) || n==null || !n.displayName().equals(d.name()) || !n.provenance().sourceNodeId().equals(d.sourceNodeId()) || !n.provenance().span().equals(d.span()) || !n.kind().name().equals(kind(d))) {
                error(diagnostics,"SIR-PROJECT-CHANGE-MODEL-001",ChangeDiagnosticStage.TARGET,side+" model/graph declaration identity or location differs: "+d.id().value());return;
            }
        }
    }
    private static String kind(NormalizedDeclaration d) {
        return switch(d) {
            case io.kcg.sir.semantic.model.NormalizedEnum e -> "ENUM";
            case io.kcg.sir.semantic.model.NormalizedEntity e -> "ENTITY";
            case io.kcg.sir.semantic.model.NormalizedInput e -> "INPUT";
            case io.kcg.sir.semantic.model.NormalizedView e -> "VIEW";
            case io.kcg.sir.semantic.model.NormalizedError e -> "ERROR";
            case io.kcg.sir.semantic.model.NormalizedCapability e -> "CAPABILITY";
        };
    }
    private static void error(List<ChangeDiagnostic> out,String code,ChangeDiagnosticStage stage,String message) { out.add(ChangeDiagnostic.error(code,stage,0,message)); }
    private static ProjectChangeAnalysis.Failure failure(String code,ChangeDiagnosticStage stage,String message) { return new ProjectChangeAnalysis.Failure(List.of(ChangeDiagnostic.error(code,stage,0,message))); }
}
