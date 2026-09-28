package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.bundle.BaselineBundle;
import io.kcg.sir.application.internal.bundle.BaselineBundleStore;
import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Package-private transaction seam for Q17: file commit and ordered B1 publication; recovery is intentionally separate. */
public final class MixedTransactionCore {
   private final BaselineBundleStore store;
   private final Path outputRoot;
   private final BaselineBundle b0;
   private final BaselineBundle b1;
   private final String planDigest;
   private final String subjectSymbol;
   private final Map<String, byte[]> candidateBytes;
   private final Observer observer;
   private final String transactionId = UUID.randomUUID().toString().replace("-", "");
   private final Path transactionDir;
   private final Path stagingDir;
   private final Path backupDir;
   private final Path candidateBundleDir;
   private MixedTransactionJournal journal;
   private List<MixedTransactionJournal.FileEntry> files;

   public MixedTransactionCore(BaselineBundleStore store, Path outputRoot, BaselineBundle b0, BaselineBundle b1,
                         String planDigest, String subjectSymbol, Map<String, byte[]> candidateBytes) {
      this(store, outputRoot, b0, b1, planDigest, subjectSymbol, candidateBytes, Observer.NONE);
   }

   MixedTransactionCore(BaselineBundleStore store, Path outputRoot, BaselineBundle b0, BaselineBundle b1,
                        String planDigest, String subjectSymbol, Map<String, byte[]> candidateBytes, Observer observer) {
      this.store = Objects.requireNonNull(store);
      this.outputRoot = Objects.requireNonNull(outputRoot).toAbsolutePath().normalize();
      this.b0 = Objects.requireNonNull(b0);
      this.b1 = Objects.requireNonNull(b1);
      this.planDigest = Objects.requireNonNull(planDigest);
      this.subjectSymbol = Objects.requireNonNull(subjectSymbol);
      this.candidateBytes = copyBytes(candidateBytes);
      this.observer = Objects.requireNonNull(observer);
      this.transactionDir = store.stateRoot().resolve("transactions").resolve(transactionId);
      this.stagingDir = transactionDir.resolve("staging");
      this.backupDir = transactionDir.resolve("backup");
      this.candidateBundleDir = transactionDir.resolve("candidate-baseline");
   }

   public Result execute() {
      try {
         this.files = deriveFiles();
         preflight();
         prepare();
         observer.afterPrepared(transactionDir);
         commitFiles();
       publish();
       journal.appendOverallTransition(MixedTransactionJournal.OverallState.CLEANUP_PENDING);
       journal.appendOverallTransition(MixedTransactionJournal.OverallState.COMPLETED);
        MixedTransactionRecoveryEngine.safeDeleteTransactionDir(transactionDir, store, b0, b1);
       return new Result(true, transactionId, null, null);
      } catch (Exception e) {
         return new Result(false, transactionId, journal == null ? null : journal.journalPath(), e.getClass().getSimpleName() + ": " + e.getMessage());
      }
   }

   private List<MixedTransactionJournal.FileEntry> deriveFiles() throws IOException {
      Map<String, BaselineManifestEntry> base = manifest(b0);
      Map<String, BaselineManifestEntry> candidate = manifest(b1);
      List<MixedTransactionJournal.FileEntry> entries = new ArrayList<>();
      for (String path : base.keySet()) {
         BaselineManifestEntry b0Entry = base.get(path);
         BaselineManifestEntry b1Entry = candidate.get(path);
         if (b1Entry == null) entries.add(new MixedTransactionJournal.FileEntry(path, MixedTransactionJournal.Kind.WITHDRAW));
         else if (b0Entry.byteCount() != b1Entry.byteCount() || !b0Entry.sha256Hex().equals(b1Entry.sha256Hex()))
            entries.add(new MixedTransactionJournal.FileEntry(path, MixedTransactionJournal.Kind.UPDATE));
         else if (!b0Entry.artifactId().equals(b1Entry.artifactId()) || !b0Entry.ownerSymbol().equals(b1Entry.ownerSymbol()))
            throw new IOException("unchanged path changed identity: " + path);
      }
      for (String path : candidate.keySet()) if (!base.containsKey(path))
         entries.add(new MixedTransactionJournal.FileEntry(path, MixedTransactionJournal.Kind.ESTABLISH));
      entries.sort(Comparator.comparing(MixedTransactionJournal.FileEntry::relativePath));
      if (entries.isEmpty()) throw new IOException("rename candidate has no file changes");
      return List.copyOf(entries);
   }

