package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.FileTransaction;
import io.kcg.sir.application.internal.PathGuard;
import io.kcg.sir.application.internal.SirCompilation;
import io.kcg.sir.application.internal.SourceReader;
import io.kcg.sir.application.internal.SpringBootProjectGraphInputFactory;
import io.kcg.sir.application.internal.TransactionResult;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraphBuilder;
import io.kcg.sir.projectgraph.api.ProjectGraphDiagnostic;
import io.kcg.sir.projectgraph.api.ProjectGraphInput;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis.Failure;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis.Success;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class ToolchainApplication {
   private final Function<SpringBootLoweredModel, GenerationResult> generationStep;
   private final Function<ProjectGraphInput, ProjectGraphAnalysis> graphStep;

   public ToolchainApplication() {
      this(model -> new SpringBootGenerator().generate(model), input -> new ProjectGraphBuilder().build(input));
   }

   ToolchainApplication(Function<SpringBootLoweredModel, GenerationResult> generationStep) {
      this(generationStep, input -> new ProjectGraphBuilder().build(input));
   }

   ToolchainApplication(Function<SpringBootLoweredModel, GenerationResult> generationStep, Function<ProjectGraphInput, ProjectGraphAnalysis> graphStep) {
      this.generationStep = Objects.requireNonNull(generationStep, "generationStep");
      this.graphStep = Objects.requireNonNull(graphStep, "graphStep");
   }

   public ToolchainResult execute(ToolchainRequest request) {
      List<ExecutionDiagnostic> accumulated = new ArrayList<>();
      if (!request.sourceFile().isAbsolute()) {
         return failure(
            ExecutionStage.READ, FailureDisposition.NO_CHANGES, error("SIR-APP-REQUEST-001", "sourceFile must be an absolute path: " + request.sourceFile())
         );
      }

      if (!request.outputRoot().isAbsolute()) {
         return failure(
            ExecutionStage.READ, FailureDisposition.NO_CHANGES, error("SIR-APP-REQUEST-002", "outputRoot must be an absolute path: " + request.outputRoot())
         );
      }

      String sourceText;
      try {
         sourceText = SourceReader.readStrictUtf8(request.sourceFile());
      } catch (SourceReader.InvalidUtf8Exception e) {
         return failure(
            ExecutionStage.READ, FailureDisposition.NO_CHANGES, error("SIR-APP-READ-001", "source file is not valid UTF-8: " + request.sourceFile())
         );
      } catch (NoSuchFileException e) {
         return failure(ExecutionStage.READ, FailureDisposition.NO_CHANGES, error("SIR-APP-READ-002", "source file does not exist: " + request.sourceFile()));
      } catch (Exception e) {
         return failure(ExecutionStage.READ, FailureDisposition.NO_CHANGES, error("SIR-APP-READ-003", "failed to read source file: " + e.getMessage()));
      }

      Optional<SirCompilation.CompilationSnapshot> compiled = SirCompilation.compile(sourceText, request.sourceId(), this.generationStep, accumulated);
      if (compiled.isEmpty()) {
         return failure(lastErrorStage(accumulated), FailureDisposition.NO_CHANGES, accumulated);
      } else {
         SirCompilation.CompilationSnapshot snapshot = compiled.get();
         NormalizedSemanticModel normalizedModel = snapshot.semanticModel();
         SpringBootLoweredModel loweredModel = snapshot.loweredModel();
         List<GeneratedFile> files = snapshot.generatedFiles();
         List<ExecutionDiagnostic> preflightErrors = PathGuard.preflight(request.outputRoot(), files, request.conflictPolicy());
         if (!preflightErrors.isEmpty()) {
            accumulated.addAll(preflightErrors);
            return failure(ExecutionStage.PREFLIGHT, FailureDisposition.NO_CHANGES, accumulated);
         } else {
            ProjectGraphInput graphInput = new SpringBootProjectGraphInputFactory().build(normalizedModel, loweredModel, files, request.sourceId());
            ProjectGraphAnalysis graphAnalysis = this.graphStep.apply(graphInput);
            if (graphAnalysis instanceof Failure graphFailure) {
               accumulated.addAll(mapGraphDiagnostics(graphFailure.diagnostics()));
               return failure(ExecutionStage.GRAPH, FailureDisposition.NO_CHANGES, accumulated);
            } else {
               Success graphSuccess = (Success)graphAnalysis;
               accumulated.addAll(mapGraphDiagnostics(graphSuccess.diagnostics()));
               ProjectGraph prebuiltGraph = graphSuccess.graph();
               FileTransaction transaction = new FileTransaction(request.outputRoot(), files, request.conflictPolicy());
               TransactionResult txResult = transaction.execute();
               if (txResult instanceof TransactionResult.Failure txFailure) {
                  accumulated.addAll(txFailure.diagnostics());
                  ExecutionStage failedStage = txFailure.disposition() == FailureDisposition.RECOVERY_REQUIRED ? ExecutionStage.ROLLBACK : ExecutionStage.WRITE;
                  return new ToolchainResult.Failure(failedStage, txFailure.disposition(), List.copyOf(accumulated));
               } else {
                  TransactionResult.Success txSuccess = (TransactionResult.Success)txResult;
                  accumulated.addAll(txSuccess.warnings());
                  ExecutionManifest manifest = new ExecutionManifest(request.outputRoot(), request.conflictPolicy(), txSuccess.appliedFiles());
                  return new ToolchainResult.Success(manifest, prebuiltGraph, List.copyOf(accumulated));
               }
            }
         }
      }
   }

   private static ExecutionStage lastErrorStage(List<ExecutionDiagnostic> diagnostics) {
      ExecutionStage last = ExecutionStage.PARSE;

      for (ExecutionDiagnostic d : diagnostics) {
         if (d.isError()) {
            last = d.stage();
         }
      }

      return last;
   }

   private static List<ExecutionDiagnostic> mapGraphDiagnostics(List<ProjectGraphDiagnostic> diagnostics) {
      List<ExecutionDiagnostic> out = new ArrayList<>(diagnostics.size());

      for (ProjectGraphDiagnostic d : diagnostics) {
         ExecutionSeverity severity = d.isError() ? ExecutionSeverity.ERROR : ExecutionSeverity.valueOf(d.severity().name());
         out.add(new ExecutionDiagnostic(d.code(), ExecutionStage.GRAPH, severity, d.message(), Optional.empty(), Optional.empty(), Optional.empty()));
      }

      return out;
   }

   private static ExecutionDiagnostic error(String code, String message) {
      return ExecutionDiagnostic.error(code, ExecutionStage.READ, message);
   }

   private static ToolchainResult.Failure failure(ExecutionStage stage, FailureDisposition disposition, List<ExecutionDiagnostic> diagnostics) {
      List<ExecutionDiagnostic> copy = List.copyOf(diagnostics);
      if (copy.stream().noneMatch(ExecutionDiagnostic::isError)) {
         copy = new ArrayList<>(copy);
         copy.add(ExecutionDiagnostic.error("SIR-APP-UNKNOWN-001", stage, "stage " + stage + " failed without an explicit error diagnostic"));
         copy = List.copyOf(copy);
      }

      return new ToolchainResult.Failure(stage, disposition, copy);
   }

   private static ToolchainResult.Failure failure(ExecutionStage stage, FailureDisposition disposition, ExecutionDiagnostic diagnostic) {
      return new ToolchainResult.Failure(stage, disposition, List.of(diagnostic));
   }
}
