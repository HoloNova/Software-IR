package io.kcg.sir.projectgraph.api;

import java.util.Comparator;
import java.util.Objects;

public record GraphEdgeId(GraphEdgeKind kind, GraphNodeId source, GraphNodeId target) implements Comparable<GraphEdgeId> {
   public GraphEdgeId {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(target, "target");
   }

   public int compareTo(GraphEdgeId other) {
      return Comparator.<GraphEdgeId, String>comparing(id -> id.kind().name())
         .thenComparing(id -> id.source().canonicalKey())
         .thenComparing(id -> id.target().canonicalKey())
         .compare(this, other);
   }

   public String canonicalKey() {
      return this.kind.name() + "|" + this.source().canonicalKey() + "|" + this.target().canonicalKey();
   }
}
