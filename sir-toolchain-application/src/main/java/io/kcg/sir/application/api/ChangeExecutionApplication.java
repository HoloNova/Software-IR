package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.InputFileGuard;
import io.kcg.sir.application.internal.PlanProtector;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.SirCompilation;
import io.kcg.sir.application.internal.SourceReader;
import io.kcg.sir.application.internal.SpringBootProjectGraphInputFactory;
import io.kcg.sir.application.internal.bundle.BaselineBuilder;
import io.kcg.sir.application.internal.bundle.BaselineBundle;
import io.kcg.sir.application.internal.bundle.BaselineBundleStore;
import io.kcg.sir.application.internal.bundle.BaselineDescriptor;
import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import io.kcg.sir.application.internal.bundle.OutputManifestVerifier;
import io.kcg.sir.application.internal.changeplanning.ChangePlanningHasher;
import io.kcg.sir.application.internal.changeplanning.TargetCatalogBuilder;
import io.kcg.sir.application.internal.state.ApplyHooks;
import io.kcg.sir.application.internal.state.ChangeApplyTransaction;
import io.kcg.sir.application.internal.state.ChangeCreateTransaction;
import io.kcg.sir.application.internal.state.ChangeDeleteTransaction;
import io.kcg.sir.application.internal.state.ChangeRecoveryEngine;
import io.kcg.sir.application.internal.state.CreateFilePayload;
import io.kcg.sir.application.internal.state.CreateManifestDeltaVerifier;
import io.kcg.sir.application.internal.state.DeleteFilePayload;
import io.kcg.sir.application.internal.state.DeleteManifestDeltaVerifier;
import io.kcg.sir.application.internal.state.DeletePlanBindingVerifier;
import io.kcg.sir.application.internal.state.JournalGate;
import io.kcg.sir.application.internal.state.StateRootLock;
import io.kcg.sir.application.internal.state.StateRootPathGuard;
import io.kcg.sir.change.api.AddCapability;
import io.kcg.sir.change.api.ArtifactAddition;
import io.kcg.sir.change.api.ArtifactDeletion;
import io.kcg.sir.change.api.ChangeAnalysis;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeDiagnostic;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangeOperation;
import io.kcg.sir.change.api.ChangePlanner;
import io.kcg.sir.change.api.ChangePlanningInput;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.change.api.FileAddition;
import io.kcg.sir.change.api.FileChange;
import io.kcg.sir.change.api.FileDeletion;
import io.kcg.sir.change.api.RemoveCapability;
import io.kcg.sir.change.api.ChangeAnalysis.NoChanges;
import io.kcg.sir.change.api.ChangeAnalysis.Planned;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.api.LoweredIrVersion;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.profile.SpringBootTargetProfile;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraphBuilder;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphDiagnostic;
import io.kcg.sir.projectgraph.api.ProjectGraphInput;
import io.kcg.sir.projectgraph.api.ProjectGraphLoader;
import io.kcg.sir.projectgraph.api.ArtifactRole.DeclarationRole;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis.Failure;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis.Success;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Function;

public final class ChangeExecutionApplication {
   private static final long MAX_BASELINE_SNAPSHOT_BYTES = 16777216L;
   Runnable afterDigestMatchHook = null;
   Runnable afterGeneratedRegistrationReadHook = null;
   Runnable afterApplyDigestMatchHook = null;
   private final ChangePlanner planner;
   private final Function<ChangePlanningInput, ChangeAnalysis> planningStep;
   private final ChangeExecutionApplication.PlanProtection planProtection;
   private final Function<SpringBootLoweredModel, GenerationResult> generationStep;
   private final Function<ProjectGraphInput, ProjectGraphAnalysis> graphStep;
   private final ApplyHooks applyHooks;

   public ChangeExecutionApplication() {
      this(new ChangePlanner(), model -> new SpringBootGenerator().generate(model), input -> new ProjectGraphBuilder().build(input), ApplyHooks.noOp());
   }

   ChangeExecutionApplication(
      ChangePlanner planner,
      Function<SpringBootLoweredModel, GenerationResult> generationStep,
      Function<ProjectGraphInput, ProjectGraphAnalysis> graphStep,
      ApplyHooks applyHooks
   ) {
      this(planner, planner::plan, generationStep, graphStep, applyHooks, ChangeExecutionApplication.PlanProtection.standard());
   }

   ChangeExecutionApplication(
      Function<ChangePlanningInput, ChangeAnalysis> planningStep,
      Function<SpringBootLoweredModel, GenerationResult> generationStep,
      Function<ProjectGraphInput, ProjectGraphAnalysis> graphStep,
      ApplyHooks applyHooks,
      ChangeExecutionApplication.PlanProtection planProtection
   ) {
      this(new ChangePlanner(), planningStep, generationStep, graphStep, applyHooks, planProtection);
   }

   private ChangeExecutionApplication(
      ChangePlanner planner,
      Function<ChangePlanningInput, ChangeAnalysis> planningStep,
      Function<SpringBootLoweredModel, GenerationResult> generationStep,
      Function<ProjectGraphInput, ProjectGraphAnalysis> graphStep,
      ApplyHooks applyHooks,
      ChangeExecutionApplication.PlanProtection planProtection
   ) {
      this.planner = Objects.requireNonNull(planner, "planner");
      this.planningStep = Objects.requireNonNull(planningStep, "planningStep");
      this.generationStep = Objects.requireNonNull(generationStep, "generationStep");
      this.graphStep = Objects.requireNonNull(graphStep, "graphStep");
      this.applyHooks = Objects.requireNonNull(applyHooks, "applyHooks");
      this.planProtection = Objects.requireNonNull(planProtection, "planProtection");
   }

   public ChangeBaselineRegistrationResult register(ChangeBaselineRegistrationRequest request) {
      Objects.requireNonNull(request, "request");
      List<ChangeExecutionDiagnostic> diagnostics = new ArrayList<>();
      ChangeBaseRevision basedOn = request.basedOn();
      SourceId sourceId = basedOn.sourceId();
      StateRootPathGuard.Result pathResult = StateRootPathGuard.validate(request.stateRoot(), request.outputRoot(), false);
      if (!pathResult.isSuccess()) {
         return ChangeBaselineRegistrationResult.failure(ChangeExecutionStage.BASELINE, concat(diagnostics, pathResult.errors()));
      }

      Path normalizedStateRoot = pathResult.normalizedStateRoot();
      Path normalizedOutputRoot = pathResult.normalizedOutputRoot();

      try {
         Files.createDirectories(normalizedStateRoot);
      } catch (IOException | SecurityException e) {
         return ChangeBaselineRegistrationResult.failure(
            ChangeExecutionStage.BASELINE, List.of(diag("SIR-APP-CHANGE-BASELINE-006", "failed to create stateRoot directory: " + e.getMessage()))
         );
      }

      try (StateRootLock.HeldLock lock = this.acquireLock(normalizedStateRoot, diagnostics)) {
         if (lock == null) {
            return ChangeBaselineRegistrationResult.failure(ChangeExecutionStage.BASELINE, List.copyOf(diagnostics));
         }

         StateRootPathGuard.Result revalidated = StateRootPathGuard.validate(normalizedStateRoot, normalizedOutputRoot, false);
         if (!revalidated.isSuccess()) {
            return ChangeBaselineRegistrationResult.failure(ChangeExecutionStage.BASELINE, concat(diagnostics, revalidated.errors()));
         }

         BaselineBundleStore store = new BaselineBundleStore(normalizedStateRoot);
         JournalGate.InspectionResult gateResult = new JournalGate(normalizedStateRoot).inspect(Optional.empty());
         return !gateResult.isOpen()
            ? ChangeBaselineRegistrationResult.recoveryRequired(RecoveryHandle.any(), concat(diagnostics, gateResult.errors()))
            : this.doRegister(request, store, normalizedStateRoot, normalizedOutputRoot, sourceId, basedOn, diagnostics);
      }
   }

   private ChangeBaselineRegistrationResult doRegister(
      ChangeBaselineRegistrationRequest request,
      BaselineBundleStore store,
      Path normalizedStateRoot,
      Path normalizedOutputRoot,
      SourceId sourceId,
      ChangeBaseRevision basedOn,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      List<ExecutionDiagnostic> readDiags = new ArrayList<>();
      Optional<byte[]> baseRawOpt = InputFileGuard.readSirFile(request.baseSirFile(), readDiags);
      if (baseRawOpt.isEmpty()) {
         diagnostics.addAll(mapExecutionDiags(readDiags, ChangeExecutionStage.READ));
         return failure(ChangeExecutionStage.READ, diagnostics);
      } else {
         Optional<byte[]> snapshotOpt = InputFileGuard.readBoundedSnapshot(request.baselineSnapshotFile(), 16777216L, readDiags);
         if (snapshotOpt.isEmpty()) {
            diagnostics.addAll(mapExecutionDiags(readDiags, ChangeExecutionStage.READ));
            return failure(ChangeExecutionStage.READ, diagnostics);
         } else {
            byte[] baseSourceBytes = baseRawOpt.get();
            byte[] snapshotBytes = snapshotOpt.get();
            String actualSourceSha = Sha256.hexDigest(baseSourceBytes);
            if (!actualSourceSha.equals(basedOn.baseSourceSha256Hex())) {
               return failure(
                  ChangeExecutionStage.BASELINE,
                  diagnostics,
                  "SIR-APP-CHANGE-BASELINE-001",
                  "base SIR SHA-256 mismatch: expected=" + basedOn.baseSourceSha256Hex() + " actual=" + actualSourceSha
               );
            } else if (basedOn.snapshotFormatVersion() != ProjectGraphCanonicalFormatVersion.V1) {
               return failure(
                  ChangeExecutionStage.BASELINE,
                  diagnostics,
                  "SIR-APP-CHANGE-BASELINE-002",
                  "unsupported snapshot format version: " + basedOn.snapshotFormatVersion()
               );
            } else {
               ProjectGraphLoader loader = new ProjectGraphLoader();
               ProjectGraphAnalysis loadResult = loader.load(snapshotBytes);
               if (loadResult instanceof Failure loadFailure) {
                  return failure(
                     ChangeExecutionStage.BASELINE, diagnostics, "SIR-APP-CHANGE-BASELINE-003", "snapshot load failed: " + loadFailure.diagnostics()
                  );
               } else {
                  ProjectGraph loadedGraph = ((Success)loadResult).graph();
                  if (loadedGraph.version() != basedOn.graphVersion()) {
                     return failure(
                        ChangeExecutionStage.BASELINE,
                        diagnostics,
                        "SIR-APP-CHANGE-BASELINE-004",
                        "graph version mismatch: expected=" + basedOn.graphVersion() + " loaded=" + loadedGraph.version()
                     );
                  }

                  if (!loadedGraph.canonicalDigest().equals(basedOn.graphCanonicalDigest())) {
                     return failure(
                        ChangeExecutionStage.BASELINE,
                        diagnostics,
                        "SIR-APP-CHANGE-BASELINE-005",
                        "graph digest mismatch: expected=" + basedOn.graphCanonicalDigest() + " loaded=" + loadedGraph.canonicalDigest()
                     );
                  }

                  String baseSourceText;
                  try {
                     baseSourceText = SourceReader.decodeStrictUtf8(baseSourceBytes);
                  } catch (SourceReader.InvalidUtf8Exception e) {
                     return failure(ChangeExecutionStage.READ, diagnostics, "SIR-APP-CHANGE-BASELINE-001", "base SIR is not valid UTF-8");
                  }

                  List<ExecutionDiagnostic> compileDiags = new ArrayList<>();
                  Optional<SirCompilation.CompilationSnapshot> baseCompiled = SirCompilation.compile(
                     baseSourceText, sourceId, this.generationStep, compileDiags
                  );
                  if (baseCompiled.isEmpty()) {
                     diagnostics.addAll(mapExecutionDiags(compileDiags, ChangeExecutionStage.RECOMPILE));
                     return failure(ChangeExecutionStage.RECOMPILE, diagnostics);
                  }

                  SirCompilation.CompilationSnapshot baseSnapshot = baseCompiled.get();
                  ProjectGraph rebuiltBaseGraph = this.buildGraph(baseSnapshot, sourceId, diagnostics);
                  if (rebuiltBaseGraph == null) {
                     return failure(ChangeExecutionStage.RECOMPILE, diagnostics);
                  }

                  if (!rebuiltBaseGraph.canonicalDigest().equals(loadedGraph.canonicalDigest())) {
                     return failure(
                        ChangeExecutionStage.RECOMPILE,
                        diagnostics,
                        "SIR-APP-CHANGE-BASELINE-005",
                        "rebuilt graph digest mismatch: rebuilt=" + rebuiltBaseGraph.canonicalDigest() + " loaded=" + loadedGraph.canonicalDigest()
                     );
                  }

                  byte[] reserialized = BaselineBuilder.serializeGraph(rebuiltBaseGraph);
                  if (reserialized == null) {
                     return failure(ChangeExecutionStage.RECOMPILE, diagnostics, "SIR-APP-CHANGE-BASELINE-005", "re-serialization of rebuilt graph failed");
                  }

                  if (!Arrays.equals(snapshotBytes, reserialized)) {
                     return failure(
                        ChangeExecutionStage.RECOMPILE, diagnostics, "SIR-APP-CHANGE-BASELINE-005", "re-serialized bytes do not match snapshot bytes"
                     );
                  }

                  BaselineBundle b0Bundle;
                  try {
                     b0Bundle = BaselineBuilder.build(baseSnapshot, rebuiltBaseGraph, baseSourceBytes, snapshotBytes, sourceId, normalizedOutputRoot);
                  } catch (IllegalArgumentException e) {
                     return failure(
                        ChangeExecutionStage.BASELINE, diagnostics, "SIR-APP-CHANGE-BASELINE-001", "B0 Bundle construction failed: " + e.getMessage()
                     );
                  }

                  return this.publishInitialBaseline(store, b0Bundle, normalizedOutputRoot, diagnostics);
               }
            }
         }
      }
   }

