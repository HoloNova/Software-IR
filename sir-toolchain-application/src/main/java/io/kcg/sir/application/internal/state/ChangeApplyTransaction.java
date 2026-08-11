package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.api.ChangeApplyDisposition;
import io.kcg.sir.application.api.ChangeApplyOutcome;
import io.kcg.sir.application.api.ChangeApplyResult;
import io.kcg.sir.application.api.ChangeBaselineReceipt;
import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeExecutionStage;
import io.kcg.sir.application.api.ChangeOutputManifest;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.api.RecoveryHandle;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.bundle.BaselineBundle;
import io.kcg.sir.application.internal.bundle.BaselineBundleStore;
import io.kcg.sir.application.internal.bundle.BaselineDescriptor;
import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import io.kcg.sir.application.internal.bundle.OutputManifestVerifier;
import io.kcg.sir.change.api.FileChange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.stream.Stream;

public final class ChangeApplyTransaction {
   private final BaselineBundleStore store;
   private final Path outputRoot;
   private final BaselineBundle b0Bundle;
   private final BaselineBundle b1Bundle;
   private final List<FileChange> fileChanges;
   private final Map<String, byte[]> candidateFileBytes;
   private final ChangeApplyOutcome outcome;
   private final ApplyHooks hooks;
   private final String transactionId;
   private final Path transactionDir;
   private final Path stagingDir;
   private final Path backupDir;
   private final Path candidateBaselineDir;
   private SafeTargetResolver outputResolver;
   private SafeTargetResolver stagingResolver;
   private SafeTargetResolver backupResolver;

   public ChangeApplyTransaction(
      BaselineBundleStore store,
      Path outputRoot,
      BaselineBundle b0Bundle,
      BaselineBundle b1Bundle,
      List<FileChange> fileChanges,
      Map<String, byte[]> candidateFileBytes,
      ChangeApplyOutcome outcome,
      ApplyHooks hooks
   ) {
      this.store = Objects.requireNonNull(store, "store");
      this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
      this.b0Bundle = Objects.requireNonNull(b0Bundle, "b0Bundle");
      this.b1Bundle = Objects.requireNonNull(b1Bundle, "b1Bundle");
      this.fileChanges = List.copyOf(Objects.requireNonNull(fileChanges, "fileChanges"));
      this.candidateFileBytes = Map.copyOf(Objects.requireNonNull(candidateFileBytes, "candidateFileBytes"));
      this.outcome = Objects.requireNonNull(outcome, "outcome");
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

      TransactionJournal journal = new TransactionJournal(
         this.transactionDir, this.transactionId, this.outputRoot, this.b0Bundle.baselineId(), this.b1Bundle.baselineId(), this.plannedFilePaths()
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
      } else if (this.commit(journal, diagnostics) instanceof ChangeApplyTransaction.CommitResult.Failure f) {
         ChangeApplyTransaction.RollbackResult rollbackResult = this.rollback(journal, diagnostics);
         ChangeApplyDisposition disposition = rollbackResult instanceof ChangeApplyTransaction.RollbackResult.Complete
            ? ChangeApplyDisposition.ROLLED_BACK
            : ChangeApplyDisposition.NO_CHANGES;
         if (disposition == ChangeApplyDisposition.ROLLED_BACK) {
            this.cleanupTransactionDirQuietly();
            return ChangeApplyResult.failure(ChangeExecutionStage.COMMIT, disposition, Optional.of(b0Receipt), withFallback(diagnostics, f.error()));
         } else {
            return ChangeApplyResult.recoveryRequired(
               ChangeExecutionStage.COMMIT, RecoveryHandle.of(this.transactionId), Optional.of(b0Receipt), withFallback(diagnostics, f.error())
            );
         }
      } else if (this.publish(journal, diagnostics) instanceof ChangeApplyTransaction.PublishResult.Failure pf) {
         return this.recoveryRequiredAfterPublish(journal, diagnostics, pf.error());
      } else {
         List<ChangeExecutionDiagnostic> cleanupWarnings = this.cleanup(journal);
         diagnostics.addAll(cleanupWarnings);
         ChangeOutputManifest outputManifest = buildOutputManifest(this.b1Bundle.descriptor().manifest());
         return ChangeApplyResult.applied(this.outcome, b1Receipt, outputManifest, List.copyOf(diagnostics));
      }
   }

