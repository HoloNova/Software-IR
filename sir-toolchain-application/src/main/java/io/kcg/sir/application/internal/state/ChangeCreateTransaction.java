package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.api.ChangeApplyDisposition;
import io.kcg.sir.application.api.ChangeApplyOutcome;
import io.kcg.sir.application.api.ChangeApplyResult;
import io.kcg.sir.application.api.ChangeBaselineReceipt;
import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeExecutionStage;
import io.kcg.sir.application.api.ChangeOutputManifest;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.api.RecoveryHandle;
import io.kcg.sir.application.internal.PlanProtector;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.bundle.BaselineBundle;
import io.kcg.sir.application.internal.bundle.BaselineBundleStore;
import io.kcg.sir.application.internal.bundle.BaselineDescriptor;
import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import io.kcg.sir.application.internal.bundle.OutputManifestVerifier;
import io.kcg.sir.change.api.FileAddition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class ChangeCreateTransaction {
   private final BaselineBundleStore store;
   private final Path outputRoot;
   private final BaselineBundle b0Bundle;
   private final BaselineBundle b1Bundle;
   private final List<CreateFilePayload> filePayloads;
   private final ApplyHooks hooks;
   private final String transactionId;
   private final Path transactionDir;
   private final Path stagingDir;
   private final Path backupDir;
   private final Path candidateBaselineDir;
   private SafeTargetResolver outputResolver;
   private SafeTargetResolver stagingResolver;
   private OutputMutationEvidence mutationEvidence = OutputMutationEvidence.NO_OUTPUT_MUTATION;
   private final List<String> createdDirectories = new ArrayList<>();
   private List<String> prepareDirectoryPlan = List.of();

   public ChangeCreateTransaction(
      BaselineBundleStore store, Path outputRoot, BaselineBundle b0Bundle, BaselineBundle b1Bundle, List<CreateFilePayload> filePayloads, ApplyHooks hooks
   ) {
      this.store = Objects.requireNonNull(store, "store");
      this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
      this.b0Bundle = Objects.requireNonNull(b0Bundle, "b0Bundle");
      this.b1Bundle = Objects.requireNonNull(b1Bundle, "b1Bundle");
      this.filePayloads = List.copyOf(Objects.requireNonNull(filePayloads, "filePayloads"));
      this.hooks = Objects.requireNonNull(hooks, "hooks");
      this.transactionId = generateTransactionId();
      this.transactionDir = store.stateRoot().resolve("transactions").resolve(this.transactionId);
      this.stagingDir = this.transactionDir.resolve("staging");
      this.backupDir = this.transactionDir.resolve("backup");
      this.candidateBaselineDir = this.transactionDir.resolve("candidate-baseline");
   }

   public String transactionId() {
      return this.transactionId;
   }

   public ChangeApplyResult execute() {
      List<ChangeExecutionDiagnostic> diagnostics = new ArrayList<>();
      ChangeBaselineReceipt b0Receipt = toReceipt(this.b0Bundle);
      ChangeBaselineReceipt b1Receipt = toReceipt(this.b1Bundle);

      try {
         this.outputResolver = SafeTargetResolver.forRoot(this.outputRoot);
      } catch (SafeTargetResolver.UnsafePathException e) {
         return ChangeApplyResult.failure(
            ChangeExecutionStage.COMMIT,
            ChangeApplyDisposition.NO_CHANGES,
            Optional.of(b0Receipt),
            List.of(diag("SIR-APP-CHANGE-COMMIT-001", "unsafe outputRoot: " + e.getMessage()))
         );
      }

      ChangeCreateTransaction.DirectoryPlanResult planResult = this.computeDirectoryPlan();
      if (planResult instanceof ChangeCreateTransaction.DirectoryPlanResult.Failure f) {
         return ChangeApplyResult.failure(ChangeExecutionStage.COMMIT, ChangeApplyDisposition.NO_CHANGES, Optional.of(b0Receipt), List.of(f.error()));
      } else {
         this.prepareDirectoryPlan = ((ChangeCreateTransaction.DirectoryPlanResult.Success)planResult).directories();
         CreateTransactionJournal journal = new CreateTransactionJournal(
            this.transactionDir,
            this.transactionId,
            this.outputRoot,
            this.b0Bundle.baselineId(),
            this.b1Bundle.baselineId(),
            this.prepareDirectoryPlan,
            this.plannedFilePaths()
         );
         ChangeExecutionDiagnostic prepareError = this.prepare(journal, diagnostics);
         if (prepareError != null) {
            if (Files.exists(journal.journalPath(), LinkOption.NOFOLLOW_LINKS)) {
               return ChangeApplyResult.recoveryRequired(
                  ChangeExecutionStage.COMMIT, RecoveryHandle.of(this.transactionId), Optional.of(b0Receipt), withFallback(diagnostics, prepareError)
               );
            }

            this.cleanupTransactionDirQuietly();
            return ChangeApplyResult.failure(
               ChangeExecutionStage.COMMIT, ChangeApplyDisposition.NO_CHANGES, Optional.of(b0Receipt), withFallback(diagnostics, prepareError)
            );
         } else if (this.commit(journal, diagnostics) instanceof ChangeCreateTransaction.CommitResult.Failure f) {
            ChangeCreateTransaction.RollbackResult rollbackResult = this.rollback(journal, diagnostics);
            ChangeCreateTransaction.RollbackOutcome outcome = this.selectOutcomeAfterRollback(rollbackResult);
            if (outcome == ChangeCreateTransaction.RollbackOutcome.ROLLED_BACK) {
               if (!this.createdDirectories.isEmpty()) {
                  diagnostics.add(this.retainedDirectoriesWarning());
               }

               this.cleanupTransactionDirQuietly();
               return ChangeApplyResult.failure(
                  ChangeExecutionStage.COMMIT, ChangeApplyDisposition.ROLLED_BACK, Optional.of(b0Receipt), withFallback(diagnostics, f.error())
               );
            } else if (outcome == ChangeCreateTransaction.RollbackOutcome.NO_CHANGES) {
               this.cleanupTransactionDirQuietly();
               return ChangeApplyResult.failure(
                  ChangeExecutionStage.COMMIT, ChangeApplyDisposition.NO_CHANGES, Optional.of(b0Receipt), withFallback(diagnostics, f.error())
               );
            } else {
               return ChangeApplyResult.recoveryRequired(
                  ChangeExecutionStage.COMMIT, RecoveryHandle.of(this.transactionId), Optional.of(b0Receipt), withFallback(diagnostics, f.error())
               );
            }
         } else if (this.publish(journal, diagnostics) instanceof ChangeCreateTransaction.PublishResult.Failure pf) {
            return this.recoveryRequiredAfterPublish(journal, diagnostics, pf.error());
         } else {
            List<ChangeExecutionDiagnostic> cleanupWarnings = this.cleanup(journal);
            diagnostics.addAll(cleanupWarnings);
            ChangeOutputManifest outputManifest = buildOutputManifest(this.b1Bundle.descriptor().manifest());
            return ChangeApplyResult.applied(ChangeApplyOutcome.FILES_AND_BASELINE, b1Receipt, outputManifest, List.copyOf(diagnostics));
         }
      }
   }

   private ChangeCreateTransaction.RollbackOutcome selectOutcomeAfterRollback(ChangeCreateTransaction.RollbackResult rollbackResult) {
      if (rollbackResult instanceof ChangeCreateTransaction.RollbackResult.Complete) {
         return this.mutationEvidence.isMutationObserved()
            ? ChangeCreateTransaction.RollbackOutcome.ROLLED_BACK
            : ChangeCreateTransaction.RollbackOutcome.NO_CHANGES;
      } else {
         return ChangeCreateTransaction.RollbackOutcome.RECOVERY_REQUIRED;
      }
   }

   private static ChangeOutputManifest buildOutputManifest(List<BaselineManifestEntry> manifest) {
      List<ChangeOutputManifest.Entry> entries = new ArrayList<>(manifest.size());

      for (BaselineManifestEntry e : manifest) {
         entries.add(new ChangeOutputManifest.Entry(e.relativePath(), e.byteCount(), e.sha256Hex(), e.artifactId(), e.ownerSymbol()));
      }

      return new ChangeOutputManifest(entries);
   }

   private ChangeExecutionDiagnostic prepare(CreateTransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics) {
      try {
         Files.createDirectories(this.transactionDir);
         this.hooks.afterTransactionDirCreate(this.transactionDir);
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-COMMIT-001", "failed to create transaction directory: " + e.getMessage());
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-001", "fault after transaction directory create: " + e.getMessage());
      }

      try {
         journal.create();
         this.hooks.afterJournalCreate(journal);
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-COMMIT-001", "V2 journal create failed: " + e.getMessage());
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-001", "fault after V2 journal create: " + e.getMessage());
      }

      try {
         Files.createDirectory(this.stagingDir);
         Files.createDirectory(this.backupDir);
         Files.createDirectory(this.candidateBaselineDir);
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-COMMIT-001", "failed to create staging/backup/candidate-baseline directories: " + e.getMessage());
      }

      try {
         this.stagingResolver = SafeTargetResolver.forRoot(this.stagingDir);
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-COMMIT-001", "unsafe staging directory: " + e.getMessage());
      }

      try {
         this.writeStagedFiles();
         this.hooks.afterStagingWrite(this.stagingDir);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-001", "staged file write failed: " + e.getMessage());
      }

      if (this.store.stageCandidateBundle(this.b1Bundle, this.candidateBaselineDir) instanceof BaselineBundleStore.LoadResult.Failure f) {
         return diag(f.code(), "candidate B1 staging failed: " + f.message());
      } else {
         try {
            this.hooks.afterBundleStage(this.b1Bundle);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-001", "fault after B1 stage: " + e.getMessage());
         }

         try {
            this.hooks.afterCreateDirectoryPlanComputed(journal, this.prepareDirectoryPlan);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-001", "fault after directory plan computed: " + e.getMessage());
         }

         try {
            journal.appendOverallTransition(CreateTransactionJournal.OverallState.PREPARED);
            this.hooks.afterJournalForce(journal, "PREPARED");
            return null;
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-COMMIT-001", "journal PREPARED force failed: " + e.getMessage());
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-001", "fault after PREPARED force: " + e.getMessage());
         }
      }
   }

   private void writeStagedFiles() throws IOException {
      for (CreateFilePayload payload : this.filePayloads) {
         String rel = payload.relativePath();

         Path staged;
         try {
            staged = this.stagingResolver.resolveForCreate(rel);
         } catch (SafeTargetResolver.UnsafePathException e) {
            throw new IOException("unsafe staging path for " + rel + ": " + e.getMessage());
         }

         Files.write(
            staged, payload.stagedBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.SYNC
         );
      }
   }

   private ChangeCreateTransaction.CommitResult commit(CreateTransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics) {
      ChangeExecutionDiagnostic committingError = this.appendOverall(journal, CreateTransactionJournal.OverallState.COMMITTING, "COMMITTING", diagnostics);
      if (committingError != null) {
         return new ChangeCreateTransaction.CommitResult.Failure(committingError);
      } else {
         List<ChangeExecutionDiagnostic> b0ManifestErrors = OutputManifestVerifier.verify(
            this.outputRoot, this.b0Bundle.descriptor().manifest(), ChangeExecutionStage.COMMIT
         );
         if (!b0ManifestErrors.isEmpty()) {
            diagnostics.addAll(b0ManifestErrors);
            return new ChangeCreateTransaction.CommitResult.Failure(b0ManifestErrors.get(0));
         } else {
            List<FileAddition> additions = this.plannedAdditions();
            List<ExecutionDiagnostic> protectErrors = PlanProtector.protectAdditions(this.outputRoot, additions);
            if (!protectErrors.isEmpty()) {
               diagnostics.addAll(mapExecutionDiags(protectErrors, ChangeExecutionStage.PROTECT));
               return new ChangeCreateTransaction.CommitResult.Failure(
                  diagnostics.stream()
                     .filter(ChangeExecutionDiagnostic::isError)
                     .findFirst()
                     .orElse(diag("SIR-APP-CHANGE-PROTECT-110", "protectAdditions failed without explicit error"))
               );
            } else {
               ChangeCreateTransaction.DirectoryPlanResult commitPlanResult = this.computeDirectoryPlan();
               if (commitPlanResult instanceof ChangeCreateTransaction.DirectoryPlanResult.Failure f) {
                  return new ChangeCreateTransaction.CommitResult.Failure(f.error());
               } else {
                  List<String> commitPlan = ((ChangeCreateTransaction.DirectoryPlanResult.Success)commitPlanResult).directories();
                  if (!isSubset(commitPlan, this.prepareDirectoryPlan)) {
                     return new ChangeCreateTransaction.CommitResult.Failure(
                        diag(
                           "SIR-APP-CHANGE-COMMIT-006", "directory plan expanded since PREPARE: prepare=" + this.prepareDirectoryPlan + " commit=" + commitPlan
                        )
                     );
                  }

                  for (CreateFilePayload payload : this.filePayloads) {
                     try {
                        this.stagingResolver.resolveAndVerifyBytes(payload.relativePath(), payload.byteCount(), payload.sha256Hex());
                     } catch (SafeTargetResolver.UnsafePathException e) {
                        return new ChangeCreateTransaction.CommitResult.Failure(
                           diag(
                              "SIR-APP-CHANGE-COMMIT-008",
                              "staged file verification failed for " + payload.relativePath() + ": " + e.getMessage(),
                              payload.relativePath()
                           )
                        );
                     }
                  }

                  for (int i = 0; i < commitPlan.size(); i++) {
                     String rel = commitPlan.get(i);
                     ChangeExecutionDiagnostic dirError = this.createDirectory(journal, i, rel, diagnostics);
                     if (dirError != null) {
                        return new ChangeCreateTransaction.CommitResult.Failure(dirError);
                     }
                  }

                  for (int i = 0; i < this.filePayloads.size(); i++) {
                     CreateFilePayload payload = this.filePayloads.get(i);
                     ChangeExecutionDiagnostic fileError = this.installFile(journal, i, payload, diagnostics);
                     if (fileError != null) {
                        return new ChangeCreateTransaction.CommitResult.Failure(fileError);
                     }
                  }

                  try {
                     this.hooks.afterAllCreateLinks();
                  } catch (Exception e) {
                     return new ChangeCreateTransaction.CommitResult.Failure(
                        diag("SIR-APP-CHANGE-COMMIT-008", "fault after all create links: " + e.getMessage())
                     );
                  }

                  List<ChangeExecutionDiagnostic> b1ManifestErrors = OutputManifestVerifier.verify(
                     this.outputRoot, this.b1Bundle.descriptor().manifest(), ChangeExecutionStage.COMMIT
                  );
                  if (!b1ManifestErrors.isEmpty()) {
                     diagnostics.addAll(b1ManifestErrors);
                     return new ChangeCreateTransaction.CommitResult.Failure(b1ManifestErrors.get(0));
                  } else {
                     ChangeExecutionDiagnostic fcError = this.appendOverall(
                        journal, CreateTransactionJournal.OverallState.FILES_COMMITTED, "FILES_COMMITTED", diagnostics
                     );
                     return fcError != null ? new ChangeCreateTransaction.CommitResult.Failure(fcError) : new ChangeCreateTransaction.CommitResult.Complete();
                  }
               }
            }
         }
      }
   }

   private ChangeExecutionDiagnostic createDirectory(CreateTransactionJournal journal, int index, String rel, List<ChangeExecutionDiagnostic> diagnostics) {
      Path dir;
      try {
         dir = this.outputResolver.resolveNoCreate(rel);
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-COMMIT-006", "failed to resolve directory path for " + rel + ": " + e.getMessage(), rel);
      }

      BasicFileAttributes attrs = null;

      try {
         attrs = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException var15) {
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-COMMIT-006", "failed to recheck directory absence for " + rel + ": " + e.getMessage(), rel);
      } catch (RuntimeException e) {
         return diag("SIR-APP-CHANGE-COMMIT-006", "runtime error rechecking directory absence for " + rel + ": " + e.getMessage(), rel);
      }

      if (attrs != null) {
         if (attrs.isSymbolicLink()) {
            return diag("SIR-APP-CHANGE-COMMIT-006", "directory path is a symlink: " + rel, rel);
         } else {
            return attrs.isDirectory() ? null : diag("SIR-APP-CHANGE-COMMIT-006", "directory path exists as non-directory: " + rel, rel);
         }
      } else {
         try {
            this.hooks.beforeCreateDirectoryIntent(index, rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-006", "fault before directory intent of " + rel + ": " + e.getMessage(), rel);
         }

         try {
            journal.appendDirectoryTransition(index, rel, CreateTransactionJournal.DirectoryTransitionState.DIRECTORY_CREATE_INTENT_DURABLE);
            this.hooks.afterJournalForce(journal, "directory " + index + " DIRECTORY_CREATE_INTENT_DURABLE");
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-COMMIT-006", "journal DIRECTORY_CREATE_INTENT force failed for " + rel + ": " + e.getMessage(), rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-006", "fault after journal DIRECTORY_CREATE_INTENT for " + rel + ": " + e.getMessage(), rel);
         }

         try {
            Files.createDirectory(dir);
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-COMMIT-006", "failed to create directory " + rel + ": " + e.getMessage(), rel);
         }

         this.mutationEvidence = this.mutationEvidence.advanceTo(OutputMutationEvidence.DIRECTORY_MUTATION_OBSERVED);
         this.createdDirectories.add(rel);

         try {
            this.hooks.afterCreateDirectoryCreate(index, rel, dir);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-006", "fault after createDirectory of " + rel + ": " + e.getMessage(), rel);
         }

         try {
            journal.appendDirectoryTransition(index, rel, CreateTransactionJournal.DirectoryTransitionState.DIRECTORY_CREATED_DURABLE);
            this.hooks.afterJournalForce(journal, "directory " + index + " DIRECTORY_CREATED_DURABLE");
            this.hooks.afterCreateDirectoryComplete(index, rel);
            return null;
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-COMMIT-006", "journal DIRECTORY_CREATED force failed for " + rel + ": " + e.getMessage(), rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-006", "fault after journal DIRECTORY_CREATED for " + rel + ": " + e.getMessage(), rel);
         }
      }
   }

   private ChangeExecutionDiagnostic installFile(
      CreateTransactionJournal journal, int index, CreateFilePayload payload, List<ChangeExecutionDiagnostic> diagnostics
   ) {
      String rel = payload.relativePath();

      Path staged;
      try {
         staged = this.stagingResolver.resolveAndVerifyBytes(rel, payload.byteCount(), payload.sha256Hex());
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "staged file verification failed for " + rel + ": " + e.getMessage(), rel);
      }

      Path target;
      try {
         target = this.outputResolver.resolveNoCreate(rel);
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-COMMIT-006", "failed to resolve target path for " + rel + ": " + e.getMessage(), rel);
      }

      try {
         Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         return diag("SIR-APP-CHANGE-COMMIT-006", "target already exists before installation: " + rel, rel);
      } catch (NoSuchFileException var23) {
         try {
            this.hooks.beforeCreateFileIntent(index, rel, staged, target);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "fault before create intent of " + rel + ": " + e.getMessage(), rel);
         }

         try {
            journal.appendFileTransition(index, rel, CreateTransactionJournal.FileTransitionState.CREATE_INTENT_DURABLE);
            this.hooks.afterJournalForce(journal, "file " + index + " CREATE_INTENT_DURABLE");
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "journal CREATE_INTENT force failed for " + rel + ": " + e.getMessage(), rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "fault after journal CREATE_INTENT for " + rel + ": " + e.getMessage(), rel);
         }

         this.mutationEvidence = this.mutationEvidence.advanceTo(OutputMutationEvidence.TARGET_MUTATION_POSSIBLE);

         try {
            Files.createLink(target, staged);
         } catch (IOException | SecurityException e) {
            return this.handleCreateLinkException(journal, index, rel, target, staged, payload, e, diagnostics);
         } catch (UnsupportedOperationException e) {
            return this.handleCreateLinkException(journal, index, rel, target, staged, payload, e, diagnostics);
         }

         try {
            this.hooks.afterCreateLink(index, rel, target);
         } catch (Exception e) {
            this.mutationEvidence = this.mutationEvidence.advanceTo(OutputMutationEvidence.TARGET_MUTATION_PROVED);
            return diag("SIR-APP-CHANGE-COMMIT-008", "fault after createLink of " + rel + ": " + e.getMessage(), rel);
         }

         try {
            if (!Files.isSameFile(target, staged)) {
               this.mutationEvidence = this.mutationEvidence.advanceTo(OutputMutationEvidence.TARGET_MUTATION_PROVED);
               return diag("SIR-APP-CHANGE-COMMIT-008", "target is not the same file as staged after createLink: " + rel, rel);
            }
         } catch (IOException | SecurityException e) {
            this.mutationEvidence = this.mutationEvidence.advanceTo(OutputMutationEvidence.TARGET_MUTATION_PROVED);
            return diag("SIR-APP-CHANGE-COMMIT-008", "isSameFile verification failed for " + rel + ": " + e.getMessage(), rel);
         }

         try {
            BasicFileAttributes targetAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (targetAttrs.size() != payload.byteCount()) {
               this.mutationEvidence = this.mutationEvidence.advanceTo(OutputMutationEvidence.TARGET_MUTATION_PROVED);
               return diag(
                  "SIR-APP-CHANGE-COMMIT-008",
                  "target byte count mismatch after createLink for " + rel + ": expected=" + payload.byteCount() + " actual=" + targetAttrs.size(),
                  rel
               );
            }

            byte[] targetBytes = Files.readAllBytes(target);
            String targetSha = Sha256.hexDigest(targetBytes);
            if (!targetSha.equals(payload.sha256Hex())) {
               this.mutationEvidence = this.mutationEvidence.advanceTo(OutputMutationEvidence.TARGET_MUTATION_PROVED);
               return diag("SIR-APP-CHANGE-COMMIT-008", "target SHA-256 mismatch after createLink for " + rel, rel);
            }
         } catch (IOException | SecurityException e) {
            this.mutationEvidence = this.mutationEvidence.advanceTo(OutputMutationEvidence.TARGET_MUTATION_PROVED);
            return diag("SIR-APP-CHANGE-COMMIT-008", "failed to verify target bytes after createLink for " + rel + ": " + e.getMessage(), rel);
         }

         this.mutationEvidence = this.mutationEvidence.advanceTo(OutputMutationEvidence.TARGET_MUTATION_PROVED);

         try {
            journal.appendFileTransition(index, rel, CreateTransactionJournal.FileTransitionState.CREATED_DURABLE);
            this.hooks.afterJournalForce(journal, "file " + index + " CREATED_DURABLE");
            this.hooks.afterCreateFileComplete(index, rel);
            return null;
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "journal CREATED force failed for " + rel + ": " + e.getMessage(), rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "fault after journal CREATED for " + rel + ": " + e.getMessage(), rel);
         }
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-COMMIT-006", "failed to prove target absence for " + rel + ": " + e.getMessage(), rel);
      }
   }

   private ChangeExecutionDiagnostic handleCreateLinkException(
      CreateTransactionJournal journal,
      int index,
      String rel,
      Path target,
      Path staged,
      CreateFilePayload payload,
      Exception cause,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      BasicFileAttributes targetAttrs;
      try {
         targetAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         return diag("SIR-APP-CHANGE-COMMIT-007", "createLink failed and target is absent for " + rel + ": " + cause.getMessage(), rel);
      } catch (IOException | SecurityException e) {
         this.mutationEvidence = this.mutationEvidence.advanceTo(OutputMutationEvidence.TARGET_MUTATION_PROVED);
         return diag("SIR-APP-CHANGE-COMMIT-008", "cannot prove target state after createLink failure for " + rel + ": " + e.getMessage(), rel);
      }

      this.mutationEvidence = this.mutationEvidence.advanceTo(OutputMutationEvidence.TARGET_MUTATION_PROVED);
      if (!targetAttrs.isSymbolicLink() && targetAttrs.isRegularFile()) {
         try {
            if (Files.isSameFile(target, staged)) {
               return this.verifyAndForceCreated(journal, index, rel, target, payload);
            }
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "isSameFile check failed after createLink exception for " + rel + ": " + e.getMessage(), rel);
         }

         return diag(
            "SIR-APP-CHANGE-COMMIT-008",
            "target exists but is not the staged file after createLink failure for " + rel + " — external file must not be deleted",
            rel
         );
      } else {
         return diag("SIR-APP-CHANGE-COMMIT-008", "target exists as non-regular-file after createLink failure for " + rel, rel);
      }
   }

   private ChangeExecutionDiagnostic verifyAndForceCreated(CreateTransactionJournal journal, int index, String rel, Path target, CreateFilePayload payload) {
      try {
         BasicFileAttributes targetAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         if (targetAttrs.size() != payload.byteCount()) {
            return diag(
               "SIR-APP-CHANGE-COMMIT-008",
               "target byte count mismatch after createLink for " + rel + ": expected=" + payload.byteCount() + " actual=" + targetAttrs.size(),
               rel
            );
         }

         byte[] targetBytes = Files.readAllBytes(target);
         String targetSha = Sha256.hexDigest(targetBytes);
         if (!targetSha.equals(payload.sha256Hex())) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "target SHA-256 mismatch after createLink for " + rel, rel);
         }
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "failed to verify target bytes after createLink for " + rel + ": " + e.getMessage(), rel);
      }

      try {
         journal.appendFileTransition(index, rel, CreateTransactionJournal.FileTransitionState.CREATED_DURABLE);
         this.hooks.afterJournalForce(journal, "file " + index + " CREATED_DURABLE");
         this.hooks.afterCreateFileComplete(index, rel);
         return null;
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "journal CREATED force failed for " + rel + ": " + e.getMessage(), rel);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "fault after journal CREATED for " + rel + ": " + e.getMessage(), rel);
      }
   }

   private ChangeCreateTransaction.PublishResult publish(CreateTransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics) {
      ChangeExecutionDiagnostic publishingError = this.appendOverall(journal, CreateTransactionJournal.OverallState.PUBLISHING, "PUBLISHING", diagnostics);
      if (publishingError != null) {
         return new ChangeCreateTransaction.PublishResult.Failure(publishingError);
      }

      try {
         this.store.publishCandidateBundle(this.candidateBaselineDir, this.b1Bundle.baselineId());
         this.hooks.afterBundlePublish();
      } catch (IOException e) {
         return new ChangeCreateTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "candidate Bundle publish failed: " + e.getMessage()));
      } catch (Exception e) {
         return new ChangeCreateTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "fault after candidate Bundle publish: " + e.getMessage()));
      }

      try {
         this.hooks.beforeCurrentNewWrite();
      } catch (Exception e) {
         return new ChangeCreateTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "fault before CURRENT.new write: " + e.getMessage()));
      }

      try {
         this.store.writeCurrentNew(this.b1Bundle.baselineId());
         this.hooks.afterCurrentNewWrite();
         this.store.moveCurrentNewToCurrent();
         this.hooks.afterCurrentAtomicMove();
      } catch (IOException e) {
         return new ChangeCreateTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "CURRENT publish failed: " + e.getMessage()));
      } catch (Exception e) {
         return new ChangeCreateTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "fault during CURRENT publish: " + e.getMessage()));
      }

      ChangeExecutionDiagnostic publishedError = this.appendOverall(
         journal, CreateTransactionJournal.OverallState.BASELINE_PUBLISHED, "BASELINE_PUBLISHED", diagnostics
      );
      if (publishedError != null) {
         return new ChangeCreateTransaction.PublishResult.Failure(publishedError);
      }

      try {
         this.hooks.afterPublishReread();
      } catch (Exception e) {
         return new ChangeCreateTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-002", "fault after publish reread: " + e.getMessage()));
      }

      BaselineBundleStore.CurrentReadResult currentRead = this.store.readCurrent();
      if (currentRead instanceof BaselineBundleStore.CurrentReadResult.Failure f) {
         return new ChangeCreateTransaction.PublishResult.Failure(diag(f.code(), "reread CURRENT failed: " + f.message()));
      } else if (currentRead instanceof BaselineBundleStore.CurrentReadResult.Absent) {
         return new ChangeCreateTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-002", "reread CURRENT: file is absent after publish"));
      } else {
         String currentId = ((BaselineBundleStore.CurrentReadResult.Present)currentRead).baselineId();
         if (!currentId.equals(this.b1Bundle.baselineId())) {
            return new ChangeCreateTransaction.PublishResult.Failure(
               diag("SIR-APP-CHANGE-PUBLISH-002", "reread CURRENT mismatch: expected=" + this.b1Bundle.baselineId() + " actual=" + currentId)
            );
         } else {
            return this.store.loadBundle(this.b1Bundle.baselineId()) instanceof BaselineBundleStore.LoadResult.Failure lf
               ? new ChangeCreateTransaction.PublishResult.Failure(diag(lf.code(), "reread B1 Bundle failed: " + lf.message()))
               : new ChangeCreateTransaction.PublishResult.Complete();
         }
      }
   }

   private ChangeCreateTransaction.RollbackResult rollback(CreateTransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics) {
      try {
         journal.appendOverallTransition(CreateTransactionJournal.OverallState.ROLLING_BACK);
         this.hooks.afterJournalForce(journal, "ROLLING_BACK");
      } catch (IOException e) {
         return new ChangeCreateTransaction.RollbackResult.Incomplete(
            diag("SIR-APP-CHANGE-RECOVERY-001", "journal ROLLING_BACK force failed: " + e.getMessage())
         );
      } catch (Exception e) {
         return new ChangeCreateTransaction.RollbackResult.Incomplete(diag("SIR-APP-CHANGE-RECOVERY-001", "fault after ROLLING_BACK force: " + e.getMessage()));
      }

      for (int i = this.filePayloads.size() - 1; i >= 0; i--) {
         CreateFilePayload payload = this.filePayloads.get(i);
         String rel = payload.relativePath();
         int index = i;

         try {
            this.hooks.beforeCreateRollbackDeleteIntent(i, rel);
         } catch (Exception e) {
            return new ChangeCreateTransaction.RollbackResult.Incomplete(
               diag("SIR-APP-CHANGE-RECOVERY-001", "fault during rollback of " + rel + ": " + e.getMessage(), rel)
            );
         }

         CreateTransactionJournal.FileTransitionState fileState;
         try {
            if (!(CreateTransactionJournal.parse(journal.journalPath()) instanceof CreateTransactionJournal.ParseOk ok)) {
               return new ChangeCreateTransaction.RollbackResult.Incomplete(
                  diag("SIR-APP-CHANGE-RECOVERY-001", "cannot re-read journal during rollback: " + rel, rel)
               );
            }

            fileState = ok.snapshot().fileStates().get(rel);
         } catch (Exception e) {
            return new ChangeCreateTransaction.RollbackResult.Incomplete(
               diag("SIR-APP-CHANGE-RECOVERY-001", "journal re-read failed during rollback of " + rel + ": " + e.getMessage(), rel)
            );
         }

         if (fileState == null || fileState == CreateTransactionJournal.FileTransitionState.PREPARING) {
            ChangeExecutionDiagnostic absentError = this.proveTargetAbsent(rel);
            if (absentError != null) {
               return new ChangeCreateTransaction.RollbackResult.Incomplete(absentError);
            }
         } else if (fileState != CreateTransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE) {
            ChangeExecutionDiagnostic rollbackError = this.rollbackFile(journal, index, rel, payload, fileState, diagnostics);
            if (rollbackError != null) {
               return new ChangeCreateTransaction.RollbackResult.Incomplete(rollbackError);
            }
         }
      }

      try {
         journal.appendOverallTransition(CreateTransactionJournal.OverallState.ROLLED_BACK);
         this.hooks.afterJournalForce(journal, "ROLLED_BACK");
      } catch (IOException e) {
         return new ChangeCreateTransaction.RollbackResult.Incomplete(
            diag("SIR-APP-CHANGE-RECOVERY-001", "journal ROLLED_BACK force failed: " + e.getMessage())
         );
      } catch (Exception e) {
         return new ChangeCreateTransaction.RollbackResult.Incomplete(diag("SIR-APP-CHANGE-RECOVERY-001", "fault after ROLLED_BACK force: " + e.getMessage()));
      }

      List<ChangeExecutionDiagnostic> manifestErrors = OutputManifestVerifier.verify(
         this.outputRoot, this.b0Bundle.descriptor().manifest(), ChangeExecutionStage.COMMIT
      );
      if (!manifestErrors.isEmpty()) {
         diagnostics.addAll(manifestErrors);
         return new ChangeCreateTransaction.RollbackResult.Incomplete(manifestErrors.get(0));
      } else {
         return new ChangeCreateTransaction.RollbackResult.Complete();
      }
   }

   private ChangeExecutionDiagnostic rollbackFile(
      CreateTransactionJournal journal,
      int index,
      String rel,
      CreateFilePayload payload,
      CreateTransactionJournal.FileTransitionState fileState,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      Path target;
      Path staged;
      try {
         target = this.outputResolver.resolveNoCreate(rel);
         staged = this.stagingResolver.resolveExistingFile(rel);
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve target/staged path for rollback of " + rel + ": " + e.getMessage(), rel);
      }

      BasicFileAttributes targetAttrs;
      try {
         targetAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         targetAttrs = null;
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read target during rollback of " + rel + ": " + e.getMessage(), rel);
      }

      if (targetAttrs == null) {
         return this.forceRollbackWithoutDeletion(journal, index, rel);
      }

      if (!targetAttrs.isSymbolicLink() && targetAttrs.isRegularFile()) {
         try {
            if (!Files.isSameFile(target, staged)) {
               return diag(
                  "SIR-APP-CHANGE-RECOVERY-001",
                  "target exists but is not the staged file during rollback of " + rel + " — external file must not be deleted",
                  rel
               );
            }
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "isSameFile check failed during rollback of " + rel + ": " + e.getMessage(), rel);
         }

         try {
            if (targetAttrs.size() != payload.byteCount()) {
               return diag(
                  "SIR-APP-CHANGE-RECOVERY-001",
                  "target byte count mismatch during rollback of " + rel + " expected B1=" + payload.byteCount() + " actual=" + targetAttrs.size(),
                  rel
               );
            }

            byte[] targetBytes = Files.readAllBytes(target);
            String targetSha = Sha256.hexDigest(targetBytes);
            if (!targetSha.equals(payload.sha256Hex())) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "target SHA-256 mismatch during rollback of " + rel + " — external file must not be deleted", rel);
            }
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read target bytes during rollback of " + rel, rel);
         }

         try {
            journal.appendFileTransition(index, rel, CreateTransactionJournal.FileTransitionState.ROLLBACK_DELETE_INTENT_DURABLE);
            this.hooks.afterJournalForce(journal, "file " + index + " ROLLBACK_DELETE_INTENT_DURABLE");
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "journal ROLLBACK_DELETE_INTENT force failed for " + rel, rel);
         }

         try {
            if (!Files.isSameFile(target, staged)) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "target identity changed before deletion during rollback of " + rel, rel);
            }

            byte[] targetBytes = Files.readAllBytes(target);
            String targetSha = Sha256.hexDigest(targetBytes);
            if (!targetSha.equals(payload.sha256Hex())) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "target SHA-256 changed before deletion during rollback of " + rel, rel);
            }
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to revalidate target before deletion during rollback of " + rel, rel);
         }

         try {
            Files.delete(target);
            this.hooks.afterCreateRollbackDelete(index, rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to delete target during rollback of " + rel + ": " + e.getMessage(), rel);
         }

         try {
            Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return diag("SIR-APP-CHANGE-RECOVERY-001", "target was concurrently recreated after deletion during rollback of " + rel, rel);
         } catch (NoSuchFileException var13) {
            try {
               journal.appendFileTransition(index, rel, CreateTransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE);
               this.hooks.afterJournalForce(journal, "file " + index + " ROLLED_BACK_DURABLE");
               this.hooks.afterCreateRollbackComplete(index, rel);
               return null;
            } catch (Exception e) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "journal ROLLED_BACK force failed for " + rel, rel);
            }
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to prove target absence after deletion during rollback of " + rel, rel);
         }
      } else {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "target is not regular file during rollback of " + rel, rel);
      }
   }

   private ChangeExecutionDiagnostic forceRollbackWithoutDeletion(CreateTransactionJournal journal, int index, String rel) {
      try {
         journal.appendFileTransition(index, rel, CreateTransactionJournal.FileTransitionState.ROLLBACK_DELETE_INTENT_DURABLE);
         this.hooks.afterJournalForce(journal, "file " + index + " ROLLBACK_DELETE_INTENT_DURABLE");
         journal.appendFileTransition(index, rel, CreateTransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE);
         this.hooks.afterJournalForce(journal, "file " + index + " ROLLED_BACK_DURABLE");
         this.hooks.afterCreateRollbackComplete(index, rel);
         return null;
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "journal rollback force failed for " + rel + ": " + e.getMessage(), rel);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "fault after journal rollback force for " + rel + ": " + e.getMessage(), rel);
      }
   }

   private ChangeExecutionDiagnostic proveTargetAbsent(String rel) {
      Path target;
      try {
         target = this.outputResolver.resolveNoCreate(rel);
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve target path for absence proof of " + rel + ": " + e.getMessage(), rel);
      }

      try {
         Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         return diag("SIR-APP-CHANGE-RECOVERY-001", "target exists during PREPARING rollback of " + rel + " — external file must not be deleted", rel);
      } catch (NoSuchFileException e) {
         return null;
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to prove target absence during PREPARING rollback of " + rel, rel);
      }
   }

   private List<ChangeExecutionDiagnostic> cleanup(CreateTransactionJournal journal) {
      List<ChangeExecutionDiagnostic> warnings = new ArrayList<>();

      try {
         journal.appendOverallTransition(CreateTransactionJournal.OverallState.CLEANUP_PENDING);
      } catch (IOException e) {
         warnings.add(diag("SIR-APP-CHANGE-CLEANUP-001", "journal CLEANUP_PENDING force failed: " + e.getMessage()));
         return warnings;
      }

      try {
         this.hooks.beforeCleanup();
      } catch (Exception e) {
         warnings.add(diag("SIR-APP-CHANGE-CLEANUP-002", "fault before cleanup: " + e.getMessage()));
         return warnings;
      }

      try {
         deleteRecursively(this.transactionDir);
         return warnings;
      } catch (IOException e) {
         warnings.add(diag("SIR-APP-CHANGE-CLEANUP-001", "cleanup of transaction directory failed: " + e.getMessage()));
         return warnings;
      }
   }

   private ChangeApplyResult recoveryRequiredAfterPublish(
      CreateTransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics, ChangeExecutionDiagnostic primaryError
   ) {
      Optional<ChangeBaselineReceipt> knownReceipt;
      if (this.store.readCurrent() instanceof BaselineBundleStore.CurrentReadResult.Present p) {
         if (this.store.loadBundle(p.baselineId()) instanceof BaselineBundleStore.LoadResult.Success s) {
            knownReceipt = Optional.of(toReceipt(s.bundle()));
         } else {
            knownReceipt = Optional.empty();
         }
      } else {
         knownReceipt = Optional.empty();
      }

      return ChangeApplyResult.recoveryRequired(
         ChangeExecutionStage.BASELINE_PUBLISH, RecoveryHandle.of(this.transactionId), knownReceipt, withFallback(diagnostics, primaryError)
      );
   }

   private ChangeCreateTransaction.DirectoryPlanResult computeDirectoryPlan() {
      Set<String> missingDirs = new LinkedHashSet<>();

      for (CreateFilePayload payload : this.filePayloads) {
         String rel = payload.relativePath();
         int lastSlash = rel.lastIndexOf(47);
         if (lastSlash >= 0) {
            String parentRel = rel.substring(0, lastSlash);
            String[] segments = parentRel.split("/");
            StringBuilder current = new StringBuilder();

            for (int i = 0; i < segments.length; i++) {
               if (i > 0) {
                  current.append('/');
               }

               current.append(segments[i]);
               String dirRel = current.toString();

               Path dirPath;
               try {
                  dirPath = this.outputResolver.resolveNoCreate(dirRel);
               } catch (SafeTargetResolver.UnsafePathException e) {
                  String pathError = TransactionJournal.validateRelativePath(dirRel);
                  if (pathError != null) {
                     return new ChangeCreateTransaction.DirectoryPlanResult.Failure(
                        diag("SIR-APP-CHANGE-COMMIT-006", "unsafe directory path in plan: " + pathError + ": " + dirRel, dirRel)
                     );
                  }

                  if (!this.isSafeMissingDirectory(dirRel)) {
                     return new ChangeCreateTransaction.DirectoryPlanResult.Failure(
                        diag("SIR-APP-CHANGE-COMMIT-006", "unsafe parent chain for directory: " + dirRel, dirRel)
                     );
                  }

                  missingDirs.add(dirRel);
                  continue;
               }

               try {
                  BasicFileAttributes attrs = Files.readAttributes(dirPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                  if (attrs.isSymbolicLink()) {
                     return new ChangeCreateTransaction.DirectoryPlanResult.Failure(diag("SIR-APP-CHANGE-COMMIT-006", "parent is a symlink: " + dirRel, dirRel));
                  }

                  if (!attrs.isDirectory()) {
                     return new ChangeCreateTransaction.DirectoryPlanResult.Failure(
                        diag("SIR-APP-CHANGE-COMMIT-006", "parent is not a directory: " + dirRel, dirRel)
                     );
                  }
               } catch (NoSuchFileException e) {
                  missingDirs.add(dirRel);
               } catch (IOException | SecurityException e) {
                  return new ChangeCreateTransaction.DirectoryPlanResult.Failure(
                     diag("SIR-APP-CHANGE-COMMIT-006", "failed to read parent attributes for " + dirRel + ": " + e.getMessage(), dirRel)
                  );
               }
            }
         }
      }

      List<String> sorted = new ArrayList<>(missingDirs);
      sorted.sort((a, b) -> {
         int depthA = depthOf(a);
         int depthB = depthOf(b);
         return depthA != depthB ? Integer.compare(depthA, depthB) : a.compareTo(b);
      });
      return new ChangeCreateTransaction.DirectoryPlanResult.Success(List.copyOf(sorted));
   }

   private boolean isSafeMissingDirectory(String dirRel) {
      Path dirPath = this.outputRoot.resolve(dirRel).normalize();
      if (!dirPath.startsWith(this.outputRoot)) {
         return false;
      }

      Path current = this.outputRoot;
      String[] segments = dirRel.split("/");

      for (String segment : segments) {
         current = current.resolve(segment);

         try {
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink()) {
               return false;
            }

            if (!attrs.isDirectory()) {
               return false;
            }
         } catch (NoSuchFileException e) {
            return true;
         } catch (IOException | SecurityException e) {
            return false;
         }
      }

      return false;
   }

   private static int depthOf(String relPath) {
      int depth = 1;

      for (int i = 0; i < relPath.length(); i++) {
         if (relPath.charAt(i) == '/') {
            depth++;
         }
      }

      return depth;
   }

   private static boolean isSubset(List<String> subset, List<String> superset) {
      Set<String> superSet = new LinkedHashSet<>(superset);

      for (String s : subset) {
         if (!superSet.contains(s)) {
            return false;
         }
      }

      return true;
   }

   private List<String> plannedFilePaths() {
      List<String> paths = new ArrayList<>(this.filePayloads.size());

      for (CreateFilePayload p : this.filePayloads) {
         paths.add(p.relativePath());
      }

      return paths;
   }

   private List<FileAddition> plannedAdditions() {
      List<FileAddition> additions = new ArrayList<>(this.filePayloads.size());

      for (CreateFilePayload p : this.filePayloads) {
         additions.add(p.addition());
      }

      return additions;
   }

   private ChangeExecutionDiagnostic retainedDirectoriesWarning() {
      List<String> sorted = new ArrayList<>(this.createdDirectories);
      sorted.sort((a, b) -> {
         int depthA = depthOf(a);
         int depthB = depthOf(b);
         return depthA != depthB ? Integer.compare(depthA, depthB) : a.compareTo(b);
      });
      return new ChangeExecutionDiagnostic(
         "SIR-APP-CHANGE-CLEANUP-003",
         ChangeExecutionStage.CLEANUP,
         ExecutionSeverity.WARNING,
         "transaction-created output directories retained: " + sorted,
         Optional.empty()
      );
   }

   private ChangeExecutionDiagnostic appendOverall(
      CreateTransactionJournal journal, CreateTransactionJournal.OverallState state, String label, List<ChangeExecutionDiagnostic> diagnostics
   ) {
      try {
         journal.appendOverallTransition(state);
         this.hooks.afterJournalForce(journal, label);
         return null;
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-COMMIT-001", "journal " + label + " force failed: " + e.getMessage());
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-001", "fault after " + label + " force: " + e.getMessage());
      }
   }

   private void cleanupTransactionDirQuietly() {
      try {
         deleteRecursively(this.transactionDir);
      } catch (IOException var2) {
      }
   }

   private static void deleteRecursively(Path dir) throws IOException {
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
         try (Stream<Path> stream = Files.walk(dir)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
               Files.deleteIfExists(p);
            }
         }
      }
   }

   private static String generateTransactionId() {
      SecureRandom rng = new SecureRandom();
      byte[] bytes = new byte[16];
      rng.nextBytes(bytes);
      StringBuilder sb = new StringBuilder(32);

      for (byte b : bytes) {
         int v = b & 255;
         if (v < 16) {
            sb.append('0');
         }

         sb.append(Integer.toHexString(v));
      }

      return sb.toString();
   }

   private static ChangeBaselineReceipt toReceipt(BaselineBundle bundle) {
      BaselineDescriptor d = bundle.descriptor();
      return new ChangeBaselineReceipt(
         d.formatVersion(), bundle.baselineId(), d.toBaseRevision(), d.boundOutputRoot(), d.targetId(), d.loweredIrVersion(), d.manifestDigest()
      );
   }

   private static List<ChangeExecutionDiagnostic> withFallback(List<ChangeExecutionDiagnostic> diagnostics, ChangeExecutionDiagnostic primary) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>(diagnostics);
      if (out.stream().noneMatch(ChangeExecutionDiagnostic::isError)) {
         out.add(primary);
      }

      return List.copyOf(out);
   }

   private static List<ChangeExecutionDiagnostic> mapExecutionDiags(List<ExecutionDiagnostic> diags, ChangeExecutionStage stage) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>(diags.size());

      for (ExecutionDiagnostic d : diags) {
         out.add(new ChangeExecutionDiagnostic(d.code(), stage, ExecutionSeverity.valueOf(d.severity().name()), d.message(), d.relativePath()));
      }

      return out;
   }

   private static ChangeExecutionDiagnostic diag(String code, String message) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.COMMIT, ExecutionSeverity.ERROR, message, Optional.empty());
   }

   private static ChangeExecutionDiagnostic diag(String code, String message, String relativePath) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.COMMIT, ExecutionSeverity.ERROR, message, Optional.of(relativePath));
   }

   private sealed interface CommitResult permits ChangeCreateTransaction.CommitResult.Complete, ChangeCreateTransaction.CommitResult.Failure {
      record Complete() implements ChangeCreateTransaction.CommitResult {
      }

      record Failure(ChangeExecutionDiagnostic error) implements ChangeCreateTransaction.CommitResult {
         public Failure {
            Objects.requireNonNull(error, "error");
         }
      }
   }

   private sealed interface DirectoryPlanResult
      permits ChangeCreateTransaction.DirectoryPlanResult.Success,
      ChangeCreateTransaction.DirectoryPlanResult.Failure {
      record Failure(ChangeExecutionDiagnostic error) implements ChangeCreateTransaction.DirectoryPlanResult {
         public Failure {
            Objects.requireNonNull(error, "error");
         }
      }

      record Success(List<String> directories) implements ChangeCreateTransaction.DirectoryPlanResult {
         public Success {
            Objects.requireNonNull(directories, "directories");
            directories = List.copyOf(directories);
         }
      }
   }

   private sealed interface PublishResult permits ChangeCreateTransaction.PublishResult.Complete, ChangeCreateTransaction.PublishResult.Failure {
      record Complete() implements ChangeCreateTransaction.PublishResult {
      }

      record Failure(ChangeExecutionDiagnostic error) implements ChangeCreateTransaction.PublishResult {
         public Failure {
            Objects.requireNonNull(error, "error");
         }
      }
   }

   private enum RollbackOutcome {
      NO_CHANGES,
      ROLLED_BACK,
      RECOVERY_REQUIRED;
   }

   private sealed interface RollbackResult permits ChangeCreateTransaction.RollbackResult.Complete, ChangeCreateTransaction.RollbackResult.Incomplete {
      record Complete() implements ChangeCreateTransaction.RollbackResult {
      }

      record Incomplete(ChangeExecutionDiagnostic error) implements ChangeCreateTransaction.RollbackResult {
         public Incomplete {
            Objects.requireNonNull(error, "error");
         }
      }
   }
}
