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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public final class ChangeRecoveryEngine {
   private final BaselineBundleStore store;
   private final Path outputRoot;
   private final RecoveryHandle handle;

   public ChangeRecoveryEngine(BaselineBundleStore store, Path outputRoot, RecoveryHandle handle) {
      this.store = Objects.requireNonNull(store, "store");
      this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
      this.handle = Objects.requireNonNull(handle, "handle");
   }

   /**
    * [RQ-11] Recovery is only ever reachable through ChangeExecutionApplication
    * .recover (explicit API) — never implicitly after a failed apply. When no
    * active journal remains the engine returns idempotentResult instead of
    * inventing work, so repeated recovery of an already-closed transaction is a
    * deterministic report. Verified by RecoveryStateMachineTest
    * .repeatedRecoveryIsIdempotent.
    */
   public ChangeRecoveryResult execute() {
      List<ChangeExecutionDiagnostic> diagnostics = new ArrayList<>();
      JournalGate gate = new JournalGate(this.store.stateRoot());
      BaselineBundleStore.CurrentReadResult currentRead = this.store.readCurrent();
      Optional<String> currentId;
      if (currentRead instanceof BaselineBundleStore.CurrentReadResult.Present p) {
         currentId = Optional.of(p.baselineId());
      } else {
         if (!(currentRead instanceof BaselineBundleStore.CurrentReadResult.Absent)) {
            BaselineBundleStore.CurrentReadResult.Failure f = (BaselineBundleStore.CurrentReadResult.Failure)currentRead;
            return ChangeRecoveryResult.failure(
               ChangeExecutionStage.RECOVERY, List.of(diag("SIR-APP-CHANGE-RECOVERY-002", "cannot read CURRENT: " + f.message()))
            );
         }

         currentId = Optional.empty();
      }

      JournalGate.InspectionResult inspection = gate.inspect(currentId);
      if (!inspection.isOpen()) {
         if (inspection.activeJournals().isEmpty()) {
            return ChangeRecoveryResult.recoveryRequired(RecoveryHandle.any(), Optional.empty(), inspection.errors());
         }

         JournalGate.ActiveJournal target = this.findTargetJournal(inspection.activeJournals());
         return target == null
            ? ChangeRecoveryResult.recoveryRequired(RecoveryHandle.any(), this.loadReceiptSafe(currentId), inspection.errors())
            : this.recoverTransaction(target, currentId, diagnostics);
      } else {
         return this.idempotentResult(currentId, diagnostics);
      }
   }

   private ChangeRecoveryResult recoverTransaction(JournalGate.ActiveJournal active, Optional<String> currentId, List<ChangeExecutionDiagnostic> diagnostics) {
      if (active instanceof JournalGate.ActiveJournal.V2Create v2) {
         ChangeCreateRecoveryEngine createEngine = new ChangeCreateRecoveryEngine(this.store, this.outputRoot, this.handle);
         return createEngine.execute(v2.snapshot(), currentId);
      } else if (active instanceof JournalGate.ActiveJournal.V3Delete v3) {
         ChangeDeleteRecoveryEngine deleteEngine = new ChangeDeleteRecoveryEngine(this.store, this.outputRoot, this.handle);
         return deleteEngine.execute(v3.snapshot(), currentId);
      } else {
         JournalGate.ActiveJournal.V1Update v1 = (JournalGate.ActiveJournal.V1Update)active;
         TransactionJournal.Snapshot snapshot = v1.snapshot();
         if (currentId.isEmpty()) {
            return ChangeRecoveryResult.recoveryRequired(
               RecoveryHandle.of(snapshot.transactionId()),
               Optional.empty(),
               withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-002", "CURRENT is absent but transaction exists: " + snapshot.transactionId())
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
               return currentIsB0 ? this.backwardRecovery(snapshot, currentId, diagnostics) : this.forwardRecovery(snapshot, currentId, diagnostics);
            }
         }
      }
   }

   private ChangeRecoveryResult backwardRecovery(TransactionJournal.Snapshot snapshot, Optional<String> currentId, List<ChangeExecutionDiagnostic> diagnostics) {
      BaselineBundleStore.LoadResult b0Load = this.store.loadBundle(snapshot.b0BaselineId());
      if (b0Load instanceof BaselineBundleStore.LoadResult.Failure f) {
         return ChangeRecoveryResult.recoveryRequired(
            RecoveryHandle.of(snapshot.transactionId()),
            Optional.empty(),
            withError(diagnostics, f.code(), "cannot load B0 for backward recovery: " + f.message())
         );
      } else {
         BaselineBundle b0Bundle = ((BaselineBundleStore.LoadResult.Success)b0Load).bundle();
         Map<String, BaselineManifestEntry> b0ManifestMap = manifestToMap(b0Bundle.descriptor().manifest());
         BaselineBundle b1Bundle = null;
         Map<String, BaselineManifestEntry> b1ManifestMap = null;
         Path txDir = this.store.stateRoot().resolve("transactions").resolve(snapshot.transactionId());
         Path backupDir = txDir.resolve("backup");
         String txDirName = txDir.getFileName().toString();
         if (!txDirName.equals(snapshot.transactionId())) {
            return ChangeRecoveryResult.recoveryRequired(
               RecoveryHandle.of(snapshot.transactionId()),
               Optional.of(toReceipt(b0Bundle)),
               withError(
                  diagnostics,
                  "SIR-APP-CHANGE-RECOVERY-002",
                  "transaction directory name '" + txDirName + "' does not match journal transactionId '" + snapshot.transactionId() + "'"
               )
            );
         }

         SafeTargetResolver outputResolver;
         try {
            outputResolver = SafeTargetResolver.forRoot(this.outputRoot);
         } catch (SafeTargetResolver.UnsafePathException e) {
            return ChangeRecoveryResult.recoveryRequired(
               RecoveryHandle.of(snapshot.transactionId()),
               Optional.of(toReceipt(b0Bundle)),
               withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "unsafe outputRoot for recovery: " + e.getMessage())
            );
         }

         boolean needsDeltaBinding = false;
         boolean hasCommittedPhase = false;

         for (String rel : snapshot.plannedFiles()) {
            TransactionJournal.FileTransitionState state = snapshot.fileStates().get(rel);
            if (state != null
               && state != TransactionJournal.FileTransitionState.PREPARING
               && state != TransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE) {
               needsDeltaBinding = true;
               if (state == TransactionJournal.FileTransitionState.COMMIT_INTENT_DURABLE || state == TransactionJournal.FileTransitionState.COMMITTED_DURABLE) {
                  hasCommittedPhase = true;
               }
            }
         }

         if (needsDeltaBinding) {
            BaselineBundleStore.LoadResult b1Load = this.loadB1WithFallback(snapshot.b1BaselineId(), txDir);
            if (b1Load instanceof BaselineBundleStore.LoadResult.Failure f) {
               String msg = "cannot load B1 for delta binding: " + f.message();
               if (hasCommittedPhase) {
                  msg = "committed-phase file requires B1 but B1 is missing: " + f.message();
               }

               return ChangeRecoveryResult.recoveryRequired(
                  RecoveryHandle.of(snapshot.transactionId()), Optional.of(toReceipt(b0Bundle)), withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", msg)
               );
            }

            b1Bundle = ((BaselineBundleStore.LoadResult.Success)b1Load).bundle();
            b1ManifestMap = manifestToMap(b1Bundle.descriptor().manifest());
            List<String> driftErrors = new ArrayList<>();
            List<String> delta = computeUpdateDelta(b0Bundle.descriptor().manifest(), b1Bundle.descriptor().manifest(), driftErrors);
            if (delta == null) {
               return ChangeRecoveryResult.recoveryRequired(
                  RecoveryHandle.of(snapshot.transactionId()),
                  Optional.of(toReceipt(b0Bundle)),
                  withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "B0/B1 manifest delta binding failed: " + String.join("; ", driftErrors))
               );
            }

            if (!delta.equals(snapshot.plannedFiles())) {
               return ChangeRecoveryResult.recoveryRequired(
                  RecoveryHandle.of(snapshot.transactionId()),
                  Optional.of(toReceipt(b0Bundle)),
                  withError(
                     diagnostics,
                     "SIR-APP-CHANGE-RECOVERY-001",
                     "journal plannedFiles do not match B0/B1 UPDATE delta: journal=" + snapshot.plannedFiles() + " delta=" + delta
                  )
               );
            }
         }

         List<String> planned = snapshot.plannedFiles();
         SafeTargetResolver backupResolver = null;

         for (int i = planned.size() - 1; i >= 0; i--) {
            String rel = planned.get(i);
            TransactionJournal.FileTransitionState state = snapshot.fileStates().get(rel);
            if (state != null
               && state != TransactionJournal.FileTransitionState.PREPARING
               && state != TransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE) {
               if (state == TransactionJournal.FileTransitionState.ROLLBACK_INTENT_DURABLE) {
                  return ChangeRecoveryResult.recoveryRequired(
                     RecoveryHandle.of(snapshot.transactionId()),
                     Optional.of(toReceipt(b0Bundle)),
                     withError(
                        diagnostics,
                        "SIR-APP-CHANGE-RECOVERY-001",
                        "file " + rel + " is in ROLLBACK_INTENT_DURABLE state; cannot determine if rollback completed",
                        rel
                     )
                  );
               }

               Path target;
               try {
                  target = outputResolver.resolveNoCreate(rel);
               } catch (SafeTargetResolver.UnsafePathException e) {
                  return ChangeRecoveryResult.recoveryRequired(
                     RecoveryHandle.of(snapshot.transactionId()),
                     Optional.of(toReceipt(b0Bundle)),
                     withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "failed to resolve target path for " + rel + ": " + e.getMessage(), rel)
                  );
               }

               boolean isCommittedPhase = state == TransactionJournal.FileTransitionState.COMMIT_INTENT_DURABLE
                  || state == TransactionJournal.FileTransitionState.COMMITTED_DURABLE;
               if (isCommittedPhase && b1ManifestMap == null) {
                  BaselineBundleStore.LoadResult b1Load = this.store.loadBundle(snapshot.b1BaselineId());
                  if (b1Load instanceof BaselineBundleStore.LoadResult.Failure) {
                     Path candidateDir = txDir.resolve("candidate-baseline");
                     b1Load = this.store.loadBundleFromDir(candidateDir, snapshot.b1BaselineId());
                  }

                  if (b1Load instanceof BaselineBundleStore.LoadResult.Failure f) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(diagnostics, f.code(), "cannot load B1 for backward recovery: " + f.message())
                     );
                  }

                  b1Bundle = ((BaselineBundleStore.LoadResult.Success)b1Load).bundle();
                  b1ManifestMap = manifestToMap(b1Bundle.descriptor().manifest());
               }

               BasicFileAttributes targetAttrs;
               try {
                  targetAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
               } catch (NoSuchFileException e) {
                  targetAttrs = null;
               } catch (IOException | SecurityException e) {
                  return ChangeRecoveryResult.recoveryRequired(
                     RecoveryHandle.of(snapshot.transactionId()),
                     Optional.of(toReceipt(b0Bundle)),
                     withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "failed to read target during recovery of " + rel + ": " + e.getMessage(), rel)
                  );
               }

               if (targetAttrs != null) {
                  if (state == TransactionJournal.FileTransitionState.BACKUP_INTENT_DURABLE) {
                     if (targetAttrs.isSymbolicLink() || !targetAttrs.isRegularFile()) {
                        return ChangeRecoveryResult.recoveryRequired(
                           RecoveryHandle.of(snapshot.transactionId()),
                           Optional.of(toReceipt(b0Bundle)),
                           withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "target is not regular file during BACKUP_INTENT recovery: " + rel, rel)
                        );
                     }

                     BaselineManifestEntry b0Entry = b0ManifestMap.get(rel);
                     if (b0Entry == null) {
                        return ChangeRecoveryResult.recoveryRequired(
                           RecoveryHandle.of(snapshot.transactionId()),
                           Optional.of(toReceipt(b0Bundle)),
                           withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", rel + " not found in B0 manifest", rel)
                        );
                     }

                     if (targetAttrs.size() != b0Entry.byteCount()) {
                        return ChangeRecoveryResult.recoveryRequired(
                           RecoveryHandle.of(snapshot.transactionId()),
                           Optional.of(toReceipt(b0Bundle)),
                           withError(
                              diagnostics,
                              "SIR-APP-CHANGE-RECOVERY-001",
                              "target byte count mismatch during BACKUP_INTENT recovery of "
                                 + rel
                                 + " expected B0="
                                 + b0Entry.byteCount()
                                 + " actual="
                                 + targetAttrs.size(),
                              rel
                           )
                        );
                     }

                     try {
                        byte[] targetBytes = Files.readAllBytes(target);
                        String targetSha = Sha256.hexDigest(targetBytes);
                        if (!targetSha.equals(b0Entry.sha256Hex())) {
                           return ChangeRecoveryResult.recoveryRequired(
                              RecoveryHandle.of(snapshot.transactionId()),
                              Optional.of(toReceipt(b0Bundle)),
                              withError(
                                 diagnostics,
                                 "SIR-APP-CHANGE-RECOVERY-001",
                                 "target SHA-256 mismatch during BACKUP_INTENT recovery of " + rel + " — external file must not be deleted",
                                 rel
                              )
                           );
                        }
                        continue;
                     } catch (IOException | SecurityException e) {
                        return ChangeRecoveryResult.recoveryRequired(
                           RecoveryHandle.of(snapshot.transactionId()),
                           Optional.of(toReceipt(b0Bundle)),
                           withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "failed to read target bytes during BACKUP_INTENT recovery of " + rel, rel)
                        );
                     }
                  }

                  if (!isCommittedPhase) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(
                           diagnostics,
                           "SIR-APP-CHANGE-RECOVERY-001",
                           "target exists during backup-phase recovery of " + rel + " — external file must not be deleted",
                           rel
                        )
                     );
                  }

                  if (targetAttrs.isSymbolicLink() || !targetAttrs.isRegularFile()) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "target is not regular file during recovery: " + rel, rel)
                     );
                  }

                  BaselineManifestEntry b1Entry = b1ManifestMap.get(rel);
                  if (b1Entry == null) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", rel + " not found in B1 manifest", rel)
                     );
                  }

                  if (targetAttrs.size() != b1Entry.byteCount()) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(
                           diagnostics,
                           "SIR-APP-CHANGE-RECOVERY-001",
                           "target byte count mismatch during recovery of " + rel + " expected B1=" + b1Entry.byteCount() + " actual=" + targetAttrs.size(),
                           rel
                        )
                     );
                  }

                  try {
                     byte[] targetBytes = Files.readAllBytes(target);
                     String targetSha = Sha256.hexDigest(targetBytes);
                     if (!targetSha.equals(b1Entry.sha256Hex())) {
                        return ChangeRecoveryResult.recoveryRequired(
                           RecoveryHandle.of(snapshot.transactionId()),
                           Optional.of(toReceipt(b0Bundle)),
                           withError(
                              diagnostics,
                              "SIR-APP-CHANGE-RECOVERY-001",
                              "target SHA-256 mismatch during recovery of " + rel + " — external file must not be deleted",
                              rel
                           )
                        );
                     }
                  } catch (IOException | SecurityException e) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "failed to read target bytes during recovery of " + rel, rel)
                     );
                  }

                  try {
                     Files.delete(target);
                  } catch (IOException | SecurityException e) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "failed to delete target during recovery of " + rel + ": " + e.getMessage(), rel)
                     );
                  }
               }

               if (backupResolver == null) {
                  try {
                     backupResolver = SafeTargetResolver.forRoot(backupDir);
                  } catch (SafeTargetResolver.UnsafePathException e) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "unsafe backup directory for recovery of " + rel + ": " + e.getMessage(), rel)
                     );
                  }
               }

               Path backup;
               try {
                  backup = backupResolver.resolveExistingFile(rel);
               } catch (SafeTargetResolver.UnsafePathException e) {
                  return ChangeRecoveryResult.recoveryRequired(
                     RecoveryHandle.of(snapshot.transactionId()),
                     Optional.of(toReceipt(b0Bundle)),
                     withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "failed to resolve backup path for " + rel + ": " + e.getMessage(), rel)
                  );
               }

               if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                  return ChangeRecoveryResult.recoveryRequired(
                     RecoveryHandle.of(snapshot.transactionId()),
                     Optional.of(toReceipt(b0Bundle)),
                     withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "backup missing for " + rel + " during backward recovery", rel)
                  );
               }

               try {
                  BasicFileAttributes backupAttrs = Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                  if (backupAttrs.isSymbolicLink() || !backupAttrs.isRegularFile()) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "backup is not regular file during recovery of " + rel, rel)
                     );
                  }

                  BaselineManifestEntry b0Entry = b0ManifestMap.get(rel);
                  if (b0Entry == null) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", rel + " not found in B0 manifest", rel)
                     );
                  }

                  if (backupAttrs.size() != b0Entry.byteCount()) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(
                           diagnostics,
                           "SIR-APP-CHANGE-RECOVERY-001",
                           "backup byte count mismatch during recovery of " + rel + " expected B0=" + b0Entry.byteCount() + " actual=" + backupAttrs.size(),
                           rel
                        )
                     );
                  }

                  byte[] backupBytes = Files.readAllBytes(backup);
                  String backupSha = Sha256.hexDigest(backupBytes);
                  if (!backupSha.equals(b0Entry.sha256Hex())) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "backup SHA-256 mismatch during recovery of " + rel, rel)
                     );
                  }
               } catch (IOException | SecurityException e) {
                  return ChangeRecoveryResult.recoveryRequired(
                     RecoveryHandle.of(snapshot.transactionId()),
                     Optional.of(toReceipt(b0Bundle)),
                     withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "failed to verify backup during recovery of " + rel + ": " + e.getMessage(), rel)
                  );
               }

               try {
                  Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
               } catch (IOException | SecurityException e) {
                  return ChangeRecoveryResult.recoveryRequired(
                     RecoveryHandle.of(snapshot.transactionId()),
                     Optional.of(toReceipt(b0Bundle)),
                     withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "rollback restore failed for " + rel + ": " + e.getMessage(), rel)
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

         List<ChangeExecutionDiagnostic> cleanupWarnings = this.cleanupTransactionDir(txDir);
         diagnostics.addAll(cleanupWarnings);
         return ChangeRecoveryResult.rolledBack(toReceipt(b0Bundle), List.copyOf(diagnostics));
      }
   }

   private static Map<String, BaselineManifestEntry> manifestToMap(List<BaselineManifestEntry> manifest) {
      Map<String, BaselineManifestEntry> map = new LinkedHashMap<>();

      for (BaselineManifestEntry entry : manifest) {
         map.put(entry.relativePath(), entry);
      }

      return Collections.unmodifiableMap(map);
   }

   private BaselineBundleStore.LoadResult loadB1WithFallback(String b1BaselineId, Path txDir) {
      BaselineBundleStore.LoadResult b1Load = this.store.loadBundle(b1BaselineId);
      if (b1Load instanceof BaselineBundleStore.LoadResult.Failure) {
         Path candidateDir = txDir.resolve("candidate-baseline");
         b1Load = this.store.loadBundleFromDir(candidateDir, b1BaselineId);
      }

      return b1Load;
   }

   static List<String> computeUpdateDelta(List<BaselineManifestEntry> b0Manifest, List<BaselineManifestEntry> b1Manifest, List<String> driftErrors) {
      Map<String, BaselineManifestEntry> b0Map = manifestToMap(b0Manifest);
      Map<String, BaselineManifestEntry> b1Map = manifestToMap(b1Manifest);
      if (!b0Map.keySet().equals(b1Map.keySet())) {
         for (String rel : b0Map.keySet()) {
            if (!b1Map.containsKey(rel)) {
               driftErrors.add("path " + rel + " in B0 but not in B1");
            }
         }

         for (String rel : b1Map.keySet()) {
            if (!b0Map.containsKey(rel)) {
               driftErrors.add("path " + rel + " in B1 but not in B0");
            }
         }

         return null;
      } else {
         for (String rel : b0Map.keySet()) {
            BaselineManifestEntry b0 = b0Map.get(rel);
            BaselineManifestEntry b1 = b1Map.get(rel);
            if (!b0.artifactId().equals(b1.artifactId())) {
               driftErrors.add("artifactId drift for " + rel + ": B0=" + b0.artifactId() + " B1=" + b1.artifactId());
            }

            if (!b0.ownerSymbol().equals(b1.ownerSymbol())) {
               driftErrors.add("ownerSymbol drift for " + rel + ": B0=" + b0.ownerSymbol() + " B1=" + b1.ownerSymbol());
            }
         }

         if (!driftErrors.isEmpty()) {
            return null;
         }

         List<String> delta = new ArrayList<>();

         for (String rel : b0Map.keySet()) {
            BaselineManifestEntry b0 = b0Map.get(rel);
            BaselineManifestEntry b1 = b1Map.get(rel);
            if (b0.byteCount() != b1.byteCount() || !b0.sha256Hex().equals(b1.sha256Hex())) {
               delta.add(rel);
            }
         }

         delta.sort(Comparator.naturalOrder());
         return delta;
      }
   }

   private ChangeRecoveryResult forwardRecovery(TransactionJournal.Snapshot snapshot, Optional<String> currentId, List<ChangeExecutionDiagnostic> diagnostics) {
      BaselineBundleStore.LoadResult b1Load = this.store.loadBundle(snapshot.b1BaselineId());
      if (b1Load instanceof BaselineBundleStore.LoadResult.Failure f) {
         return ChangeRecoveryResult.recoveryRequired(
            RecoveryHandle.of(snapshot.transactionId()),
            Optional.empty(),
            withError(diagnostics, f.code(), "cannot load B1 for forward recovery: " + f.message())
         );
      } else {
         BaselineBundle b1Bundle = ((BaselineBundleStore.LoadResult.Success)b1Load).bundle();
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

   private ChangeRecoveryResult idempotentResult(Optional<String> currentId, List<ChangeExecutionDiagnostic> diagnostics) {
      if (currentId.isEmpty()) {
         return ChangeRecoveryResult.failure(
            ChangeExecutionStage.RECOVERY, List.of(diag("SIR-APP-CHANGE-RECOVERY-002", "no CURRENT and no active transactions: nothing to recover"))
         );
      } else {
         BaselineBundleStore.LoadResult load = this.store.loadBundle(currentId.get());
         if (load instanceof BaselineBundleStore.LoadResult.Failure f) {
            return ChangeRecoveryResult.failure(ChangeExecutionStage.RECOVERY, List.of(diag(f.code(), "cannot load CURRENT bundle: " + f.message())));
         } else {
            BaselineBundle bundle = ((BaselineBundleStore.LoadResult.Success)load).bundle();
            return ChangeRecoveryResult.recovered(toReceipt(bundle), List.copyOf(diagnostics));
         }
      }
   }

   private JournalGate.ActiveJournal findTargetJournal(List<JournalGate.ActiveJournal> journals) {
      if (this.handle.transactionId().isPresent()) {
         String target = this.handle.transactionId().get();

         for (JournalGate.ActiveJournal s : journals) {
            if (s.transactionId().equals(target)) {
               return s;
            }
         }

         return null;
      } else {
         return journals.size() == 1 ? journals.get(0) : null;
      }
   }

   private List<ChangeExecutionDiagnostic> cleanupTransactionDir(Path txDir) {
      List<ChangeExecutionDiagnostic> warnings = new ArrayList<>();

      try {
         deleteRecursively(txDir);
      } catch (IOException e) {
         warnings.add(diag("SIR-APP-CHANGE-CLEANUP-001", "cleanup of transaction directory failed: " + e.getMessage()));
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
      out.add(new ChangeExecutionDiagnostic(code, ChangeExecutionStage.RECOVERY, ExecutionSeverity.ERROR, message, Optional.of(rel)));
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
}