   private static ChangeOutputManifest buildOutputManifest(List<BaselineManifestEntry> manifest) {
      List<ChangeOutputManifest.Entry> entries = new ArrayList<>(manifest.size());

      for (BaselineManifestEntry e : manifest) {
         entries.add(new ChangeOutputManifest.Entry(e.relativePath(), e.byteCount(), e.sha256Hex(), e.artifactId(), e.ownerSymbol()));
      }

      return new ChangeOutputManifest(entries);
   }

   private ChangeExecutionDiagnostic prepare(TransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics) {
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
         return diag("SIR-APP-CHANGE-COMMIT-001", "journal create failed: " + e.getMessage());
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-001", "fault after journal create: " + e.getMessage());
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
         this.backupResolver = SafeTargetResolver.forRoot(this.backupDir);
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-COMMIT-001", "unsafe staging/backup directory: " + e.getMessage());
      }

      if (this.outcome == ChangeApplyOutcome.FILES_AND_BASELINE) {
         try {
            this.writeStagedFiles();
            this.hooks.afterStagingWrite(this.stagingDir);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-001", "staged file write failed: " + e.getMessage());
         }
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
            journal.appendOverallTransition(TransactionJournal.OverallState.PREPARED);
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
      for (Entry<String, byte[]> entry : this.candidateFileBytes.entrySet()) {
         String rel = entry.getKey();

         Path staged;
         try {
            staged = this.stagingResolver.resolveForCreate(rel);
         } catch (SafeTargetResolver.UnsafePathException e) {
            throw new IOException("unsafe staging path for " + rel + ": " + e.getMessage());
         }

         Files.write(
            staged, entry.getValue(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.SYNC
         );
      }
   }

   private ChangeApplyTransaction.CommitResult commit(TransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics) {
      ChangeExecutionDiagnostic committingError = this.appendOverall(journal, TransactionJournal.OverallState.COMMITTING, "COMMITTING", diagnostics);
      if (committingError != null) {
         return new ChangeApplyTransaction.CommitResult.Failure(committingError);
      }

      List<ChangeExecutionDiagnostic> b0ManifestErrors = OutputManifestVerifier.verify(
         this.outputRoot, this.b0Bundle.descriptor().manifest(), ChangeExecutionStage.COMMIT
      );
      if (!b0ManifestErrors.isEmpty()) {
         diagnostics.addAll(b0ManifestErrors);
         return new ChangeApplyTransaction.CommitResult.Failure(b0ManifestErrors.get(0));
      }

      if (this.outcome == ChangeApplyOutcome.BASELINE_ONLY) {
         ChangeExecutionDiagnostic fcError = this.appendOverall(journal, TransactionJournal.OverallState.FILES_COMMITTED, "FILES_COMMITTED", diagnostics);
         return fcError != null ? new ChangeApplyTransaction.CommitResult.Failure(fcError) : new ChangeApplyTransaction.CommitResult.Complete();
      }

      for (int i = 0; i < this.fileChanges.size(); i++) {
         FileChange fc = this.fileChanges.get(i);
         String rel = fc.relativePath();

         Path target;
         Path staged;
         Path backup;
         try {
            target = this.outputResolver.resolveNoCreate(rel);
            staged = this.stagingResolver.resolveExistingFile(rel);
            backup = this.backupResolver.resolveForCreate(rel);
         } catch (SafeTargetResolver.UnsafePathException e) {
            return new ChangeApplyTransaction.CommitResult.Failure(
               diag("SIR-APP-CHANGE-COMMIT-002", "failed to resolve target/staging/backup path for " + rel + ": " + e.getMessage(), rel)
            );
         }

         ChangeExecutionDiagnostic fileError = this.commitFile(journal, i, fc, target, staged, backup, diagnostics);
         if (fileError != null) {
            return new ChangeApplyTransaction.CommitResult.Failure(fileError);
         }
      }

      try {
         this.hooks.afterAllCommits();
      } catch (Exception e) {
         return new ChangeApplyTransaction.CommitResult.Failure(diag("SIR-APP-CHANGE-COMMIT-003", "fault after all commits: " + e.getMessage()));
      }

      List<ChangeExecutionDiagnostic> b1ManifestErrors = OutputManifestVerifier.verify(
         this.outputRoot, this.b1Bundle.descriptor().manifest(), ChangeExecutionStage.COMMIT
      );
      if (!b1ManifestErrors.isEmpty()) {
         diagnostics.addAll(b1ManifestErrors);
         return new ChangeApplyTransaction.CommitResult.Failure(b1ManifestErrors.get(0));
      } else {
         ChangeExecutionDiagnostic fcError = this.appendOverall(journal, TransactionJournal.OverallState.FILES_COMMITTED, "FILES_COMMITTED", diagnostics);
         return fcError != null ? new ChangeApplyTransaction.CommitResult.Failure(fcError) : new ChangeApplyTransaction.CommitResult.Complete();
      }
   }

   private ChangeExecutionDiagnostic commitFile(
      TransactionJournal journal, int index, FileChange fc, Path target, Path staged, Path backup, List<ChangeExecutionDiagnostic> diagnostics
   ) {
      String rel = fc.relativePath();

      try {
         BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
            return diag("SIR-APP-CHANGE-COMMIT-002", "target is not a regular file before backup: " + rel, rel);
         }

         if (attrs.size() != fc.baseByteCount()) {
            return diag(
               "SIR-APP-CHANGE-COMMIT-004",
               "target byte count drift before backup: " + rel + " expected=" + fc.baseByteCount() + " actual=" + attrs.size(),
               rel
            );
         }

         byte[] currentBytes = Files.readAllBytes(target);
         String currentSha = Sha256.hexDigest(currentBytes);
         if (!currentSha.equals(fc.baseSha256Hex())) {
            return diag("SIR-APP-CHANGE-COMMIT-004", "target SHA-256 drift before backup: " + rel, rel);
         }
      } catch (NoSuchFileException e) {
         return diag("SIR-APP-CHANGE-COMMIT-003", "target missing before backup: " + rel, rel);
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-COMMIT-005", "failed to recheck target before backup: " + rel + ": " + e.getMessage(), rel);
      }

      try {
         this.hooks.beforeBackup(index, target);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-002", "fault before backup of " + rel + ": " + e.getMessage(), rel);
      }

      try {
         journal.appendFileTransition(index, rel, TransactionJournal.FileTransitionState.BACKUP_INTENT_DURABLE);
         this.hooks.afterJournalForce(journal, "file " + index + " BACKUP_INTENT_DURABLE");
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-COMMIT-002", "journal BACKUP_INTENT force failed for " + rel + ": " + e.getMessage(), rel);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-002", "fault after journal BACKUP_INTENT for " + rel + ": " + e.getMessage(), rel);
      }