   public ChangeBaselineRegistrationResult registerGeneratedBaseline(GeneratedBaselineRegistrationRequest request) {
      Objects.requireNonNull(request, "request");
      List<ChangeExecutionDiagnostic> diagnostics = new ArrayList<>();
      SourceId sourceId = request.sourceId();
      StateRootPathGuard.Result pathResult = StateRootPathGuard.validate(request.stateRoot(), request.outputRoot(), false);
      if (!pathResult.isSuccess()) {
         return ChangeBaselineRegistrationResult.failure(ChangeExecutionStage.BASELINE, concat(diagnostics, pathResult.errors()));
      }

      Path normalizedStateRoot = pathResult.normalizedStateRoot();
      Path normalizedOutputRoot = pathResult.normalizedOutputRoot();

      try {
         Files.createDirectories(normalizedStateRoot);
      } catch (IOException | SecurityException e) {
         return ChangeBaselineRegistrationResult.failure(
            ChangeExecutionStage.BASELINE, List.of(diag("SIR-APP-CHANGE-BASELINE-006", "failed to create stateRoot directory: " + e.getMessage()))
         );
      }

      try (StateRootLock.HeldLock lock = this.acquireLock(normalizedStateRoot, diagnostics)) {
         if (lock == null) {
            return ChangeBaselineRegistrationResult.failure(ChangeExecutionStage.BASELINE, List.copyOf(diagnostics));
         }

         StateRootPathGuard.Result revalidated = StateRootPathGuard.validate(normalizedStateRoot, normalizedOutputRoot, false);
         if (!revalidated.isSuccess()) {
            return ChangeBaselineRegistrationResult.failure(ChangeExecutionStage.BASELINE, concat(diagnostics, revalidated.errors()));
         }

         BaselineBundleStore store = new BaselineBundleStore(normalizedStateRoot);
         JournalGate.InspectionResult gateResult = new JournalGate(normalizedStateRoot).inspect(Optional.empty());
         return !gateResult.isOpen()
            ? ChangeBaselineRegistrationResult.recoveryRequired(RecoveryHandle.any(), concat(diagnostics, gateResult.errors()))
            : this.doRegisterGenerated(request, store, normalizedStateRoot, normalizedOutputRoot, sourceId, diagnostics);
      }
   }

   private ChangeBaselineRegistrationResult doRegisterGenerated(
      GeneratedBaselineRegistrationRequest request,
      BaselineBundleStore store,
      Path normalizedStateRoot,
      Path normalizedOutputRoot,
      SourceId sourceId,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      List<ExecutionDiagnostic> readDiags = new ArrayList<>();
      Optional<byte[]> baseRawOpt = InputFileGuard.readSirFile(request.baseSirFile(), readDiags);
      if (baseRawOpt.isEmpty()) {
         diagnostics.addAll(mapExecutionDiags(readDiags, ChangeExecutionStage.READ));
         return failure(ChangeExecutionStage.READ, diagnostics);
      }

      byte[] baseSourceBytes = baseRawOpt.get();
      Runnable readHook = this.afterGeneratedRegistrationReadHook;
      if (readHook != null) {
         readHook.run();
      }

      String baseSourceText;
      try {
         baseSourceText = SourceReader.decodeStrictUtf8(baseSourceBytes);
      } catch (SourceReader.InvalidUtf8Exception e) {
         return failure(ChangeExecutionStage.READ, diagnostics, "SIR-APP-CHANGE-BASELINE-001", "base SIR is not valid UTF-8");
      }

      List<ExecutionDiagnostic> compileDiags = new ArrayList<>();
      Optional<SirCompilation.CompilationSnapshot> baseCompiled = SirCompilation.compile(baseSourceText, sourceId, this.generationStep, compileDiags);
      if (baseCompiled.isEmpty()) {
         diagnostics.addAll(mapExecutionDiags(compileDiags, ChangeExecutionStage.RECOMPILE));
         return failure(ChangeExecutionStage.RECOMPILE, diagnostics);
      } else {
         SirCompilation.CompilationSnapshot baseSnapshot = baseCompiled.get();
         if (!SpringBootTargetProfile.V0_2.id().equals(baseSnapshot.loweredModel().targetId())) {
            return failure(
               ChangeExecutionStage.RECOMPILE,
               diagnostics,
               "SIR-APP-CHANGE-BASELINE-001",
               "unsupported Target Profile: expected=" + SpringBootTargetProfile.V0_2.id() + " derived=" + baseSnapshot.loweredModel().targetId()
            );
         } else if (!LoweredIrVersion.V0_2.equals(baseSnapshot.loweredModel().irVersion())) {
            return failure(
               ChangeExecutionStage.RECOMPILE,
               diagnostics,
               "SIR-APP-CHANGE-BASELINE-001",
               "unsupported Lowered IR version: expected=" + LoweredIrVersion.V0_2.value() + " derived=" + baseSnapshot.loweredModel().irVersion().value()
            );
         } else {
            ProjectGraph baseGraph = this.buildGraph(baseSnapshot, sourceId, diagnostics);
            if (baseGraph == null) {
               return failure(ChangeExecutionStage.RECOMPILE, diagnostics);
            } else {
               byte[] snapshotBytes = BaselineBuilder.serializeGraph(baseGraph);
               if (snapshotBytes == null) {
                  return failure(
                     ChangeExecutionStage.RECOMPILE, diagnostics, "SIR-APP-CHANGE-BASELINE-003", "in-memory snapshot serialization of rebuilt graph failed"
                  );
               } else {
                  ProjectGraphLoader loader = new ProjectGraphLoader();
                  ProjectGraphAnalysis loadResult = loader.load(snapshotBytes);
                  if (loadResult instanceof Failure loadFailure) {
                     return failure(
                        ChangeExecutionStage.BASELINE,
                        diagnostics,
                        "SIR-APP-CHANGE-BASELINE-003",
                        "in-memory snapshot load failed: " + loadFailure.diagnostics()
                     );
                  } else {
                     ProjectGraph loadedGraph = ((Success)loadResult).graph();
                     if (!loadedGraph.canonicalDigest().equals(baseGraph.canonicalDigest())) {
                        return failure(
                           ChangeExecutionStage.BASELINE,
                           diagnostics,
                           "SIR-APP-CHANGE-BASELINE-005",
                           "in-memory snapshot digest mismatch: loaded=" + loadedGraph.canonicalDigest() + " rebuilt=" + baseGraph.canonicalDigest()
                        );
                     }

                     BaselineBundle b0Bundle;
                     try {
                        b0Bundle = BaselineBuilder.build(baseSnapshot, baseGraph, baseSourceBytes, snapshotBytes, sourceId, normalizedOutputRoot);
                     } catch (IllegalArgumentException e) {
                        return failure(
                           ChangeExecutionStage.BASELINE, diagnostics, "SIR-APP-CHANGE-BASELINE-001", "B0 Bundle construction failed: " + e.getMessage()
                        );
                     }

                     return this.publishInitialBaseline(store, b0Bundle, normalizedOutputRoot, diagnostics);
                  }
               }
            }
         }
      }
   }

   private ChangeBaselineRegistrationResult publishInitialBaseline(
      BaselineBundleStore store, BaselineBundle b0Bundle, Path normalizedOutputRoot, List<ChangeExecutionDiagnostic> diagnostics
   ) {
      BaselineBundleStore.CurrentReadResult currentRead = store.readCurrent();
      if (currentRead instanceof BaselineBundleStore.CurrentReadResult.Present p) {
         if (p.baselineId().equals(b0Bundle.baselineId())) {
            ChangeBaselineReceipt receipt = toReceipt(b0Bundle);
            return ChangeBaselineRegistrationResult.alreadyRegistered(receipt, List.copyOf(diagnostics));
         } else {
            return failure(
               ChangeExecutionStage.BASELINE,
               diagnostics,
               "SIR-APP-CHANGE-BASELINE-007",
               "stateRoot already bound to a different baseline: current=" + p.baselineId() + " new=" + b0Bundle.baselineId()
            );
         }
      } else if (currentRead instanceof BaselineBundleStore.CurrentReadResult.Failure f) {
         return failure(ChangeExecutionStage.BASELINE, diagnostics, f.code(), "failed to read CURRENT: " + f.message());
      } else {
         List<ChangeExecutionDiagnostic> manifestErrors = OutputManifestVerifier.verify(
            normalizedOutputRoot, b0Bundle.descriptor().manifest(), ChangeExecutionStage.BASELINE
         );
         if (!manifestErrors.isEmpty()) {
            return failure(ChangeExecutionStage.BASELINE, concat(diagnostics, manifestErrors));
         } else if (store.stageBundle(b0Bundle) instanceof BaselineBundleStore.LoadResult.Failure sf) {
            return failure(ChangeExecutionStage.BASELINE, diagnostics, sf.code(), "B0 staging failed: " + sf.message());
         } else {
            try {
               store.writeCurrentAtomic(b0Bundle.baselineId());
            } catch (IOException e) {
               return failure(ChangeExecutionStage.BASELINE, diagnostics, "SIR-APP-CHANGE-BASELINE-008", "CURRENT atomic publish failed: " + e.getMessage());
            }

            ChangeBaselineReceipt receipt = toReceipt(b0Bundle);
            return ChangeBaselineRegistrationResult.success(receipt, List.copyOf(diagnostics));
         }
      }
   }