   private void preflight() throws IOException, SafeTargetResolver.UnsafePathException {
      BaselineBundleStore.CurrentReadResult current = store.readCurrent();
      if (!(current instanceof BaselineBundleStore.CurrentReadResult.Present present)
          || !present.baselineId().equals(b0.baselineId())) throw new IOException("CURRENT does not identify B0");
      if (!outputRoot.equals(b0.descriptor().boundOutputRoot()) || !outputRoot.equals(b1.descriptor().boundOutputRoot()))
         throw new IOException("output root is not bound to both bundles");
      Map<String, BaselineManifestEntry> base = manifest(b0);
      Map<String, BaselineManifestEntry> candidate = manifest(b1);
      if (!candidateBytes.keySet().equals(candidate.keySet())) throw new IOException("candidate bytes do not exactly cover B1 manifest");
      for (Map.Entry<String, BaselineManifestEntry> entry : candidate.entrySet()) {
         byte[] bytes = candidateBytes.get(entry.getKey());
         if (bytes.length != entry.getValue().byteCount() || !Sha256.hexDigest(bytes).equals(entry.getValue().sha256Hex()))
            throw new IOException("candidate bytes disagree with B1 manifest: " + entry.getKey());
      }
      SafeTargetResolver resolver = SafeTargetResolver.forRoot(outputRoot);
      for (MixedTransactionJournal.FileEntry file : files) {
         if (file.kind() == MixedTransactionJournal.Kind.ESTABLISH) {
            Path target = resolver.resolveNoCreate(file.relativePath());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("establishment path is occupied: " + file.relativePath());
         } else {
            BaselineManifestEntry expected = base.get(file.relativePath());
            resolver.resolveAndVerifyB0(file.relativePath(), expected);
         }
      }
      if (!Files.getFileStore(store.stateRoot()).equals(Files.getFileStore(outputRoot)))
         throw new IOException("stateRoot and outputRoot must share a FileStore");
   }

