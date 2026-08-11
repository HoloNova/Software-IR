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
      SirParser parser = SirParser.create();
      ParseResult parseResult = parser.parse(new SirSource(sourceId, sourceText));
      accumulatedDiagnostics.addAll(DiagnosticMapper.fromParser(parseResult.diagnostics(), ExecutionStage.PARSE));
      if (!parseResult.isSuccess()) {
         return Optional.empty();
      } else {
         SemanticAnalysis semantic = new SirSemanticAnalyzer().analyze(parseResult.document().orElseThrow());
         accumulatedDiagnostics.addAll(DiagnosticMapper.fromParser(semantic.diagnostics(), ExecutionStage.SEMANTIC));
         if (!semantic.isSuccess()) {
            return Optional.empty();
         } else {
            NormalizedSemanticModel normalizedModel = semantic.model().orElseThrow();
            LoweringAnalysis<SpringBootLoweredModel> lowering = new SpringBootTargetLowering().lower(normalizedModel);
            accumulatedDiagnostics.addAll(DiagnosticMapper.fromLowering(lowering.diagnostics(), ExecutionStage.LOWERING));
            if (!lowering.isSuccess()) {
               return Optional.empty();
            } else {
               SpringBootLoweredModel loweredModel = lowering.model().orElseThrow();
               GenerationResult generation = generationStep.apply(loweredModel);
               if (generation instanceof Failure genFailure) {
                  accumulatedDiagnostics.addAll(DiagnosticMapper.fromGeneration(genFailure.diagnostics(), ExecutionStage.GENERATION));
                  return Optional.empty();
               } else {
                  List<GeneratedFile> files = ((Success)generation).files();
                  List<ExecutionDiagnostic> snapshotDiags = new ArrayList<>();

                  for (ExecutionDiagnostic d : accumulatedDiagnostics) {
                     if (!d.isError()) {
                        snapshotDiags.add(d);
                     }
                  }

                  return Optional.of(new SirCompilation.CompilationSnapshot(normalizedModel, loweredModel, files, snapshotDiags));
               }
            }
         }
      }
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