   public ChangeBaselinePlanningResult plan(ChangeBaselinePlanningRequest request) {
      Objects.requireNonNull(request, "request");
      List<ChangeExecutionDiagnostic> diagnostics = new ArrayList<>();
      ChangeExecutionApplication.OperationContext ctx = this.acquireAndValidate(
         request.stateRoot(), request.outputRoot(), request.expectedBaselineId(), request.changeSet().basedOn(), diagnostics, true
      );
      if (ctx == null) {
         return planFailure(diagnostics);
      }

      try {
         return this.doPlan(request, ctx, diagnostics);
      } finally {
         if (ctx.lock != null) {
            ctx.lock.close();
         }
      }
   }

   private ChangeBaselinePlanningResult doPlan(
      ChangeBaselinePlanningRequest request, ChangeExecutionApplication.OperationContext ctx, List<ChangeExecutionDiagnostic> diagnostics
   ) {
      List<ExecutionDiagnostic> readDiags = new ArrayList<>();
      Optional<byte[]> candidateRaw = InputFileGuard.readSirFile(request.candidateSirFile(), readDiags);
      if (candidateRaw.isEmpty()) {
         diagnostics.addAll(mapExecutionDiags(readDiags, ChangeExecutionStage.READ));
         return planFailure(ChangeExecutionStage.READ, ctx.receipt, diagnostics);
      }

      byte[] candidateSourceBytes = candidateRaw.get();
      if (request.expectedCandidateSirSha256Hex().isPresent()) {
         String actualDigest = Sha256.hexDigest(candidateSourceBytes);
         if (!actualDigest.equals(request.expectedCandidateSirSha256Hex().get())) {
            return planFailure(
               ChangeExecutionStage.READ,
               ctx.receipt,
               diagnostics,
               "SIR-APP-CHANGE-BIND-008",
               "candidate SIR digest mismatch: expected=" + request.expectedCandidateSirSha256Hex().get() + " actual=" + actualDigest
            );
         }
      }

      Runnable hook = this.afterDigestMatchHook;
      if (hook != null) {
         hook.run();
      }

      String candidateSourceText;
      try {
         candidateSourceText = SourceReader.decodeStrictUtf8(candidateSourceBytes);
      } catch (SourceReader.InvalidUtf8Exception e) {
         return planFailure(ChangeExecutionStage.READ, ctx.receipt, diagnostics, "SIR-APP-CHANGE-BASELINE-001", "candidate SIR is not valid UTF-8");
      }

      String baseSourceText;
      try {
         baseSourceText = SourceReader.decodeStrictUtf8(ctx.bundle.sourceBytes());
      } catch (SourceReader.InvalidUtf8Exception e) {
         return planFailure(ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics, "SIR-APP-CHANGE-BASELINE-001", "Bundle source.sir is not valid UTF-8");
      }

      List<ExecutionDiagnostic> baseCompileDiags = new ArrayList<>();
      Optional<SirCompilation.CompilationSnapshot> baseCompiled = SirCompilation.compile(baseSourceText, ctx.sourceId, this.generationStep, baseCompileDiags);
      if (baseCompiled.isEmpty()) {
         diagnostics.addAll(mapExecutionDiags(baseCompileDiags, ChangeExecutionStage.RECOMPILE));
         return planFailure(ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics);
      }

      SirCompilation.CompilationSnapshot baseSnapshot = baseCompiled.get();
      ProjectGraph rebuiltBaseGraph = this.buildGraph(baseSnapshot, ctx.sourceId, diagnostics);
      if (rebuiltBaseGraph == null) {
         return planFailure(ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics);
      }

      if (!rebuiltBaseGraph.canonicalDigest().equals(ctx.bundle.descriptor().graphCanonicalDigest())) {
         return planFailure(
            ChangeExecutionStage.RECOMPILE,
            ctx.receipt,
            diagnostics,
            "SIR-APP-CHANGE-BASELINE-005",
            "rebuilt base graph digest does not match Bundle: rebuilt="
               + rebuiltBaseGraph.canonicalDigest()
               + " bundle="
               + ctx.bundle.descriptor().graphCanonicalDigest()
         );
      }

      byte[] reserialized = BaselineBuilder.serializeGraph(rebuiltBaseGraph);
      if (reserialized != null && Arrays.equals(ctx.bundle.snapshotBytes(), reserialized)) {
         List<ExecutionDiagnostic> candidateCompileDiags = new ArrayList<>();
         Optional<SirCompilation.CompilationSnapshot> candidateCompiled = SirCompilation.compile(
            candidateSourceText, ctx.sourceId, this.generationStep, candidateCompileDiags
         );
         if (candidateCompiled.isEmpty()) {
            diagnostics.addAll(mapExecutionDiags(candidateCompileDiags, ChangeExecutionStage.RECOMPILE));
            return planFailure(ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics);
         }

         SirCompilation.CompilationSnapshot candidateSnapshot = candidateCompiled.get();
         ProjectGraph candidateGraph = this.buildGraph(candidateSnapshot, ctx.sourceId, diagnostics);
         if (candidateGraph == null) {
            return planFailure(ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics);
         }

         ChangePlanningInput plannerInput = new ChangePlanningInput(
            baseSnapshot.semanticModel(), candidateSnapshot.semanticModel(), rebuiltBaseGraph, candidateGraph, request.changeSet()
         );
         ChangeAnalysis analysis = this.planningStep.apply(plannerInput);
         diagnostics.addAll(mapPlannerDiags(analysis.diagnostics()));
         if (analysis instanceof io.kcg.sir.change.api.ChangeAnalysis.Failure) {
            return planFailure(ChangeExecutionStage.PLAN, ctx.receipt, diagnostics);
         }

         List<FileChange> fileChanges = fileChangesOf(analysis);
         if (!fileChanges.isEmpty()) {
            List<ExecutionDiagnostic> protectErrors = this.planProtection.protectUpdate(ctx.normalizedOutputRoot, fileChanges);
            if (!protectErrors.isEmpty()) {
               diagnostics.addAll(mapExecutionDiags(protectErrors, ChangeExecutionStage.PROTECT));
               return planFailure(ChangeExecutionStage.PROTECT, ctx.receipt, diagnostics);
            }
         }

         List<FileAddition> fileAdditions = fileAdditionsOf(analysis);
         if (!fileAdditions.isEmpty()) {
            List<ExecutionDiagnostic> protectErrors = this.planProtection.protectCreate(ctx.normalizedOutputRoot, fileAdditions);
            if (!protectErrors.isEmpty()) {
               diagnostics.addAll(mapExecutionDiags(protectErrors, ChangeExecutionStage.PROTECT));
               return planFailure(ChangeExecutionStage.PROTECT, ctx.receipt, diagnostics);
            }
         }

         List<FileDeletion> fileDeletions = fileDeletionsOf(analysis);
         if (!fileDeletions.isEmpty()) {
            List<ExecutionDiagnostic> protectErrors = this.planProtection.protectDelete(ctx.normalizedOutputRoot, fileDeletions);
            if (!protectErrors.isEmpty()) {
               diagnostics.addAll(mapExecutionDiags(protectErrors, ChangeExecutionStage.PROTECT));
               return planFailure(ChangeExecutionStage.PROTECT, ctx.receipt, diagnostics);
            }
         }

         return new ChangeBaselinePlanningResult.Success(ctx.receipt, analysis, rebuiltBaseGraph, candidateGraph, filterNonErrors(diagnostics));
      } else {
         return planFailure(
            ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics, "SIR-APP-CHANGE-BASELINE-005", "re-serialized bytes do not match Bundle snapshot"
         );
      }
   }

   public ChangePlanningContextResult inspectChangePlanningContext(ChangePlanningContextRequest request) {
      Objects.requireNonNull(request, "request");
      List<ChangeExecutionDiagnostic> diagnostics = new ArrayList<>();
      StateRootPathGuard.Result pathResult = StateRootPathGuard.validate(request.stateRoot(), request.outputRoot(), true);
      if (!pathResult.isSuccess()) {
         diagnostics.addAll(pathResult.errors());
         return ChangePlanningContextResult.failure(ChangeExecutionStage.BASELINE, diagnostics);
      }

      Path normalizedStateRoot = pathResult.normalizedStateRoot();
      Path normalizedOutputRoot = pathResult.normalizedOutputRoot();
      StateRootLock.HeldLock lock = this.acquireLock(normalizedStateRoot, diagnostics, true);
      if (lock == null) {
         return ChangePlanningContextResult.failure(ChangeExecutionStage.BASELINE, diagnostics);
      }

      try {
         return this.doInspectContext(request, normalizedStateRoot, normalizedOutputRoot, diagnostics);
      } finally {
         lock.close();
      }
   }

