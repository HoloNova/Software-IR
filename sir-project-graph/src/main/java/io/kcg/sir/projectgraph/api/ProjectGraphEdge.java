package io.kcg.sir.projectgraph.api;

import java.util.Objects;

public record ProjectGraphEdge(GraphEdgeId id) {
   public ProjectGraphEdge {
      Objects.requireNonNull(id, "id");
   }

   public ProjectGraphEdge(GraphEdgeKind kind, GraphNodeId source, GraphNodeId target) {
      this(new GraphEdgeId(kind, source, target));
   }

   public GraphEdgeKind kind() {
      return this.id.kind();
   }

   public GraphNodeId source() {
      return this.id.source();
   }

   public GraphNodeId target() {
      return this.id.target();
   }
}
