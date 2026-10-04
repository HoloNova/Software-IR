package io.kcg.sir.projectgraph.api;

import io.kcg.sir.projectgraph.internal.GraphCanonicalForm;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class ProjectGraphBuilder {
   public ProjectGraphAnalysis build(ProjectGraphInput input) {
      Objects.requireNonNull(input, "input");
      ProjectGraphValidator validator = new ProjectGraphValidator();
      List<ProjectGraphDiagnostic> inputDiagnostics = validator.validateInput(input);
      if (inputDiagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return new ProjectGraphAnalysis.Failure(inputDiagnostics);
      }

      List<ProjectGraphNode> nodes = new ArrayList<>();
      List<ProjectGraphEdge> edges = new ArrayList<>();
      GraphNodeId.ProjectNodeId projectId = GraphNodeId.ProjectNodeId.INSTANCE;
      GraphProvenance.ProjectProvenance projectProv = new GraphProvenance.ProjectProvenance(input.sourceId(), input.sourceSet());
      nodes.add(new ProjectGraphNode.Project(projectId, projectProv, input.projectDisplayName()));

      for (ProjectGraphInput.SemanticDeclarationInput sdi : input.semanticDeclarations()) {
         GraphNodeId.Semantic sid = new GraphNodeId.Semantic(sdi.symbolId());
         GraphProvenance.SemanticProvenance prov = new GraphProvenance.SemanticProvenance(
            input.version() == GraphVersion.V0_2 ? sdi.span().source() : input.sourceId(), sdi.sourceNodeId(), sdi.span());
         nodes.add(new ProjectGraphNode.SemanticDeclaration(sid, prov, sdi.kind(), sdi.displayName()));
      }

      for (ProjectGraphInput.LoweredDeclarationInput ldi : input.loweredDeclarations()) {
         GraphNodeId.Lowered lid = new GraphNodeId.Lowered(ldi.nodeId());
         GraphProvenance.LoweredProvenance prov = new GraphProvenance.LoweredProvenance(
            input.version() == GraphVersion.V0_2 ? ldi.origin().span().source() : input.sourceId(), ldi.origin());
         nodes.add(new ProjectGraphNode.LoweredDeclaration(lid, prov, ldi.sourceSymbol(), ldi.displayName()));
      }

      for (ProjectGraphInput.ArtifactInput ai : input.artifacts()) {
         GraphNodeId.Lowered aid = new GraphNodeId.Lowered(ai.artifactId());
         GraphProvenance.ArtifactProvenance prov = new GraphProvenance.ArtifactProvenance(
            input.version() == GraphVersion.V0_2 && ai.ownerSymbol().isPresent() ? ai.origin().span().source() : input.sourceId(),
            ai.origin(), ai.ownerSymbol(), ai.role(), ai.qualifiedName()
         );
         nodes.add(new ProjectGraphNode.Artifact(aid, prov, ai.role(), ai.qualifiedName()));
      }

      for (ProjectGraphInput.FileInput fi : input.files()) {
         GraphNodeId.File fid = new GraphNodeId.File(fi.relativePath());
         GraphProvenance.FileProvenance prov = new GraphProvenance.FileProvenance(
            input.version() == GraphVersion.V0_2 ? sourceForArtifact(input, fi) : input.sourceId(),
            fi.artifactId(), fi.ownerSymbol(), fi.byteCount(), fi.sha256Hex()
         );
         nodes.add(new ProjectGraphNode.ProjectFile(fid, prov));
      }

      for (ProjectGraphInput.EdgeBinding eb : input.edges()) {
         edges.add(new ProjectGraphEdge(eb.kind(), eb.source(), eb.target()));
      }

      nodes.sort(ProjectGraphCanonicalizer.nodeComparator());
      edges.sort(Comparator.comparing(ProjectGraphEdge::id));
      List<ProjectGraphDiagnostic> diagnostics = validator.validate(input.version(), nodes, edges);
      if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return new ProjectGraphAnalysis.Failure(diagnostics);
      }

      String digest = GraphCanonicalForm.digest(input.version(), nodes, edges);
      ProjectGraph graph = new ProjectGraph(input.version(), nodes, edges, digest);
      return new ProjectGraphAnalysis.Success(graph, diagnostics);
   }

   private static io.kcg.sir.source.SourceId sourceForArtifact(ProjectGraphInput input, ProjectGraphInput.FileInput file) {
      return input.artifacts().stream().filter(a -> a.artifactId().equals(file.artifactId())).findFirst()
         .map(a -> a.ownerSymbol().isPresent() ? a.origin().span().source() : input.sourceId()).orElse(input.sourceId());
   }
}
