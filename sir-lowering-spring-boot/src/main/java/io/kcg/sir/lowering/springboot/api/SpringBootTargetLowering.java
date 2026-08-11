package io.kcg.sir.lowering.springboot.api;

import io.kcg.sir.lowering.api.LoweringAnalysis;
import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.api.TargetLowering;
import io.kcg.sir.lowering.api.LoweringAnalysis.Failure;
import io.kcg.sir.lowering.api.LoweringAnalysis.Success;
import io.kcg.sir.lowering.springboot.internal.SpringBootInputValidator;
import io.kcg.sir.lowering.springboot.internal.SpringBootModelLowerer;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import java.util.List;
import java.util.Objects;

public final class SpringBootTargetLowering implements TargetLowering<SpringBootLoweredModel> {
   @Override
   public LoweringAnalysis<SpringBootLoweredModel> lower(NormalizedSemanticModel model) {
      Objects.requireNonNull(model, "model");
      List<LoweringDiagnostic> inputDiagnostics = new SpringBootInputValidator(model).validate();
      if (this.hasErrors(inputDiagnostics)) {
         return new Failure<>(inputDiagnostics);
      }

      SpringBootLoweredModel lowered = new SpringBootModelLowerer(model).lower();
      List<LoweringDiagnostic> irDiagnostics = new SpringBootLoweredIrValidator().validate(lowered);
      return this.hasErrors(irDiagnostics) ? new Failure<>(irDiagnostics) : new Success<>(lowered, irDiagnostics);
   }

   private boolean hasErrors(List<LoweringDiagnostic> diagnostics) {
      return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity().isError());
   }
}
