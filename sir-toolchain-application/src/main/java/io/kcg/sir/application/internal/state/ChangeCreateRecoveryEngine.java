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

public final class ChangeCreateRecoveryEngine {
   private final BaselineBundleStore store;
   private final Path outputRoot;
   private final RecoveryHandle handle;
   private static final ChangeCreateRecoveryEngine.CurrentNewIo DEFAULT_CURRENT_NEW_IO = new ChangeCreateRecoveryEngine.CurrentNewIo() {
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
   static volatile ChangeCreateRecoveryEngine.CurrentNewIo currentNewIoForTests = null;

   public ChangeCreateRecoveryEngine(BaselineBundleStore store, Path outputRoot, RecoveryHandle handle) {
      this.store = Objects.requireNonNull(store, "store");
      this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
      this.handle = Objects.requireNonNull(handle, "handle");
   }

   public ChangeRecoveryResult execute(CreateTransactionJournal.Snapshot snapshot, Optional<String> currentId) {
      Objects.requireNonNull(snapshot, "snapshot");
      List<ChangeExecutionDiagnostic> diagnostics = new ArrayList<>();
      if (currentId.isEmpty()) {
         return ChangeRecoveryResult.recoveryRequired(
            RecoveryHandle.of(snapshot.transactionId()),
            Optional.empty(),
            withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-002", "CURRENT is absent but CREATE transaction exists: " + snapshot.transactionId())
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

   private ChangeRecoveryResult backwardRecovery(CreateTransactionJournal.Snapshot snapshot, List<ChangeExecutionDiagnostic> diagnostics) {
      BaselineBundleStore.LoadResult b0Load = this.store.loadBundle(snapshot.b0BaselineId());
      if (b0Load instanceof BaselineBundleStore.LoadResult.Failure f) {
         return ChangeRecoveryResult.recoveryRequired(
            RecoveryHandle.of(snapshot.transactionId()),
            Optional.empty(),
            withError(diagnostics, f.code(), "cannot load B0 for CREATE backward recovery: " + f.message())
         );
      } else {
         BaselineBundle b0Bundle = ((BaselineBundleStore.LoadResult.Success)b0Load).bundle();
         Path txDir = this.store.stateRoot().resolve("transactions").resolve(snapshot.transactionId());
         Path stagingDir = txDir.resolve("staging");

         SafeTargetResolver outputResolver;
         try {
            outputResolver = SafeTargetResolver.forRoot(this.outputRoot);
         } catch (SafeTargetResolver.UnsafePathException e) {
            return ChangeRecoveryResult.recoveryRequired(
               RecoveryHandle.of(snapshot.transactionId()),
               Optional.of(toReceipt(b0Bundle)),
               withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "unsafe outputRoot for CREATE recovery: " + e.getMessage())
            );
         }

         CreateTransactionJournal journal = CreateTransactionJournal.forRecovery(snapshot);
         CreateTransactionJournal.OverallState originalOverall = snapshot.overallState();
         if (originalOverall != CreateTransactionJournal.OverallState.ROLLING_BACK && originalOverall != CreateTransactionJournal.OverallState.ROLLED_BACK) {
            try {
               journal.appendOverallTransition(CreateTransactionJournal.OverallState.ROLLING_BACK);
            } catch (IOException e) {
               return ChangeRecoveryResult.recoveryRequired(
                  RecoveryHandle.of(snapshot.transactionId()),
                  Optional.of(toReceipt(b0Bundle)),
                  withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "journal ROLLING_BACK force failed: " + e.getMessage())
               );
            }
         }

         Map<String, BaselineManifestEntry> b1ManifestMap = null;
         BaselineBundleStore.LoadResult.Failure b1Failure = null;
         SafeTargetResolver stagingResolver = null;
         List<String> planned = snapshot.plannedFiles();
         int i = planned.size() - 1;

         String rel;
         while (true) {
            if (i < 0) {
               for (String relx : snapshot.plannedFiles()) {
                  ChangeExecutionDiagnostic absentError = this.proveTargetAbsent(relx, outputResolver);
                  if (absentError != null) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(diagnostics, absentError.code(), absentError.message(), relx)
                     );
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

               if (originalOverall != CreateTransactionJournal.OverallState.ROLLED_BACK) {
                  try {
                     journal.appendOverallTransition(CreateTransactionJournal.OverallState.ROLLED_BACK);
                  } catch (IOException e) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "journal ROLLED_BACK force failed: " + e.getMessage())
                     );
                  }
               }

               if (this.cleanupCurrentNewIfPresent(snapshot.b1BaselineId()) instanceof ChangeCreateRecoveryEngine.CurrentNewCleanupResult.Failure f) {
                  return ChangeRecoveryResult.recoveryRequired(
                     RecoveryHandle.of(snapshot.transactionId()), Optional.of(toReceipt(b0Bundle)), withError(diagnostics, f.code(), f.message())
                  );
               }

               ChangeCreateRecoveryEngine.RetainedDirectoryResult retainedResult = this.retainedDirectoryWarning(snapshot, outputResolver);
               if (retainedResult instanceof ChangeCreateRecoveryEngine.RetainedDirectoryResult.Failure rf) {
                  return ChangeRecoveryResult.recoveryRequired(
                     RecoveryHandle.of(snapshot.transactionId()),
                     Optional.of(toReceipt(b0Bundle)),
                     withError(diagnostics, rf.error().code(), rf.error().message(), rf.error().relativePath().orElse(null))
                  );
               }

               ChangeCreateRecoveryEngine.RetainedDirectoryResult.Ok retainedOk = (ChangeCreateRecoveryEngine.RetainedDirectoryResult.Ok)retainedResult;
               diagnostics.addAll(retainedOk.warnings());
               List<ChangeExecutionDiagnostic> cleanupWarnings = this.cleanupTransactionDir(txDir);
               diagnostics.addAll(cleanupWarnings);
               return ChangeRecoveryResult.rolledBack(toReceipt(b0Bundle), List.copyOf(diagnostics));
            }

            rel = planned.get(i);
            CreateTransactionJournal.FileTransitionState state = snapshot.fileStates().get(rel);
            if (state == null || state == CreateTransactionJournal.FileTransitionState.PREPARING) {
               ChangeExecutionDiagnostic absentError = this.proveTargetAbsent(rel, outputResolver);
               if (absentError != null) {
                  return ChangeRecoveryResult.recoveryRequired(
                     RecoveryHandle.of(snapshot.transactionId()),
                     Optional.of(toReceipt(b0Bundle)),
                     withError(diagnostics, absentError.code(), absentError.message(), rel)
                  );
               }
            } else if (state == CreateTransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE) {
               ChangeExecutionDiagnostic absentError = this.proveTargetAbsent(rel, outputResolver);
               if (absentError != null) {
                  return ChangeRecoveryResult.recoveryRequired(
                     RecoveryHandle.of(snapshot.transactionId()),
                     Optional.of(toReceipt(b0Bundle)),
                     withError(diagnostics, absentError.code(), absentError.message(), rel)
                  );
               }
            } else {
               label118: {
                  if (b1ManifestMap == null) {
                     BaselineBundleStore.LoadResult b1Load = this.loadB1WithFallback(snapshot.b1BaselineId(), txDir);
                     if (b1Load instanceof BaselineBundleStore.LoadResult.Failure f) {
                        b1Failure = f;
                        if (state != CreateTransactionJournal.FileTransitionState.CREATE_INTENT_DURABLE) {
                           break;
                        }

                        ChangeExecutionDiagnostic absentError = this.proveTargetAbsent(rel, outputResolver);
                        if (absentError != null) {
                           break;
                        }

                        ChangeExecutionDiagnostic forceError = this.forceRollbackTransitionsWithoutDelete(journal, i, rel);
                        if (forceError != null) {
                           return ChangeRecoveryResult.recoveryRequired(
                              RecoveryHandle.of(snapshot.transactionId()),
                              Optional.of(toReceipt(b0Bundle)),
                              withError(diagnostics, forceError.code(), forceError.message(), rel)
                           );
                        }
                        break label118;
                     }

                     BaselineBundle b1Bundle = ((BaselineBundleStore.LoadResult.Success)b1Load).bundle();
                     b1ManifestMap = manifestToMap(b1Bundle.descriptor().manifest());
                  }

                  if (stagingResolver == null) {
                     try {
                        stagingResolver = SafeTargetResolver.forRoot(stagingDir);
                     } catch (SafeTargetResolver.UnsafePathException e) {
                        return ChangeRecoveryResult.recoveryRequired(
                           RecoveryHandle.of(snapshot.transactionId()),
                           Optional.of(toReceipt(b0Bundle)),
                           withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", "unsafe staging directory for CREATE recovery: " + e.getMessage(), rel)
                        );
                     }
                  }

                  BaselineManifestEntry b1Entry = b1ManifestMap.get(rel);
                  if (b1Entry == null) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(diagnostics, "SIR-APP-CHANGE-RECOVERY-001", rel + " not found in B1 manifest during CREATE recovery", rel)
                     );
                  }

                  ChangeExecutionDiagnostic fileError = this.recoverCreateFile(rel, state, outputResolver, stagingResolver, b1Entry, journal, i, diagnostics);
                  if (fileError != null) {
                     return ChangeRecoveryResult.recoveryRequired(
                        RecoveryHandle.of(snapshot.transactionId()),
                        Optional.of(toReceipt(b0Bundle)),
                        withError(diagnostics, fileError.code(), fileError.message(), rel)
                     );
                  }
               }
            }

