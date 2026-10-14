package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.FileTransaction;
import io.kcg.sir.application.internal.PathGuard;
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

   /**
    * First generation: compiles the SIR sources in the source root and writes the generated project into an output root that does
    * not exist yet. It changes nothing else; a later change to an existing project goes through the version-plan and apply entries.
    */
   public ToolchainResult execute(ToolchainRequest request) {
      Objects.requireNonNull(request, "request");
      List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
      if (!request.sourceRoot().isAbsolute() || !request.outputRoot().isAbsolute()
         || !request.sourceRoot().equals(request.sourceRoot().normalize()) || !request.outputRoot().equals(request.outputRoot().normalize())) {
         diagnostics.add(error("SIR-APP-PROJECT-REQUEST-001", "sourceRoot/outputRoot must be absolute normalized paths"));
         return projectFailure(ExecutionStage.READ, FailureDisposition.NO_CHANGES, diagnostics);
      }
      if (request.outputRoot().startsWith(request.sourceRoot()) || request.sourceRoot().startsWith(request.outputRoot())) {
         diagnostics.add(error("SIR-APP-PROJECT-REQUEST-002", "sourceRoot and outputRoot must not overlap"));
         return projectFailure(ExecutionStage.READ, FailureDisposition.NO_CHANGES, diagnostics);
      }
      if (java.nio.file.Files.exists(request.outputRoot(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
         diagnostics.add(ExecutionDiagnostic.error("SIR-APP-PROJECT-OUTPUT-001", ExecutionStage.PREFLIGHT, "project first generation requires a new output root"));
         return projectFailure(ExecutionStage.PREFLIGHT, FailureDisposition.NO_CHANGES, diagnostics);
      }
      var loaded = io.kcg.sir.application.internal.ProjectSourceReader.read(request, diagnostics);
      if (loaded.isEmpty()) return projectFailure(lastErrorStage(diagnostics), FailureDisposition.NO_CHANGES, diagnostics);
      var compilation = io.kcg.sir.application.internal.ProjectCompilation.compile(loaded.get(), generationStep, diagnostics);
      if (compilation.isEmpty()) return projectFailure(lastErrorStage(diagnostics), FailureDisposition.NO_CHANGES, diagnostics);
      var snapshot = compilation.get();
      var files = snapshot.generatedFiles();
      diagnostics.addAll(PathGuard.preflight(request.outputRoot(), files, ConflictPolicy.FAIL_IF_EXISTS));
      if (diagnostics.stream().anyMatch(ExecutionDiagnostic::isError)) return projectFailure(ExecutionStage.PREFLIGHT, FailureDisposition.NO_CHANGES, diagnostics);
      var graphInput = new SpringBootProjectGraphInputFactory().build(snapshot.semanticModel(), snapshot.loweredModel(), files, loaded.get().snapshot());
      var graphAnalysis = graphStep.apply(graphInput);
      diagnostics.addAll(mapGraphDiagnostics(graphAnalysis.diagnostics()));
      if (graphAnalysis instanceof Failure) return projectFailure(ExecutionStage.GRAPH, FailureDisposition.NO_CHANGES, diagnostics);
      var graph = ((Success) graphAnalysis).graph();
      var transaction = new FileTransaction(request.outputRoot(), files, ConflictPolicy.FAIL_IF_EXISTS).execute();
      if (transaction instanceof TransactionResult.Failure failed) {
         diagnostics.addAll(failed.diagnostics());
         return projectFailure(failed.disposition() == FailureDisposition.RECOVERY_REQUIRED ? ExecutionStage.ROLLBACK : ExecutionStage.WRITE,
            failed.disposition(), diagnostics);
      }
      var success = (TransactionResult.Success) transaction;
      diagnostics.addAll(success.warnings());
      var manifest = new ExecutionManifest(request.outputRoot(), ConflictPolicy.FAIL_IF_EXISTS, success.appliedFiles());
      return new ToolchainResult.Success(loaded.get().snapshot(), manifest, graph, diagnostics);
   }

   private static ToolchainResult.Failure projectFailure(ExecutionStage stage, FailureDisposition disposition, List<ExecutionDiagnostic> diagnostics) {
      return new ToolchainResult.Failure(stage, disposition, diagnostics);
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
}
