package io.kcg.sir.application.internal;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.generator.springboot.api.GenerationResult.Failure;
import io.kcg.sir.generator.springboot.api.GenerationResult.Success;
import io.kcg.sir.lowering.api.LoweringAnalysis;
import io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.source.SourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class SirCompilation {
   private SirCompilation() {
   }

   public static Optional<SirCompilation.CompilationSnapshot> compile(
      String sourceText, SourceId sourceId, Function<SpringBootLoweredModel, GenerationResult> generationStep, List<ExecutionDiagnostic> accumulatedDiagnostics
   ) {
      Objects.requireNonNull(sourceText, "sourceText");
      Objects.requireNonNull(sourceId, "sourceId");
      Objects.requireNonNull(generationStep, "generationStep");
      Objects.requireNonNull(accumulatedDiagnostics, "accumulatedDiagnostics");
      return legacySnapshot(CompilationStages.compile(sourceText, sourceId,
         io.kcg.sir.application.api.ValidationStopAfter.GENERATION, generationStep, accumulatedDiagnostics, stage -> {}),
         accumulatedDiagnostics);
   }

   /** Both input forms share the existing target pipeline, not a parallel generator. */
   public static Optional<CompilationSnapshot> lowerAndGenerate(NormalizedSemanticModel normalizedModel,
      Function<SpringBootLoweredModel, GenerationResult> generationStep, List<ExecutionDiagnostic> accumulatedDiagnostics) {
      return legacySnapshot(CompilationStages.lowerAndGenerate(normalizedModel,
         io.kcg.sir.application.api.ValidationStopAfter.GENERATION, generationStep, accumulatedDiagnostics,
         stage -> {}, new ArrayList<>()), accumulatedDiagnostics);
   }

   private static Optional<CompilationSnapshot> legacySnapshot(CompilationStages.Snapshot result,
      List<ExecutionDiagnostic> diagnostics) {
      if (!result.success()) return Optional.empty();
      return Optional.of(new CompilationSnapshot(result.semanticModel(), result.loweredModel(), result.files(),
         diagnostics.stream().filter(d -> !d.isError()).toList()));
   }

   public record CompilationSnapshot(
      NormalizedSemanticModel semanticModel, SpringBootLoweredModel loweredModel, List<GeneratedFile> generatedFiles, List<ExecutionDiagnostic> diagnostics
   ) {
      public CompilationSnapshot {
         Objects.requireNonNull(semanticModel, "semanticModel");
         Objects.requireNonNull(loweredModel, "loweredModel");
         generatedFiles = List.copyOf(Objects.requireNonNull(generatedFiles, "generatedFiles"));
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }
}