            i--;
         }

         return ChangeRecoveryResult.recoveryRequired(
            RecoveryHandle.of(snapshot.transactionId()),
            Optional.of(toReceipt(b0Bundle)),
            withError(
               diagnostics,
               Objects.requireNonNull(b1Failure).code(),
               "cannot load B1 for CREATE backward recovery: " + b1Failure.message(),
               rel
            )
         );
      }
   }

   private ChangeExecutionDiagnostic recoverCreateFile(
      String rel,
      CreateTransactionJournal.FileTransitionState state,
      SafeTargetResolver outputResolver,
      SafeTargetResolver stagingResolver,
      BaselineManifestEntry b1Entry,
      CreateTransactionJournal journal,
      int index,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      Path target;
      try {
         target = outputResolver.resolveNoCreate(rel);
      } catch (SafeTargetResolver.UnsafePathException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve target for CREATE recovery of " + rel + ": " + e.getMessage(), rel);
      }

      BasicFileAttributes targetAttrs;
      try {
         targetAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         if (state == CreateTransactionJournal.FileTransitionState.ROLLBACK_DELETE_INTENT_DURABLE) {
            return this.forceRolledBackDurable(journal, index, rel);
         }

         return this.forceRollbackTransitionsWithoutDelete(journal, index, rel);
      } catch (IOException | SecurityException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read target during CREATE recovery of " + rel + ": " + e.getMessage(), rel);
      }

      if (!targetAttrs.isSymbolicLink() && targetAttrs.isRegularFile()) {
         Path staged;
         try {
            staged = stagingResolver.resolveExistingFile(rel);
         } catch (SafeTargetResolver.UnsafePathException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve staged file for CREATE recovery of " + rel + ": " + e.getMessage(), rel);
         }

         if (!Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "staged file missing during CREATE recovery of " + rel + " — cannot prove target ownership", rel);
         }

         try {
            if (!Files.isSameFile(target, staged)) {
               return diag(
                  "SIR-APP-CHANGE-RECOVERY-001",
                  "target exists but is not the staged file during CREATE recovery of " + rel + " — external file must not be deleted",
                  rel
               );
            }
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "isSameFile check failed during CREATE recovery of " + rel + ": " + e.getMessage(), rel);
         }

         try {
            if (targetAttrs.size() != b1Entry.byteCount()) {
               return diag(
                  "SIR-APP-CHANGE-RECOVERY-001",
                  "target byte count mismatch during CREATE recovery of " + rel + " expected B1=" + b1Entry.byteCount() + " actual=" + targetAttrs.size(),
                  rel
               );
            }

            byte[] targetBytes = Files.readAllBytes(target);
            String targetSha = Sha256.hexDigest(targetBytes);
            if (!targetSha.equals(b1Entry.sha256Hex())) {
               return diag(
                  "SIR-APP-CHANGE-RECOVERY-001", "target SHA-256 mismatch during CREATE recovery of " + rel + " — external file must not be deleted", rel
               );
            }
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read target bytes during CREATE recovery of " + rel, rel);
         }

         if (state != CreateTransactionJournal.FileTransitionState.ROLLBACK_DELETE_INTENT_DURABLE) {
            try {
               journal.appendFileTransition(index, rel, CreateTransactionJournal.FileTransitionState.ROLLBACK_DELETE_INTENT_DURABLE);
            } catch (IOException e) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "journal ROLLBACK_DELETE_INTENT force failed for " + rel + ": " + e.getMessage(), rel);
            }
         }

         try {
            if (!Files.isSameFile(target, staged)) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "target identity changed before deletion during CREATE recovery of " + rel, rel);
            }

            byte[] targetBytes = Files.readAllBytes(target);
            String targetSha = Sha256.hexDigest(targetBytes);
            if (!targetSha.equals(b1Entry.sha256Hex())) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "target SHA-256 changed before deletion during CREATE recovery of " + rel, rel);
            }
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to revalidate target before deletion during CREATE recovery of " + rel, rel);
         }

         try {
            Files.delete(target);
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to delete target during CREATE recovery of " + rel + ": " + e.getMessage(), rel);
         }

         try {
            Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return diag("SIR-APP-CHANGE-RECOVERY-001", "target was concurrently recreated after deletion during CREATE recovery of " + rel, rel);
         } catch (NoSuchFileException var15) {
            try {
               journal.appendFileTransition(index, rel, CreateTransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE);
               return null;
            } catch (IOException e) {
               return diag("SIR-APP-CHANGE-RECOVERY-001", "journal ROLLED_BACK force failed for " + rel + ": " + e.getMessage(), rel);
            }
         } catch (IOException | SecurityException e) {
            return diag("SIR-APP-CHANGE-RECOVERY-001", "failed to prove target absence after deletion during CREATE recovery of " + rel, rel);
         }
      } else {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "target is not regular file during CREATE recovery of " + rel, rel);
      }
   }

   private ChangeExecutionDiagnostic forceRollbackTransitionsWithoutDelete(CreateTransactionJournal journal, int index, String rel) {
      try {
         journal.appendFileTransition(index, rel, CreateTransactionJournal.FileTransitionState.ROLLBACK_DELETE_INTENT_DURABLE);
         journal.appendFileTransition(index, rel, CreateTransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE);
         return null;
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "journal rollback transition force failed for " + rel + ": " + e.getMessage(), rel);
      }
   }

   private ChangeExecutionDiagnostic forceRolledBackDurable(CreateTransactionJournal journal, int index, String rel) {
      try {
         journal.appendFileTransition(index, rel, CreateTransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE);
         return null;
      } catch (IOException e) {
         return diag("SIR-APP-CHANGE-RECOVERY-001", "journal ROLLED_BACK force failed for " + rel + ": " + e.getMessage(), rel);
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

   private ChangeRecoveryResult forwardRecovery(CreateTransactionJournal.Snapshot snapshot, List<ChangeExecutionDiagnostic> diagnostics) {
      BaselineBundleStore.LoadResult b1Load = this.store.loadBundle(snapshot.b1BaselineId());
      if (b1Load instanceof BaselineBundleStore.LoadResult.Failure f) {
         return ChangeRecoveryResult.recoveryRequired(
            RecoveryHandle.of(snapshot.transactionId()),
            Optional.empty(),
            withError(diagnostics, f.code(), "cannot load B1 for CREATE forward recovery: " + f.message())
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

   private ChangeCreateRecoveryEngine.CurrentNewCleanupResult cleanupCurrentNewIfPresent(String b1BaselineId) {
      Path currentNew = this.store.stateRoot().resolve("CURRENT.new");
      ChangeCreateRecoveryEngine.CurrentNewIo io = currentNewIoForTests != null ? currentNewIoForTests : DEFAULT_CURRENT_NEW_IO;

      BasicFileAttributes attrs;
      try {
         attrs = io.readAttributes(currentNew);
      } catch (NoSuchFileException e) {
         return new ChangeCreateRecoveryEngine.CurrentNewCleanupResult.Ok();
      } catch (IOException | SecurityException e) {
         return new ChangeCreateRecoveryEngine.CurrentNewCleanupResult.Failure(
            "SIR-APP-CHANGE-RECOVERY-002", "failed to read CURRENT.new attributes: " + e.getMessage()
         );
      }

      if (attrs.isSymbolicLink()) {
         return new ChangeCreateRecoveryEngine.CurrentNewCleanupResult.Failure(
            "SIR-APP-CHANGE-RECOVERY-002", "CURRENT.new is a symlink — cannot safely interpret"
         );
      }

      if (!attrs.isRegularFile()) {
         return new ChangeCreateRecoveryEngine.CurrentNewCleanupResult.Failure(
            "SIR-APP-CHANGE-RECOVERY-002", "CURRENT.new is not a regular file — cannot safely interpret"
         );
      }

      byte[] bytes;
      try {
         bytes = io.readAllBytes(currentNew);
      } catch (IOException | SecurityException e) {
         return new ChangeCreateRecoveryEngine.CurrentNewCleanupResult.Failure(
            "SIR-APP-CHANGE-RECOVERY-002", "failed to read CURRENT.new content: " + e.getMessage()
         );
      }

      byte[] expected = (b1BaselineId + "\n").getBytes(StandardCharsets.US_ASCII);
      if (!Arrays.equals(bytes, expected)) {
         return new ChangeCreateRecoveryEngine.CurrentNewCleanupResult.Failure(
            "SIR-APP-CHANGE-RECOVERY-002", "CURRENT.new content does not exactly match B1 baselineId — cannot safely interpret"
         );
      }

      try {
         io.delete(currentNew);
      } catch (IOException | SecurityException e) {
         return new ChangeCreateRecoveryEngine.CurrentNewCleanupResult.Failure("SIR-APP-CHANGE-RECOVERY-002", "failed to delete CURRENT.new: " + e.getMessage());
      }

      return new ChangeCreateRecoveryEngine.CurrentNewCleanupResult.Ok();
   }

   private ChangeCreateRecoveryEngine.RetainedDirectoryResult retainedDirectoryWarning(
      CreateTransactionJournal.Snapshot snapshot, SafeTargetResolver outputResolver
   ) {
      List<String> retained = new ArrayList<>();

      for (String rel : snapshot.plannedDirectories()) {
         CreateTransactionJournal.DirectoryTransitionState dirState = snapshot.directoryStates().get(rel);
         if (dirState != null
            && (
               dirState == CreateTransactionJournal.DirectoryTransitionState.DIRECTORY_CREATED_DURABLE
                  || dirState == CreateTransactionJournal.DirectoryTransitionState.DIRECTORY_CREATE_INTENT_DURABLE
            )) {
            Path dir;
            try {
               dir = outputResolver.resolveNoCreate(rel);
            } catch (SafeTargetResolver.UnsafePathException e) {
               return new ChangeCreateRecoveryEngine.RetainedDirectoryResult.Failure(
                  diag("SIR-APP-CHANGE-RECOVERY-001", "failed to resolve retained directory " + rel + ": " + e.getMessage(), rel)
               );
            }

            BasicFileAttributes attrs;
            try {
               attrs = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
               continue;
            } catch (IOException | SecurityException e) {
               return new ChangeCreateRecoveryEngine.RetainedDirectoryResult.Failure(
                  diag("SIR-APP-CHANGE-RECOVERY-001", "failed to read retained directory attributes " + rel + ": " + e.getMessage(), rel)
               );
            }

            if (attrs.isSymbolicLink()) {
               return new ChangeCreateRecoveryEngine.RetainedDirectoryResult.Failure(
                  diag("SIR-APP-CHANGE-RECOVERY-001", "retained directory is a symlink: " + rel, rel)
               );
            }

            if (!attrs.isDirectory()) {
               return new ChangeCreateRecoveryEngine.RetainedDirectoryResult.Failure(
                  diag("SIR-APP-CHANGE-RECOVERY-001", "retained path is not a directory: " + rel, rel)
               );
            }

            retained.add(rel);
         }
      }

      if (retained.isEmpty()) {
         return new ChangeCreateRecoveryEngine.RetainedDirectoryResult.Ok(List.of());
      }

      retained.sort((a, b) -> {
         int depthA = depthOf(a);
         int depthB = depthOf(b);
         return depthA != depthB ? Integer.compare(depthA, depthB) : a.compareTo(b);
      });
      return new ChangeCreateRecoveryEngine.RetainedDirectoryResult.Ok(
         List.of(
            new ChangeExecutionDiagnostic(
               "SIR-APP-CHANGE-CLEANUP-003",
               ChangeExecutionStage.CLEANUP,
               ExecutionSeverity.WARNING,
               "transaction-created output directories retained: " + retained,
               Optional.empty()
            )
         )
      );
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
         warnings.add(diag("SIR-APP-CHANGE-CLEANUP-001", "cleanup of CREATE transaction directory failed: " + e.getMessage()));
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
      permits ChangeCreateRecoveryEngine.CurrentNewCleanupResult.Ok,
      ChangeCreateRecoveryEngine.CurrentNewCleanupResult.Failure {
      record Failure(String code, String message) implements ChangeCreateRecoveryEngine.CurrentNewCleanupResult {
         public Failure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
         }
      }

      record Ok() implements ChangeCreateRecoveryEngine.CurrentNewCleanupResult {
      }
   }

   interface CurrentNewIo {
      BasicFileAttributes readAttributes(Path var1) throws IOException;

      byte[] readAllBytes(Path var1) throws IOException;

      void delete(Path var1) throws IOException;
   }

   private sealed interface RetainedDirectoryResult
      permits ChangeCreateRecoveryEngine.RetainedDirectoryResult.Ok,
      ChangeCreateRecoveryEngine.RetainedDirectoryResult.Failure {
      record Failure(ChangeExecutionDiagnostic error) implements ChangeCreateRecoveryEngine.RetainedDirectoryResult {
         public Failure {
            Objects.requireNonNull(error, "error");
         }
      }

      record Ok(List<ChangeExecutionDiagnostic> warnings) implements ChangeCreateRecoveryEngine.RetainedDirectoryResult {
         public Ok {
            warnings = List.copyOf(warnings);
         }
      }
   }
}
