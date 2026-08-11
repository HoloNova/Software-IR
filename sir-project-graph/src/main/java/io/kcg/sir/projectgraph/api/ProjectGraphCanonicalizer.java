package io.kcg.sir.projectgraph.api;

import io.kcg.sir.projectgraph.internal.GraphCanonicalForm;
import java.util.Comparator;
import java.util.List;

public final class ProjectGraphCanonicalizer {
   public String digest(GraphVersion version, List<ProjectGraphNode> nodes, List<ProjectGraphEdge> edges) {
      return GraphCanonicalForm.digest(version, nodes, edges);
   }

   public static Comparator<ProjectGraphNode> nodeComparator() {
      return ProjectGraphCanonicalizer.NodeComparatorHolder.INSTANCE;
   }

   private static int rank(ProjectGraphNode node) {
      return switch (node) {
         case ProjectGraphNode.Project p -> 0;
         case ProjectGraphNode.SemanticDeclaration s -> 1;
         case ProjectGraphNode.LoweredDeclaration l -> 2;
         case ProjectGraphNode.Artifact a -> 3;
         case ProjectGraphNode.ProjectFile f -> 4;
         default -> throw new MatchException(null, null);
      };
   }

   private static final class NodeComparatorHolder {
      private static final Comparator<ProjectGraphNode> INSTANCE = Comparator.comparingInt(ProjectGraphCanonicalizer::rank)
         .thenComparing(n -> n.id().canonicalKey());
   }
}
