package io.kcg.sir.lowering.api;

import java.util.List;

@FunctionalInterface
public interface LoweredIrValidator<M extends LoweredModel> {

    List<LoweringDiagnostic> validate(M model);
}
