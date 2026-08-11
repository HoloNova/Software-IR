package io.kcg.sir.lowering.api;

import io.kcg.sir.semantic.api.NormalizedSemanticModel;

@FunctionalInterface
public interface TargetLowering<M extends LoweredModel> {

    LoweringAnalysis<M> lower(NormalizedSemanticModel model);
}