      try {
         Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
         this.hooks.afterTargetMoveToBackup(index, backup);
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-COMMIT-002", "failed to move target to backup: " + rel + ": " + e.getMessage(), rel);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-002", "fault after target move to backup: " + rel + ": " + e.getMessage(), rel);
      }

      try {
         journal.appendFileTransition(index, rel, TransactionJournal.FileTransitionState.BACKED_UP_DURABLE);
         this.hooks.afterJournalForce(journal, "file " + index + " BACKED_UP_DURABLE");
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-COMMIT-002", "journal BACKED_UP force failed for " + rel + ": " + e.getMessage(), rel);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-002", "fault after journal BACKED_UP for " + rel + ": " + e.getMessage(), rel);
      }

      try {
         this.hooks.afterBackup(index, backup);
      } catch (Exception e) {
         return diag("SIR-APP-CHANGE-COMMIT-002", "fault after backup of " + rel + ": " + e.getMessage(), rel);
      }

      try {
         BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         return diag("SIR-APP-CHANGE-COMMIT-002", "target was concurrently recreated after backup: " + rel, rel);
      } catch (NoSuchFileException var20) {
         try {
            this.hooks.beforeCommitMove(index, staged, target);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-003", "fault before commit move of " + rel + ": " + e.getMessage(), rel);
         }

         try {
            journal.appendFileTransition(index, rel, TransactionJournal.FileTransitionState.COMMIT_INTENT_DURABLE);
            this.hooks.afterJournalForce(journal, "file " + index + " COMMIT_INTENT_DURABLE");
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-COMMIT-003", "journal COMMIT_INTENT force failed for " + rel + ": " + e.getMessage(), rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-003", "fault after journal COMMIT_INTENT for " + rel + ": " + e.getMessage(), rel);
         }

         try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            this.hooks.afterStagedMoveToTarget(index, target);
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-COMMIT-003", "failed to move staged to target: " + rel + ": " + e.getMessage(), rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-003", "fault after staged move to target: " + rel + ": " + e.getMessage(), rel);
         }

         try {
            journal.appendFileTransition(index, rel, TransactionJournal.FileTransitionState.COMMITTED_DURABLE);
            this.hooks.afterJournalForce(journal, "file " + index + " COMMITTED_DURABLE");
         } catch (IOException e) {
            return diag("SIR-APP-CHANGE-COMMIT-003", "journal COMMITTED force failed for " + rel + ": " + e.getMessage(), rel);
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-003", "fault after journal COMMITTED for " + rel + ": " + e.getMessage(), rel);
         }

         try {
            this.hooks.afterCommitMove(index, target);
            return null;
         } catch (Exception e) {
            return diag("SIR-APP-CHANGE-COMMIT-003", "fault after commit move of " + rel + ": " + e.getMessage(), rel);
         }
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-COMMIT-005", "failed to recheck target absence after backup: " + rel, rel);
      }
   }

   private ChangeApplyTransaction.PublishResult publish(TransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics) {
      ChangeExecutionDiagnostic publishingError = this.appendOverall(journal, TransactionJournal.OverallState.PUBLISHING, "PUBLISHING", diagnostics);
      if (publishingError != null) {
         return new ChangeApplyTransaction.PublishResult.Failure(publishingError);
      }

      try {
         this.store.publishCandidateBundle(this.candidateBaselineDir, this.b1Bundle.baselineId());
         this.hooks.afterBundlePublish();
      } catch (IOException e) {
         return new ChangeApplyTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "candidate Bundle publish failed: " + e.getMessage()));
      } catch (Exception e) {
         return new ChangeApplyTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "fault after candidate Bundle publish: " + e.getMessage()));
      }

      try {
         this.hooks.beforeCurrentNewWrite();
      } catch (Exception e) {
         return new ChangeApplyTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "fault before CURRENT.new write: " + e.getMessage()));
      }

      try {
         this.store.writeCurrentNew(this.b1Bundle.baselineId());
         this.hooks.afterCurrentNewWrite();
         this.store.moveCurrentNewToCurrent();
         this.hooks.afterCurrentAtomicMove();
      } catch (IOException e) {
         return new ChangeApplyTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "CURRENT publish failed: " + e.getMessage()));
      } catch (Exception e) {
         return new ChangeApplyTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-001", "fault during CURRENT publish: " + e.getMessage()));
      }

      ChangeExecutionDiagnostic publishedError = this.appendOverall(
         journal, TransactionJournal.OverallState.BASELINE_PUBLISHED, "BASELINE_PUBLISHED", diagnostics
      );
      if (publishedError != null) {
         return new ChangeApplyTransaction.PublishResult.Failure(publishedError);
      }

      try {
         this.hooks.afterPublishReread();
      } catch (Exception e) {
         return new ChangeApplyTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-002", "fault after publish reread: " + e.getMessage()));
      }

      BaselineBundleStore.CurrentReadResult currentRead = this.store.readCurrent();
      if (currentRead instanceof BaselineBundleStore.CurrentReadResult.Failure f) {
         return new ChangeApplyTransaction.PublishResult.Failure(diag(f.code(), "reread CURRENT failed: " + f.message()));
      } else if (currentRead instanceof BaselineBundleStore.CurrentReadResult.Absent) {
         return new ChangeApplyTransaction.PublishResult.Failure(diag("SIR-APP-CHANGE-PUBLISH-002", "reread CURRENT: file is absent after publish"));
      } else {
         String currentId = ((BaselineBundleStore.CurrentReadResult.Present)currentRead).baselineId();
         if (!currentId.equals(this.b1Bundle.baselineId())) {
            return new ChangeApplyTransaction.PublishResult.Failure(
               diag("SIR-APP-CHANGE-PUBLISH-002", "reread CURRENT mismatch: expected=" + this.b1Bundle.baselineId() + " actual=" + currentId)
            );
         } else {
            return this.store.loadBundle(this.b1Bundle.baselineId()) instanceof BaselineBundleStore.LoadResult.Failure lf
               ? new ChangeApplyTransaction.PublishResult.Failure(diag(lf.code(), "reread B1 Bundle failed: " + lf.message()))
               : new ChangeApplyTransaction.PublishResult.Complete();
         }
      }
   }

   private ChangeApplyTransaction.RollbackResult rollback(TransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics) {
      for (int i = this.fileChanges.size() - 1; i >= 0; i--) {
         FileChange fc = this.fileChanges.get(i);
         String rel = fc.relativePath();
         int index = i;

         Path target;
         Path backup;
         try {
            target = this.outputResolver.resolveNoCreate(rel);
            backup = this.backupResolver.resolveExistingFile(rel);
         } catch (SafeTargetResolver.UnsafePathException e) {
            return new ChangeApplyTransaction.RollbackResult.Incomplete(
               diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve target/backup path for rollback of " + rel + ": " + e.getMessage(), rel)
            );
         }

         try {
            this.hooks.beforeRollbackStep(i, "restore " + rel);
         } catch (Exception e) {
            return new ChangeApplyTransaction.RollbackResult.Incomplete(
               diag("SIR-APP-CHANGE-RECOVERY-001", "fault during rollback of " + rel + ": " + e.getMessage(), rel)
            );
         }

         TransactionJournal.FileTransitionState fileState;
         try {
            if (!(TransactionJournal.parse(journal.journalPath()) instanceof TransactionJournal.ParseOk ok)) {
               return new ChangeApplyTransaction.RollbackResult.Incomplete(
                  diag("SIR-APP-CHANGE-RECOVERY-001", "cannot re-read journal during rollback: " + rel, rel)
               );
            }

            fileState = ok.snapshot().fileStates().get(rel);
         } catch (Exception e) {
            return new ChangeApplyTransaction.RollbackResult.Incomplete(
               diag("SIR-APP-CHANGE-RECOVERY-001", "journal re-read failed during rollback of " + rel + ": " + e.getMessage(), rel)
            );
         }

         if (fileState != null
            && fileState != TransactionJournal.FileTransitionState.PREPARING
            && fileState != TransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE) {
            if (fileState == TransactionJournal.FileTransitionState.ROLLBACK_INTENT_DURABLE) {
               return new ChangeApplyTransaction.RollbackResult.Incomplete(
                  diag("SIR-APP-CHANGE-RECOVERY-001", "file " + rel + " is in ROLLBACK_INTENT_DURABLE state; recovery needed", rel)
               );
            }

            boolean isCommittedPhase = fileState == TransactionJournal.FileTransitionState.COMMIT_INTENT_DURABLE
               || fileState == TransactionJournal.FileTransitionState.COMMITTED_DURABLE;

            BasicFileAttributes targetAttrs;
            try {
               targetAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
               targetAttrs = null;
            } catch (IOException | SecurityException e) {
               return new ChangeApplyTransaction.RollbackResult.Incomplete(
                  diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read target during rollback of " + rel + ": " + e.getMessage(), rel)
               );
            }

            if (targetAttrs != null) {
               if (fileState == TransactionJournal.FileTransitionState.BACKUP_INTENT_DURABLE) {
                  if (targetAttrs.isSymbolicLink() || !targetAttrs.isRegularFile()) {
                     return new ChangeApplyTransaction.RollbackResult.Incomplete(
                        diag("SIR-APP-CHANGE-RECOVERY-001", "target is not regular file during BACKUP_INTENT rollback: " + rel, rel)
                     );
                  }

                  if (targetAttrs.size() != fc.baseByteCount()) {
                     return new ChangeApplyTransaction.RollbackResult.Incomplete(
                        diag(
                           "SIR-APP-CHANGE-RECOVERY-001",
                           "target byte count mismatch during BACKUP_INTENT rollback of "
                              + rel
                              + " expected B0="
                              + fc.baseByteCount()
                              + " actual="
                              + targetAttrs.size(),
                           rel
                        )
                     );
                  }

                  try {
                     byte[] targetBytes = Files.readAllBytes(target);
                     String targetSha = Sha256.hexDigest(targetBytes);
                     if (!targetSha.equals(fc.baseSha256Hex())) {
                        return new ChangeApplyTransaction.RollbackResult.Incomplete(
                           diag(
                              "SIR-APP-CHANGE-RECOVERY-001",
                              "target SHA-256 mismatch during BACKUP_INTENT rollback of " + rel + " — external file must not be deleted",
                              rel
                           )
                        );
                     }
                     continue;
                  } catch (IOException | SecurityException e) {
                     return new ChangeApplyTransaction.RollbackResult.Incomplete(
                        diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read target bytes during BACKUP_INTENT rollback of " + rel, rel)
                     );
                  }
               }

               if (!isCommittedPhase) {
                  return new ChangeApplyTransaction.RollbackResult.Incomplete(
                     diag("SIR-APP-CHANGE-RECOVERY-001", "target exists during backup-phase rollback of " + rel + " — external file must not be deleted", rel)
                  );
               }

               if (targetAttrs.isSymbolicLink() || !targetAttrs.isRegularFile()) {
                  return new ChangeApplyTransaction.RollbackResult.Incomplete(
                     diag("SIR-APP-CHANGE-RECOVERY-001", "target is not regular file during rollback: " + rel, rel)
                  );
               }

               if (targetAttrs.size() != fc.candidateByteCount()) {
                  return new ChangeApplyTransaction.RollbackResult.Incomplete(
                     diag(
                        "SIR-APP-CHANGE-RECOVERY-001",
                        "target byte count mismatch during rollback of " + rel + " expected B1=" + fc.candidateByteCount() + " actual=" + targetAttrs.size(),
                        rel
                     )
                  );
               }

               try {
                  byte[] targetBytes = Files.readAllBytes(target);
                  String targetSha = Sha256.hexDigest(targetBytes);
                  if (!targetSha.equals(fc.candidateSha256Hex())) {
                     return new ChangeApplyTransaction.RollbackResult.Incomplete(
                        diag("SIR-APP-CHANGE-RECOVERY-001", "target SHA-256 mismatch during rollback of " + rel + " — external file must not be deleted", rel)
                     );
                  }
               } catch (IOException | SecurityException e) {
                  return new ChangeApplyTransaction.RollbackResult.Incomplete(
                     diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read target bytes during rollback of " + rel, rel)
                  );
               }
            }

            try {
               journal.appendFileTransition(index, rel, TransactionJournal.FileTransitionState.ROLLBACK_INTENT_DURABLE);
            } catch (IOException e) {
               return new ChangeApplyTransaction.RollbackResult.Incomplete(
                  diag("SIR-APP-CHANGE-RECOVERY-001", "journal ROLLBACK_INTENT force failed for " + rel, rel)
               );
            }

            if (targetAttrs != null) {
               try {
                  Files.delete(target);
               } catch (IOException | SecurityException e) {
                  return new ChangeApplyTransaction.RollbackResult.Incomplete(
                     diag("SIR-APP-CHANGE-RECOVERY-001", "failed to delete target during rollback of " + rel + ": " + e.getMessage(), rel)
                  );
               }
            }

            if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
               return new ChangeApplyTransaction.RollbackResult.Incomplete(diag("SIR-APP-CHANGE-RECOVERY-001", "backup missing during rollback of " + rel, rel));
            }

            try {
               BasicFileAttributes backupAttrs = Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
               if (backupAttrs.isSymbolicLink() || !backupAttrs.isRegularFile()) {
                  return new ChangeApplyTransaction.RollbackResult.Incomplete(
                     diag("SIR-APP-CHANGE-RECOVERY-001", "backup is not regular file during rollback of " + rel, rel)
                  );
               }

               if (backupAttrs.size() != fc.baseByteCount()) {
                  return new ChangeApplyTransaction.RollbackResult.Incomplete(
                     diag(
                        "SIR-APP-CHANGE-RECOVERY-001",
                        "backup byte count mismatch during rollback of " + rel + " expected B0=" + fc.baseByteCount() + " actual=" + backupAttrs.size(),
                        rel
                     )
                  );
               }

               byte[] backupBytes = Files.readAllBytes(backup);
               String backupSha = Sha256.hexDigest(backupBytes);
               if (!backupSha.equals(fc.baseSha256Hex())) {
                  return new ChangeApplyTransaction.RollbackResult.Incomplete(
                     diag("SIR-APP-CHANGE-RECOVERY-001", "backup SHA-256 mismatch during rollback of " + rel, rel)
                  );
               }
            } catch (IOException | SecurityException e) {
               return new ChangeApplyTransaction.RollbackResult.Incomplete(
                  diag("SIR-APP-CHANGE-RECOVERY-001", "failed to verify backup during rollback of " + rel + ": " + e.getMessage(), rel)
               );
            }

            try {
               Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | SecurityException e) {
               return new ChangeApplyTransaction.RollbackResult.Incomplete(
                  diag("SIR-APP-CHANGE-RECOVERY-001", "rollback restore failed for " + rel + ": " + e.getMessage(), rel)
               );
            }

            try {
               journal.appendFileTransition(index, rel, TransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE);
            } catch (IOException e) {
               return new ChangeApplyTransaction.RollbackResult.Incomplete(
                  diag("SIR-APP-CHANGE-RECOVERY-001", "journal ROLLED_BACK force failed for " + rel, rel)
               );
            }
         }
      }

      return new ChangeApplyTransaction.RollbackResult.Complete();
   }

   private List<ChangeExecutionDiagnostic> cleanup(TransactionJournal journal) {
      List<ChangeExecutionDiagnostic> warnings = new ArrayList<>();

      try {
         journal.appendOverallTransition(TransactionJournal.OverallState.CLEANUP_PENDING);
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
      TransactionJournal journal, List<ChangeExecutionDiagnostic> diagnostics, ChangeExecutionDiagnostic primaryError
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
      List<String> paths = new ArrayList<>(this.fileChanges.size());

      for (FileChange fc : this.fileChanges) {
         paths.add(fc.relativePath());
      }

      return paths;
   }

   private ChangeExecutionDiagnostic appendOverall(
      TransactionJournal journal, TransactionJournal.OverallState state, String label, List<ChangeExecutionDiagnostic> diagnostics
   ) {
      try {
         journal.appendOverallTransition(state);
         this.hooks.afterJournalForce(journal, label);
         return null;
      } catch (IOException e) {
         ChangeExecutionDiagnostic d = diag("SIR-APP-CHANGE-COMMIT-001", "journal " + label + " force failed: " + e.getMessage());
         return d;
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

   private static ChangeExecutionDiagnostic diag(String code, String message) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.COMMIT, ExecutionSeverity.ERROR, message, Optional.empty());
   }

   private static ChangeExecutionDiagnostic diag(String code, String message, String relativePath) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.COMMIT, ExecutionSeverity.ERROR, message, Optional.of(relativePath));
   }

   private sealed interface CommitResult permits ChangeApplyTransaction.CommitResult.Complete, ChangeApplyTransaction.CommitResult.Failure {
      record Complete() implements ChangeApplyTransaction.CommitResult {
      }

      record Failure(ChangeExecutionDiagnostic error) implements ChangeApplyTransaction.CommitResult {
         public Failure {
            Objects.requireNonNull(error, "error");
         }
      }
   }

   private sealed interface PublishResult permits ChangeApplyTransaction.PublishResult.Complete, ChangeApplyTransaction.PublishResult.Failure {
      record Complete() implements ChangeApplyTransaction.PublishResult {
      }

      record Failure(ChangeExecutionDiagnostic error) implements ChangeApplyTransaction.PublishResult {
         public Failure {
            Objects.requireNonNull(error, "error");
         }
      }
   }

   private sealed interface RollbackResult permits ChangeApplyTransaction.RollbackResult.Complete, ChangeApplyTransaction.RollbackResult.Incomplete {
      record Complete() implements ChangeApplyTransaction.RollbackResult {
      }

      record Incomplete(ChangeExecutionDiagnostic error) implements ChangeApplyTransaction.RollbackResult {
         public Incomplete {
            Objects.requireNonNull(error, "error");
         }
      }
   }
}
