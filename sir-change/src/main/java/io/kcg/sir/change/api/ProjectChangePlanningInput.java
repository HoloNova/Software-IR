package io.kcg.sir.change.api;

import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import java.util.Objects;

/** Pure input. Application independently replays source bytes; this API verifies their model/graph evidence. */
public record ProjectChangePlanningInput(ProjectChangeRevision basedOn, ProjectChangeRevision candidate,
        NormalizedSemanticModel baseSemanticModel, NormalizedSemanticModel candidateSemanticModel,
        ProjectGraph baseGraph, ProjectGraph candidateGraph, ModifyCapabilityWorkflow operation) {
    public ProjectChangePlanningInput {
        Objects.requireNonNull(basedOn);Objects.requireNonNull(candidate);Objects.requireNonNull(baseSemanticModel);Objects.requireNonNull(candidateSemanticModel);
        Objects.requireNonNull(baseGraph);Objects.requireNonNull(candidateGraph);Objects.requireNonNull(operation);
    }
}
