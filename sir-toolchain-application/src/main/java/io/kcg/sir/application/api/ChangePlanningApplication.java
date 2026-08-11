package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.InputFileGuard;
import io.kcg.sir.application.internal.PlanProtector;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.SirCompilation;
import io.kcg.sir.application.internal.SourceReader;
import io.kcg.sir.application.internal.SpringBootProjectGraphInputFactory;
import io.kcg.sir.change.api.ChangeAnalysis;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeDiagnostic;
import io.kcg.sir.change.api.ChangePlanner;
import io.kcg.sir.change.api.ChangePlanningInput;
import io.kcg.sir.change.api.FileAddition;
import io.kcg.sir.change.api.FileChange;
import io.kcg.sir.change.api.FileDeletion;
import io.kcg.sir.change.api.ChangeAnalysis.Planned;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraphBuilder;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphDiagnostic;
import io.kcg.sir.projectgraph.api.ProjectGraphInput;
import io.kcg.sir.projectgraph.api.ProjectGraphLoader;
import io.kcg.sir.projectgraph.api.ProjectGraphSerialization;
import io.kcg.sir.projectgraph.api.ProjectGraphSerializer;
import io.kcg.sir.projectgraph.api.ProjectGraphSerialization.Failure;
import io.kcg.sir.projectgraph.api.ProjectGraphSerialization.Success;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.source.SourceId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class ChangePlanningApplication {
   private static final long MAX_BASELINE_SNAPSHOT_BYTES = 16777216L;
   private final ChangePlanner planner;
   private final Function<SpringBootLoweredModel, GenerationResult> generationStep;
   private final Function<ProjectGraphInput, ProjectGraphAnalysis> graphStep;

   public ChangePlanningApplication() {
      this(new ChangePlanner(), model -> new SpringBootGenerator().generate(model), input -> new ProjectGraphBuilder().build(input));
   }

   ChangePlanningApplication(ChangePlanner planner) {
      this(planner, model -> new SpringBootGenerator().generate(model), input -> new ProjectGraphBuilder().build(input));
   }

   ChangePlanningApplication(
      ChangePlanner planner, Function<SpringBootLoweredModel, GenerationResult> generationStep, Function<ProjectGraphInput, ProjectGraphAnalysis> graphStep
   ) {
      this.planner = Objects.requireNonNull(planner, "planner");
      this.generationStep = Objects.requireNonNull(generationStep, "generationStep");
      this.graphStep = Objects.requireNonNull(graphStep, "graphStep");
   }

   public ChangePlanningResult execute(ChangePlanningRequest request) {
      Objects.requireNonNull(request, "request");
      List<ExecutionDiagnostic> accumulated = new ArrayList<>();
      ChangeBaseRevision baseRevision = request.changeSet().basedOn();
      SourceId sourceId = baseRevision.sourceId();
      ChangePlanningApplication.ReadResult readResult = this.readInputs(request, accumulated);
      if (readResult == null) {
         return failure(ChangePlanningStage.READ, accumulated);
      } else {
         ProjectGraph loadedBaseGraph = this.verifyBaseline(request, readResult, sourceId, baseRevision, accumulated);
         if (loadedBaseGraph == null) {
            return failure(ChangePlanningStage.BASELINE, accumulated);
         } else {
            Optional<SirCompilation.CompilationSnapshot> baseCompiled = SirCompilation.compile(
               readResult.baseSourceText, sourceId, this.generationStep, accumulated
            );
            if (baseCompiled.isEmpty()) {
               return failure(ChangePlanningStage.RECOMPILE, accumulated);
            } else {
               SirCompilation.CompilationSnapshot baseSnapshot = baseCompiled.get();
               ProjectGraph rebuiltBaseGraph = this.buildGraph(
                  baseSnapshot.semanticModel(), baseSnapshot.loweredModel(), baseSnapshot.generatedFiles(), sourceId, accumulated
               );
               if (rebuiltBaseGraph == null) {
                  return failure(ChangePlanningStage.RECOMPILE, accumulated);
               } else if (!rebuiltBaseGraph.canonicalDigest().equals(loadedBaseGraph.canonicalDigest())) {
                  accumulated.add(
                     graphError(
                        "SIR-APP-CHANGE-BASE-006",
                        "rebuilt base graph canonicalDigest does not match loaded snapshot: rebuilt="
                           + rebuiltBaseGraph.canonicalDigest()
                           + " loaded="
                           + loadedBaseGraph.canonicalDigest()
                     )
                  );
                  return failure(ChangePlanningStage.RECOMPILE, accumulated);
               } else {
                  ProjectGraphSerializer serializer = new ProjectGraphSerializer();
                  ProjectGraphSerialization serialization = serializer.serialize(rebuiltBaseGraph, baseRevision.snapshotFormatVersion());
                  if (serialization instanceof Failure serFailure) {
                     accumulated.add(graphError("SIR-APP-CHANGE-BASE-007", "re-serialization of rebuilt base graph failed: " + serFailure.diagnostics()));
                     return failure(ChangePlanningStage.RECOMPILE, accumulated);
                  } else {
                     byte[] reserializedBytes = ((Success)serialization).document().bytes();
                     if (!Arrays.equals(readResult.snapshotBytes, reserializedBytes)) {
                        accumulated.add(graphError("SIR-APP-CHANGE-BASE-007", "re-serialized bytes of rebuilt base graph do not match baseline snapshot bytes"));
                        return failure(ChangePlanningStage.RECOMPILE, accumulated);
                     }

                     Optional<SirCompilation.CompilationSnapshot> candidateCompiled = SirCompilation.compile(
                        readResult.candidateSourceText, sourceId, this.generationStep, accumulated
                     );
                     if (candidateCompiled.isEmpty()) {
                        return failure(ChangePlanningStage.RECOMPILE, accumulated);
                     }

                     SirCompilation.CompilationSnapshot candidateSnapshot = candidateCompiled.get();
                     ProjectGraph candidateGraph = this.buildGraph(
                        candidateSnapshot.semanticModel(), candidateSnapshot.loweredModel(), candidateSnapshot.generatedFiles(), sourceId, accumulated
                     );
                     if (candidateGraph == null) {
                        return failure(ChangePlanningStage.RECOMPILE, accumulated);
                     }

                     ChangePlanningInput plannerInput = new ChangePlanningInput(
                        baseSnapshot.semanticModel(), candidateSnapshot.semanticModel(), rebuiltBaseGraph, candidateGraph, request.changeSet()
                     );
                     ChangeAnalysis analysis = this.planner.plan(plannerInput);

                     for (ChangeDiagnostic d : analysis.diagnostics()) {
                        ExecutionSeverity severity = d.isError() ? ExecutionSeverity.ERROR : ExecutionSeverity.valueOf(d.severity().name());
                        accumulated.add(
                           new ExecutionDiagnostic(d.code(), ExecutionStage.GRAPH, severity, d.message(), d.sourceSpan(), d.artifactId(), d.relativePath())
                        );
                     }

                     if (analysis instanceof io.kcg.sir.change.api.ChangeAnalysis.Failure) {
                        return failure(ChangePlanningStage.PLAN, accumulated);
                     }

                     List<FileChange> fileChanges = fileChangesOf(analysis);
                     if (!fileChanges.isEmpty()) {
                        List<ExecutionDiagnostic> protectErrors = PlanProtector.protect(request.outputRoot(), fileChanges);
                        if (!protectErrors.isEmpty()) {
                           accumulated.addAll(protectErrors);
                           return failure(ChangePlanningStage.PROTECT, accumulated);
                        }
                     }

                     List<FileAddition> fileAdditions = fileAdditionsOf(analysis);
                     if (!fileAdditions.isEmpty()) {
                        List<ExecutionDiagnostic> protectErrors = PlanProtector.protectAdditions(request.outputRoot(), fileAdditions);
                        if (!protectErrors.isEmpty()) {
                           accumulated.addAll(protectErrors);
                           return failure(ChangePlanningStage.PROTECT, accumulated);
                        }
                     }

                     List<FileDeletion> fileDeletions = fileDeletionsOf(analysis);
                     if (!fileDeletions.isEmpty()) {
                        List<ExecutionDiagnostic> protectErrors = PlanProtector.protectDeletions(request.outputRoot(), fileDeletions);
                        if (!protectErrors.isEmpty()) {
                           accumulated.addAll(protectErrors);
                           return failure(ChangePlanningStage.PROTECT, accumulated);
                        }
                     }

                     List<ExecutionDiagnostic> successDiags = filterNonErrors(accumulated);
                     return new ChangePlanningResult.Success(analysis, rebuiltBaseGraph, candidateGraph, successDiags);
                  }
               }
            }
         }
      }
   }

   private ChangePlanningApplication.ReadResult readInputs(ChangePlanningRequest request, List<ExecutionDiagnostic> accumulated) {
      Optional<byte[]> baseRaw = InputFileGuard.readSirFile(request.baseSirFile(), accumulated);
      if (baseRaw.isEmpty()) {
         return null;
      }

      Optional<byte[]> candidateRaw = InputFileGuard.readSirFile(request.candidateSirFile(), accumulated);
      if (candidateRaw.isEmpty()) {
         return null;
      }

      Optional<byte[]> snapshotOpt = InputFileGuard.readBoundedSnapshot(request.baselineSnapshotFile(), 16777216L, accumulated);
      if (snapshotOpt.isEmpty()) {
         return null;
      }

      byte[] baseRawBytes = baseRaw.get();
      byte[] candidateRawBytes = candidateRaw.get();
      byte[] snapshotBytes = snapshotOpt.get();

      String baseSourceText;
      try {
         baseSourceText = SourceReader.decodeStrictUtf8(baseRawBytes);
      } catch (SourceReader.InvalidUtf8Exception e) {
         accumulated.add(readError("SIR-APP-CHANGE-PROTECT-006", "base SIR file is not valid UTF-8: " + request.baseSirFile()));
         return null;
      }

      String candidateSourceText;
      try {
         candidateSourceText = SourceReader.decodeStrictUtf8(candidateRawBytes);
      } catch (SourceReader.InvalidUtf8Exception e) {
         accumulated.add(readError("SIR-APP-CHANGE-PROTECT-006", "candidate SIR file is not valid UTF-8: " + request.candidateSirFile()));
         return null;
      }

      return new ChangePlanningApplication.ReadResult(baseRawBytes, candidateRawBytes, snapshotBytes, baseSourceText, candidateSourceText);
   }

   private ProjectGraph verifyBaseline(
      ChangePlanningRequest request,
      ChangePlanningApplication.ReadResult readResult,
      SourceId sourceId,
      ChangeBaseRevision baseRevision,
      List<ExecutionDiagnostic> accumulated
   ) {
      String actualSha256 = Sha256.hexDigest(readResult.baseRawBytes);
      if (!actualSha256.equals(baseRevision.baseSourceSha256Hex())) {
         accumulated.add(
            graphError("SIR-APP-CHANGE-BASE-001", "base SIR raw SHA-256 mismatch: expected=" + baseRevision.baseSourceSha256Hex() + " actual=" + actualSha256)
         );
         return null;
      } else if (baseRevision.snapshotFormatVersion() != ProjectGraphCanonicalFormatVersion.V1) {
         accumulated.add(graphError("SIR-APP-CHANGE-BASE-002", "unsupported snapshot format version: " + baseRevision.snapshotFormatVersion()));
         return null;
      } else {
         ProjectGraphLoader loader = new ProjectGraphLoader();
         ProjectGraphAnalysis loadResult = loader.load(readResult.snapshotBytes);
         if (loadResult instanceof io.kcg.sir.projectgraph.api.ProjectGraphAnalysis.Failure loadFailure) {
            accumulated.add(graphError("SIR-APP-CHANGE-BASE-003", "baseline snapshot load failed: " + loadFailure.diagnostics()));
            return null;
         } else {
            ProjectGraph loadedGraph = ((io.kcg.sir.projectgraph.api.ProjectGraphAnalysis.Success)loadResult).graph();
            if (loadedGraph.version() != baseRevision.graphVersion()) {
               accumulated.add(
                  graphError("SIR-APP-CHANGE-BASE-004", "graph version mismatch: expected=" + baseRevision.graphVersion() + " loaded=" + loadedGraph.version())
               );
               return null;
            } else if (!loadedGraph.canonicalDigest().equals(baseRevision.graphCanonicalDigest())) {
               accumulated.add(
                  graphError(
                     "SIR-APP-CHANGE-BASE-005",
                     "graph canonical digest mismatch: expected=" + baseRevision.graphCanonicalDigest() + " loaded=" + loadedGraph.canonicalDigest()
                  )
               );
               return null;
            } else {
               return loadedGraph;
            }
         }
      }
   }

   private ProjectGraph buildGraph(
      NormalizedSemanticModel semanticModel,
      SpringBootLoweredModel loweredModel,
      List<GeneratedFile> generatedFiles,
      SourceId sourceId,
      List<ExecutionDiagnostic> accumulated
   ) {
      ProjectGraphInput graphInput = new SpringBootProjectGraphInputFactory().build(semanticModel, loweredModel, generatedFiles, sourceId);
      ProjectGraphAnalysis graphAnalysis = this.graphStep.apply(graphInput);
      if (graphAnalysis instanceof io.kcg.sir.projectgraph.api.ProjectGraphAnalysis.Failure graphFailure) {
         accumulated.addAll(mapGraphDiagnostics(graphFailure.diagnostics()));
         return null;
      } else {
         io.kcg.sir.projectgraph.api.ProjectGraphAnalysis.Success graphSuccess = (io.kcg.sir.projectgraph.api.ProjectGraphAnalysis.Success)graphAnalysis;
         accumulated.addAll(mapGraphDiagnostics(graphSuccess.diagnostics()));
         return graphSuccess.graph();
      }
   }

   private static List<ExecutionDiagnostic> mapGraphDiagnostics(List<ProjectGraphDiagnostic> diagnostics) {
      List<ExecutionDiagnostic> out = new ArrayList<>(diagnostics.size());

      for (ProjectGraphDiagnostic d : diagnostics) {
         ExecutionSeverity severity = d.isError() ? ExecutionSeverity.ERROR : ExecutionSeverity.valueOf(d.severity().name());
         out.add(new ExecutionDiagnostic(d.code(), ExecutionStage.GRAPH, severity, d.message(), Optional.empty(), Optional.empty(), Optional.empty()));
      }

      return out;
   }

   private static List<FileChange> fileChangesOf(ChangeAnalysis analysis) {
      return analysis instanceof Planned planned ? planned.plan().fileChanges() : List.of();
   }

   private static List<FileAddition> fileAdditionsOf(ChangeAnalysis analysis) {
      return analysis instanceof Planned planned ? planned.plan().fileAdditions() : List.of();
   }

   private static List<FileDeletion> fileDeletionsOf(ChangeAnalysis analysis) {
      return analysis instanceof Planned planned ? planned.plan().fileDeletions() : List.of();
   }

   private static List<ExecutionDiagnostic> filterNonErrors(List<ExecutionDiagnostic> diagnostics) {
      List<ExecutionDiagnostic> out = new ArrayList<>();

      for (ExecutionDiagnostic d : diagnostics) {
         if (!d.isError()) {
            out.add(d);
         }
      }

      return out;
   }

   private static ExecutionDiagnostic readError(String code, String message) {
      return new ExecutionDiagnostic(code, ExecutionStage.READ, ExecutionSeverity.ERROR, message, Optional.empty(), Optional.empty(), Optional.empty());
   }

   private static ExecutionDiagnostic graphError(String code, String message) {
      return new ExecutionDiagnostic(code, ExecutionStage.GRAPH, ExecutionSeverity.ERROR, message, Optional.empty(), Optional.empty(), Optional.empty());
   }

   private static ChangePlanningResult.Failure failure(ChangePlanningStage stage, List<ExecutionDiagnostic> diagnostics) {
      List<ExecutionDiagnostic> copy = List.copyOf(diagnostics);
      if (copy.stream().noneMatch(ExecutionDiagnostic::isError)) {
         copy = new ArrayList<>(copy);
         copy.add(graphError("SIR-APP-CHANGE-UNKNOWN-001", "stage " + stage + " failed without an explicit error diagnostic"));
         copy = List.copyOf(copy);
      }

      return new ChangePlanningResult.Failure(stage, copy);
   }

   private record ReadResult(byte[] baseRawBytes, byte[] candidateRawBytes, byte[] snapshotBytes, String baseSourceText, String candidateSourceText) {
      private ReadResult {
         Objects.requireNonNull(baseRawBytes, "baseRawBytes");
         Objects.requireNonNull(candidateRawBytes, "candidateRawBytes");
         Objects.requireNonNull(snapshotBytes, "snapshotBytes");
         Objects.requireNonNull(baseSourceText, "baseSourceText");
         Objects.requireNonNull(candidateSourceText, "candidateSourceText");
      }
   }
}
