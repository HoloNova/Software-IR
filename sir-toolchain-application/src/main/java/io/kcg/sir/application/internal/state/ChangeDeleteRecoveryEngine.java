package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.api.ChangeBaselineReceipt;
import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeExecutionStage;
import io.kcg.sir.application.api.ChangeRecoveryResult;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.api.RecoveryHandle;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.bundle.BaselineBundle;
import io.kcg.sir.application.internal.bundle.BaselineBundleStore;
import io.kcg.sir.application.internal.bundle.BaselineDescriptor;
import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import io.kcg.sir.application.internal.bundle.OutputManifestVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public final class ChangeDeleteRecoveryEngine {
   private final BaselineBundleStore store;
   private final Path outputRoot;
   private final RecoveryHandle handle;
   private static final ChangeDeleteRecoveryEngine.CurrentNewIo DEFAULT_CURRENT_NEW_IO = new ChangeDeleteRecoveryEngine.CurrentNewIo() {
      @Override
      public BasicFileAttributes readAttributes(Path path) throws IOException {
         return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      }

      @Override
      public byte[] readAllBytes(Path path) throws IOException {
         return Files.readAllBytes(path);
      }

      @Override
      public void delete(Path path) throws IOException {
         Files.delete(path);
      }
   };
   static volatile ChangeDeleteRecoveryEngine.CurrentNewIo currentNewIoForTests = null;

   public ChangeDeleteRecoveryEngine(BaselineBundleStore store, Path outputRoot, RecoveryHandle handle) {
      this.store = Objects.requireNonNull(store, "store");
      this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
      this.handle = Objects.requireNonNull(handle, "handle");
   }

   /**
    * [RQ-11] Direction is decided ONLY by CURRENT against the journal's B0/B1
    * ids, never by inspecting files: CURRENT=B0 means the transaction never
    * published, so backward compensation is legal; CURRENT=B1 means the delete
    * was published, so only forward verification/cleanup is legal and deleted
    * files must NOT be restored. A CURRENT matching neither id is a structured
    * rejection. Verified by RecoveryStateMachineTest direction cases.
    */
   public ChangeRecoveryResult execute(DeleteTransactionJournal.Snapshot snapshot, Optional<String> currentId) {
      Objects.requireNonNull(snapshot, "snapshot");
      List<ChangeExecutionDiagnostic> diagnostics = new ArrayList<>();
      if (currentId.isEmpty()) {
         return ChangeRecoveryResult.recoveryRequired(
            RecoveryHandle.of(snapshot.transactionId()),
            Optional.empty(),
            withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-002", "CURRENT is absent but DELETE transaction exists: " + snapshot.transactionId())
         );
      } else {
         String current = currentId.get();
         boolean currentIsB0 = current.equals(snapshot.b0BaselineId());
         boolean currentIsB1 = current.equals(snapshot.b1BaselineId());
         if (!currentIsB0 && !currentIsB1) {
            return ChangeRecoveryResult.recoveryRequired(
               RecoveryHandle.of(snapshot.transactionId()),
               this.loadReceiptSafe(currentId),
               withError(
                  diagnostics,
                  "SIR-APP-CHANGE-RECOVERY-002",
                  "CURRENT=" + current + " matches neither B0=" + snapshot.b0BaselineId() + " nor B1=" + snapshot.b1BaselineId()
               )
            );
         } else {
            return currentIsB0 ? this.backwardRecovery(snapshot, diagnostics) : this.forwardRecovery(snapshot, diagnostics);
         }
      }
   }

   private ChangeRecoveryResult backwardRecovery(DeleteTransactionJournal.Snapshot snapshot, List<ChangeExecutionDiagnostic> diagnostics) {
      BaselineBundleStore.LoadResult b0Load = this.store.loadBundle(snapshot.b0BaselineId());
      if (b0Load instanceof BaselineBundleStore.LoadResult.Failure f) {
         return ChangeRecoveryResult.recoveryRequired(
            RecoveryHandle.of(snapshot.transactionId()),
            Optional.empty(),
            withError(diagnostics, f.code(), "cannot load B0 for DELETE backward recovery: " + f.message())
         );
      } else {
         BaselineBundle b0Bundle = ((BaselineBundleStore.LoadResult.Success)b0Load).bundle();
         Map<String, BaselineManifestEntry> b0ManifestMap = manifestToMap(b0Bundle.descriptor().manifest());
         Path txDir = this.store.stateRoot().resolve("transactions").resolve(snapshot.transactionId());
         Path backupDir = txDir.resolve("backup");

         SafeTargetResolver outputResolver;
         try {
            outputResolver = SafeTargetResolver.forRoot(this.outputRoot);
         } catch (SafeTargetResolver.UnsafePathException e) {
            return ChangeRecoveryResult.recoveryRequired(
               RecoveryHandle.of(snapshot.transactionId()),
               Optional.of(toReceipt(b0Bundle)),
               withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "unsafe outputRoot for DELETE recovery: " + e.getMessage())
            );
         }

         DeleteTransactionJournal journal = DeleteTransactionJournal.forRecovery(snapshot);
         DeleteTransactionJournal.OverallState originalOverall = snapshot.overallState();
         if (originalOverall != DeleteTransactionJournal.OverallState.ROLLING_BACK && originalOverall != DeleteTransactionJournal.OverallState.ROLLED_BACK) {
            try {
               journal.appendOverallTransition(DeleteTransactionJournal.OverallState.ROLLING_BACK);
            } catch (IOException e) {
               return ChangeRecoveryResult.recoveryRequired(
                  RecoveryHandle.of(snapshot.transactionId()),
                  Optional.of(toReceipt(b0Bundle)),
                  withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "journal ROLLING_BACK force failed: " + e.getMessage())
               );
            }
         }

         SafeTargetResolver backupResolver = null;
         List<String> planned = snapshot.plannedFiles();

         for (int i = planned.size() - 1; i >= 0; i--) {
            String rel = planned.get(i);
            DeleteTransactionJournal.FileTransitionState state = snapshot.fileStates().get(rel);
            if (state != null
               && state != DeleteTransactionJournal.FileTransitionState.PREPARING
               && state != DeleteTransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE) {
               if (backupResolver == null) {
                  try {
                     backupResolver = SafeTargetResolver.forRoot(backupDir);
                  } catch (SafeTargetResolver.UnsafePathException e) {
                     if (state != DeleteTransactionJournal.FileTransitionState.BACKUP_LINK_INTENT_DURABLE) {
                        return ChangeRecoveryResult.recoveryRequired(
                           RecoveryHandle.of(snapshot.transactionId()),
                           Optional.of(toReceipt(b0Bundle)),
                           withError(
                              diagnostics,
                              "SIR-APP-CHANGE-RECOVERY-001",
                              "unsafe/absent backup directory for DELETE recovery of " + rel + ": " + e.getMessage(),
                              rel
                           )
                        );
                     }
                  }
               }

               ChangeExecutionDiagnostic fileError = this.recoverDeleteFile(rel, state, outputResolver, backupResolver, b0ManifestMap, journal, i, diagnostics);
               if (fileError != null) {
                  return ChangeRecoveryResult.recoveryRequired(
                     RecoveryHandle.of(snapshot.transactionId()),
                     Optional.of(toReceipt(b0Bundle)),
                     withError(diagnostics, fileError.code(), fileError.message(), rel)
                  );
               }
            }
         }

         List<ChangeExecutionDiagnostic> manifestErrors = OutputManifestVerifier.verify(
            this.outputRoot, b0Bundle.descriptor().manifest(), ChangeExecutionStage.RECOVERY
         );
         if (!manifestErrors.isEmpty()) {
            return ChangeRecoveryResult.recoveryRequired(
               RecoveryHandle.of(snapshot.transactionId()), Optional.of(toReceipt(b0Bundle)), concat(diagnostics, manifestErrors)
            );
         }

         if (originalOverall != DeleteTransactionJournal.OverallState.ROLLED_BACK) {
            try {
               journal.appendOverallTransition(DeleteTransactionJournal.OverallState.ROLLED_BACK);
            } catch (IOException e) {
               return ChangeRecoveryResult.recoveryRequired(
                  RecoveryHandle.of(snapshot.transactionId()),
                  Optional.of(toReceipt(b0Bundle)),
                  withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "journal ROLLED_BACK force failed: " + e.getMessage())
               );
            }
         }

         if (this.cleanupCurrentNewIfPresent(snapshot.b1BaselineId()) instanceof ChangeDeleteRecoveryEngine.CurrentNewCleanupResult.Failure f) {
            return ChangeRecoveryResult.recoveryRequired(
               RecoveryHandle.of(snapshot.transactionId()), Optional.of(toReceipt(b0Bundle)), withError(diagnostics, f.code(), f.message())
            );
         } else {
            List<ChangeExecutionDiagnostic> cleanupWarnings = this.cleanupTransactionDir(txDir);
            diagnostics.addAll(cleanupWarnings);
            return ChangeRecoveryResult.rolledBack(toReceipt(b0Bundle), List.copyOf(diagnostics));
         }
      }
   }

   private ChangeExecutionDiagnostic recoverDeleteFile(
      String rel,
      DeleteTransactionJournal.FileTransitionState state,
      SafeTargetResolver outputResolver,
      SafeTargetResolver backupResolver,
      Map<String, BaselineManifestEntry> b0ManifestMap,
      DeleteTransactionJournal journal,
      int index,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      BaselineManifestEntry b0Entry = b0ManifestMap.get(rel);
      if (b0Entry == null) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", rel + " not found in B0 manifest during DELETE recovery", rel);
      }

      Path target;
      try {
         target = outputResolver.resolveNoCreate(rel);
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve target for DELETE recovery of " + rel + ": " + e.getMessage(), rel);
      }

      BasicFileAttributes targetAttrs;
      try {
         targetAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         targetAttrs = null;
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read target during DELETE recovery of " + rel + ": " + e.getMessage(), rel);
      }

      if (state == DeleteTransactionJournal.FileTransitionState.BACKUP_LINK_INTENT_DURABLE) {
         return this.rollbackBackupLinkIntentRecovery(rel, target, targetAttrs, backupResolver, b0Entry, journal, index);
      } else if (state == DeleteTransactionJournal.FileTransitionState.BACKUP_LINKED_DURABLE) {
         return this.rollbackBackupLinkedRecovery(rel, target, targetAttrs, backupResolver, b0Entry, journal, index);
      } else if (state == DeleteTransactionJournal.FileTransitionState.DELETE_INTENT_DURABLE) {
         return this.rollbackDeleteIntentRecovery(rel, target, targetAttrs, backupResolver, b0Entry, journal, index);
      } else if (state == DeleteTransactionJournal.FileTransitionState.DELETED_DURABLE) {
         return this.rollbackDeletedRecovery(rel, target, targetAttrs, backupResolver, b0Entry, journal, index);
      } else if (state == DeleteTransactionJournal.FileTransitionState.RESTORE_LINK_INTENT_DURABLE) {
         return this.rollbackRestoreLinkIntentRecovery(rel, target, targetAttrs, backupResolver, b0Entry, journal, index);
      } else {
         return state == DeleteTransactionJournal.FileTransitionState.RESTORED_DURABLE
            ? this.rollbackRestoredRecovery(rel, target, targetAttrs, backupResolver, b0Entry, journal, index)
            : diag("SIR-APP-CHANGE-RECOVERY-001", "unexpected file state " + state + " for " + rel + " during DELETE backward recovery", rel);
      }
   }

   private ChangeExecutionDiagnostic rollbackBackupLinkIntentRecovery(
      String rel,
      Path target,
      BasicFileAttributes targetAttrs,
      SafeTargetResolver backupResolver,
      BaselineManifestEntry b0Entry,
      DeleteTransactionJournal journal,
      int index
   ) {
      if (targetAttrs == null) {
         return diag(
            "SIR-APP-CHANGE-RECOVERY-001", "target absent during BACKUP_LINK_INTENT recovery of " + rel + " — external mutation must not be inferred", rel
         );
      }

      if (!targetAttrs.isSymbolicLink() && targetAttrs.isRegularFile()) {
         ChangeExecutionDiagnostic targetProof = this.verifyFileMatchesB0(target, targetAttrs, b0Entry, rel);
         if (targetProof != null) {
            return targetProof;
         }

         if (backupResolver == null) {
            return this.forceRolledBackDurable(journal, index, rel);
         }

         Path backup;
         try {
            backup = backupResolver.resolveNoCreate(DeleteTransactionJournal.backupFileName(index));
         } catch (SafeTargetResolver.UnsafePathException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve backup path for " + rel + ": " + e.getMessage(), rel);
         }

         BasicFileAttributes backupAttrs;
         try {
            backupAttrs = Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         } catch (NoSuchFileException e) {
            return this.forceRolledBackDurable(journal, index, rel);
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read backup during BACKUP_LINK_INTENT recovery of " + rel + ": " + e.getMessage(), rel);
         }

         ChangeExecutionDiagnostic proofError = this.proveBackupLinkage(target, targetAttrs, backup, backupAttrs, b0Entry, rel);
         if (proofError != null) {
            return proofError;
         }

         try {
            Files.delete(backup);
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to delete backup during BACKUP_LINK_INTENT recovery of " + rel + ": " + e.getMessage(), rel);
         }

         ChangeExecutionDiagnostic backupAbsent = this.proveBackupAbsent(backup, rel);
         return backupAbsent != null ? backupAbsent : this.forceRolledBackDurable(journal, index, rel);
      } else {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "target is not regular file during BACKUP_LINK_INTENT recovery of " + rel, rel);
      }
   }

   private ChangeExecutionDiagnostic rollbackBackupLinkedRecovery(
      String rel,
      Path target,
      BasicFileAttributes targetAttrs,
      SafeTargetResolver backupResolver,
      BaselineManifestEntry b0Entry,
      DeleteTransactionJournal journal,
      int index
   ) {
      if (targetAttrs == null) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "target absent during BACKUP_LINKED recovery of " + rel + " — external mutation must not be inferred", rel);
      }

      if (!targetAttrs.isSymbolicLink() && targetAttrs.isRegularFile()) {
         ChangeExecutionDiagnostic targetProof = this.verifyFileMatchesB0(target, targetAttrs, b0Entry, rel);
         if (targetProof != null) {
            return targetProof;
         }

         if (backupResolver == null) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "backup directory absent during BACKUP_LINKED recovery of " + rel, rel);
         }

         Path backup;
         try {
            backup = backupResolver.resolveNoCreate(DeleteTransactionJournal.backupFileName(index));
         } catch (SafeTargetResolver.UnsafePathException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve backup path for " + rel + ": " + e.getMessage(), rel);
         }

         BasicFileAttributes backupAttrs;
         try {
            backupAttrs = Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         } catch (NoSuchFileException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "backup absent during BACKUP_LINKED recovery of " + rel, rel);
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read backup during BACKUP_LINKED recovery of " + rel + ": " + e.getMessage(), rel);
         }

         ChangeExecutionDiagnostic proofError = this.proveBackupLinkage(target, targetAttrs, backup, backupAttrs, b0Entry, rel);
         if (proofError != null) {
            return proofError;
         }

         try {
            Files.delete(backup);
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to delete backup during BACKUP_LINKED recovery of " + rel + ": " + e.getMessage(), rel);
         }

         ChangeExecutionDiagnostic backupAbsent = this.proveBackupAbsent(backup, rel);
         return backupAbsent != null ? backupAbsent : this.forceRolledBackDurable(journal, index, rel);
      } else {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "target is not regular file during BACKUP_LINKED recovery of " + rel, rel);
      }
   }

   private ChangeExecutionDiagnostic rollbackDeleteIntentRecovery(
      String rel,
      Path target,
      BasicFileAttributes targetAttrs,
      SafeTargetResolver backupResolver,
      BaselineManifestEntry b0Entry,
      DeleteTransactionJournal journal,
      int index
   ) {
      if (targetAttrs != null) {
         if (!targetAttrs.isSymbolicLink() && targetAttrs.isRegularFile()) {
            ChangeExecutionDiagnostic targetProof = this.verifyFileMatchesB0(target, targetAttrs, b0Entry, rel);
            if (targetProof != null) {
               return targetProof;
            }

            if (backupResolver == null) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "backup directory absent during DELETE_INTENT recovery of " + rel, rel);
            }

            Path backup;
            try {
               backup = backupResolver.resolveNoCreate(DeleteTransactionJournal.backupFileName(index));
            } catch (SafeTargetResolver.UnsafePathException e) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve backup path for " + rel + ": " + e.getMessage(), rel);
            }

            BasicFileAttributes backupAttrs;
            try {
               backupAttrs = Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "backup absent during DELETE_INTENT recovery of " + rel + " — cannot prove same-file linkage", rel);
            } catch (IOException | SecurityException e) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read backup during DELETE_INTENT recovery of " + rel + ": " + e.getMessage(), rel);
            }

            ChangeExecutionDiagnostic proofError = this.proveBackupLinkage(target, targetAttrs, backup, backupAttrs, b0Entry, rel);
            if (proofError != null) {
               return proofError;
            }

            try {
               Files.delete(backup);
            } catch (IOException | SecurityException e) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to delete backup during DELETE_INTENT recovery of " + rel + ": " + e.getMessage(), rel);
            }

            ChangeExecutionDiagnostic backupAbsent = this.proveBackupAbsent(backup, rel);
            return backupAbsent != null ? backupAbsent : this.forceRolledBackDurable(journal, index, rel);
         } else {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "target is not regular file during DELETE_INTENT recovery of " + rel, rel);
         }
      } else {
         return this.restoreTargetFromBackup(rel, target, backupResolver, b0Entry, journal, index);
      }
   }

   private ChangeExecutionDiagnostic rollbackDeletedRecovery(
      String rel,
      Path target,
      BasicFileAttributes targetAttrs,
      SafeTargetResolver backupResolver,
      BaselineManifestEntry b0Entry,
      DeleteTransactionJournal journal,
      int index
   ) {
      return targetAttrs != null
         ? diag("SIR-APP-CHANGE-RECOVERY-001", "target exists during DELETED recovery of " + rel + " — external file must not be deleted or overwritten", rel)
         : this.restoreTargetFromBackup(rel, target, backupResolver, b0Entry, journal, index);
   }

   private ChangeExecutionDiagnostic restoreTargetFromBackup(
      String rel, Path target, SafeTargetResolver backupResolver, BaselineManifestEntry b0Entry, DeleteTransactionJournal journal, int index
   ) {
      return this.restoreTargetFromBackup(rel, target, backupResolver, b0Entry, journal, index, true);
   }

   private ChangeExecutionDiagnostic restoreTargetFromBackupSkipIntent(
      String rel, Path target, SafeTargetResolver backupResolver, BaselineManifestEntry b0Entry, DeleteTransactionJournal journal, int index
   ) {
      return this.restoreTargetFromBackup(rel, target, backupResolver, b0Entry, journal, index, false);
   }

   private ChangeExecutionDiagnostic restoreTargetFromBackup(
      String rel,
      Path target,
      SafeTargetResolver backupResolver,
      BaselineManifestEntry b0Entry,
      DeleteTransactionJournal journal,
      int index,
      boolean forceRestoreIntent
   ) {
      if (backupResolver == null) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "backup directory absent during DELETE restore of " + rel, rel);
      }

      Path backup;
      try {
         backup = backupResolver.resolveNoCreate(DeleteTransactionJournal.backupFileName(index));
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve backup path for " + rel + ": " + e.getMessage(), rel);
      }

      BasicFileAttributes backupAttrs;
      try {
         backupAttrs = Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "backup absent during DELETE restore of " + rel + " — cannot restore target", rel);
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read backup during DELETE restore of " + rel + ": " + e.getMessage(), rel);
      }

      if (!backupAttrs.isSymbolicLink() && backupAttrs.isRegularFile()) {
         ChangeExecutionDiagnostic backupProof = this.verifyFileMatchesB0(backup, backupAttrs, b0Entry, rel);
         if (backupProof != null) {
            return backupProof;
         }

         if (forceRestoreIntent) {
            try {
               journal.appendFileTransition(index, rel, DeleteTransactionJournal.FileTransitionState.RESTORE_LINK_INTENT_DURABLE);
            } catch (IOException e) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "journal RESTORE_LINK_INTENT force failed for " + rel + ": " + e.getMessage(), rel);
            }
         }

         try {
            Files.createLink(target, backup);
         } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "createLink(target, backup) failed during DELETE restore of " + rel + ": " + e.getMessage(), rel);
         }

         BasicFileAttributes restoredAttrs;
         try {
            restoredAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         } catch (NoSuchFileException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "target absent after createLink during DELETE restore of " + rel, rel);
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read target after createLink during DELETE restore of " + rel + ": " + e.getMessage(), rel);
         }

         ChangeExecutionDiagnostic proofError = this.proveBackupLinkage(target, restoredAttrs, backup, backupAttrs, b0Entry, rel);
         if (proofError != null) {
            return proofError;
         }

         try {
            journal.appendFileTransition(index, rel, DeleteTransactionJournal.FileTransitionState.RESTORED_DURABLE);
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "journal RESTORED force failed for " + rel + ": " + e.getMessage(), rel);
         }

         return this.forceRolledBackDurable(journal, index, rel);
      } else {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "backup is not regular file during DELETE restore of " + rel, rel);
      }
   }

   private ChangeExecutionDiagnostic rollbackRestoreLinkIntentRecovery(
      String rel,
      Path target,
      BasicFileAttributes targetAttrs,
      SafeTargetResolver backupResolver,
      BaselineManifestEntry b0Entry,
      DeleteTransactionJournal journal,
      int index
   ) {
      if (targetAttrs == null) {
         return this.restoreTargetFromBackupSkipIntent(rel, target, backupResolver, b0Entry, journal, index);
      }

      if (targetAttrs.isSymbolicLink() || !targetAttrs.isRegularFile()) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "target is not regular file during RESTORE_LINK_INTENT recovery of " + rel, rel);
      }

      if (backupResolver == null) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "backup directory absent during RESTORE_LINK_INTENT recovery of " + rel, rel);
      }

      Path backup;
      try {
         backup = backupResolver.resolveNoCreate(DeleteTransactionJournal.backupFileName(index));
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve backup path for " + rel + ": " + e.getMessage(), rel);
      }

      BasicFileAttributes backupAttrs;
      try {
         backupAttrs = Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "backup absent during RESTORE_LINK_INTENT recovery of " + rel, rel);
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read backup during RESTORE_LINK_INTENT recovery of " + rel + ": " + e.getMessage(), rel);
      }

      ChangeExecutionDiagnostic proofError = this.proveBackupLinkage(target, targetAttrs, backup, backupAttrs, b0Entry, rel);
      if (proofError != null) {
         return proofError;
      }

      try {
         journal.appendFileTransition(index, rel, DeleteTransactionJournal.FileTransitionState.RESTORED_DURABLE);
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "journal RESTORED force failed for " + rel + ": " + e.getMessage(), rel);
      }

      return this.forceRolledBackDurable(journal, index, rel);
   }

   private ChangeExecutionDiagnostic rollbackRestoredRecovery(
      String rel,
      Path target,
      BasicFileAttributes targetAttrs,
      SafeTargetResolver backupResolver,
      BaselineManifestEntry b0Entry,
      DeleteTransactionJournal journal,
      int index
   ) {
      if (targetAttrs == null) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "target absent during RESTORED recovery of " + rel + " — external mutation must not be inferred", rel);
      }

      if (!targetAttrs.isSymbolicLink() && targetAttrs.isRegularFile()) {
         ChangeExecutionDiagnostic targetProof = this.verifyFileMatchesB0(target, targetAttrs, b0Entry, rel);
         if (targetProof != null) {
            return targetProof;
         }

         if (backupResolver != null) {
            Path backup;
            try {
               backup = backupResolver.resolveNoCreate(DeleteTransactionJournal.backupFileName(index));
            } catch (SafeTargetResolver.UnsafePathException e) {
               return this.forceRolledBackDurable(journal, index, rel);
            }

            BasicFileAttributes backupAttrs;
            try {
               backupAttrs = Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
               return this.forceRolledBackDurable(journal, index, rel);
            } catch (IOException | SecurityException e) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read backup during RESTORED recovery of " + rel + ": " + e.getMessage(), rel);
            }

            ChangeExecutionDiagnostic proofError = this.proveBackupLinkage(target, targetAttrs, backup, backupAttrs, b0Entry, rel);
            if (proofError != null) {
               return proofError;
            }

            try {
               Files.delete(backup);
            } catch (IOException | SecurityException e) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to delete backup during RESTORED recovery of " + rel + ": " + e.getMessage(), rel);
            }

            ChangeExecutionDiagnostic backupAbsent = this.proveBackupAbsent(backup, rel);
            if (backupAbsent != null) {
               return backupAbsent;
            }
         }

         return this.forceRolledBackDurable(journal, index, rel);
      } else {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "target is not regular file during RESTORED recovery of " + rel, rel);
      }
   }

   private ChangeExecutionDiagnostic forceRolledBackDurable(DeleteTransactionJournal journal, int index, String rel) {
      try {
         journal.appendFileTransition(index, rel, DeleteTransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE);
         return null;
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "journal ROLLED_BACK_DURABLE force failed for " + rel + ": " + e.getMessage(), rel);
      }
   }

   private ChangeExecutionDiagnostic proveBackupLinkage(
      Path target, BasicFileAttributes targetAttrs, Path backup, BasicFileAttributes backupAttrs, BaselineManifestEntry b0Entry, String rel
   ) {
      if (targetAttrs.isSymbolicLink() || !targetAttrs.isRegularFile()) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "target is not regular file during backup proof of " + rel, rel);
      }

      if (!backupAttrs.isSymbolicLink() && backupAttrs.isRegularFile()) {
         try {
            if (!Files.isSameFile(target, backup)) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "isSameFile(target, backup) returned false for " + rel, rel);
            }
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "isSameFile check failed during backup proof of " + rel + ": " + e.getMessage(), rel);
         }

         ChangeExecutionDiagnostic targetProof = this.verifyFileMatchesB0(target, targetAttrs, b0Entry, rel);
         if (targetProof != null) {
            return targetProof;
         }

         ChangeExecutionDiagnostic backupProof = this.verifyFileMatchesB0(backup, backupAttrs, b0Entry, rel);
         return backupProof != null ? backupProof : null;
      } else {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "backup is not regular file during backup proof of " + rel, rel);
      }
   }

   private ChangeExecutionDiagnostic verifyFileMatchesB0(Path file, BasicFileAttributes attrs, BaselineManifestEntry b0Entry, String rel) {
      if (attrs.size() != b0Entry.byteCount()) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "byte count mismatch for " + rel + ": expected B0=" + b0Entry.byteCount() + " actual=" + attrs.size(), rel);
      }

      try {
         byte[] bytes = Files.readAllBytes(file);
         String sha = Sha256.hexDigest(bytes);
         return !sha.equals(b0Entry.sha256Hex())
            ? diag("SIR-APP-CHANGE-RECOVERY-001", "SHA-256 mismatch for " + rel + " — external file must not be deleted", rel)
            : null;
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read bytes of " + rel + ": " + e.getMessage(), rel);
      }
   }

   private ChangeExecutionDiagnostic proveBackupAbsent(Path backup, String rel) {
      try {
         Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         return diag("SIR-APP-CHANGE-RECOVERY-001", "backup exists for absence proof of " + rel + " — concurrent mutation", rel);
      } catch (NoSuchFileException e) {
         return null;
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to prove backup absence for " + rel + ": " + e.getMessage(), rel);
      }
   }

   private ChangeRecoveryResult forwardRecovery(DeleteTransactionJournal.Snapshot snapshot, List<ChangeExecutionDiagnostic> diagnostics) {
      BaselineBundleStore.LoadResult b1Load = this.store.loadBundle(snapshot.b1BaselineId());
      if (b1Load instanceof BaselineBundleStore.LoadResult.Failure f) {
         return ChangeRecoveryResult.recoveryRequired(
            RecoveryHandle.of(snapshot.transactionId()),
            Optional.empty(),
            withError(diagnostics, f.code(), "cannot load B1 for DELETE forward recovery: " + f.message())
         );
      } else {
         BaselineBundle b1Bundle = ((BaselineBundleStore.LoadResult.Success)b1Load).bundle();

         SafeTargetResolver outputResolver;
         try {
            outputResolver = SafeTargetResolver.forRoot(this.outputRoot);
         } catch (SafeTargetResolver.UnsafePathException e) {
            return ChangeRecoveryResult.recoveryRequired(
               RecoveryHandle.of(snapshot.transactionId()),
               Optional.of(toReceipt(b1Bundle)),
               withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "unsafe outputRoot for DELETE forward recovery: " + e.getMessage())
            );
         }

         for (String rel : snapshot.plannedFiles()) {
            ChangeExecutionDiagnostic absentError = this.proveTargetAbsent(rel, outputResolver);
            if (absentError != null) {
               return ChangeRecoveryResult.recoveryRequired(
                  RecoveryHandle.of(snapshot.transactionId()),
                  Optional.of(toReceipt(b1Bundle)),
                  withError(diagnostics, absentError.code(), absentError.message(), rel)
               );
            }
         }

         List<ChangeExecutionDiagnostic> manifestErrors = OutputManifestVerifier.verify(
            this.outputRoot, b1Bundle.descriptor().manifest(), ChangeExecutionStage.RECOVERY
         );
         if (!manifestErrors.isEmpty()) {
            return ChangeRecoveryResult.recoveryRequired(
               RecoveryHandle.of(snapshot.transactionId()), Optional.of(toReceipt(b1Bundle)), concat(diagnostics, manifestErrors)
            );
         }

         Path txDir = this.store.stateRoot().resolve("transactions").resolve(snapshot.transactionId());
         List<ChangeExecutionDiagnostic> cleanupWarnings = this.cleanupTransactionDir(txDir);
         diagnostics.addAll(cleanupWarnings);
         return ChangeRecoveryResult.recovered(toReceipt(b1Bundle), List.copyOf(diagnostics));
      }
   }

   private ChangeExecutionDiagnostic proveTargetAbsent(String rel, SafeTargetResolver outputResolver) {
      Path target;
      try {
         target = outputResolver.resolveNoCreate(rel);
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve target for absence proof of " + rel + ": " + e.getMessage(), rel);
      }

      try {
         Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         return diag("SIR-APP-CHANGE-RECOVERY-001", "target exists during absence proof of " + rel + " — external file must not be deleted", rel);
      } catch (NoSuchFileException e) {
         return null;
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to prove target absence during recovery of " + rel, rel);
      }
   }

   private ChangeDeleteRecoveryEngine.CurrentNewCleanupResult cleanupCurrentNewIfPresent(String b1BaselineId) {
      Path currentNew = this.store.stateRoot().resolve("CURRENT.new");
      ChangeDeleteRecoveryEngine.CurrentNewIo io = currentNewIoForTests != null ? currentNewIoForTests : DEFAULT_CURRENT_NEW_IO;

      BasicFileAttributes attrs;
      try {
         attrs = io.readAttributes(currentNew);
      } catch (NoSuchFileException e) {
         return new ChangeDeleteRecoveryEngine.CurrentNewCleanupResult.Ok();
      } catch (IOException | SecurityException e) {
         return new ChangeDeleteRecoveryEngine.CurrentNewCleanupResult.Failure(
            "SIR-APP-CHANGE-RECOVERY-002", "failed to read CURRENT.new attributes: " + e.getMessage()
         );
      }

      if (attrs.isSymbolicLink()) {
         return new ChangeDeleteRecoveryEngine.CurrentNewCleanupResult.Failure(
            "SIR-APP-CHANGE-RECOVERY-002", "CURRENT.new is a symlink — cannot safely interpret"
         );
      }

      if (!attrs.isRegularFile()) {
         return new ChangeDeleteRecoveryEngine.CurrentNewCleanupResult.Failure(
            "SIR-APP-CHANGE-RECOVERY-002", "CURRENT.new is not a regular file — cannot safely interpret"
         );
      }

      byte[] bytes;
      try {
         bytes = io.readAllBytes(currentNew);
      } catch (IOException | SecurityException e) {
         return new ChangeDeleteRecoveryEngine.CurrentNewCleanupResult.Failure(
            "SIR-APP-CHANGE-RECOVERY-002", "failed to read CURRENT.new content: " + e.getMessage()
         );
      }

      byte[] expected = (b1BaselineId + "\n").getBytes(StandardCharsets.US_ASCII);
      if (!Arrays.equals(bytes, expected)) {
         return new ChangeDeleteRecoveryEngine.CurrentNewCleanupResult.Failure(
            "SIR-APP-CHANGE-RECOVERY-002", "CURRENT.new content does not exactly match B1 baselineId — cannot safely interpret"
         );
      }

      try {
         io.delete(currentNew);
      } catch (IOException | SecurityException e) {
         return new ChangeDeleteRecoveryEngine.CurrentNewCleanupResult.Failure("SIR-APP-CHANGE-RECOVERY-002", "failed to delete CURRENT.new: " + e.getMessage());
      }

      return new ChangeDeleteRecoveryEngine.CurrentNewCleanupResult.Ok();
   }

   private BaselineBundleStore.LoadResult loadB1WithFallback(String b1BaselineId, Path txDir) {
      BaselineBundleStore.LoadResult b1Load = this.store.loadBundle(b1BaselineId);
      if (b1Load instanceof BaselineBundleStore.LoadResult.Failure) {
         Path candidateDir = txDir.resolve("candidate-baseline");
         b1Load = this.store.loadBundleFromDir(candidateDir, b1BaselineId);
      }

      return b1Load;
   }

   private static Map<String, BaselineManifestEntry> manifestToMap(List<BaselineManifestEntry> manifest) {
      Map<String, BaselineManifestEntry> map = new LinkedHashMap<>();

      for (BaselineManifestEntry entry : manifest) {
         map.put(entry.relativePath(), entry);
      }

      return Collections.unmodifiableMap(map);
   }

   private List<ChangeExecutionDiagnostic> cleanupTransactionDir(Path txDir) {
      List<ChangeExecutionDiagnostic> warnings = new ArrayList<>();

      try {
         deleteRecursively(txDir);
      } catch (IOException e) {
         warnings.add(diag("SIR-APP-CHANGE-CLEANUP-001", "cleanup of DELETE transaction directory failed: " + e.getMessage()));
      }

      return warnings;
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

   private Optional<ChangeBaselineReceipt> loadReceiptSafe(Optional<String> baselineId) {
      if (baselineId.isEmpty()) {
         return Optional.empty();
      } else {
         return this.store.loadBundle(baselineId.get()) instanceof BaselineBundleStore.LoadResult.Success s
            ? Optional.of(toReceipt(s.bundle()))
            : Optional.empty();
      }
   }

   private static ChangeBaselineReceipt toReceipt(BaselineBundle bundle) {
      BaselineDescriptor d = bundle.descriptor();
      return new ChangeBaselineReceipt(
         d.formatVersion(), bundle.baselineId(), d.toBaseRevision(), d.boundOutputRoot(), d.targetId(), d.loweredIrVersion(), d.manifestDigest()
      );
   }

   private static List<ChangeExecutionDiagnostic> withError(List<ChangeExecutionDiagnostic> diagnostics, String code, String message) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>(diagnostics);
      out.add(diag(code, message));
      return List.copyOf(out);
   }

   private static List<ChangeExecutionDiagnostic> withError(List<ChangeExecutionDiagnostic> diagnostics, String code, String message, String rel) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>(diagnostics);
      out.add(new ChangeExecutionDiagnostic(code, ChangeExecutionStage.RECOVERY, ExecutionSeverity.ERROR, message, Optional.ofNullable(rel)));
      return List.copyOf(out);
   }

   private static List<ChangeExecutionDiagnostic> concat(List<ChangeExecutionDiagnostic> a, List<ChangeExecutionDiagnostic> b) {
      List<ChangeExecutionDiagnostic> out = new ArrayList<>(a);
      out.addAll(b);
      return List.copyOf(out);
   }

   private static ChangeExecutionDiagnostic diag(String code, String message) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.RECOVERY, ExecutionSeverity.ERROR, message, Optional.empty());
   }

   private static ChangeExecutionDiagnostic diag(String code, String message, String rel) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.RECOVERY, ExecutionSeverity.ERROR, message, Optional.of(rel));
   }

   private sealed interface CurrentNewCleanupResult
      permits ChangeDeleteRecoveryEngine.CurrentNewCleanupResult.Ok,
      ChangeDeleteRecoveryEngine.CurrentNewCleanupResult.Failure {
      record Failure(String code, String message) implements ChangeDeleteRecoveryEngine.CurrentNewCleanupResult {
         public Failure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
         }
      }

      record Ok() implements ChangeDeleteRecoveryEngine.CurrentNewCleanupResult {
      }
   }

   interface CurrentNewIo {
      BasicFileAttributes readAttributes(Path var1) throws IOException;

      byte[] readAllBytes(Path var1) throws IOException;

      void delete(Path var1) throws IOException;
   }
}
