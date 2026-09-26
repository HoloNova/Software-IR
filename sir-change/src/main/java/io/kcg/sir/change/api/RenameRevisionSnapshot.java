package io.kcg.sir.change.api;

import io.kcg.sir.projectgraph.api.ProjectGraph;
import java.util.Objects;

/**
 * One side of a rename: the project graph and the source bytes that produced it.
 *
 * <p>Both halves are needed because they move independently. A plan is only valid for the revision it
 * was computed from, and a revision is a source plus its graph — not a graph on its own.
 */
public record RenameRevisionSnapshot(ProjectGraph graph, RenameSourceSnapshot source) {
   public RenameRevisionSnapshot {
      Objects.requireNonNull(graph, "graph");
      Objects.requireNonNull(source, "source");
   }
}
