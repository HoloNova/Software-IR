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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** CURRENT-directed recovery for V4 mixed file transactions. */
final class MixedTransactionRecoveryEngine {
   private final BaselineBundleStore store;
   private final Path outputRoot;

   MixedTransactionRecoveryEngine(BaselineBundleStore store, Path outputRoot) {
      this.store = store;
      this.outputRoot = outputRoot.toAbsolutePath().normalize();
   }

   ChangeRecoveryResult execute(MixedTransactionJournal.Snapshot snapshot, Optional<String> currentId) {
      String tx = snapshot.transactionId();
      RecoveryHandle handle = RecoveryHandle.of(tx);
      if (currentId.isEmpty()) return required(handle, null, "CURRENT is absent while a V4 transaction exists");
      String current = currentId.get();
      boolean b0 = current.equals(snapshot.b0BaselineId());
      boolean b1 = current.equals(snapshot.b1BaselineId());
      if (!b0 && !b1) return required(handle, receipt(currentId).orElse(null), "CURRENT matches neither V4 B0 nor B1");
      Path txDir = store.stateRoot().resolve("transactions").resolve(tx).normalize();
      if (!txDir.getParent().equals(store.stateRoot().resolve("transactions").toAbsolutePath().normalize())
          || !snapshot.journalPath().equals(txDir.resolve("journal").toAbsolutePath().normalize())
          || !snapshot.boundOutputRoot().equals(outputRoot)) return required(handle, null, "V4 transaction path or output root binding is unsafe");
      try {
         SafeTargetResolver.forRoot(store.stateRoot()).resolveNoCreate("transactions/" + tx + "/journal");
         requireDirectory(txDir);
         requireDirectoryIfPresent(txDir.resolve("backup"));
         requireDirectoryIfPresent(txDir.resolve("staging"));
         requireDirectoryIfPresent(txDir.resolve("candidate-baseline"));
         BaselineBundle b0Bundle = load(snapshot.b0BaselineId(), null);
         BaselineBundle b1Bundle = load(snapshot.b1BaselineId(), txDir.resolve("candidate-baseline"));
         if (!b0Bundle.descriptor().manifestDigest().equals(snapshot.b0ManifestDigest())
             || !b1Bundle.descriptor().manifestDigest().equals(snapshot.b1ManifestDigest())) throw new IOException("manifest digest does not match V4 journal");
         if (b1) {
            verifyOutput(b1Bundle);
            removeCurrentNewIfOwned(snapshot.b1BaselineId());
            return cleanupAndReturn(txDir, handle, b1Bundle, true);
         }
         rollback(snapshot, txDir, b0Bundle, b1Bundle);
         verifyOutput(b0Bundle);
         removeCurrentNewIfOwned(snapshot.b1BaselineId());
         return cleanupAndReturn(txDir, handle, b0Bundle, false);
      } catch (IOException | SafeTargetResolver.UnsafePathException | SecurityException e) {
         return required(handle, receipt(currentId).orElse(null), "V4 recovery stopped without deleting unproven output: " + e.getMessage());
      }
   }

