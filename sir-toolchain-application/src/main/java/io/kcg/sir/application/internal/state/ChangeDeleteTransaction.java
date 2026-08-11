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
import io.kcg.sir.change.api.FileDeletion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public final class ChangeDeleteTransaction {
   private final BaselineBundleStore store;
   private final Path outputRoot;
   private final BaselineBundle b0Bundle;
   private final BaselineBundle b1Bundle;
   private final List<DeleteFilePayload> filePayloads;
   private final ApplyHooks hooks;
   private final String transactionId;
   private final Path transactionDir;
   private final Path backupDir;
   private final Path candidateBaselineDir;
   private SafeTargetResolver outputResolver;
   private SafeTargetResolver backupResolver;
   private DeleteOutputMutationEvidence mutationEvidence = DeleteOutputMutationEvidence.NO_OUTPUT_MUTATION;
   private static final ChangeDeleteTransaction.DeleteLinkIo DEFAULT_DELETE_LINK_IO = new ChangeDeleteTransaction.DeleteLinkIo() {
      @Override
      public void createLink(Path link, Path existing) throws IOException {
         Files.createLink(link, existing);
      }
   };
   static volatile ChangeDeleteTransaction.DeleteLinkIo deleteLinkIoForTests = null;

   private ChangeDeleteTransaction.DeleteLinkIo deleteLinkIo() {
      return deleteLinkIoForTests != null ? deleteLinkIoForTests : DEFAULT_DELETE_LINK_IO;
   }

   public ChangeDeleteTransaction(
      BaselineBundleStore store, Path outputRoot, BaselineBundle b0Bundle, BaselineBundle b1Bundle, List<DeleteFilePayload> filePayloads, ApplyHooks hooks
   ) {
      this.store = Objects.requireNonNull(store, "store");
      this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
      this.b0Bundle = Objects.requireNonNull(b0Bundle, "b0Bundle");
      this.b1Bundle = Objects.requireNonNull(b1Bundle, "b1Bundle");
      this.filePayloads = List.copyOf(Objects.requireNonNull(filePayloads, "filePayloads"));
      this.hooks = Objects.requireNonNull(hooks, "hooks");
      this.transactionId = generateTransactionId();
      this.transactionDir = store.stateRoot().resolve("transactions").resolve(this.transactionId);
      this.backupDir = this.transactionDir.resolve("backup");
      this.candidateBaselineDir = this.transactionDir.resolve("candidate-baseline");
   }

   public String transactionId() {
      return this.transactionId;
   }

   /**
    * [RQ-11] Why this orchestration exists: prepare/commit/publish are journaled
    * separately so an interruption at any boundary leaves a durable intent. A
    * commit failure is compensated by rollback, but the moment rollback cannot be
    * proven complete (RollbackResult.Incomplete) the result is RECOVERY_REQUIRED
    * and the transaction directory (journal + backup hard links + candidate B1)
    * is deliberately retained as authoritative recovery material — see
    * RecoveryStateMachineTest and the ADR-016 DELETE contract.
    */
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

      DeleteTransactionJournal journal = new DeleteTransactionJournal(
         this.transactionDir,
         this.transactionId,
         this.outputRoot,
         this.b0Bundle.baselineId(),
         this.b1Bundle.baselineId(),
         this.b0Bundle.descriptor().manifestDigest(),
         this.b1Bundle.descriptor().manifestDigest(),
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
      } else if (this.commit(journal, diagnostics) instanceof ChangeDeleteTransaction.CommitResult.Failure f) {
         ChangeDeleteTransaction.RollbackResult rollbackResult = this.rollback(journal, diagnostics);
         ChangeDeleteTransaction.RollbackOutcome outcome = this.selectOutcomeAfterRollback(rollbackResult);
         if (outcome == ChangeDeleteTransaction.RollbackOutcome.ROLLED_BACK) {
            this.cleanupTransactionDirQuietly();
            return ChangeApplyResult.failure(
               ChangeExecutionStage.COMMIT, ChangeApplyDisposition.ROLLED_BACK, Optional.of(b0Receipt), withFallback(diagnostics, f.error())
            );
         } else if (outcome == ChangeDeleteTransaction.RollbackOutcome.NO_CHANGES) {
            this.cleanupTransactionDirQuietly();
            return ChangeApplyResult.failure(
               ChangeExecutionStage.COMMIT, ChangeApplyDisposition.NO_CHANGES, Optional.of(b0Receipt), withFallback(diagnostics, f.error())
            );
         } else {
            return ChangeApplyResult.recoveryRequired(
               ChangeExecutionStage.COMMIT, RecoveryHandle.of(this.transactionId), Optional.of(b0Receipt), withFallback(diagnostics, f.error())
            );
         }
      } else if (this.publish(journal, diagnostics) instanceof ChangeDeleteTransaction.PublishResult.Failure pf) {
         return this.recoveryRequiredAfterPublish(journal, diagnostics, pf.error());
      } else {
         List<ChangeExecutionDiagnostic> cleanupWarnings = this.cleanup(journal);
         diagnostics.addAll(cleanupWarnings);
         ChangeOutputManifest outputManifest = buildOutputManifest(this.b1Bundle.descriptor().manifest());
         return ChangeApplyResult.applied(ChangeApplyOutcome.FILES_AND_BASELINE, b1Receipt, outputManifest, List.copyOf(diagnostics));
      }
   }

   private ChangeDeleteTransaction.RollbackOutcome selectOutcomeAfterRollback(ChangeDeleteTransaction.RollbackResult rollbackResult) {
      if (rollbackResult instanceof ChangeDeleteTransaction.RollbackResult.Complete) {
         return this.mutationEvidence.isMutationObserved()
            ? ChangeDeleteTransaction.RollbackOutcome.ROLLED_BACK
            : ChangeDeleteTransaction.RollbackOutcome.NO_CHANGES;
      } else {
         return ChangeDeleteTransaction.RollbackOutcome.RECOVERY_REQUIRED;
      }
   }

   private static ChangeOutputManifest buildOutputManifest(List<BaselineManifestEntry> manifest) {
      List<ChangeOutputManifest.Entry> entries = new ArrayList<>(manifest.size());

      for (BaselineManifestEntry e : manifest) {
         entries.add(new ChangeOutputManifest.Entry(e.relativePath(), e.byteCount(), e.sha256Hex(), e.artifactId(), e.ownerSymbol()));
      }

      return new ChangeOutputManifest(entries);
   }

   private ChangeExecutionDiagnostic prepare(DeleteTransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics) {
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
         return diag("SIR-APP-CHANGE-COMMIT-001", "V3 journal create failed: " + e.getMessage());
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-001", "fault after V3 journal create: " + e.getMessage());
      }

      try {
         Files.createDirectory(this.backupDir);
         Files.createDirectory(this.candidateBaselineDir);
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-COMMIT-001", "failed to create backup/candidate-baseline directories: " + e.getMessage());
      }

      try {
         this.backupResolver = SafeTargetResolver.forRoot(this.backupDir);
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-COMMIT-001", "unsafe backup directory: " + e.getMessage());
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
            journal.appendOverallTransition(DeleteTransactionJournal.OverallState.PREPARED);
            this.hooks.afterJournalForce(journal, "PREPARED");
            return null;
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-COMMIT-001", "journal PREPARED force failed: " + e.getMessage());
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-001", "fault after PREPARED force: " + e.getMessage());
         }
      }
   }

   private ChangeDeleteTransaction.CommitResult commit(DeleteTransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics) {
      ChangeExecutionDiagnostic committingError = this.appendOverall(journal, DeleteTransactionJournal.OverallState.COMMITTING, "COMMITTING", diagnostics);
      if (committingError != null) {
         return new ChangeDeleteTransaction.CommitResult.Failure(committingError);
      } else {
         List<ChangeExecutionDiagnostic> b0ManifestErrors = OutputManifestVerifier.verify(
            this.outputRoot, this.b0Bundle.descriptor().manifest(), ChangeExecutionStage.COMMIT
         );
         if (!b0ManifestErrors.isEmpty()) {
            diagnostics.addAll(b0ManifestErrors);
            return new ChangeDeleteTransaction.CommitResult.Failure(b0ManifestErrors.get(0));
         } else {
            List<FileDeletion> deletions = this.plannedDeletions();
            List<ExecutionDiagnostic> protectErrors = PlanProtector.protectDeletions(this.outputRoot, deletions);
            if (!protectErrors.isEmpty()) {
               diagnostics.addAll(mapExecutionDiags(protectErrors, ChangeExecutionStage.PROTECT));
               return new ChangeDeleteTransaction.CommitResult.Failure(
                  diagnostics.stream()
                     .filter(ChangeExecutionDiagnostic::isError)
                     .findFirst()
                     .orElse(diag("SIR-APP-CHANGE-PROTECT-110", "protectDeletions failed without explicit error"))
               );
            } else if (DeleteManifestDeltaVerifier.verify(this.b0Bundle.descriptor().manifest(), this.b1Bundle.descriptor().manifest(), deletions) instanceof DeleteManifestDeltaVerifier.Result.Failure f
               )
             {
               diagnostics.add(f.error());
               return new ChangeDeleteTransaction.CommitResult.Failure(f.error());
            } else {
               for (int i = 0; i < this.filePayloads.size(); i++) {
                  DeleteFilePayload payload = this.filePayloads.get(i);
                  ChangeExecutionDiagnostic fileError = this.deleteFile(journal, i, payload, diagnostics);
                  if (fileError != null) {
                     return new ChangeDeleteTransaction.CommitResult.Failure(fileError);
                  }
               }

               try {
                  this.hooks.afterAllDeleteLinks();
               } catch (Exception e) {
                  return new ChangeDeleteTransaction.CommitResult.Failure(diag("SIR-APP-CHANGE-COMMIT-008", "fault after all delete links: " + e.getMessage()));
               }

               for (DeleteFilePayload payload : this.filePayloads) {
                  ChangeExecutionDiagnostic absentError = this.proveTargetAbsent(payload.relativePath());
                  if (absentError != null) {
                     return new ChangeDeleteTransaction.CommitResult.Failure(absentError);
                  }
               }

               List<ChangeExecutionDiagnostic> b1ManifestErrors = OutputManifestVerifier.verify(
                  this.outputRoot, this.b1Bundle.descriptor().manifest(), ChangeExecutionStage.COMMIT
               );
               if (!b1ManifestErrors.isEmpty()) {
                  diagnostics.addAll(b1ManifestErrors);
                  return new ChangeDeleteTransaction.CommitResult.Failure(b1ManifestErrors.get(0));
               } else {
                  ChangeExecutionDiagnostic fcError = this.appendOverall(
                     journal, DeleteTransactionJournal.OverallState.FILES_COMMITTED, "FILES_COMMITTED", diagnostics
                  );
                  return fcError != null ? new ChangeDeleteTransaction.CommitResult.Failure(fcError) : new ChangeDeleteTransaction.CommitResult.Complete();
               }
            }
         }
      }
   }

   private ChangeExecutionDiagnostic deleteFile(
      DeleteTransactionJournal journal, int index, DeleteFilePayload payload, List<ChangeExecutionDiagnostic> diagnostics
   ) {
      String rel = payload.relativePath();

      Path target;
      try {
         target = this.outputResolver.resolveAndVerifyB0(rel, payload.b0Entry());
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-COMMIT-006", "target B0 verification failed for " + rel + ": " + e.getMessage(), rel);
      }

      Path backup;
      try {
         backup = this.backupResolver.resolveNoCreate(DeleteTransactionJournal.backupFileName(index));
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-COMMIT-006", "failed to resolve backup path for " + rel + ": " + e.getMessage(), rel);
      }

      try {
         Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         return diag("SIR-APP-CHANGE-COMMIT-006", "backup already exists before createLink: " + rel, rel);
      } catch (NoSuchFileException var27) {
         try {
            this.hooks.beforeBackupLinkIntent(index, rel, target, backup);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "fault before backup link intent of " + rel + ": " + e.getMessage(), rel);
         }

         try {
            journal.appendFileTransition(index, rel, DeleteTransactionJournal.FileTransitionState.BACKUP_LINK_INTENT_DURABLE);
            this.hooks.afterJournalForce(journal, "file " + index + " BACKUP_LINK_INTENT_DURABLE");
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "journal BACKUP_LINK_INTENT force failed for " + rel + ": " + e.getMessage(), rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "fault after journal BACKUP_LINK_INTENT for " + rel + ": " + e.getMessage(), rel);
         }

         this.mutationEvidence = this.mutationEvidence.advanceTo(DeleteOutputMutationEvidence.BACKUP_LINK_POSSIBLE);

         try {
            this.deleteLinkIo().createLink(backup, target);
         } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return this.handleCreateLinkException(journal, index, rel, target, backup, payload, e, diagnostics);
         }

         try {
            this.hooks.afterBackupLinkCreate(index, rel, backup);
         } catch (Exception e) {
            this.mutationEvidence = this.mutationEvidence.advanceTo(DeleteOutputMutationEvidence.BACKUP_LINK_PROVED);
            return diag("SIR-APP-CHANGE-COMMIT-008", "fault after createLink of " + rel + ": " + e.getMessage(), rel);
         }

         ChangeExecutionDiagnostic proofError = this.proveBackupLinkage(target, backup, payload);
         if (proofError != null) {
            this.mutationEvidence = this.mutationEvidence.advanceTo(DeleteOutputMutationEvidence.BACKUP_LINK_PROVED);
            return proofError;
         }

         this.mutationEvidence = this.mutationEvidence.advanceTo(DeleteOutputMutationEvidence.BACKUP_LINK_PROVED);

         try {
            journal.appendFileTransition(index, rel, DeleteTransactionJournal.FileTransitionState.BACKUP_LINKED_DURABLE);
            this.hooks.afterJournalForce(journal, "file " + index + " BACKUP_LINKED_DURABLE");
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "journal BACKUP_LINKED force failed for " + rel + ": " + e.getMessage(), rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "fault after journal BACKUP_LINKED for " + rel + ": " + e.getMessage(), rel);
         }

         ChangeExecutionDiagnostic reProofError = this.proveBackupLinkage(target, backup, payload);
         if (reProofError != null) {
            return reProofError;
         }

         try {
            this.hooks.beforeDeleteIntent(index, rel, target);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "fault before delete intent of " + rel + ": " + e.getMessage(), rel);
         }

         try {
            journal.appendFileTransition(index, rel, DeleteTransactionJournal.FileTransitionState.DELETE_INTENT_DURABLE);
            this.hooks.afterJournalForce(journal, "file " + index + " DELETE_INTENT_DURABLE");
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "journal DELETE_INTENT force failed for " + rel + ": " + e.getMessage(), rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "fault after journal DELETE_INTENT for " + rel + ": " + e.getMessage(), rel);
         }

         this.mutationEvidence = this.mutationEvidence.advanceTo(DeleteOutputMutationEvidence.DELETE_INTENT_FORCED);

         try {
            Files.delete(target);
            this.hooks.afterTargetDelete(index, rel);
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "failed to delete target " + rel + ": " + e.getMessage(), rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "fault after target delete of " + rel + ": " + e.getMessage(), rel);
         }

         ChangeExecutionDiagnostic absentError = this.proveTargetAbsent(rel);
         if (absentError != null) {
            this.mutationEvidence = this.mutationEvidence.advanceTo(DeleteOutputMutationEvidence.TARGET_DELETED_PROVED);
            return absentError;
         }

         this.mutationEvidence = this.mutationEvidence.advanceTo(DeleteOutputMutationEvidence.TARGET_DELETED_PROVED);

         try {
            this.backupResolver.resolveAndVerifyB0(DeleteTransactionJournal.backupFileName(index), payload.b0Entry());
         } catch (SafeTargetResolver.UnsafePathException e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "backup B0 verification failed after delete of " + rel + ": " + e.getMessage(), rel);
         }

         try {
            journal.appendFileTransition(index, rel, DeleteTransactionJournal.FileTransitionState.DELETED_DURABLE);
            this.hooks.afterJournalForce(journal, "file " + index + " DELETED_DURABLE");
            this.hooks.afterDeleteFileComplete(index, rel);
            return null;
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "journal DELETED force failed for " + rel + ": " + e.getMessage(), rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "fault after journal DELETED for " + rel + ": " + e.getMessage(), rel);
         }
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-COMMIT-006", "failed to prove backup absence for " + rel + ": " + e.getMessage(), rel);
      }
   }

   /**
    * [RQ-11] The NOFOLLOW_LINKS regular-file proof on BOTH ends is mandatory
    * before Files.isSameFile: isSameFile follows links, so a symlink swapped in
    * after backup creation would otherwise make an external file "the same" as
    * the backup and the SHA proof would still read through it. Verified by
    * RecoveryStateMachineTest.currentB0_deleted_restoresTargetFromBackupLink and
    * the RQ-09 reparse-point review.
    */
   private ChangeExecutionDiagnostic proveBackupLinkage(Path target, Path backup, DeleteFilePayload payload) {
      String rel = payload.relativePath();
      long expectedBytes = payload.byteCount();
      String expectedSha = payload.sha256Hex();

      BasicFileAttributes targetAttrs;
      try {
         targetAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "target absent during backup proof of " + rel, rel);
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "failed to read target attributes during backup proof of " + rel + ": " + e.getMessage(), rel);
      }

      if (!targetAttrs.isSymbolicLink() && targetAttrs.isRegularFile()) {
         BasicFileAttributes backupAttrs;
         try {
            backupAttrs = Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         } catch (NoSuchFileException e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "backup absent during backup proof of " + rel, rel);
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-COMMIT-008", "failed to read backup attributes during backup proof of " + rel + ": " + e.getMessage(), rel);
         }

         if (!backupAttrs.isSymbolicLink() && backupAttrs.isRegularFile()) {
            try {
               if (!Files.isSameFile(target, backup)) {
                  return diag("SIR-APP-CHANGE-COMMIT-008", "isSameFile(target, backup) returned false for " + rel, rel);
               }
            } catch (IOException | SecurityException e) {
               return diag("SIR-APP-CHANGE-COMMIT-008", "isSameFile check failed during backup proof of " + rel + ": " + e.getMessage(), rel);
            }

            try {
               if (targetAttrs.size() != expectedBytes) {
                  return diag(
                     "SIR-APP-CHANGE-COMMIT-008",
                     "target byte count mismatch during backup proof of " + rel + ": expected=" + expectedBytes + " actual=" + targetAttrs.size(),
                     rel
                  );
               }

               byte[] targetBytes = Files.readAllBytes(target);
               String targetSha = Sha256.hexDigest(targetBytes);
               if (!targetSha.equals(expectedSha)) {
                  return diag("SIR-APP-CHANGE-COMMIT-008", "target SHA-256 mismatch during backup proof of " + rel, rel);
               }
            } catch (IOException | SecurityException e) {
               return diag("SIR-APP-CHANGE-COMMIT-008", "failed to verify target bytes during backup proof of " + rel + ": " + e.getMessage(), rel);
            }

            try {
               if (backupAttrs.size() != expectedBytes) {
                  return diag(
                     "SIR-APP-CHANGE-COMMIT-008",
                     "backup byte count mismatch during backup proof of " + rel + ": expected=" + expectedBytes + " actual=" + backupAttrs.size(),
                     rel
                  );
               }

               byte[] backupBytes = Files.readAllBytes(backup);
               String backupSha = Sha256.hexDigest(backupBytes);
               return !backupSha.equals(expectedSha) ? diag("SIR-APP-CHANGE-COMMIT-008", "backup SHA-256 mismatch during backup proof of " + rel, rel) : null;
            } catch (IOException | SecurityException e) {
               return diag("SIR-APP-CHANGE-COMMIT-008", "failed to verify backup bytes during backup proof of " + rel + ": " + e.getMessage(), rel);
            }
         } else {
            return diag("SIR-APP-CHANGE-COMMIT-008", "backup is not regular file during backup proof of " + rel, rel);
         }
      } else {
         return diag("SIR-APP-CHANGE-COMMIT-008", "target is not regular file during backup proof of " + rel, rel);
      }
   }

   private ChangeExecutionDiagnostic handleCreateLinkException(
      DeleteTransactionJournal journal,
      int index,
      String rel,
      Path target,
      Path backup,
      DeleteFilePayload payload,
      Exception cause,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      BasicFileAttributes backupAttrs;
      try {
         backupAttrs = Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         return diag("SIR-APP-CHANGE-COMMIT-007", "createLink failed and backup is absent for " + rel + ": " + cause.getMessage(), rel);
      } catch (IOException | SecurityException e) {
         this.mutationEvidence = this.mutationEvidence.advanceTo(DeleteOutputMutationEvidence.BACKUP_LINK_PROVED);
         return diag("SIR-APP-CHANGE-COMMIT-008", "cannot prove backup state after createLink failure for " + rel + ": " + e.getMessage(), rel);
      }

      this.mutationEvidence = this.mutationEvidence.advanceTo(DeleteOutputMutationEvidence.BACKUP_LINK_PROVED);
      if (!backupAttrs.isSymbolicLink() && backupAttrs.isRegularFile()) {
         ChangeExecutionDiagnostic proofError = this.proveBackupLinkage(target, backup, payload);
         return proofError != null ? proofError : this.resumeAfterCreateLinkSuccess(journal, index, rel, target, backup, payload);
      } else {
         return diag("SIR-APP-CHANGE-COMMIT-008", "backup exists as non-regular-file after createLink failure for " + rel, rel);
      }
   }

   private ChangeExecutionDiagnostic resumeAfterCreateLinkSuccess(
      DeleteTransactionJournal journal, int index, String rel, Path target, Path backup, DeleteFilePayload payload
   ) {
      ChangeExecutionDiagnostic proofError = this.proveBackupLinkage(target, backup, payload);
      if (proofError != null) {
         return proofError;
      }

      try {
         journal.appendFileTransition(index, rel, DeleteTransactionJournal.FileTransitionState.BACKUP_LINKED_DURABLE);
         this.hooks.afterJournalForce(journal, "file " + index + " BACKUP_LINKED_DURABLE");
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "journal BACKUP_LINKED force failed for " + rel + ": " + e.getMessage(), rel);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "fault after journal BACKUP_LINKED for " + rel + ": " + e.getMessage(), rel);
      }

      ChangeExecutionDiagnostic reProofError = this.proveBackupLinkage(target, backup, payload);
      if (reProofError != null) {
         return reProofError;
      }

      try {
         journal.appendFileTransition(index, rel, DeleteTransactionJournal.FileTransitionState.DELETE_INTENT_DURABLE);
         this.hooks.afterJournalForce(journal, "file " + index + " DELETE_INTENT_DURABLE");
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "journal DELETE_INTENT force failed for " + rel + ": " + e.getMessage(), rel);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "fault after journal DELETE_INTENT for " + rel + ": " + e.getMessage(), rel);
      }

      this.mutationEvidence = this.mutationEvidence.advanceTo(DeleteOutputMutationEvidence.DELETE_INTENT_FORCED);

      try {
         Files.delete(target);
         this.hooks.afterTargetDelete(index, rel);
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "failed to delete target " + rel + ": " + e.getMessage(), rel);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "fault after target delete of " + rel + ": " + e.getMessage(), rel);
      }

      ChangeExecutionDiagnostic absentError = this.proveTargetAbsent(rel);
      if (absentError != null) {
         this.mutationEvidence = this.mutationEvidence.advanceTo(DeleteOutputMutationEvidence.TARGET_DELETED_PROVED);
         return absentError;
      }

      this.mutationEvidence = this.mutationEvidence.advanceTo(DeleteOutputMutationEvidence.TARGET_DELETED_PROVED);

      try {
         this.backupResolver.resolveAndVerifyB0(DeleteTransactionJournal.backupFileName(index), payload.b0Entry());
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "backup B0 verification failed after delete of " + rel + ": " + e.getMessage(), rel);
      }

      try {
         journal.appendFileTransition(index, rel, DeleteTransactionJournal.FileTransitionState.DELETED_DURABLE);
         this.hooks.afterJournalForce(journal, "file " + index + " DELETED_DURABLE");
         this.hooks.afterDeleteFileComplete(index, rel);
         return null;
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "journal DELETED force failed for " + rel + ": " + e.getMessage(), rel);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-008", "fault after journal DELETED for " + rel + ": " + e.getMessage(), rel);
      }
   }

   private ChangeDeleteTransaction.PublishResult publish(DeleteTransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics) {
      ChangeExecutionDiagnostic publishingError = this.appendOverall(journal, DeleteTransactionJournal.OverallState.PUBLISHING, "PUBLISHING", diagnostics);
      if (publishingError != null) {
         return new ChangeDeleteTransaction.PublishResult.Failure(publishingError);
      }

      try {
         this.store.publishCandidateBundle(this.candidateBaselineDir, this.b1Bundle.baselineId());
         this.hooks.afterBundlePublish();
      } catch (IOException e) {
         return new ChangeDeleteTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "candidate Bundle publish failed: " + e.getMessage()));
      } catch (Exception e) {
         return new ChangeDeleteTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "fault after candidate Bundle publish: " + e.getMessage()));
      }

      try {
         this.hooks.beforeCurrentNewWrite();
      } catch (Exception e) {
         return new ChangeDeleteTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "fault before CURRENT.new write: " + e.getMessage()));
      }

      try {
         this.store.writeCurrentNew(this.b1Bundle.baselineId());
         this.hooks.afterCurrentNewWrite();
         this.store.moveCurrentNewToCurrent();
         this.hooks.afterCurrentAtomicMove();
      } catch (IOException e) {
         return new ChangeDeleteTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "CURRENT publish failed: " + e.getMessage()));
      } catch (Exception e) {
         return new ChangeDeleteTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "fault during CURRENT publish: " + e.getMessage()));
      }

      ChangeExecutionDiagnostic publishedError = this.appendOverall(
         journal, DeleteTransactionJournal.OverallState.BASELINE_PUBLISHED, "BASELINE_PUBLISHED", diagnostics
      );
      if (publishedError != null) {
         return new ChangeDeleteTransaction.PublishResult.Failure(publishedError);
      }

      try {
         this.hooks.afterPublishReread();
      } catch (Exception e) {
         return new ChangeDeleteTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-002", "fault after publish reread: " + e.getMessage()));
      }

      BaselineBundleStore.CurrentReadResult currentRead = this.store.readCurrent();
      if (currentRead instanceof BaselineBundleStore.CurrentReadResult.Failure f) {
         return new ChangeDeleteTransaction.PublishResult.Failure(diag(f.code(), "reread CURRENT failed: " + f.message()));
      } else if (currentRead instanceof BaselineBundleStore.CurrentReadResult.Absent) {
         return new ChangeDeleteTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-002", "reread CURRENT: file is absent after publish"));
      } else {
         String currentId = ((BaselineBundleStore.CurrentReadResult.Present)currentRead).baselineId();
         if (!currentId.equals(this.b1Bundle.baselineId())) {
            return new ChangeDeleteTransaction.PublishResult.Failure(
               diag("SIR-APP-CHANGE-PUBLISH-002", "reread CURRENT mismatch: expected=" + this.b1Bundle.baselineId() + " actual=" + currentId)
            );
         } else {
            return this.store.loadBundle(this.b1Bundle.baselineId()) instanceof BaselineBundleStore.LoadResult.Failure lf
               ? new ChangeDeleteTransaction.PublishResult.Failure(diag(lf.code(), "reread B1 Bundle failed: " + lf.message()))
               : new ChangeDeleteTransaction.PublishResult.Complete();
         }
      }
   }

   private ChangeDeleteTransaction.RollbackResult rollback(DeleteTransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics) {
      try {
         journal.appendOverallTransition(DeleteTransactionJournal.OverallState.ROLLING_BACK);
         this.hooks.afterJournalForce(journal, "ROLLING_BACK");
      } catch (IOException e) {
         return new ChangeDeleteTransaction.RollbackResult.Incomplete(
            diag("SIR-APP-CHANGE-RECOVERY-001", "journal ROLLING_BACK force failed: " + e.getMessage())
         );
      } catch (Exception e) {
         return new ChangeDeleteTransaction.RollbackResult.Incomplete(diag("SIR-APP-CHANGE-RECOVERY-001", "fault after ROLLING_BACK force: " + e.getMessage()));
      }

      for (int i = this.filePayloads.size() - 1; i >= 0; i--) {
         DeleteFilePayload payload = this.filePayloads.get(i);
         String rel = payload.relativePath();
         int index = i;

         DeleteTransactionJournal.FileTransitionState fileState;
         try {
            if (!(DeleteTransactionJournal.parse(journal.journalPath()) instanceof DeleteTransactionJournal.ParseOk ok)) {
               return new ChangeDeleteTransaction.RollbackResult.Incomplete(
                  diag("SIR-APP-CHANGE-RECOVERY-001", "cannot re-read journal during rollback: " + rel, rel)
               );
            }

            fileState = ok.snapshot().fileStates().get(rel);
         } catch (Exception e) {
            return new ChangeDeleteTransaction.RollbackResult.Incomplete(
               diag("SIR-APP-CHANGE-RECOVERY-001", "journal re-read failed during rollback of " + rel + ": " + e.getMessage(), rel)
            );
         }

         if (fileState == null) {
            return new ChangeDeleteTransaction.RollbackResult.Incomplete(
               diag("SIR-APP-CHANGE-RECOVERY-001", "journal lost file state during rollback of " + rel + " — internal inconsistency", rel)
            );
         }

         if (fileState != DeleteTransactionJournal.FileTransitionState.PREPARING
            && fileState != DeleteTransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE) {
            ChangeExecutionDiagnostic rollbackError = this.rollbackFile(journal, index, rel, payload, fileState, diagnostics);
            if (rollbackError != null) {
               return new ChangeDeleteTransaction.RollbackResult.Incomplete(rollbackError);
            }
         }
      }

      try {
         journal.appendOverallTransition(DeleteTransactionJournal.OverallState.ROLLED_BACK);
         this.hooks.afterJournalForce(journal, "ROLLED_BACK");
      } catch (IOException e) {
         return new ChangeDeleteTransaction.RollbackResult.Incomplete(
            diag("SIR-APP-CHANGE-RECOVERY-001", "journal ROLLED_BACK force failed: " + e.getMessage())
         );
      } catch (Exception e) {
         return new ChangeDeleteTransaction.RollbackResult.Incomplete(diag("SIR-APP-CHANGE-RECOVERY-001", "fault after ROLLED_BACK force: " + e.getMessage()));
      }

      List<ChangeExecutionDiagnostic> manifestErrors = OutputManifestVerifier.verify(
         this.outputRoot, this.b0Bundle.descriptor().manifest(), ChangeExecutionStage.COMMIT
      );
      if (!manifestErrors.isEmpty()) {
         diagnostics.addAll(manifestErrors);
         return new ChangeDeleteTransaction.RollbackResult.Incomplete(manifestErrors.get(0));
      } else {
         return new ChangeDeleteTransaction.RollbackResult.Complete();
      }
   }

   private ChangeExecutionDiagnostic rollbackFile(
      DeleteTransactionJournal journal,
      int index,
      String rel,
      DeleteFilePayload payload,
      DeleteTransactionJournal.FileTransitionState fileState,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      Path target;
      Path backup;
      try {
         target = this.outputResolver.resolveNoCreate(rel);
         backup = this.backupResolver.resolveNoCreate(DeleteTransactionJournal.backupFileName(index));
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve target/backup path for rollback of " + rel + ": " + e.getMessage(), rel);
      }

      BasicFileAttributes targetAttrs;
      try {
         targetAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         targetAttrs = null;
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read target during rollback of " + rel + ": " + e.getMessage(), rel);
      }

      try {
         this.hooks.beforeDeleteRollbackStep(index, rel, fileState);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "fault during rollback of " + rel + ": " + e.getMessage(), rel);
      }

      if (fileState == DeleteTransactionJournal.FileTransitionState.BACKUP_LINK_INTENT_DURABLE) {
         return this.rollbackBackupLinkIntent(journal, index, rel, target, targetAttrs, backup, payload);
      } else if (fileState == DeleteTransactionJournal.FileTransitionState.BACKUP_LINKED_DURABLE) {
         return this.rollbackBackupLinked(journal, index, rel, target, targetAttrs, backup, payload);
      } else {
         return fileState == DeleteTransactionJournal.FileTransitionState.DELETE_INTENT_DURABLE
            ? this.rollbackDeleteIntent(journal, index, rel, target, targetAttrs, backup, payload)
            : this.rollbackDeleted(journal, index, rel, target, targetAttrs, backup, payload);
      }
   }

   private ChangeExecutionDiagnostic rollbackBackupLinkIntent(
      DeleteTransactionJournal journal, int index, String rel, Path target, BasicFileAttributes targetAttrs, Path backup, DeleteFilePayload payload
   ) {
      BasicFileAttributes backupAttrs;
      try {
         backupAttrs = Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         backupAttrs = null;
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read backup during BACKUP_LINK_INTENT rollback of " + rel + ": " + e.getMessage(), rel);
      }

      if (backupAttrs == null) {
         ChangeExecutionDiagnostic targetProof = this.proveTargetMatchesB0Fresh(target, payload);
         return targetProof != null ? targetProof : this.forceRolledBackDurable(journal, index, rel);
      }

      ChangeExecutionDiagnostic proofError = this.proveBackupLinkage(target, backup, payload);
      if (proofError != null) {
         return proofError;
      }

      try {
         Files.delete(backup);
         this.hooks.afterBackupDelete(index, rel);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to delete backup during BACKUP_LINK_INTENT rollback of " + rel + ": " + e.getMessage(), rel);
      }

      ChangeExecutionDiagnostic backupAbsent = this.proveBackupAbsent(index, rel);
      return backupAbsent != null ? backupAbsent : this.forceRolledBackDurable(journal, index, rel);
   }

   private ChangeExecutionDiagnostic proveTargetMatchesB0Fresh(Path target, DeleteFilePayload payload) {
      String rel = payload.relativePath();

      BasicFileAttributes attrs;
      try {
         attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         return diag(
            "SIR-APP-CHANGE-RECOVERY-001", "target absent during BACKUP_LINK_INTENT rollback of " + rel + " — external mutation must not be inferred", rel
         );
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read target during BACKUP_LINK_INTENT rollback of " + rel + ": " + e.getMessage(), rel);
      }

      return !attrs.isSymbolicLink() && attrs.isRegularFile()
         ? this.verifyFileMatchesB0(target, attrs, payload)
         : diag("SIR-APP-CHANGE-RECOVERY-001", "target is not regular file during BACKUP_LINK_INTENT rollback of " + rel, rel);
   }

   private ChangeExecutionDiagnostic rollbackBackupLinked(
      DeleteTransactionJournal journal, int index, String rel, Path target, BasicFileAttributes targetAttrs, Path backup, DeleteFilePayload payload
   ) {
      ChangeExecutionDiagnostic proofError = this.proveBackupLinkage(target, backup, payload);
      if (proofError != null) {
         return proofError;
      }

      try {
         Files.delete(backup);
         this.hooks.afterBackupDelete(index, rel);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to delete backup during BACKUP_LINKED rollback of " + rel + ": " + e.getMessage(), rel);
      }

      ChangeExecutionDiagnostic backupAbsent = this.proveBackupAbsent(index, rel);
      return backupAbsent != null ? backupAbsent : this.forceRolledBackDurable(journal, index, rel);
   }

   private ChangeExecutionDiagnostic rollbackDeleteIntent(
      DeleteTransactionJournal journal, int index, String rel, Path target, BasicFileAttributes targetAttrs, Path backup, DeleteFilePayload payload
   ) {
      if (targetAttrs != null) {
         if (!targetAttrs.isSymbolicLink() && targetAttrs.isRegularFile()) {
            ChangeExecutionDiagnostic targetProof = this.verifyFileMatchesB0(target, targetAttrs, payload);
            if (targetProof != null) {
               return targetProof;
            }

            try {
               BasicFileAttributes backupAttrs = Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "backup absent during DELETE_INTENT rollback of " + rel + " — cannot prove same-file linkage", rel);
            } catch (IOException | SecurityException e) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read backup during DELETE_INTENT rollback of " + rel + ": " + e.getMessage(), rel);
            }

            ChangeExecutionDiagnostic proofError = this.proveBackupLinkage(target, backup, payload);
            if (proofError != null) {
               return proofError;
            }

            try {
               Files.delete(backup);
               this.hooks.afterBackupDelete(index, rel);
            } catch (Exception e) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to delete backup during DELETE_INTENT rollback of " + rel + ": " + e.getMessage(), rel);
            }

            ChangeExecutionDiagnostic backupAbsent = this.proveBackupAbsent(index, rel);
            return backupAbsent != null ? backupAbsent : this.forceRolledBackDurable(journal, index, rel);
         } else {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "target is not regular file during DELETE_INTENT rollback of " + rel, rel);
         }
      } else {
         return this.restoreTargetFromBackup(journal, index, rel, target, backup, payload);
      }
   }

   private ChangeExecutionDiagnostic rollbackDeleted(
      DeleteTransactionJournal journal, int index, String rel, Path target, BasicFileAttributes targetAttrs, Path backup, DeleteFilePayload payload
   ) {
      return targetAttrs != null
         ? diag("SIR-APP-CHANGE-RECOVERY-001", "target exists during DELETED rollback of " + rel + " — external file must not be deleted or overwritten", rel)
         : this.restoreTargetFromBackup(journal, index, rel, target, backup, payload);
   }

   private ChangeExecutionDiagnostic restoreTargetFromBackup(
      DeleteTransactionJournal journal, int index, String rel, Path target, Path backup, DeleteFilePayload payload
   ) {
      BasicFileAttributes backupAttrs;
      try {
         backupAttrs = Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "backup absent during DELETE rollback of " + rel + " — cannot restore target", rel);
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read backup during DELETE rollback of " + rel + ": " + e.getMessage(), rel);
      }

      if (!backupAttrs.isSymbolicLink() && backupAttrs.isRegularFile()) {
         ChangeExecutionDiagnostic backupProof = this.verifyFileMatchesB0(backup, backupAttrs, payload);
         if (backupProof != null) {
            return backupProof;
         }

         try {
            journal.appendFileTransition(index, rel, DeleteTransactionJournal.FileTransitionState.RESTORE_LINK_INTENT_DURABLE);
            this.hooks.afterJournalForce(journal, "file " + index + " RESTORE_LINK_INTENT_DURABLE");
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "journal RESTORE_LINK_INTENT force failed for " + rel + ": " + e.getMessage(), rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "fault after journal RESTORE_LINK_INTENT for " + rel + ": " + e.getMessage(), rel);
         }

         try {
            Files.createLink(target, backup);
            this.hooks.afterRestoreLinkCreate(index, rel, target);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "createLink(target, backup) failed during rollback of " + rel + ": " + e.getMessage(), rel);
         }

         ChangeExecutionDiagnostic proofError = this.proveBackupLinkage(target, backup, payload);
         if (proofError != null) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "restore proof failed for " + rel + ": " + proofError.message(), rel);
         }

         try {
            journal.appendFileTransition(index, rel, DeleteTransactionJournal.FileTransitionState.RESTORED_DURABLE);
            this.hooks.afterJournalForce(journal, "file " + index + " RESTORED_DURABLE");
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "journal RESTORED force failed for " + rel + ": " + e.getMessage(), rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "fault after journal RESTORED for " + rel + ": " + e.getMessage(), rel);
         }

         return this.forceRolledBackDurable(journal, index, rel);
      } else {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "backup is not regular file during DELETE rollback of " + rel, rel);
      }
   }

   private ChangeExecutionDiagnostic forceRolledBackDurable(DeleteTransactionJournal journal, int index, String rel) {
      try {
         journal.appendFileTransition(index, rel, DeleteTransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE);
         this.hooks.afterJournalForce(journal, "file " + index + " ROLLED_BACK_DURABLE");
         this.hooks.afterDeleteRollbackComplete(index, rel);
         return null;
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "journal ROLLED_BACK_DURABLE force failed for " + rel + ": " + e.getMessage(), rel);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "fault after journal ROLLED_BACK_DURABLE for " + rel + ": " + e.getMessage(), rel);
      }
   }

   private ChangeExecutionDiagnostic verifyFileMatchesB0(Path file, BasicFileAttributes attrs, DeleteFilePayload payload) {
      String rel = payload.relativePath();
      if (attrs.size() != payload.byteCount()) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "byte count mismatch for " + rel + ": expected B0=" + payload.byteCount() + " actual=" + attrs.size(), rel);
      }

      try {
         byte[] bytes = Files.readAllBytes(file);
         String sha = Sha256.hexDigest(bytes);
         return !sha.equals(payload.sha256Hex())
            ? diag("SIR-APP-CHANGE-RECOVERY-001", "SHA-256 mismatch for " + rel + " — external file must not be deleted", rel)
            : null;
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read bytes of " + rel + ": " + e.getMessage(), rel);
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
         return diag("SIR-APP-CHANGE-RECOVERY-001", "target exists for absence proof of " + rel + " — external file must not be deleted", rel);
      } catch (NoSuchFileException e) {
         return null;
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to prove target absence for " + rel + ": " + e.getMessage(), rel);
      }
   }

   private ChangeExecutionDiagnostic proveBackupAbsent(int index, String rel) {
      Path backup;
      try {
         backup = this.backupResolver.resolveNoCreate(DeleteTransactionJournal.backupFileName(index));
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve backup path for absence proof of " + rel + ": " + e.getMessage(), rel);
      }

      try {
         Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         return diag("SIR-APP-CHANGE-RECOVERY-001", "backup exists for absence proof of " + rel + " — concurrent mutation", rel);
      } catch (NoSuchFileException e) {
         return null;
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to prove backup absence for " + rel + ": " + e.getMessage(), rel);
      }
   }

   private List<ChangeExecutionDiagnostic> cleanup(DeleteTransactionJournal journal) {
      List<ChangeExecutionDiagnostic> warnings = new ArrayList<>();

      try {
         journal.appendOverallTransition(DeleteTransactionJournal.OverallState.CLEANUP_PENDING);
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
      DeleteTransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics, ChangeExecutionDiagnostic primaryError
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

   private List<String> plannedFilePaths() {
      List<String> paths = new ArrayList<>(this.filePayloads.size());

      for (DeleteFilePayload p : this.filePayloads) {
         paths.add(p.relativePath());
      }

      return paths;
   }

   private List<FileDeletion> plannedDeletions() {
      List<FileDeletion> deletions = new ArrayList<>(this.filePayloads.size());

      for (DeleteFilePayload p : this.filePayloads) {
         deletions.add(p.deletion());
      }

      return deletions;
   }

   private ChangeExecutionDiagnostic appendOverall(
      DeleteTransactionJournal journal, DeleteTransactionJournal.OverallState state, String label, List<ChangeExecutionDiagnostic> diagnostics
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

   private sealed interface CommitResult permits ChangeDeleteTransaction.CommitResult.Complete, ChangeDeleteTransaction.CommitResult.Failure {
      record Complete() implements ChangeDeleteTransaction.CommitResult {
      }

      record Failure(ChangeExecutionDiagnostic error) implements ChangeDeleteTransaction.CommitResult {
         public Failure {
            Objects.requireNonNull(error, "error");
         }
      }
   }

   interface DeleteLinkIo {
      void createLink(Path var1, Path var2) throws IOException;
   }

   private sealed interface PublishResult permits ChangeDeleteTransaction.PublishResult.Complete, ChangeDeleteTransaction.PublishResult.Failure {
      record Complete() implements ChangeDeleteTransaction.PublishResult {
      }

      record Failure(ChangeExecutionDiagnostic error) implements ChangeDeleteTransaction.PublishResult {
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

   private sealed interface RollbackResult permits ChangeDeleteTransaction.RollbackResult.Complete, ChangeDeleteTransaction.RollbackResult.Incomplete {
      record Complete() implements ChangeDeleteTransaction.RollbackResult {
      }

      record Incomplete(ChangeExecutionDiagnostic error) implements ChangeDeleteTransaction.RollbackResult {
         public Incomplete {
            Objects.requireNonNull(error, "error");
         }
      }
   }
}
