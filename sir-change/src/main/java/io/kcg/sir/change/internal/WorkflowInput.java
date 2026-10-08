package io.kcg.sir.change.internal;

import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.projectgraph.api.ProjectGraph;

/** Already admitted models/graphs; deliberately contains no revision or source-file fiction. */
record WorkflowInput(NormalizedSemanticModel baseSemanticModel, NormalizedSemanticModel candidateSemanticModel,
                     ProjectGraph baseGraph, ProjectGraph candidateGraph) {}