   private void rollback(MixedTransactionJournal.Snapshot s, Path txDir, BaselineBundle b0, BaselineBundle b1)
       throws IOException, SafeTargetResolver.UnsafePathException {
      MixedTransactionJournal journal = journal(s, txDir);
      if (s.overallState() != MixedTransactionJournal.OverallState.ROLLING_BACK) {
         switch (s.overallState()) {
            case PREPARING, PREPARED, COMMITTING, FILES_COMMITTED, PUBLISHING ->
                journal.appendOverallTransition(MixedTransactionJournal.OverallState.ROLLING_BACK);
            case ROLLING_BACK -> { }
            case BASELINE_PUBLISHED, CLEANUP_PENDING, COMPLETED -> throw new IOException("CURRENT=B0 conflicts with V4 post-publication journal state");
            case ROLLED_BACK -> { }
         }
      }
      if (s.overallState() == MixedTransactionJournal.OverallState.ROLLED_BACK) return;
      SafeTargetResolver output = SafeTargetResolver.forRoot(outputRoot);
      SafeTargetResolver backups = SafeTargetResolver.forRoot(txDir.resolve("backup"));
      SafeTargetResolver staging = SafeTargetResolver.forRoot(txDir.resolve("staging"));
      Map<String, BaselineManifestEntry> base = entries(b0);
      Map<String, BaselineManifestEntry> candidate = entries(b1);
      for (int i = s.files().size() - 1; i >= 0; i--) {
         MixedTransactionJournal.FileEntry f = s.files().get(i);
         String rel = f.relativePath();
         MixedTransactionJournal.FileState state = s.fileStates().get(i);
         Path target = output.resolveNoCreate(rel);
         Path backup = backups.resolveNoCreate(Integer.toString(i));
         BaselineManifestEntry oldEntry = base.get(rel), newEntry = candidate.get(rel);
         switch (f.kind()) {
            case ESTABLISH -> rollbackEstablishment(journal, i, f, state, target, staging.resolveNoCreate(rel), newEntry);
            case WITHDRAW -> rollbackWithdrawal(journal, i, f, state, target, backup, oldEntry);
            case UPDATE -> rollbackUpdate(journal, i, f, state, target, backup, oldEntry, newEntry);
         }
      }
      if (journalSnapshot(journal).overallState() == MixedTransactionJournal.OverallState.ROLLING_BACK)
         journal.appendOverallTransition(MixedTransactionJournal.OverallState.ROLLED_BACK);
   }

