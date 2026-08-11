package io.kcg.sir.lowering.springboot.api;

import io.kcg.sir.lowering.api.LoweredIrValidator;
import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.springboot.internal.SpringBootIrValidation;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import java.util.List;
import java.util.Objects;

public final class SpringBootLoweredIrValidator implements LoweredIrValidator<SpringBootLoweredModel> {
   public List<LoweringDiagnostic> validate(SpringBootLoweredModel model) {
      return new SpringBootIrValidation(Objects.requireNonNull(model, "model")).validate();
   }
}
