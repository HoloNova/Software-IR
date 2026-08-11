package io.kcg.sir.generator.springboot.api;

import io.kcg.sir.generator.springboot.internal.GenerationEngine;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SpringBootGenerator {
   public GenerationResult generate(SpringBootLoweredModel model) {
      Objects.requireNonNull(model, "model");
      List<LoweringDiagnostic> lowered = new SpringBootLoweredIrValidator().validate(model);
      List<GenerationDiagnostic> diagnostics = new ArrayList<>();

      for (LoweringDiagnostic d : lowered) {
         if (d.severity().isError()) {
            diagnostics.add(GenerationDiagnostic.of("SIR-GEN-INPUT-001", "Lowered IR validation failed: " + d.message()));
         }
      }

      if (!diagnostics.isEmpty()) {
         return new GenerationResult.Failure(diagnostics);
      }

      GenerationEngine engine = new GenerationEngine(model);
      GenerationEngine.Outcome outcome = engine.run();
      if (!outcome.diagnostics().isEmpty()) {
         return new GenerationResult.Failure(outcome.diagnostics());
      }

      List<GeneratedFile> files = outcome.files();
      List<GenerationDiagnostic> pathErrors = validatePaths(files);
      return !pathErrors.isEmpty() ? new GenerationResult.Failure(pathErrors) : new GenerationResult.Success(files);
   }

   private static List<GenerationDiagnostic> validatePaths(List<GeneratedFile> files) {
      List<GenerationDiagnostic> errors = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();

      for (GeneratedFile file : files) {
         String path = file.relativePath();
         if (!seen.add(path)) {
            LoweredNodeId nodeId = file.artifactId();
            errors.add(GenerationDiagnostic.of("SIR-GEN-PATH-001", "Duplicate generated path: " + path, nodeId == null ? null : nodeId));
         }
      }

      return errors;
   }
}