   private ChangePlanningContextResult doInspectContext(
      ChangePlanningContextRequest request, Path normalizedStateRoot, Path normalizedOutputRoot, List<ChangeExecutionDiagnostic> diagnostics
   ) {
      StateRootPathGuard.Result revalidated = StateRootPathGuard.validate(normalizedStateRoot, normalizedOutputRoot, true);
      if (!revalidated.isSuccess()) {
         diagnostics.addAll(revalidated.errors());
         return ChangePlanningContextResult.failure(ChangeExecutionStage.BASELINE, diagnostics);
      }

      BaselineBundleStore store = new BaselineBundleStore(normalizedStateRoot);
      BaselineBundleStore.CurrentReadResult currentRead = store.readCurrent();
      Optional<String> currentId;
      if (currentRead instanceof BaselineBundleStore.CurrentReadResult.Present p) {
         currentId = Optional.of(p.baselineId());
      } else {
         if (!(currentRead instanceof BaselineBundleStore.CurrentReadResult.Absent)) {
            BaselineBundleStore.CurrentReadResult.Failure f = (BaselineBundleStore.CurrentReadResult.Failure)currentRead;
            diagnostics.add(diag(f.code(), "failed to read CURRENT: " + f.message()));
            return ChangePlanningContextResult.failure(ChangeExecutionStage.BASELINE, diagnostics);
         }

         currentId = Optional.empty();
      }

      JournalGate.InspectionResult gateResult = new JournalGate(normalizedStateRoot).inspect(currentId);
      if (!gateResult.isOpen()) {
         diagnostics.addAll(gateResult.errors());
         return ChangePlanningContextResult.recoveryRequired(RecoveryHandle.any(), diagnostics);
      } else if (currentId.isEmpty()) {
         diagnostics.add(diag("SIR-APP-CHANGE-BASELINE-002", "no CURRENT baseline: register first"));
         return ChangePlanningContextResult.failure(ChangeExecutionStage.BASELINE, diagnostics);
      } else {
         BaselineBundleStore.LoadResult loadResult = store.loadBundle(currentId.get());
         if (loadResult instanceof BaselineBundleStore.LoadResult.Failure f) {
            diagnostics.add(diag(f.code(), f.message()));
            return ChangePlanningContextResult.failure(ChangeExecutionStage.BASELINE, diagnostics);
         } else {
            BaselineBundle bundle = ((BaselineBundleStore.LoadResult.Success)loadResult).bundle();
            if (!bundle.descriptor().boundOutputRoot().equals(normalizedOutputRoot)) {
               diagnostics.add(
                  diag(
                     "SIR-APP-CHANGE-BASELINE-007",
                     "outputRoot does not match Bundle binding: expected=" + bundle.descriptor().boundOutputRoot() + " actual=" + normalizedOutputRoot
                  )
               );
               return ChangePlanningContextResult.failure(ChangeExecutionStage.BASELINE, diagnostics);
            }

            ChangeBaselineReceipt receipt = toReceipt(bundle);
            SourceId sourceId = bundle.descriptor().sourceId();
            List<ExecutionDiagnostic> readDiags = new ArrayList<>();
            Optional<byte[]> candidateRaw = InputFileGuard.readSirFile(request.candidateSirFile(), readDiags);
            if (candidateRaw.isEmpty()) {
               diagnostics.addAll(mapExecutionDiags(readDiags, ChangeExecutionStage.READ));
               return ChangePlanningContextResult.failure(ChangeExecutionStage.READ, diagnostics);
            }

            byte[] candidateSourceBytes = candidateRaw.get();
            String candidateSourceSha256Hex = Sha256.hexDigest(candidateSourceBytes);

            String baseSourceText;
            try {
               baseSourceText = SourceReader.decodeStrictUtf8(bundle.sourceBytes());
            } catch (SourceReader.InvalidUtf8Exception e) {
               return contextFailure(ChangeExecutionStage.RECOMPILE, diagnostics, "SIR-APP-CHANGE-BASELINE-001", "Bundle source.sir is not valid UTF-8");
            }

            List<ExecutionDiagnostic> baseCompileDiags = new ArrayList<>();
            Optional<SirCompilation.CompilationSnapshot> baseCompiled = SirCompilation.compile(baseSourceText, sourceId, this.generationStep, baseCompileDiags);
            if (baseCompiled.isEmpty()) {
               diagnostics.addAll(mapExecutionDiags(baseCompileDiags, ChangeExecutionStage.RECOMPILE));
               return ChangePlanningContextResult.failure(ChangeExecutionStage.RECOMPILE, diagnostics);
            }

            SirCompilation.CompilationSnapshot baseSnapshot = baseCompiled.get();
            ProjectGraph rebuiltBaseGraph = this.buildGraph(baseSnapshot, sourceId, diagnostics);
            if (rebuiltBaseGraph == null) {
               return ChangePlanningContextResult.failure(ChangeExecutionStage.RECOMPILE, diagnostics);
            }

            if (!rebuiltBaseGraph.canonicalDigest().equals(bundle.descriptor().graphCanonicalDigest())) {
               return contextFailure(
                  ChangeExecutionStage.RECOMPILE,
                  diagnostics,
                  "SIR-APP-CHANGE-BASELINE-005",
                  "rebuilt base graph digest does not match Bundle: rebuilt="
                     + rebuiltBaseGraph.canonicalDigest()
                     + " bundle="
                     + bundle.descriptor().graphCanonicalDigest()
               );
            }

            byte[] reserialized = BaselineBuilder.serializeGraph(rebuiltBaseGraph);
            if (reserialized != null && Arrays.equals(bundle.snapshotBytes(), reserialized)) {
               String candidateSourceText;
               try {
                  candidateSourceText = SourceReader.decodeStrictUtf8(candidateSourceBytes);
               } catch (SourceReader.InvalidUtf8Exception e) {
                  return contextFailure(ChangeExecutionStage.RECOMPILE, diagnostics, "SIR-APP-CHANGE-BASELINE-001", "candidate SIR is not valid UTF-8");
               }

               List<ExecutionDiagnostic> candidateCompileDiags = new ArrayList<>();
               Optional<SirCompilation.CompilationSnapshot> candidateCompiled = SirCompilation.compile(
                  candidateSourceText, sourceId, this.generationStep, candidateCompileDiags
               );
               if (candidateCompiled.isEmpty()) {
                  diagnostics.addAll(mapExecutionDiags(candidateCompileDiags, ChangeExecutionStage.RECOMPILE));
                  return ChangePlanningContextResult.failure(ChangeExecutionStage.RECOMPILE, diagnostics);
               }

               SirCompilation.CompilationSnapshot candidateSnapshot = candidateCompiled.get();
               ProjectGraph candidateGraph = this.buildGraph(candidateSnapshot, sourceId, diagnostics);
               if (candidateGraph == null) {
                  return ChangePlanningContextResult.failure(ChangeExecutionStage.RECOMPILE, diagnostics);
               }

               List<ChangePlanningTarget> targets = TargetCatalogBuilder.build(baseSnapshot.semanticModel(), candidateSnapshot.semanticModel());
               String contextId = ChangePlanningHasher.contextId(ChangePlanningContextFormatVersion.V1, receipt, candidateSourceSha256Hex, targets);
               ChangePlanningContext context = new ChangePlanningContext(
                  ChangePlanningContextFormatVersion.V1, receipt, candidateSourceSha256Hex, contextId, targets
               );
               return ChangePlanningContextResult.success(context, filterNonErrors(diagnostics));
            } else {
               return contextFailure(
                  ChangeExecutionStage.RECOMPILE, diagnostics, "SIR-APP-CHANGE-BASELINE-005", "re-serialized bytes do not match Bundle snapshot"
               );
            }
         }
      }
   }