   private static void rollbackEstablishment(MixedTransactionJournal journal, int i, MixedTransactionJournal.FileEntry f,
       MixedTransactionJournal.FileState state, Path target, Path staged, BaselineManifestEntry expected) throws IOException {
      if (state == MixedTransactionJournal.FileState.PREPARING) return;
      boolean exists = exists(target);
      if (exists) {
         requireRegular(target);
         requireDigest(target, expected);
         if (!exists(staged) || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS) || !Files.isSameFile(target, staged))
            throw new IOException("establishment target ownership cannot be proven: " + f.relativePath());
         if (state != MixedTransactionJournal.FileState.ROLLBACK_DELETE_INTENT_DURABLE)
            journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.ROLLBACK_DELETE_INTENT_DURABLE);
         requireDigest(target, expected);
         if (!Files.isSameFile(target, staged)) throw new IOException("establishment target identity changed: " + f.relativePath());
         Files.delete(target);
      } else if (state != MixedTransactionJournal.FileState.ROLLBACK_DELETE_INTENT_DURABLE) {
         journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.ROLLBACK_DELETE_INTENT_DURABLE);
      }
      if (state != MixedTransactionJournal.FileState.ROLLED_BACK_DURABLE)
         journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.ROLLED_BACK_DURABLE);
   }

   private static void rollbackWithdrawal(MixedTransactionJournal journal, int i, MixedTransactionJournal.FileEntry f,
       MixedTransactionJournal.FileState state, Path target, Path backup, BaselineManifestEntry expected) throws IOException {
      if (state == MixedTransactionJournal.FileState.PREPARING || state == MixedTransactionJournal.FileState.ROLLED_BACK_DURABLE) return;
      boolean targetExists = exists(target), backupExists = exists(backup);
      if (!backupExists) {
         if (!targetExists) throw new IOException("withdrawal has neither target nor backup: " + f.relativePath());
         requireDigest(target, expected);
         if (state != MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE)
            throw new IOException("withdrawal backup missing after link phase: " + f.relativePath());
         journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.ROLLED_BACK_DURABLE);
         return;
      }
      requireRegular(backup);
      requireDigest(backup, expected);
      if (targetExists) {
         requireRegular(target);
         if (!Files.isSameFile(target, backup)) throw new IOException("withdrawal target is occupied; refusing to overwrite: " + f.relativePath());
         requireDigest(target, expected);
         if (state == MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE
             || state == MixedTransactionJournal.FileState.BACKUP_LINKED_DURABLE) {
            journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.ROLLED_BACK_DURABLE);
            return;
         }
         if (state == MixedTransactionJournal.FileState.DELETE_INTENT_DURABLE) {
            journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.RESTORE_LINK_INTENT_DURABLE);
            journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.RESTORED_DURABLE);
            journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.ROLLED_BACK_DURABLE);
            return;
         }
         throw new IOException("withdrawal target unexpectedly exists during restore: " + f.relativePath());
      }
      if (state != MixedTransactionJournal.FileState.RESTORE_LINK_INTENT_DURABLE)
         journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.RESTORE_LINK_INTENT_DURABLE);
      Files.createLink(target, backup);
      if (!Files.isSameFile(target, backup)) throw new IOException("restored withdrawal is not linked to backup: " + f.relativePath());
      requireDigest(target, expected);
      journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.RESTORED_DURABLE);
      journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.ROLLED_BACK_DURABLE);
   }

   private static void rollbackUpdate(MixedTransactionJournal journal, int i, MixedTransactionJournal.FileEntry f,
       MixedTransactionJournal.FileState state, Path target, Path backup, BaselineManifestEntry oldEntry,
       BaselineManifestEntry newEntry) throws IOException {
      if (state == MixedTransactionJournal.FileState.PREPARING || state == MixedTransactionJournal.FileState.ROLLED_BACK_DURABLE) return;
      boolean backupExists = exists(backup), targetExists = exists(target);
      if (!backupExists) {
         if (state == MixedTransactionJournal.FileState.ROLLBACK_REPLACE_INTENT_DURABLE && targetExists) {
            requireDigest(target, oldEntry);
            journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.ROLLED_BACK_DURABLE);
            return;
         }
         if (state == MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE && targetExists) {
            requireDigest(target, oldEntry);
            journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.ROLLBACK_REPLACE_INTENT_DURABLE);
            journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.ROLLED_BACK_DURABLE);
            return;
         }
         throw new IOException("update backup missing: " + f.relativePath());
      }
      requireRegular(backup);
      requireDigest(backup, oldEntry);
      if (targetExists && Files.isSameFile(target, backup)) {
         requireDigest(target, oldEntry);
         if (state == MixedTransactionJournal.FileState.ROLLED_BACK_DURABLE) return;
         if (state != MixedTransactionJournal.FileState.ROLLBACK_REPLACE_INTENT_DURABLE)
            journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.ROLLBACK_REPLACE_INTENT_DURABLE);
         journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.ROLLED_BACK_DURABLE);
         return;
      }
      if (!targetExists) throw new IOException("update target missing; refusing uncertain restore: " + f.relativePath());
      requireRegular(target);
      requireDigest(target, newEntry);
      if (state != MixedTransactionJournal.FileState.ROLLBACK_REPLACE_INTENT_DURABLE)
         journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.ROLLBACK_REPLACE_INTENT_DURABLE);
      requireDigest(target, newEntry);
      requireDigest(backup, oldEntry);
      Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      requireDigest(target, oldEntry);
      journal.appendFileTransition(i, f.relativePath(), MixedTransactionJournal.FileState.ROLLED_BACK_DURABLE);
   }

   private ChangeRecoveryResult cleanupAndReturn(Path txDir, RecoveryHandle handle, BaselineBundle bundle, boolean recovered) throws IOException {
      MixedTransactionJournal.ParseResult parsed = MixedTransactionJournal.parse(txDir.resolve("journal"));
      if (!(parsed instanceof MixedTransactionJournal.ParseOk ok)) throw new IOException("journal became invalid before transaction cleanup");
      BaselineBundle b0 = load(ok.snapshot().b0BaselineId(), null);
      BaselineBundle b1 = load(ok.snapshot().b1BaselineId(), txDir.resolve("candidate-baseline"));
      safeDeleteTransactionDir(txDir, store, b0, b1);
      return recovered ? ChangeRecoveryResult.recovered(toReceipt(bundle), List.of()) : ChangeRecoveryResult.rolledBack(toReceipt(bundle), List.of());
   }

   private void removeCurrentNewIfOwned(String b1) throws IOException {
      Path path = store.stateRoot().resolve("CURRENT.new");
      if (!exists(path)) return;
      requireRegular(path);
      byte[] actual = Files.readAllBytes(path);
      if (!java.util.Arrays.equals(actual, (b1 + "\n").getBytes(StandardCharsets.US_ASCII)))
         throw new IOException("CURRENT.new is not exactly the transaction B1 value");
      Files.delete(path);
   }

   static void safeDeleteTransactionDir(Path dir, BaselineBundleStore store, BaselineBundle b0, BaselineBundle b1) throws IOException {
      requireDirectory(dir);
      Path journalPath = dir.resolve("journal");
      MixedTransactionJournal.ParseResult parsed = MixedTransactionJournal.parse(journalPath);
      if (!(parsed instanceof MixedTransactionJournal.ParseOk ok)) throw new IOException("cannot validate transaction journal before cleanup");
      MixedTransactionJournal.Snapshot snapshot = ok.snapshot();
      if (!snapshot.transactionId().equals(dir.getFileName().toString())
          || !snapshot.b0BaselineId().equals(b0.baselineId()) || !snapshot.b1BaselineId().equals(b1.baselineId())
          || !snapshot.b0ManifestDigest().equals(b0.descriptor().manifestDigest())
          || !snapshot.b1ManifestDigest().equals(b1.descriptor().manifestDigest()))
         throw new IOException("transaction journal identity changed before cleanup");
      Map<String, BaselineManifestEntry> oldFiles = entries(b0), newFiles = entries(b1);
      Map<String, BaselineManifestEntry> expected = new HashMap<>();
      newFiles.forEach((path, entry) -> expected.put("staging/" + path, entry));
      for (int i = 0; i < snapshot.files().size(); i++) {
         MixedTransactionJournal.FileEntry file = snapshot.files().get(i);
         if (file.kind() != MixedTransactionJournal.Kind.ESTABLISH)
            expected.put("backup/" + i, oldFiles.get(file.relativePath()));
      }
      expected.put("candidate-baseline/descriptor.kcg-baseline", expectedFile("candidate-baseline/descriptor.kcg-baseline", b1.descriptorBytes(), b1.descriptor().manifest().get(0).artifactId()));
      expected.put("candidate-baseline/source.sir", expectedFile("candidate-baseline/source.sir", b1.sourceBytes(), b1.descriptor().manifest().get(0).artifactId()));
      expected.put("candidate-baseline/graph.kcg-psg", expectedFile("candidate-baseline/graph.kcg-psg", b1.snapshotBytes(), b1.descriptor().manifest().get(0).artifactId()));
      BaselineBundleStore.LoadResult candidate = store.loadBundleFromDir(dir.resolve("candidate-baseline"), b1.baselineId());
      if (!(candidate instanceof BaselineBundleStore.LoadResult.Success)) throw new IOException("candidate bundle changed before cleanup");

      java.util.Set<String> expectedDirectories = new java.util.HashSet<>(List.of("backup", "staging", "candidate-baseline"));
      for (String file : expected.keySet()) {
         Path relative = Path.of(file).getParent();
         while (relative != null) { expectedDirectories.add(relative.toString().replace(dir.getFileSystem().getSeparator(), "/")); relative = relative.getParent(); }
      }
      List<Path> paths;
      try (Stream<Path> walk = Files.walk(dir)) { paths = walk.sorted(java.util.Comparator.reverseOrder()).toList(); }
      for (Path path : paths) {
         if (Files.isSymbolicLink(path)) throw new IOException("transaction contains symlink: " + path);
         if (path.equals(dir)) continue;
         String rel = dir.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
         if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!expectedDirectories.contains(rel)) throw new IOException("unexpected transaction directory: " + rel);
         } else {
            if (rel.equals("journal")) continue;
            BaselineManifestEntry expectedEntry = expected.get(rel);
            if (expectedEntry == null) throw new IOException("unexpected transaction file: " + rel);
            requireDigest(path, expectedEntry);
         }
      }
      requireRegular(journalPath);
      for (String top : List.of("backup", "staging", "candidate-baseline")) {
         Path path = dir.resolve(top);
         if (!exists(path)) throw new IOException("missing expected transaction artifact: " + top);
         requireDirectory(path);
      }
      for (Path path : paths) Files.delete(path);
   }

   private static BaselineManifestEntry expectedFile(String path, byte[] bytes, io.kcg.sir.lowering.api.LoweredNodeId artifactId) {
      return new BaselineManifestEntry(path, bytes.length, Sha256.hexDigest(bytes), artifactId, Optional.empty());
   }

   private BaselineBundle load(String id, Path fallback) throws IOException {
      BaselineBundleStore.LoadResult result = store.loadBundle(id);
      if (result instanceof BaselineBundleStore.LoadResult.Failure && fallback != null) result = store.loadBundleFromDir(fallback, id);
      if (result instanceof BaselineBundleStore.LoadResult.Failure f) throw new IOException(f.message());
      return ((BaselineBundleStore.LoadResult.Success) result).bundle();
   }

   private static void verifyOutput(BaselineBundle bundle) throws IOException {
      List<ChangeExecutionDiagnostic> errors = OutputManifestVerifier.verify(bundle.descriptor().boundOutputRoot(), bundle.descriptor().manifest(), ChangeExecutionStage.RECOVERY);
      if (!errors.isEmpty()) throw new IOException("output manifest mismatch: " + errors);
   }

   private static MixedTransactionJournal journal(MixedTransactionJournal.Snapshot s, Path dir) {
      return new MixedTransactionJournal(dir, s.transactionId(), s.boundOutputRoot(), s.b0BaselineId(), s.b1BaselineId(),
          s.b0ManifestDigest(), s.b1ManifestDigest(), s.planDigest(), s.subjectSymbol(), s.files());
   }

   private static MixedTransactionJournal.Snapshot journalSnapshot(MixedTransactionJournal journal) throws IOException {
      MixedTransactionJournal.ParseResult parsed = MixedTransactionJournal.parse(journal.journalPath());
      if (parsed instanceof MixedTransactionJournal.ParseFailure f) throw new IOException(f.message());
      return ((MixedTransactionJournal.ParseOk) parsed).snapshot();
   }

   private Optional<ChangeBaselineReceipt> receipt(Optional<String> id) {
      if (id.isEmpty()) return Optional.empty();
      BaselineBundleStore.LoadResult loaded = store.loadBundle(id.get());
      return loaded instanceof BaselineBundleStore.LoadResult.Success ok ? Optional.of(toReceipt(ok.bundle())) : Optional.empty();
   }

   private static Map<String, BaselineManifestEntry> entries(BaselineBundle bundle) {
      Map<String, BaselineManifestEntry> result = new HashMap<>();
      for (BaselineManifestEntry e : bundle.descriptor().manifest()) result.put(e.relativePath(), e);
      return result;
   }

   private static boolean exists(Path path) throws IOException {
      try { Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return true; }
      catch (NoSuchFileException e) { return false; }
   }
   private static void requireDirectory(Path path) throws IOException {
      BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (a.isSymbolicLink() || !a.isDirectory()) throw new IOException("not a real directory: " + path);
   }
   private static void requireDirectoryIfPresent(Path path) throws IOException { if (exists(path)) requireDirectory(path); }
   private static void requireRegular(Path path) throws IOException {
      BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (a.isSymbolicLink() || !a.isRegularFile()) throw new IOException("not a regular non-symlink file: " + path);
   }
   private static void requireDigest(Path path, BaselineManifestEntry expected) throws IOException {
      requireRegular(path);
      byte[] bytes = Files.readAllBytes(path);
      if (bytes.length != expected.byteCount() || !Sha256.hexDigest(bytes).equals(expected.sha256Hex()))
         throw new IOException("unexpected file bytes; preserving path: " + expected.relativePath());
   }
   private static ChangeBaselineReceipt toReceipt(BaselineBundle bundle) {
      BaselineDescriptor d = bundle.descriptor();
      return new ChangeBaselineReceipt(d.formatVersion(), bundle.baselineId(), d.toBaseRevision(), d.boundOutputRoot(), d.targetId(), d.loweredIrVersion(), d.manifestDigest());
   }
   private static ChangeRecoveryResult required(RecoveryHandle handle, ChangeBaselineReceipt receipt, String message) {
      ChangeExecutionDiagnostic diagnostic = new ChangeExecutionDiagnostic("SIR-APP-CHANGE-RECOVERY-001", ChangeExecutionStage.RECOVERY,
          ExecutionSeverity.ERROR, message, Optional.empty());
      return ChangeRecoveryResult.recoveryRequired(handle, Optional.ofNullable(receipt), List.of(diagnostic));
   }
}
