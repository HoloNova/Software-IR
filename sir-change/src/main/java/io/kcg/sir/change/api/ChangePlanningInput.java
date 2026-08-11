package io.kcg.sir.change.api;

import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import java.util.Objects;

public record ChangePlanningInput(
   NormalizedSemanticModel baseSemanticModel,
   NormalizedSemanticModel candidateSemanticModel,
   ProjectGraph baseGraph,
   ProjectGraph candidateGraph,
   ChangeSet changeSet
) {
   public ChangePlanningInput {
      Objects.requireNonNull(baseSemanticModel, "baseSemanticModel");
      Objects.requireNonNull(candidateSemanticModel, "candidateSemanticModel");
      Objects.requireNonNull(baseGraph, "baseGraph");
      Objects.requireNonNull(candidateGraph, "candidateGraph");
      Objects.requireNonNull(changeSet, "changeSet");
   }
}