   private static ChangePlanningContextResult contextFailure(
      ChangeExecutionStage stage, List<ChangeExecutionDiagnostic> diagnostics, String code, String message
   ) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>(diagnostics);
      out.add(new ChangeExecutionDiagnostic(code, stage, ExecutionSeverity.ERROR, message, Optional.empty()));
      return ChangePlanningContextResult.failure(stage, List.copyOf(out));
   }

   public ChangeApplyResult apply(ChangeApplyRequest request) {
      Objects.requireNonNull(request, "request");
      List<ChangeExecutionDiagnostic> diagnostics = new ArrayList<>();
      ChangeExecutionApplication.OperationContext ctx = this.acquireAndValidate(
         request.stateRoot(), request.outputRoot(), request.expectedBaselineId(), request.changeSet().basedOn(), diagnostics
      );
      if (ctx == null) {
         return applyFailure(diagnostics);
      }

      try {
         return this.doApply(request, ctx, diagnostics);
      } finally {
         if (ctx.lock != null) {
            ctx.lock.close();
         }
      }
   }

   private ChangeApplyResult doApply(ChangeApplyRequest request, ChangeExecutionApplication.OperationContext ctx, List<ChangeExecutionDiagnostic> diagnostics) {
      List<ExecutionDiagnostic> readDiags = new ArrayList<>();
      Optional<byte[]> candidateRaw = InputFileGuard.readSirFile(request.candidateSirFile(), readDiags);
      if (candidateRaw.isEmpty()) {
         diagnostics.addAll(mapExecutionDiags(readDiags, ChangeExecutionStage.READ));
         return applyFailure(ChangeExecutionStage.READ, ctx.receipt, diagnostics);
      }

      byte[] candidateSourceBytes = candidateRaw.get();
      if (request.expectedCandidateSirSha256Hex().isPresent()) {
         String actualDigest = Sha256.hexDigest(candidateSourceBytes);
         if (!actualDigest.equals(request.expectedCandidateSirSha256Hex().get())) {
            return applyFailure(
               ChangeExecutionStage.READ,
               ctx.receipt,
               diagnostics,
               "SIR-APP-CHANGE-BIND-009",
               "candidate SIR digest mismatch: expected=" + request.expectedCandidateSirSha256Hex().get() + " actual=" + actualDigest
            );
         }
      }

      Runnable applyHook = this.afterApplyDigestMatchHook;
      if (applyHook != null) {
         applyHook.run();
      }

      String candidateSourceText;
      try {
         candidateSourceText = SourceReader.decodeStrictUtf8(candidateSourceBytes);
      } catch (SourceReader.InvalidUtf8Exception e) {
         return applyFailure(ChangeExecutionStage.READ, ctx.receipt, diagnostics, "SIR-APP-CHANGE-BASELINE-001", "candidate SIR is not valid UTF-8");
      }

      String baseSourceText;
      try {
         baseSourceText = SourceReader.decodeStrictUtf8(ctx.bundle.sourceBytes());
      } catch (SourceReader.InvalidUtf8Exception e) {
         return applyFailure(ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics, "SIR-APP-CHANGE-BASELINE-001", "Bundle source.sir is not valid UTF-8");
      }

      List<ExecutionDiagnostic> baseCompileDiags = new ArrayList<>();
      Optional<SirCompilation.CompilationSnapshot> baseCompiled = SirCompilation.compile(baseSourceText, ctx.sourceId, this.generationStep, baseCompileDiags);
      if (baseCompiled.isEmpty()) {
         diagnostics.addAll(mapExecutionDiags(baseCompileDiags, ChangeExecutionStage.RECOMPILE));
         return applyFailure(ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics);
      }

      SirCompilation.CompilationSnapshot baseSnapshot = baseCompiled.get();
      ProjectGraph rebuiltBaseGraph = this.buildGraph(baseSnapshot, ctx.sourceId, diagnostics);
      if (rebuiltBaseGraph == null) {
         return applyFailure(ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics);
      }

      if (!rebuiltBaseGraph.canonicalDigest().equals(ctx.bundle.descriptor().graphCanonicalDigest())) {
         return applyFailure(
            ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics, "SIR-APP-CHANGE-BASELINE-005", "rebuilt base graph digest does not match Bundle"
         );
      }

      byte[] reserialized = BaselineBuilder.serializeGraph(rebuiltBaseGraph);
      if (reserialized != null && Arrays.equals(ctx.bundle.snapshotBytes(), reserialized)) {
         List<ExecutionDiagnostic> candidateCompileDiags = new ArrayList<>();
         Optional<SirCompilation.CompilationSnapshot> candidateCompiled = SirCompilation.compile(
            candidateSourceText, ctx.sourceId, this.generationStep, candidateCompileDiags
         );
         if (candidateCompiled.isEmpty()) {
            diagnostics.addAll(mapExecutionDiags(candidateCompileDiags, ChangeExecutionStage.RECOMPILE));
            return applyFailure(ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics);
         }

         SirCompilation.CompilationSnapshot candidateSnapshot = candidateCompiled.get();
         ProjectGraph candidateGraph = this.buildGraph(candidateSnapshot, ctx.sourceId, diagnostics);
         if (candidateGraph == null) {
            return applyFailure(ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics);
         }

         ChangePlanningInput plannerInput = new ChangePlanningInput(
            baseSnapshot.semanticModel(), candidateSnapshot.semanticModel(), rebuiltBaseGraph, candidateGraph, request.changeSet()
         );
         ChangeAnalysis analysis = this.planner.plan(plannerInput);
         diagnostics.addAll(mapPlannerDiags(analysis.diagnostics()));
         if (analysis instanceof io.kcg.sir.change.api.ChangeAnalysis.Failure) {
            return applyFailure(ChangeExecutionStage.PLAN, ctx.receipt, diagnostics);
         }

         byte[] candidateSnapshotBytes = BaselineBuilder.serializeGraph(candidateGraph);
         if (candidateSnapshotBytes == null) {
            return applyFailure(ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics, "SIR-APP-CHANGE-BASELINE-001", "failed to serialize candidate graph");
         }

         BaselineBundle b1Bundle;
         try {
            b1Bundle = BaselineBuilder.build(
               candidateSnapshot, candidateGraph, candidateSourceBytes, candidateSnapshotBytes, ctx.sourceId, ctx.normalizedOutputRoot
            );
         } catch (IllegalArgumentException e) {
            return applyFailure(
               ChangeExecutionStage.BASELINE, ctx.receipt, diagnostics, "SIR-APP-CHANGE-BASELINE-001", "B1 Bundle construction failed: " + e.getMessage()
            );
         }

         if (analysis instanceof NoChanges noChanges) {
            return b1Bundle.baselineId().equals(ctx.bundle.baselineId())
               ? new ChangeApplyResult.NoChanges(ctx.receipt, noChanges.reason(), filterNonErrors(diagnostics))
               : this.runTransaction(ctx, b1Bundle, List.of(), Map.of(), ChangeApplyOutcome.BASELINE_ONLY, diagnostics);
         } else {
            List<FileChange> fileChanges = fileChangesOf(analysis);
            List<FileAddition> fileAdditions = fileAdditionsOf(analysis);
            if (!fileChanges.isEmpty()) {
               List<ExecutionDiagnostic> protectErrors = PlanProtector.protect(ctx.normalizedOutputRoot, fileChanges);
               if (!protectErrors.isEmpty()) {
                  diagnostics.addAll(mapExecutionDiags(protectErrors, ChangeExecutionStage.PROTECT));
                  return applyFailure(ChangeExecutionStage.PROTECT, ctx.receipt, diagnostics);
               }

               ChangeExecutionDiagnostic bindError = this.verifyCandidateBinding(
                  fileChanges, baseSnapshot.generatedFiles(), candidateSnapshot.generatedFiles(), diagnostics
               );
               if (bindError != null) {
                  return applyFailure(ChangeExecutionStage.PROTECT, ctx.receipt, withFallback(diagnostics, bindError));
               }

               Map<String, byte[]> candidateBytes = this.buildCandidateBytesMap(fileChanges, candidateSnapshot.generatedFiles());
               return this.runTransaction(ctx, b1Bundle, fileChanges, candidateBytes, ChangeApplyOutcome.FILES_AND_BASELINE, diagnostics);
            } else {
               if (!fileAdditions.isEmpty()) {
                  return this.runCreateApply(ctx, request, b1Bundle, analysis, baseSnapshot, candidateSnapshot, candidateGraph, diagnostics);
               }

               List<FileDeletion> fileDeletions = fileDeletionsOf(analysis);
               return !fileDeletions.isEmpty()
                  ? this.runDeleteApply(ctx, request, b1Bundle, analysis, baseSnapshot, candidateSnapshot, candidateGraph, diagnostics)
                  : applyFailure(
                     ChangeExecutionStage.PLAN,
                     ctx.receipt,
                     diagnostics,
                     "SIR-APP-CHANGE-BIND-001",
                     "Apply only supports UPDATE, AddCapability CREATE, and RemoveCapability DELETE families"
                  );
            }
         }
      } else {
         return applyFailure(
            ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics, "SIR-APP-CHANGE-BASELINE-005", "re-serialized bytes do not match Bundle snapshot"
         );
      }
   }

   private ChangeApplyResult runTransaction(
      ChangeExecutionApplication.OperationContext ctx,
      BaselineBundle b1Bundle,
      List<FileChange> fileChanges,
      Map<String, byte[]> candidateBytes,
      ChangeApplyOutcome outcome,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      BaselineBundleStore store = new BaselineBundleStore(ctx.normalizedStateRoot);
      ChangeApplyTransaction transaction = new ChangeApplyTransaction(
         store, ctx.normalizedOutputRoot, ctx.bundle, b1Bundle, fileChanges, candidateBytes, outcome, this.applyHooks
      );
      return transaction.execute();
   }

   private ChangeApplyResult runCreateApply(
      ChangeExecutionApplication.OperationContext ctx,
      ChangeApplyRequest request,
      BaselineBundle b1Bundle,
      ChangeAnalysis analysis,
      SirCompilation.CompilationSnapshot baseSnapshot,
      SirCompilation.CompilationSnapshot candidateSnapshot,
      ProjectGraph candidateGraph,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      List<FileAddition> fileAdditions = fileAdditionsOf(analysis);
      List<ArtifactAddition> artifactAdditions = artifactAdditionsOf(analysis);
      ChangeExecutionDiagnostic authError = this.verifyCreateAuthorization(request.changeSet(), artifactAdditions, fileAdditions);
      if (authError != null) {
         return applyFailure(ChangeExecutionStage.PLAN, ctx.receipt, withFallback(diagnostics, authError));
      } else {
         List<ExecutionDiagnostic> protectErrors = PlanProtector.protectAdditions(ctx.normalizedOutputRoot, fileAdditions);
         if (!protectErrors.isEmpty()) {
            diagnostics.addAll(mapExecutionDiags(protectErrors, ChangeExecutionStage.PROTECT));
            return applyFailure(ChangeExecutionStage.PROTECT, ctx.receipt, diagnostics);
         } else {
            ChangeExecutionDiagnostic bindError = this.verifyCreateCandidateBinding(
               fileAdditions,
               artifactAdditions,
               baseSnapshot.generatedFiles(),
               candidateSnapshot.generatedFiles(),
               ctx.bundle.descriptor().manifest(),
               b1Bundle.descriptor().manifest(),
               candidateGraph,
               diagnostics
            );
            if (bindError != null) {
               return applyFailure(ChangeExecutionStage.PROTECT, ctx.receipt, withFallback(diagnostics, bindError));
            } else if (CreateManifestDeltaVerifier.verify(ctx.bundle.descriptor().manifest(), b1Bundle.descriptor().manifest(), fileAdditions) instanceof CreateManifestDeltaVerifier.Result.Failure f
               )
             {
               return applyFailure(ChangeExecutionStage.PROTECT, ctx.receipt, withFallback(diagnostics, f.error()));
            } else {
               List<CreateFilePayload> payloads;
               try {
                  payloads = this.buildCreateFilePayloads(fileAdditions, candidateSnapshot.generatedFiles(), b1Bundle.descriptor().manifest());
               } catch (IllegalArgumentException e) {
                  return applyFailure(
                     ChangeExecutionStage.PROTECT,
                     ctx.receipt,
                     diagnostics,
                     "SIR-APP-CHANGE-BIND-005",
                     "CreateFilePayload construction failed: " + e.getMessage()
                  );
               }

               BaselineBundleStore store = new BaselineBundleStore(ctx.normalizedStateRoot);
               ChangeCreateTransaction transaction = new ChangeCreateTransaction(
                  store, ctx.normalizedOutputRoot, ctx.bundle, b1Bundle, payloads, this.applyHooks
               );
               return transaction.execute();
            }
         }
      }
   }

   private ChangeApplyResult runDeleteApply(
      ChangeExecutionApplication.OperationContext ctx,
      ChangeApplyRequest request,
      BaselineBundle b1Bundle,
      ChangeAnalysis analysis,
      SirCompilation.CompilationSnapshot baseSnapshot,
      SirCompilation.CompilationSnapshot candidateSnapshot,
      ProjectGraph candidateGraph,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      ProjectGraph baseGraph = this.buildGraph(baseSnapshot, ctx.sourceId, diagnostics);
      if (baseGraph == null) {
         return applyFailure(ChangeExecutionStage.RECOMPILE, ctx.receipt, diagnostics);
      } else if (!baseGraph.canonicalDigest().equals(ctx.bundle.descriptor().graphCanonicalDigest())) {
         return applyFailure(
            ChangeExecutionStage.RECOMPILE,
            ctx.receipt,
            diagnostics,
            "SIR-APP-CHANGE-BASELINE-005",
            "rebuilt base graph digest does not match Bundle (DELETE Apply)"
         );
      } else {
         List<FileDeletion> fileDeletions = fileDeletionsOf(analysis);
         List<ArtifactDeletion> artifactDeletions = artifactDeletionsOf(analysis);
         ChangeExecutionDiagnostic authError = this.verifyDeleteAuthorization(request.changeSet(), artifactDeletions, fileDeletions);
         if (authError != null) {
            return applyFailure(ChangeExecutionStage.PLAN, ctx.receipt, withFallback(diagnostics, authError));
         } else {
            List<ExecutionDiagnostic> protectErrors = PlanProtector.protectDeletions(ctx.normalizedOutputRoot, fileDeletions);
            if (!protectErrors.isEmpty()) {
               diagnostics.addAll(mapExecutionDiags(protectErrors, ChangeExecutionStage.PROTECT));
               return applyFailure(ChangeExecutionStage.PROTECT, ctx.receipt, diagnostics);
            } else if (DeletePlanBindingVerifier.verify(
               fileDeletions,
               artifactDeletions,
               baseSnapshot.generatedFiles(),
               candidateSnapshot.generatedFiles(),
               ctx.bundle.descriptor().manifest(),
               b1Bundle.descriptor().manifest(),
               baseGraph,
               candidateGraph
            ) instanceof DeletePlanBindingVerifier.Result.Failure f) {
               return applyFailure(ChangeExecutionStage.PROTECT, ctx.receipt, withFallback(diagnostics, f.error()));
            } else if (DeleteManifestDeltaVerifier.verify(ctx.bundle.descriptor().manifest(), b1Bundle.descriptor().manifest(), fileDeletions) instanceof DeleteManifestDeltaVerifier.Result.Failure df
               )
             {
               return applyFailure(ChangeExecutionStage.PROTECT, ctx.receipt, withFallback(diagnostics, df.error()));
            } else {
               List<DeleteFilePayload> payloads;
               try {
                  payloads = this.buildDeleteFilePayloads(fileDeletions, ctx.bundle.descriptor().manifest());
               } catch (IllegalArgumentException e) {
                  return applyFailure(
                     ChangeExecutionStage.PROTECT,
                     ctx.receipt,
                     diagnostics,
                     "SIR-APP-CHANGE-BIND-005",
                     "DeleteFilePayload construction failed: " + e.getMessage()
                  );
               }

               BaselineBundleStore store = new BaselineBundleStore(ctx.normalizedStateRoot);
               ChangeDeleteTransaction deleteTransaction = new ChangeDeleteTransaction(
                  store, ctx.normalizedOutputRoot, ctx.bundle, b1Bundle, payloads, this.applyHooks
               );
               return deleteTransaction.execute();
            }
         }
      }
   }

   private ChangeExecutionDiagnostic verifyDeleteAuthorization(ChangeSet changeSet, List<ArtifactDeletion> artifactDeletions, List<FileDeletion> fileDeletions) {
      ChangeOperation op = changeSet.operations().get(0);
      if (!(op instanceof RemoveCapability)) {
         return diag("SIR-APP-CHANGE-BIND-001", "DELETE Apply requires RemoveCapability operation; got " + op.getClass().getSimpleName());
      }

      ChangeIrVersion version = changeSet.version();
      if (version != ChangeIrVersion.V0_3 && version != ChangeIrVersion.V0_4 && version != ChangeIrVersion.V0_5 && version != ChangeIrVersion.V0_6) {
         return diag("SIR-APP-CHANGE-BIND-001", "RemoveCapability Apply requires ChangeIrVersion V0_3..V0_6; got " + version);
      }

      if (!artifactDeletions.isEmpty() && !fileDeletions.isEmpty()) {
         for (ArtifactDeletion ad : artifactDeletions) {
            DeclarationRole role = ad.role();
            if (role != DeclarationRole.SERVICE && role != DeclarationRole.CONTROLLER) {
               return diag(
                  "SIR-APP-CHANGE-BIND-001",
                  "RemoveCapability Apply only permits SERVICE/CONTROLLER deletions; got " + role + " for " + ad.qualifiedName(),
                  ad.artifactId().value()
               );
            }
         }

         return null;
      } else {
         return diag("SIR-APP-CHANGE-BIND-001", "RemoveCapability Apply requires non-empty artifactDeletions and fileDeletions");
      }
   }

   private List<DeleteFilePayload> buildDeleteFilePayloads(List<FileDeletion> fileDeletions, List<BaselineManifestEntry> b0Manifest) {
      Map<String, BaselineManifestEntry> b0Map = new LinkedHashMap<>();

      for (BaselineManifestEntry e : b0Manifest) {
         b0Map.put(e.relativePath(), e);
      }

      List<FileDeletion> sorted = new ArrayList<>(fileDeletions);
      sorted.sort(Comparator.comparing(FileDeletion::relativePath));
      List<DeleteFilePayload> payloads = new ArrayList<>(sorted.size());

      for (FileDeletion fd : sorted) {
         BaselineManifestEntry b0Entry = b0Map.get(fd.relativePath());
         if (b0Entry == null) {
            throw new IllegalArgumentException("B0 manifest entry missing for " + fd.relativePath());
         }

         payloads.add(new DeleteFilePayload(fd, b0Entry));
      }

      return List.copyOf(payloads);
   }

   private ChangeExecutionDiagnostic verifyCreateAuthorization(ChangeSet changeSet, List<ArtifactAddition> artifactAdditions, List<FileAddition> fileAdditions) {
      ChangeOperation op = changeSet.operations().get(0);
      if (!(op instanceof AddCapability)) {
         return diag("SIR-APP-CHANGE-BIND-001", "CREATE Apply requires AddCapability operation; got " + op.getClass().getSimpleName());
      }

      ChangeIrVersion version = changeSet.version();
      if (version != ChangeIrVersion.V0_2
         && version != ChangeIrVersion.V0_3
         && version != ChangeIrVersion.V0_4
         && version != ChangeIrVersion.V0_5
         && version != ChangeIrVersion.V0_6) {
         return diag("SIR-APP-CHANGE-BIND-001", "AddCapability Apply requires ChangeIrVersion V0_2..V0_6; got " + version);
      }

      if (!artifactAdditions.isEmpty() && !fileAdditions.isEmpty()) {
         for (ArtifactAddition aa : artifactAdditions) {
            DeclarationRole role = aa.role();
            if (role != DeclarationRole.SERVICE && role != DeclarationRole.CONTROLLER) {
               return diag(
                  "SIR-APP-CHANGE-BIND-001",
                  "AddCapability Apply only permits SERVICE/CONTROLLER additions; got " + role + " for " + aa.qualifiedName(),
                  aa.artifactId().value()
               );
            }
         }

         return null;
      } else {
         return diag("SIR-APP-CHANGE-BIND-001", "AddCapability Apply requires non-empty artifactAdditions and fileAdditions");
      }
   }

   private ChangeExecutionDiagnostic verifyCreateCandidateBinding(
      List<FileAddition> fileAdditions,
      List<ArtifactAddition> artifactAdditions,
      List<GeneratedFile> baseFiles,
      List<GeneratedFile> candidateFiles,
      List<BaselineManifestEntry> b0Manifest,
      List<BaselineManifestEntry> b1Manifest,
      ProjectGraph candidateGraph,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      Map<String, GeneratedFile> baseMap = new LinkedHashMap<>();

      for (GeneratedFile gf : baseFiles) {
         if (baseMap.put(gf.relativePath(), gf) != null) {
            return diag("SIR-APP-CHANGE-BIND-001", "duplicate relativePath in base GeneratedFiles: " + gf.relativePath(), gf.relativePath());
         }
      }

      Map<String, GeneratedFile> candidateMap = new LinkedHashMap<>();

      for (GeneratedFile gf : candidateFiles) {
         if (candidateMap.put(gf.relativePath(), gf) != null) {
            return diag("SIR-APP-CHANGE-BIND-001", "duplicate relativePath in candidate GeneratedFiles: " + gf.relativePath(), gf.relativePath());
         }
      }

      Map<String, BaselineManifestEntry> b0Map = new LinkedHashMap<>();

      for (BaselineManifestEntry e : b0Manifest) {
         b0Map.put(e.relativePath(), e);
      }

      Map<String, BaselineManifestEntry> b1Map = new LinkedHashMap<>();

      for (BaselineManifestEntry e : b1Manifest) {
         b1Map.put(e.relativePath(), e);
      }

      Set<String> additionPaths = new LinkedHashSet<>();

      for (FileAddition fa : fileAdditions) {
         additionPaths.add(fa.relativePath());
         GeneratedFile candidateGf = candidateMap.get(fa.relativePath());
         if (candidateGf == null) {
            return diag("SIR-APP-CHANGE-BIND-002", "candidate GeneratedFile missing for planned addition: " + fa.relativePath(), fa.relativePath());
         }

         if (!candidateGf.artifactId().equals(fa.artifactId())) {
            return diag(
               "SIR-APP-CHANGE-BIND-002",
               "artifactId mismatch for addition " + fa.relativePath() + ": plan=" + fa.artifactId() + " candidate=" + candidateGf.artifactId(),
               fa.relativePath()
            );
         }

         if (!candidateGf.symbolId().isPresent() || !candidateGf.symbolId().get().equals(fa.ownerSymbol())) {
            return diag(
               "SIR-APP-CHANGE-BIND-002",
               "ownerSymbol mismatch for addition " + fa.relativePath() + ": plan=" + fa.ownerSymbol() + " candidate=" + candidateGf.symbolId(),
               fa.relativePath()
            );
         }

         byte[] candidateBytes = candidateGf.content().getBytes(StandardCharsets.UTF_8);
         if (candidateBytes.length != fa.candidateByteCount()) {
            return diag(
               "SIR-APP-CHANGE-BIND-003",
               "candidateByteCount mismatch for addition " + fa.relativePath() + ": plan=" + fa.candidateByteCount() + " actual=" + candidateBytes.length,
               fa.relativePath()
            );
         }

         String candidateSha = Sha256.hexDigest(candidateBytes);
         if (!candidateSha.equals(fa.candidateSha256Hex())) {
            return diag("SIR-APP-CHANGE-BIND-003", "candidateSha256 mismatch for addition " + fa.relativePath(), fa.relativePath());
         }

         if (baseMap.containsKey(fa.relativePath())) {
            return diag("SIR-APP-CHANGE-BIND-004", "addition path exists in base GeneratedFiles: " + fa.relativePath(), fa.relativePath());
         }

         if (b0Map.containsKey(fa.relativePath())) {
            return diag("SIR-APP-CHANGE-BIND-004", "addition path exists in B0 manifest: " + fa.relativePath(), fa.relativePath());
         }
      }

      Set<String> candidateAdditionSet = new LinkedHashSet<>();

      for (String rel : candidateMap.keySet()) {
         if (!baseMap.containsKey(rel)) {
            candidateAdditionSet.add(rel);
         }
      }

      for (Entry<String, GeneratedFile> entry : baseMap.entrySet()) {
         String rel = entry.getKey();
         GeneratedFile baseGf = entry.getValue();
         GeneratedFile candidateGf = candidateMap.get(rel);
         if (candidateGf == null) {
            return diag("SIR-APP-CHANGE-BIND-004", "base GeneratedFile missing in candidate: " + rel, rel);
         }

         if (!candidateGf.artifactId().equals(baseGf.artifactId())) {
            return diag(
               "SIR-APP-CHANGE-BIND-004",
               "artifactId drift for existing file " + rel + ": base=" + baseGf.artifactId() + " candidate=" + candidateGf.artifactId(),
               rel
            );
         }

         if (!candidateGf.symbolId().equals(baseGf.symbolId())) {
            return diag("SIR-APP-CHANGE-BIND-004", "ownerSymbol drift for existing file " + rel, rel);
         }

         byte[] baseBytes = baseGf.content().getBytes(StandardCharsets.UTF_8);
         byte[] candBytes = candidateGf.content().getBytes(StandardCharsets.UTF_8);
         if (!Arrays.equals(baseBytes, candBytes)) {
            return diag("SIR-APP-CHANGE-BIND-004", "byte drift for existing file " + rel, rel);
         }
      }

      if (!candidateAdditionSet.equals(additionPaths)) {
         return diag(
            "SIR-APP-CHANGE-BIND-004", "candidate-minus-base generated set does not match plan: plan=" + additionPaths + " actual=" + candidateAdditionSet
         );
      }

      for (ArtifactAddition aa : artifactAdditions) {
         for (FileAddition fa : aa.fileAdditions()) {
            if (!additionPaths.contains(fa.relativePath())) {
               return diag("SIR-APP-CHANGE-BIND-004", "ArtifactAddition file not in top-level fileAdditions: " + fa.relativePath(), fa.relativePath());
            }
         }
      }

      return null;
   }

   private List<CreateFilePayload> buildCreateFilePayloads(
      List<FileAddition> fileAdditions, List<GeneratedFile> candidateFiles, List<BaselineManifestEntry> b1Manifest
   ) {
      Map<String, GeneratedFile> candidateMap = new LinkedHashMap<>();

      for (GeneratedFile gf : candidateFiles) {
         candidateMap.put(gf.relativePath(), gf);
      }

      Map<String, BaselineManifestEntry> b1Map = new LinkedHashMap<>();

      for (BaselineManifestEntry e : b1Manifest) {
         b1Map.put(e.relativePath(), e);
      }

      List<FileAddition> sorted = new ArrayList<>(fileAdditions);
      sorted.sort(Comparator.comparing(FileAddition::relativePath));
      List<CreateFilePayload> payloads = new ArrayList<>(sorted.size());

      for (FileAddition fa : sorted) {
         GeneratedFile gf = candidateMap.get(fa.relativePath());
         if (gf == null) {
            throw new IllegalArgumentException("candidate GeneratedFile missing for " + fa.relativePath());
         }

         BaselineManifestEntry b1Entry = b1Map.get(fa.relativePath());
         if (b1Entry == null) {
            throw new IllegalArgumentException("B1 manifest entry missing for " + fa.relativePath());
         }

         byte[] stagedBytes = gf.content().getBytes(StandardCharsets.UTF_8);
         payloads.add(new CreateFilePayload(fa, stagedBytes, b1Entry));
      }

      return List.copyOf(payloads);
   }

   /**
    * The CURRENT bundle is loaded and its boundOutputRoot checked BEFORE
    * any recovery engine runs: an unreadable or foreign CURRENT must fail closed
    * before the engine could act on stale files. Absent CURRENT is a plain
    * failure ("nothing to recover"), not a silent no-op. Verified by
    * RecoveryStateMachineTest.currentB1_forwardRecoveryDoesNotRestoreDeletedFiles
    * and currentMatchesNeitherB0NorB1_isRejected.
    */
   public ChangeRecoveryResult recover(ChangeRecoveryRequest request) {
      Objects.requireNonNull(request, "request");
      List<ChangeExecutionDiagnostic> diagnostics = new ArrayList<>();
      StateRootPathGuard.Result pathResult = StateRootPathGuard.validate(request.stateRoot(), request.outputRoot(), true);
      if (!pathResult.isSuccess()) {
         return ChangeRecoveryResult.failure(ChangeExecutionStage.RECOVERY, concat(diagnostics, pathResult.errors()));
      }

      Path normalizedStateRoot = pathResult.normalizedStateRoot();
      Path normalizedOutputRoot = pathResult.normalizedOutputRoot();

      try (StateRootLock.HeldLock lock = this.acquireLock(normalizedStateRoot, diagnostics)) {
         if (lock == null) {
            return ChangeRecoveryResult.failure(ChangeExecutionStage.RECOVERY, List.copyOf(diagnostics));
         } else {
            StateRootPathGuard.Result revalidated = StateRootPathGuard.validate(normalizedStateRoot, normalizedOutputRoot, true);
            if (!revalidated.isSuccess()) {
               return ChangeRecoveryResult.failure(ChangeExecutionStage.RECOVERY, concat(diagnostics, revalidated.errors()));
            } else {
               BaselineBundleStore store = new BaselineBundleStore(normalizedStateRoot);
               BaselineBundleStore.CurrentReadResult currentRead = store.readCurrent();
               if (currentRead instanceof BaselineBundleStore.CurrentReadResult.Absent) {
                  return ChangeRecoveryResult.failure(
                     ChangeExecutionStage.RECOVERY, List.of(diag("SIR-APP-CHANGE-RECOVERY-002", "no CURRENT: nothing to recover"))
                  );
               } else if (currentRead instanceof BaselineBundleStore.CurrentReadResult.Failure f) {
                  return ChangeRecoveryResult.failure(ChangeExecutionStage.RECOVERY, List.of(diag(f.code(), f.message())));
               } else {
                  String currentId = ((BaselineBundleStore.CurrentReadResult.Present)currentRead).baselineId();
                  BaselineBundleStore.LoadResult loadResult = store.loadBundle(currentId);
                  if (loadResult instanceof BaselineBundleStore.LoadResult.Failure lf) {
                     return ChangeRecoveryResult.failure(ChangeExecutionStage.RECOVERY, List.of(diag(lf.code(), lf.message())));
                  } else {
                     BaselineBundle currentBundle = ((BaselineBundleStore.LoadResult.Success)loadResult).bundle();
                     if (!currentBundle.descriptor().boundOutputRoot().equals(normalizedOutputRoot)) {
                        return ChangeRecoveryResult.failure(
                           ChangeExecutionStage.RECOVERY,
                           List.of(
                              diag(
                                 "SIR-APP-CHANGE-BASELINE-007",
                                 "outputRoot does not match CURRENT binding: expected="
                                    + currentBundle.descriptor().boundOutputRoot()
                                    + " actual="
                                    + normalizedOutputRoot
                              )
                           )
                        );
                     }

                     ChangeRecoveryEngine engine = new ChangeRecoveryEngine(store, normalizedOutputRoot, request.handle());
                     return engine.execute();
                  }
               }
            }
         }
      }
   }

   private ChangeExecutionApplication.OperationContext acquireAndValidate(
      Path stateRoot, Path outputRoot, String expectedBaselineId, ChangeBaseRevision basedOn, List<ChangeExecutionDiagnostic> diagnostics
   ) {
      return this.acquireAndValidate(stateRoot, outputRoot, expectedBaselineId, basedOn, diagnostics, false);
   }

   private ChangeExecutionApplication.OperationContext acquireAndValidate(
      Path stateRoot, Path outputRoot, String expectedBaselineId, ChangeBaseRevision basedOn, List<ChangeExecutionDiagnostic> diagnostics, boolean existingOnly
   ) {
      StateRootPathGuard.Result pathResult = StateRootPathGuard.validate(stateRoot, outputRoot, true);
      if (!pathResult.isSuccess()) {
         diagnostics.addAll(pathResult.errors());
         return null;
      }

      Path normalizedStateRoot = pathResult.normalizedStateRoot();
      Path normalizedOutputRoot = pathResult.normalizedOutputRoot();
      StateRootLock.HeldLock lock = this.acquireLock(normalizedStateRoot, diagnostics, existingOnly);
      if (lock == null) {
         return null;
      }

      StateRootPathGuard.Result revalidated = StateRootPathGuard.validate(normalizedStateRoot, normalizedOutputRoot, true);
      if (!revalidated.isSuccess()) {
         lock.close();
         diagnostics.addAll(revalidated.errors());
         return null;
      }

      BaselineBundleStore store = new BaselineBundleStore(normalizedStateRoot);
      BaselineBundleStore.CurrentReadResult currentRead = store.readCurrent();
      Optional<String> currentId;
      if (currentRead instanceof BaselineBundleStore.CurrentReadResult.Present p) {
         currentId = Optional.of(p.baselineId());
      } else {
         if (!(currentRead instanceof BaselineBundleStore.CurrentReadResult.Absent)) {
            BaselineBundleStore.CurrentReadResult.Failure f = (BaselineBundleStore.CurrentReadResult.Failure)currentRead;
            diagnostics.add(diag(f.code(), "failed to read CURRENT: " + f.message()));
            lock.close();
            return null;
         }

         currentId = Optional.empty();
      }

      JournalGate.InspectionResult gateResult = new JournalGate(normalizedStateRoot).inspect(currentId);
      if (!gateResult.isOpen()) {
         diagnostics.addAll(gateResult.errors());
         lock.close();
         return null;
      } else if (currentId.isEmpty()) {
         diagnostics.add(diag("SIR-APP-CHANGE-BASELINE-002", "no CURRENT baseline: register first"));
         lock.close();
         return null;
      } else if (!currentId.get().equals(expectedBaselineId)) {
         diagnostics.add(diag("SIR-APP-CHANGE-BASELINE-002", "expectedBaselineId mismatch: expected=" + expectedBaselineId + " current=" + currentId.get()));
         lock.close();
         return null;
      } else {
         BaselineBundleStore.LoadResult loadResult = store.loadBundle(currentId.get());
         if (loadResult instanceof BaselineBundleStore.LoadResult.Failure f) {
            diagnostics.add(diag(f.code(), f.message()));
            lock.close();
            return null;
         } else {
            BaselineBundle bundle = ((BaselineBundleStore.LoadResult.Success)loadResult).bundle();
            if (!bundle.descriptor().boundOutputRoot().equals(normalizedOutputRoot)) {
               diagnostics.add(
                  diag(
                     "SIR-APP-CHANGE-BASELINE-007",
                     "outputRoot does not match Bundle binding: expected=" + bundle.descriptor().boundOutputRoot() + " actual=" + normalizedOutputRoot
                  )
               );
               lock.close();
               return null;
            } else {
               ChangeBaseRevision bundleRevision = bundle.descriptor().toBaseRevision();
               if (!basedOn.equals(bundleRevision)) {
                  diagnostics.add(
                     diag(
                        "SIR-APP-CHANGE-BASELINE-006",
                        "ChangeSet.basedOn does not match CURRENT Bundle revision: expected=" + bundleRevision + " request=" + basedOn
                     )
                  );
                  lock.close();
                  return null;
               } else {
                  return new ChangeExecutionApplication.OperationContext(
                     lock, normalizedStateRoot, normalizedOutputRoot, bundle, toReceipt(bundle), bundle.descriptor().sourceId()
                  );
               }
            }
         }
      }
   }

   private ChangeExecutionDiagnostic verifyCandidateBinding(
      List<FileChange> fileChanges, List<GeneratedFile> baseFiles, List<GeneratedFile> candidateFiles, List<ChangeExecutionDiagnostic> diagnostics
   ) {
      Map<String, GeneratedFile> baseMap = new LinkedHashMap<>();

      for (GeneratedFile gf : baseFiles) {
         baseMap.put(gf.relativePath(), gf);
      }

      Map<String, GeneratedFile> candidateMap = new LinkedHashMap<>();

      for (GeneratedFile gf : candidateFiles) {
         candidateMap.put(gf.relativePath(), gf);
      }

      for (FileChange fc : fileChanges) {
         GeneratedFile candidateGf = candidateMap.get(fc.relativePath());
         if (candidateGf == null) {
            return diag("SIR-APP-CHANGE-BIND-001", "candidate GeneratedFile missing for planned file: " + fc.relativePath(), fc.relativePath());
         }

         if (!candidateGf.artifactId().equals(fc.artifactId())) {
            return diag("SIR-APP-CHANGE-BIND-002", "artifactId mismatch for " + fc.relativePath(), fc.relativePath());
         }

         if (!candidateGf.symbolId().equals(fc.ownerSymbol())) {
            return diag("SIR-APP-CHANGE-BIND-002", "ownerSymbol mismatch for " + fc.relativePath(), fc.relativePath());
         }

         byte[] candidateBytes = candidateGf.content().getBytes(StandardCharsets.UTF_8);
         if (candidateBytes.length != fc.candidateByteCount()) {
            return diag("SIR-APP-CHANGE-BIND-003", "candidateByteCount mismatch for " + fc.relativePath(), fc.relativePath());
         }

         String candidateSha = Sha256.hexDigest(candidateBytes);
         if (!candidateSha.equals(fc.candidateSha256Hex())) {
            return diag("SIR-APP-CHANGE-BIND-003", "candidateSha256 mismatch for " + fc.relativePath(), fc.relativePath());
         }

         GeneratedFile baseGf = baseMap.get(fc.relativePath());
         if (baseGf == null) {
            return diag("SIR-APP-CHANGE-BIND-001", "base GeneratedFile missing for planned file: " + fc.relativePath(), fc.relativePath());
         }

         byte[] baseBytes = baseGf.content().getBytes(StandardCharsets.UTF_8);
         if (baseBytes.length != fc.baseByteCount()) {
            return diag("SIR-APP-CHANGE-BIND-003", "baseByteCount mismatch for " + fc.relativePath(), fc.relativePath());
         }

         String baseSha = Sha256.hexDigest(baseBytes);
         if (!baseSha.equals(fc.baseSha256Hex())) {
            return diag("SIR-APP-CHANGE-BIND-003", "baseSha256 mismatch for " + fc.relativePath(), fc.relativePath());
         }
      }

      Set<String> changedSet = new LinkedHashSet<>();

      for (FileChange fc : fileChanges) {
         changedSet.add(fc.relativePath());
      }

      Set<String> actualChangedSet = new LinkedHashSet<>();

      for (GeneratedFile candidateGf : candidateFiles) {
         GeneratedFile baseGf = baseMap.get(candidateGf.relativePath());
         if (baseGf != null) {
            byte[] baseBytes = baseGf.content().getBytes(StandardCharsets.UTF_8);
            byte[] candBytes = candidateGf.content().getBytes(StandardCharsets.UTF_8);
            if (!Arrays.equals(baseBytes, candBytes)) {
               actualChangedSet.add(candidateGf.relativePath());
            }
         }
      }

      return !actualChangedSet.equals(changedSet)
         ? diag("SIR-APP-CHANGE-BIND-004", "changed-file set does not match plan: plan=" + changedSet + " actual=" + actualChangedSet)
         : null;
   }

   private Map<String, byte[]> buildCandidateBytesMap(List<FileChange> fileChanges, List<GeneratedFile> candidateFiles) {
      Map<String, GeneratedFile> candidateMap = new LinkedHashMap<>();

      for (GeneratedFile gf : candidateFiles) {
         candidateMap.put(gf.relativePath(), gf);
      }

      Map<String, byte[]> result = new LinkedHashMap<>();

      for (FileChange fc : fileChanges) {
         GeneratedFile gf = candidateMap.get(fc.relativePath());
         result.put(fc.relativePath(), gf.content().getBytes(StandardCharsets.UTF_8));
      }

      return result;
   }

   private ProjectGraph buildGraph(SirCompilation.CompilationSnapshot snapshot, SourceId sourceId, List<ChangeExecutionDiagnostic> diagnostics) {
      ProjectGraphInput input = new SpringBootProjectGraphInputFactory()
         .build(snapshot.semanticModel(), snapshot.loweredModel(), snapshot.generatedFiles(), sourceId);
      ProjectGraphAnalysis analysis = this.graphStep.apply(input);
      if (analysis instanceof Failure graphFailure) {
         diagnostics.addAll(mapGraphDiags(graphFailure.diagnostics()));
         return null;
      } else {
         Success success = (Success)analysis;
         diagnostics.addAll(mapGraphDiags(success.diagnostics()));
         return success.graph();
      }
   }

   private StateRootLock.HeldLock acquireLock(Path normalizedStateRoot, List<ChangeExecutionDiagnostic> diagnostics) {
      return this.acquireLock(normalizedStateRoot, diagnostics, false);
   }

   private StateRootLock.HeldLock acquireLock(Path normalizedStateRoot, List<ChangeExecutionDiagnostic> diagnostics, boolean existingOnly) {
      StateRootLock lock = new StateRootLock(normalizedStateRoot);
      StateRootLock.AcquireResult result = existingOnly ? lock.tryAcquireExisting() : lock.tryAcquire();
      if (result instanceof StateRootLock.LockFailure f) {
         diagnostics.add(f.toDiagnostic());
         return null;
      } else {
         return (StateRootLock.HeldLock)result;
      }
   }

   private static List<FileChange> fileChangesOf(ChangeAnalysis analysis) {
      return analysis instanceof Planned planned ? planned.plan().fileChanges() : List.of();
   }

   private static List<FileAddition> fileAdditionsOf(ChangeAnalysis analysis) {
      return analysis instanceof Planned planned ? planned.plan().fileAdditions() : List.of();
   }

   private static List<ArtifactAddition> artifactAdditionsOf(ChangeAnalysis analysis) {
      return analysis instanceof Planned planned ? planned.plan().artifactAdditions() : List.of();
   }

   private static List<FileDeletion> fileDeletionsOf(ChangeAnalysis analysis) {
      return analysis instanceof Planned planned ? planned.plan().fileDeletions() : List.of();
   }

   private static List<ArtifactDeletion> artifactDeletionsOf(ChangeAnalysis analysis) {
      return analysis instanceof Planned planned ? planned.plan().artifactDeletions() : List.of();
   }

   private static ChangeBaselineReceipt toReceipt(BaselineBundle bundle) {
      BaselineDescriptor d = bundle.descriptor();
      return new ChangeBaselineReceipt(
         d.formatVersion(), bundle.baselineId(), d.toBaseRevision(), d.boundOutputRoot(), d.targetId(), d.loweredIrVersion(), d.manifestDigest()
      );
   }

   private static List<ChangeExecutionDiagnostic> mapExecutionDiags(List<ExecutionDiagnostic> diags, ChangeExecutionStage stage) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>(diags.size());

      for (ExecutionDiagnostic d : diags) {
         out.add(new ChangeExecutionDiagnostic(d.code(), stage, ExecutionSeverity.valueOf(d.severity().name()), d.message(), d.relativePath()));
      }

      return out;
   }

   private static List<ChangeExecutionDiagnostic> mapPlannerDiags(List<ChangeDiagnostic> diags) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>(diags.size());

      for (ChangeDiagnostic d : diags) {
         ExecutionSeverity severity = d.isError() ? ExecutionSeverity.ERROR : ExecutionSeverity.valueOf(d.severity().name());
         out.add(new ChangeExecutionDiagnostic(d.code(), ChangeExecutionStage.PLAN, severity, d.message(), d.relativePath()));
      }

      return out;
   }

   private static List<ChangeExecutionDiagnostic> mapGraphDiags(List<ProjectGraphDiagnostic> diags) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>(diags.size());

      for (ProjectGraphDiagnostic d : diags) {
         ExecutionSeverity severity = d.isError() ? ExecutionSeverity.ERROR : ExecutionSeverity.valueOf(d.severity().name());
         out.add(new ChangeExecutionDiagnostic(d.code(), ChangeExecutionStage.RECOMPILE, severity, d.message(), Optional.empty()));
      }

      return out;
   }

   private static List<ChangeExecutionDiagnostic> filterNonErrors(List<ChangeExecutionDiagnostic> diagnostics) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>();

      for (ChangeExecutionDiagnostic d : diagnostics) {
         if (!d.isError()) {
            out.add(d);
         }
      }

      return out;
   }

   private static List<ChangeExecutionDiagnostic> concat(List<ChangeExecutionDiagnostic> a, List<ChangeExecutionDiagnostic> b) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>(a);
      out.addAll(b);
      return List.copyOf(out);
   }

   private static List<ChangeExecutionDiagnostic> withFallback(List<ChangeExecutionDiagnostic> diagnostics, ChangeExecutionDiagnostic primary) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>(diagnostics);
      if (out.stream().noneMatch(ChangeExecutionDiagnostic::isError)) {
         out.add(primary);
      }

      return List.copyOf(out);
   }

   private static ChangeExecutionDiagnostic diag(String code, String message) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.BASELINE, ExecutionSeverity.ERROR, message, Optional.empty());
   }

   private static ChangeExecutionDiagnostic diag(String code, String message, String rel) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.BASELINE, ExecutionSeverity.ERROR, message, Optional.of(rel));
   }

   private static ChangeBaselineRegistrationResult failure(ChangeExecutionStage stage, List<ChangeExecutionDiagnostic> diagnostics) {
      return ChangeBaselineRegistrationResult.failure(stage, withErrorFallback(diagnostics, stage));
   }

   private static ChangeBaselineRegistrationResult failure(ChangeExecutionStage stage, List<ChangeExecutionDiagnostic> diagnostics, String code, String message) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>(diagnostics);
      out.add(new ChangeExecutionDiagnostic(code, stage, ExecutionSeverity.ERROR, message, Optional.empty()));
      return ChangeBaselineRegistrationResult.failure(stage, List.copyOf(out));
   }

   private static ChangeBaselinePlanningResult planFailure(List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangeBaselinePlanningResult.Failure(
         ChangeExecutionStage.BASELINE, Optional.empty(), withErrorFallback(diagnostics, ChangeExecutionStage.BASELINE)
      );
   }

   private static ChangeBaselinePlanningResult planFailure(
      ChangeExecutionStage stage, ChangeBaselineReceipt receipt, List<ChangeExecutionDiagnostic> diagnostics
   ) {
      return new ChangeBaselinePlanningResult.Failure(stage, Optional.of(receipt), withErrorFallback(diagnostics, stage));
   }

   private static ChangeBaselinePlanningResult planFailure(
      ChangeExecutionStage stage, ChangeBaselineReceipt receipt, List<ChangeExecutionDiagnostic> diagnostics, String code, String message
   ) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>(diagnostics);
      out.add(new ChangeExecutionDiagnostic(code, stage, ExecutionSeverity.ERROR, message, Optional.empty()));
      return new ChangeBaselinePlanningResult.Failure(stage, Optional.of(receipt), List.copyOf(out));
   }

   private static ChangeApplyResult applyFailure(List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangeApplyResult.Failure(
         ChangeExecutionStage.BASELINE, ChangeApplyDisposition.NO_CHANGES, Optional.empty(), withErrorFallback(diagnostics, ChangeExecutionStage.BASELINE)
      );
   }

   private static ChangeApplyResult applyFailure(ChangeExecutionStage stage, ChangeBaselineReceipt receipt, List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangeApplyResult.Failure(stage, ChangeApplyDisposition.NO_CHANGES, Optional.of(receipt), withErrorFallback(diagnostics, stage));
   }

   private static ChangeApplyResult applyFailure(
      ChangeExecutionStage stage, ChangeBaselineReceipt receipt, List<ChangeExecutionDiagnostic> diagnostics, String code, String message
   ) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>(diagnostics);
      out.add(new ChangeExecutionDiagnostic(code, stage, ExecutionSeverity.ERROR, message, Optional.empty()));
      return new ChangeApplyResult.Failure(stage, ChangeApplyDisposition.NO_CHANGES, Optional.of(receipt), List.copyOf(out));
   }

   private static List<ChangeExecutionDiagnostic> withErrorFallback(List<ChangeExecutionDiagnostic> diagnostics, ChangeExecutionStage stage) {
      if (diagnostics.stream().anyMatch(ChangeExecutionDiagnostic::isError)) {
         return List.copyOf(diagnostics);
      }

      List<ChangeExecutionDiagnostic> out = new ArrayList<>(diagnostics);
      out.add(
         new ChangeExecutionDiagnostic(
            "SIR-APP-CHANGE-BASELINE-001", stage, ExecutionSeverity.ERROR, "stage " + stage + " failed without an explicit error diagnostic", Optional.empty()
         )
      );
      return List.copyOf(out);
   }

   private static final class OperationContext {
      final StateRootLock.HeldLock lock;
      final Path normalizedStateRoot;
      final Path normalizedOutputRoot;
      final BaselineBundle bundle;
      final ChangeBaselineReceipt receipt;
      final SourceId sourceId;

      OperationContext(
         StateRootLock.HeldLock lock,
         Path normalizedStateRoot,
         Path normalizedOutputRoot,
         BaselineBundle bundle,
         ChangeBaselineReceipt receipt,
         SourceId sourceId
      ) {
         this.lock = lock;
         this.normalizedStateRoot = normalizedStateRoot;
         this.normalizedOutputRoot = normalizedOutputRoot;
         this.bundle = bundle;
         this.receipt = receipt;
         this.sourceId = sourceId;
      }
   }

   interface PlanProtection {
      List<ExecutionDiagnostic> protectUpdate(Path var1, List<FileChange> var2);

      List<ExecutionDiagnostic> protectCreate(Path var1, List<FileAddition> var2);

      List<ExecutionDiagnostic> protectDelete(Path var1, List<FileDeletion> var2);

      static ChangeExecutionApplication.PlanProtection standard() {
         return new ChangeExecutionApplication.PlanProtection() {
            @Override
            public List<ExecutionDiagnostic> protectUpdate(Path outputRoot, List<FileChange> fileChanges) {
               return PlanProtector.protect(outputRoot, fileChanges);
            }

            @Override
            public List<ExecutionDiagnostic> protectCreate(Path outputRoot, List<FileAddition> fileAdditions) {
               return PlanProtector.protectAdditions(outputRoot, fileAdditions);
            }

            @Override
            public List<ExecutionDiagnostic> protectDelete(Path outputRoot, List<FileDeletion> fileDeletions) {
               return PlanProtector.protectDeletions(outputRoot, fileDeletions);
            }
         };
      }
   }
}