   private void prepare() throws IOException {
      Files.createDirectories(transactionDir);
      Files.createDirectory(stagingDir);
      Files.createDirectory(backupDir);
      Files.createDirectory(candidateBundleDir);
      journal = new MixedTransactionJournal(transactionDir, transactionId, outputRoot, b0.baselineId(), b1.baselineId(),
          b0.descriptor().manifestDigest(), b1.descriptor().manifestDigest(), planDigest, subjectSymbol, files);
      journal.create();
      for (Map.Entry<String, byte[]> entry : candidateBytes.entrySet()) {
         Path staged = stagingDir.resolve(entry.getKey());
         Files.createDirectories(staged.getParent());
         Files.write(staged, entry.getValue(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
      }
      BaselineBundleStore.LoadResult stagedBundle = store.stageCandidateBundle(b1, candidateBundleDir);
      if (stagedBundle instanceof BaselineBundleStore.LoadResult.Failure failure)
         throw new IOException("failed to stage candidate bundle: " + failure.message());
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.PREPARED);
   }

   private void commitFiles() throws IOException, SafeTargetResolver.UnsafePathException {
      SafeTargetResolver output = SafeTargetResolver.forRoot(outputRoot);
      SafeTargetResolver backup = SafeTargetResolver.forRoot(backupDir);
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.COMMITTING);
      Map<String, BaselineManifestEntry> base = manifest(b0);
      Map<String, BaselineManifestEntry> candidate = manifest(b1);
      // Create and durably record every old-file backup before the first destructive operation.
      for (int i = 0; i < files.size(); i++) {
         MixedTransactionJournal.FileEntry file = files.get(i);
         if (file.kind() == MixedTransactionJournal.Kind.ESTABLISH) continue;
         Path target = output.resolveAndVerifyB0(file.relativePath(), base.get(file.relativePath()));
         Path backupPath = backup.resolveForCreate(Integer.toString(i));
         journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE);
         Files.createLink(backupPath, target);
         if (!Files.isSameFile(target, backupPath)) throw new IOException("backup link identity mismatch: " + file.relativePath());
         verifyBytes(backupPath, base.get(file.relativePath()));
         journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.BACKUP_LINKED_DURABLE);
      }
      for (int i = 0; i < files.size(); i++) {
         MixedTransactionJournal.FileEntry file = files.get(i);
         Path target = output.resolveNoCreate(file.relativePath());
         Path staged = stagingDir.resolve(file.relativePath());
         if (file.kind() == MixedTransactionJournal.Kind.UPDATE) {
            Path backupPath = backup.resolveNoCreate(Integer.toString(i));
            BaselineManifestEntry expected = base.get(file.relativePath());
            requireBackupStillRepresentsB0(target, backupPath, expected, file.relativePath());
            journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.REPLACE_INTENT_DURABLE);
            requireBackupStillRepresentsB0(target, backupPath, expected, file.relativePath());
            verifyBytes(staged, candidate.get(file.relativePath()));
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.REPLACED_DURABLE);
         } else if (file.kind() == MixedTransactionJournal.Kind.WITHDRAW) {
            Path backupPath = backup.resolveNoCreate(Integer.toString(i));
            requireBackupStillRepresentsB0(target, backupPath, base.get(file.relativePath()), file.relativePath());
            journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.DELETE_INTENT_DURABLE);
            requireBackupStillRepresentsB0(target, backupPath, base.get(file.relativePath()), file.relativePath());
            Files.delete(target);
            journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.DELETED_DURABLE);
         } else {
            verifyBytes(staged, candidate.get(file.relativePath()));
            journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.CREATE_INTENT_DURABLE);
            // createLink is atomic and exclusive, unlike ATOMIC_MOVE on providers that replace an occupant.
            Files.createLink(target, staged);
            journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.CREATED_DURABLE);
         }
      }
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.FILES_COMMITTED);
   }

   private void publish() throws IOException {
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.PUBLISHING);
      BaselineBundleStore.LoadResult published = store.stageBundle(b1);
      if (published instanceof BaselineBundleStore.LoadResult.Failure failure)
         throw new IOException("B1 bundle publication failed: " + failure.message());
      observer.afterBundlePublished(store.bundleDir(b1.baselineId()));
      Path currentNew = store.stateRoot().resolve("CURRENT.new");
      if (Files.exists(currentNew, LinkOption.NOFOLLOW_LINKS)) throw new IOException("CURRENT.new is already occupied");
      store.writeCurrentNew(b1.baselineId());
      observer.afterCurrentNew(store.stateRoot().resolve(BaselineBundleStore.CURRENT_FILE).resolveSibling("CURRENT.new"));
      store.moveCurrentNewToCurrent();
      observer.afterCurrentPublished(store.currentFile());
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.BASELINE_PUBLISHED);
   }

   private static void requireBackupStillRepresentsB0(Path target, Path backup, BaselineManifestEntry expected, String rel) throws IOException {
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
          || !Files.isSameFile(target, backup)) throw new IOException("target no longer has its B0 backup identity: " + rel);
      verifyBytes(backup, expected);
   }

   private static void verifyBytes(Path path, BaselineManifestEntry expected) throws IOException {
      byte[] bytes = Files.readAllBytes(path);
      if (bytes.length != expected.byteCount() || !Sha256.hexDigest(bytes).equals(expected.sha256Hex()))
         throw new IOException("file bytes differ from manifest: " + expected.relativePath());
   }

   private static Map<String, BaselineManifestEntry> manifest(BaselineBundle bundle) {
      Map<String, BaselineManifestEntry> result = new HashMap<>();
      for (BaselineManifestEntry entry : bundle.descriptor().manifest()) result.put(entry.relativePath(), entry);
      return result;
   }

   private static Map<String, byte[]> copyBytes(Map<String, byte[]> input) {
      Map<String, byte[]> copy = new HashMap<>();
      Objects.requireNonNull(input).forEach((path, bytes) -> copy.put(Objects.requireNonNull(path), Objects.requireNonNull(bytes).clone()));
      return Map.copyOf(copy);
   }

   public record Result(boolean published, String transactionId, Path journalPath, String error) {}

   interface Observer {
      Observer NONE = new Observer() {};
      default void afterPrepared(Path transactionDir) throws IOException {}
      default void afterBundlePublished(Path path) throws IOException {}
      default void afterCurrentNew(Path path) throws IOException {}
      default void afterCurrentPublished(Path path) throws IOException {}
   }
}
