package io.kcg.sir.projectgraph.api;

import io.kcg.sir.projectgraph.internal.SnapshotEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ProjectGraphSerializer {
   public ProjectGraphSerialization serialize(ProjectGraph graph) {
      Objects.requireNonNull(graph, "graph");
      List<ProjectGraphDiagnostic> diagnostics = new ArrayList<>();
      SnapshotEncoder.EncodeResult result = SnapshotEncoder.encode(graph, diagnostics);
      if (result == null) {
         if (diagnostics.stream().noneMatch(ProjectGraphDiagnostic::isError)) {
            diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-SERIALIZE-001", "serialization failed without a specific diagnostic"));
         }

         return new ProjectGraphSerialization.Failure(diagnostics);
      } else {
         CanonicalProjectGraphDocument document = new CanonicalProjectGraphDocument(result.documentBytes);
         return new ProjectGraphSerialization.Success(document, diagnostics);
      }
   }
}
