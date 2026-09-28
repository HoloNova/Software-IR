package io.kcg.sir.application.internal.state;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ChangeExecutionBaselineFormatVersion;
import io.kcg.sir.application.api.ChangeRecoveryResult;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.SirCompiler;
import io.kcg.sir.application.internal.bundle.BaselineBundle;
import io.kcg.sir.application.internal.bundle.BaselineBundleStore;
import io.kcg.sir.application.internal.bundle.BaselineDescriptor;
import io.kcg.sir.application.internal.bundle.BaselineDescriptorCodec;
import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MixedTransactionCoreTest {
   private static final List<CrashScenario> CRASH_SCENARIOS = List.of(
       new CrashScenario("update-backup-intent", MixedTransactionJournal.Kind.UPDATE, MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE, MixedTransactionJournal.OverallState.COMMITTING),
       new CrashScenario("withdraw-backup-intent", MixedTransactionJournal.Kind.WITHDRAW, MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE, MixedTransactionJournal.OverallState.COMMITTING),
       new CrashScenario("withdraw-delete-intent", MixedTransactionJournal.Kind.WITHDRAW, MixedTransactionJournal.FileState.DELETE_INTENT_DURABLE, MixedTransactionJournal.OverallState.COMMITTING),
       new CrashScenario("update-replace-intent", MixedTransactionJournal.Kind.UPDATE, MixedTransactionJournal.FileState.REPLACE_INTENT_DURABLE, MixedTransactionJournal.OverallState.COMMITTING),
       new CrashScenario("update-replaced", MixedTransactionJournal.Kind.UPDATE, MixedTransactionJournal.FileState.REPLACED_DURABLE, MixedTransactionJournal.OverallState.COMMITTING),
       new CrashScenario("withdraw-deleted", MixedTransactionJournal.Kind.WITHDRAW, MixedTransactionJournal.FileState.DELETED_DURABLE, MixedTransactionJournal.OverallState.COMMITTING),
       new CrashScenario("update-rollback-move-done", MixedTransactionJournal.Kind.UPDATE, MixedTransactionJournal.FileState.ROLLBACK_REPLACE_INTENT_DURABLE, MixedTransactionJournal.OverallState.ROLLING_BACK),
       new CrashScenario("create-intent", MixedTransactionJournal.Kind.ESTABLISH, MixedTransactionJournal.FileState.CREATE_INTENT_DURABLE, MixedTransactionJournal.OverallState.COMMITTING),
       new CrashScenario("establish-created", MixedTransactionJournal.Kind.ESTABLISH, MixedTransactionJournal.FileState.CREATED_DURABLE, MixedTransactionJournal.OverallState.COMMITTING));
   private static final List<String> FOREIGN_ARTIFACTS = List.of("backup/foreign", "staging/src/foreign", "candidate-baseline/foreign");
   private static final List<String> INJECTED_CRASH_POINTS = List.of("afterPrepared", "afterBundlePublished", "afterCurrentNew", "afterCurrentPublished");
   private static final List<Boolean> ARTIFACT_VARIANTS = List.of(false, true);
   @TempDir Path temp;

   @Test void mixedCommitPublishesOnlyAfterAllFilesAndCleansTransaction() throws Exception {
      Fixture f = fixture(temp.resolve("success"));
      Path state = temp.resolve("success/state");
      BaselineBundleStore store = new BaselineBundleStore(state);
      Files.createDirectories(state);
      store.writeCurrentAtomic(f.b0().baselineId());
      List<String> publicationObservations = new ArrayList<>();
      MixedTransactionCore.Observer observer = new MixedTransactionCore.Observer() {
         @Override public void afterBundlePublished(Path bundle) {
            publicationObservations.add("bundle:" + Files.exists(bundle.resolve(BaselineBundleStore.DESCRIPTOR_FILE))
                + ":current=" + ((BaselineBundleStore.CurrentReadResult.Present) store.readCurrent()).baselineId());
         }
         @Override public void afterCurrentNew(Path currentNew) {
            publicationObservations.add("new:" + Files.exists(currentNew)
                + ":current=" + ((BaselineBundleStore.CurrentReadResult.Present) store.readCurrent()).baselineId());
         }
         @Override public void afterCurrentPublished(Path current) {
            publicationObservations.add("current:" + ((BaselineBundleStore.CurrentReadResult.Present) store.readCurrent()).baselineId());
         }
      };
      MixedTransactionCore.Result result = new MixedTransactionCore(store, f.output(), f.b0(), f.b1(), "a".repeat(64),
          "sir://synthetic-subject", f.candidateBytes(), observer).execute();
      assertTrue(result.published(), result.error());
      assertEquals(List.of("bundle:true:current=" + f.b0().baselineId(), "new:true:current=" + f.b0().baselineId(),
          "current:" + f.b1().baselineId()), publicationObservations);
      assertEquals(new BaselineBundleStore.CurrentReadResult.Present(f.b1().baselineId()), store.readCurrent());
      for (BaselineManifestEntry entry : f.b1().descriptor().manifest()) {
         byte[] bytes = Files.readAllBytes(f.output().resolve(entry.relativePath()));
         assertEquals(entry.byteCount(), bytes.length, entry.relativePath());
         assertEquals(entry.sha256Hex(), Sha256.hexDigest(bytes), entry.relativePath());
      }
      assertFalse(Files.exists(f.output().resolve(f.withdrawnPath())));
      assertFalse(Files.exists(state.resolve("transactions").resolve(result.transactionId())),
          "successful B1 publication must remove its terminal V4 journal and transaction artifacts");
   }

   @Test void invalidCandidateManifestBytesFailBeforeOutputOrTransactionMutation() throws Exception {
      Fixture f = fixture(temp.resolve("preflight"));
      Path state = temp.resolve("preflight/state");
      Files.createDirectories(state);
      new BaselineBundleStore(state).writeCurrentAtomic(f.b0().baselineId());
      Map<String, byte[]> invalid = new HashMap<>(f.candidateBytes());
      invalid.put(f.updatedPath(), "not the declared B1 bytes".getBytes(StandardCharsets.UTF_8));
      MixedTransactionCore.Result result = new MixedTransactionCore(new BaselineBundleStore(state), f.output(), f.b0(), f.b1(),
          "a".repeat(64), "sir://synthetic-subject", invalid).execute();
      assertFalse(result.published());
      assertTrue(result.error().contains("candidate bytes disagree"));
      assertTrue(Files.exists(f.output().resolve(f.withdrawnPath())));
      assertFalse(Files.exists(state.resolve("transactions")));
      assertEquals(new BaselineBundleStore.CurrentReadResult.Present(f.b0().baselineId()), new BaselineBundleStore(state).readCurrent());
   }

   @Test void recoveryAtPreparedB0CleansTransactionAndIsIdempotent() throws Exception {
      Fixture f = fixture(temp.resolve("prepared-recovery"));
      Path state = temp.resolve("prepared-recovery/state");
      Files.createDirectories(state);
      BaselineBundleStore store = new BaselineBundleStore(state);
      store.stageBundle(f.b0());
      store.writeCurrentAtomic(f.b0().baselineId());
      MixedTransactionCore.Observer failAfterPrepare = new MixedTransactionCore.Observer() {
         @Override public void afterPrepared(Path transactionDir) throws IOException { throw new IOException("injected stop"); }
      };
      MixedTransactionCore.Result failed = new MixedTransactionCore(store, f.output(), f.b0(), f.b1(), "a".repeat(64),
          "sir://synthetic-subject", f.candidateBytes(), failAfterPrepare).execute();
       assertFalse(failed.published());
       assertTrue(failed.error().contains("injected stop"), "afterPrepared injection must stop execution: " + failed.error());
       assertNotNull(failed.journalPath());
      MixedTransactionJournal.ParseOk journal = assertInstanceOf(MixedTransactionJournal.ParseOk.class,
          MixedTransactionJournal.parse(failed.journalPath()));
      ChangeRecoveryResult recovered = new MixedTransactionRecoveryEngine(store, f.output()).execute(journal.snapshot(),
          Optional.of(f.b0().baselineId()));
      assertInstanceOf(ChangeRecoveryResult.RolledBack.class, recovered, recovered.toString());
      assertFalse(Files.exists(failed.journalPath().getParent()));
      ChangeRecoveryResult repeated = new ChangeRecoveryEngine(store, f.output(), io.kcg.sir.application.api.RecoveryHandle.any()).execute();
      assertInstanceOf(ChangeRecoveryResult.Recovered.class, repeated);
      assertEquals(new BaselineBundleStore.CurrentReadResult.Present(f.b0().baselineId()), store.readCurrent());
   }

   @Test void recoveryRefusesToCleanUnknownBytesFromPreparedTransaction() throws Exception {
      Fixture f = fixture(temp.resolve("prepared-external"));
      Path state = temp.resolve("prepared-external/state");
      Files.createDirectories(state);
      BaselineBundleStore store = new BaselineBundleStore(state);
      store.stageBundle(f.b0());
      store.writeCurrentAtomic(f.b0().baselineId());
      byte[] external = "external edit".getBytes(StandardCharsets.UTF_8);
      MixedTransactionCore.Observer mutateAfterPrepare = new MixedTransactionCore.Observer() {
         @Override public void afterPrepared(Path transactionDir) throws IOException {
            Files.write(f.output().resolve(f.updatedPath()), external);
            throw new IOException("injected stop");
         }
      };
      MixedTransactionCore.Result failed = new MixedTransactionCore(store, f.output(), f.b0(), f.b1(), "a".repeat(64),
          "sir://synthetic-subject", f.candidateBytes(), mutateAfterPrepare).execute();
      MixedTransactionJournal.ParseOk journal = assertInstanceOf(MixedTransactionJournal.ParseOk.class,
          MixedTransactionJournal.parse(failed.journalPath()));
      ChangeRecoveryResult result = new MixedTransactionRecoveryEngine(store, f.output()).execute(journal.snapshot(),
          Optional.of(f.b0().baselineId()));
      assertInstanceOf(ChangeRecoveryResult.RecoveryRequired.class, result);
      assertEquals("external edit", Files.readString(f.output().resolve(f.updatedPath())));
      assertTrue(Files.exists(failed.journalPath()));
   }

   @Test void recoveryCompletesInterruptedBackupReplaceAndCreateStates() throws Exception {
       assertEquals(9, CRASH_SCENARIOS.size(), "crash-state matrix size is pinned");
       for (CrashScenario crash : CRASH_SCENARIOS) {
          String scenario = crash.name();
          Fixture f = fixture(temp.resolve(scenario));
         Prepared prepared = prepared(f, temp.resolve(scenario + "/state"));
         MixedTransactionJournal.ParseOk parsed = assertInstanceOf(MixedTransactionJournal.ParseOk.class,
             MixedTransactionJournal.parse(prepared.result().journalPath()));
         MixedTransactionJournal.Snapshot snapshot = parsed.snapshot();
         MixedTransactionJournal journal = journal(prepared.result().journalPath().getParent(), snapshot);
         journal.appendOverallTransition(MixedTransactionJournal.OverallState.COMMITTING);
         MixedTransactionJournal.Kind kind;
         String path;
         if (scenario.equals("create-intent") || scenario.equals("establish-created")) {
            kind = MixedTransactionJournal.Kind.ESTABLISH; path = f.establishedPath();
         } else if (scenario.contains("withdraw")) {
            kind = MixedTransactionJournal.Kind.WITHDRAW; path = f.withdrawnPath();
         } else {
            kind = MixedTransactionJournal.Kind.UPDATE; path = f.updatedPath();
         }
         int index = snapshot.files().stream().map(MixedTransactionJournal.FileEntry::relativePath).toList().indexOf(path);
         Path txDir = prepared.result().journalPath().getParent();
         Path target = f.output().resolve(path), backup = txDir.resolve("backup").resolve(Integer.toString(index));
         boolean create = scenario.equals("create-intent") || scenario.equals("establish-created");
         if (!create) {
            journal.appendFileTransition(index, path, MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE);
            if (!scenario.equals("update-backup-intent") && !scenario.equals("withdraw-backup-intent")) {
               Files.createLink(backup, target);
               journal.appendFileTransition(index, path, MixedTransactionJournal.FileState.BACKUP_LINKED_DURABLE);
            }
         }
         if (scenario.equals("update-replace-intent") || scenario.equals("update-replaced")
             || scenario.equals("update-rollback-move-done")) {
            journal.appendFileTransition(index, path, MixedTransactionJournal.FileState.REPLACE_INTENT_DURABLE);
            if (!scenario.equals("update-replace-intent")) {
               Path staged = txDir.resolve("staging").resolve(path);
               Files.move(staged, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
               journal.appendFileTransition(index, path, MixedTransactionJournal.FileState.REPLACED_DURABLE);
            }
            if (scenario.equals("update-rollback-move-done")) {
               journal.appendOverallTransition(MixedTransactionJournal.OverallState.ROLLING_BACK);
               journal.appendFileTransition(index, path, MixedTransactionJournal.FileState.ROLLBACK_REPLACE_INTENT_DURABLE);
               Files.move(backup, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
         } else if (scenario.equals("withdraw-delete-intent") || scenario.equals("withdraw-deleted")) {
            journal.appendFileTransition(index, path, MixedTransactionJournal.FileState.DELETE_INTENT_DURABLE);
            if (scenario.equals("withdraw-deleted")) {
               Files.delete(target);
               journal.appendFileTransition(index, path, MixedTransactionJournal.FileState.DELETED_DURABLE);
            }
         } else if (create) {
            journal.appendFileTransition(index, path, MixedTransactionJournal.FileState.CREATE_INTENT_DURABLE);
            if (scenario.equals("establish-created")) {
               Path staged = txDir.resolve("staging").resolve(path);
               Files.createLink(target, staged);
               journal.appendFileTransition(index, path, MixedTransactionJournal.FileState.CREATED_DURABLE);
            }
         }
          parsed = assertInstanceOf(MixedTransactionJournal.ParseOk.class, MixedTransactionJournal.parse(journal.journalPath()));
          MixedTransactionJournal.Snapshot crashImage = parsed.snapshot();
          assertEquals(crash.overallState(), crashImage.overallState(), scenario + " overall state");
          int targetIndex = crashImage.files().stream().map(MixedTransactionJournal.FileEntry::relativePath).toList().indexOf(path);
          assertTrue(targetIndex >= 0, scenario + " target journal entry exists");
          assertEquals(crash.kind(), crashImage.files().get(targetIndex).kind(), scenario + " target kind");
          assertEquals(crash.fileState(), crashImage.fileStates().get(targetIndex), scenario + " targeted file state");
          for (int untouched = 0; untouched < crashImage.files().size(); untouched++) {
             if (untouched != targetIndex) assertEquals(MixedTransactionJournal.FileState.PREPARING,
                 crashImage.fileStates().get(untouched), scenario + " untouched " + crashImage.files().get(untouched).relativePath());
          }
          ChangeRecoveryResult recovered = new MixedTransactionRecoveryEngine(prepared.store(), f.output()).execute(crashImage, Optional.of(f.b0().baselineId()));
         assertInstanceOf(ChangeRecoveryResult.RolledBack.class, recovered, scenario + ": " + recovered);
         assertB0Exact(f, prepared.store(), txDir, scenario);
         ChangeRecoveryResult repeated = new ChangeRecoveryEngine(prepared.store(), f.output(), io.kcg.sir.application.api.RecoveryHandle.any()).execute();
         assertInstanceOf(ChangeRecoveryResult.Recovered.class, repeated, scenario + " repeat: " + repeated);
         assertB0Exact(f, prepared.store(), txDir, scenario + " repeat");
      }
   }

   @Test void recoveryAfterFilesCommittedBeforePublicationRestoresExactB0() throws Exception {
      Fixture f = fixture(temp.resolve("files-committed"));
      Prepared prepared = prepared(f, temp.resolve("files-committed/state"));
      MixedTransactionJournal.Snapshot snapshot = assertInstanceOf(MixedTransactionJournal.ParseOk.class,
          MixedTransactionJournal.parse(prepared.result().journalPath())).snapshot();
      MixedTransactionJournal journal = journal(prepared.result().journalPath().getParent(), snapshot);
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.COMMITTING);
      Path txDir = prepared.result().journalPath().getParent();
      for (int i = 0; i < snapshot.files().size(); i++) {
         MixedTransactionJournal.FileEntry file = snapshot.files().get(i);
         Path target = f.output().resolve(file.relativePath());
         Path backup = txDir.resolve("backup").resolve(Integer.toString(i));
         if (file.kind() != MixedTransactionJournal.Kind.ESTABLISH) {
            journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE);
            Files.createLink(backup, target);
            journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.BACKUP_LINKED_DURABLE);
         }
      }
      for (int i = 0; i < snapshot.files().size(); i++) {
         MixedTransactionJournal.FileEntry file = snapshot.files().get(i);
         Path target = f.output().resolve(file.relativePath());
         Path staged = txDir.resolve("staging").resolve(file.relativePath());
         if (file.kind() == MixedTransactionJournal.Kind.UPDATE) {
            journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.REPLACE_INTENT_DURABLE);
            Files.move(staged, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.REPLACED_DURABLE);
         } else if (file.kind() == MixedTransactionJournal.Kind.WITHDRAW) {
            journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.DELETE_INTENT_DURABLE);
            Files.delete(target);
            journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.DELETED_DURABLE);
         } else {
            journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.CREATE_INTENT_DURABLE);
            Files.createLink(target, staged);
            journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.CREATED_DURABLE);
         }
      }
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.FILES_COMMITTED);
      ChangeRecoveryResult recovered = new MixedTransactionRecoveryEngine(prepared.store(), f.output()).execute(
          assertInstanceOf(MixedTransactionJournal.ParseOk.class, MixedTransactionJournal.parse(journal.journalPath())).snapshot(),
          Optional.of(f.b0().baselineId()));
      assertInstanceOf(ChangeRecoveryResult.RolledBack.class, recovered, recovered.toString());
      assertB0Exact(f, prepared.store(), txDir, "FILES_COMMITTED before publication");
      ChangeRecoveryResult repeated = new ChangeRecoveryEngine(prepared.store(), f.output(), io.kcg.sir.application.api.RecoveryHandle.any()).execute();
      assertInstanceOf(ChangeRecoveryResult.Recovered.class, repeated, repeated.toString());
      assertB0Exact(f, prepared.store(), txDir, "FILES_COMMITTED repeat");
   }

   @Test void recoveryHandlesPublicationCrashBoundariesInBothDirections() throws Exception {
       assertEquals(4, INJECTED_CRASH_POINTS.size(), "injected-point inventory size is pinned");
       assertObserverCrashRecovered("after-bundle", "bundle", "afterBundlePublished", false);
       assertObserverCrashRecovered("after-current-new", "current-new", "afterCurrentNew", false);
       assertObserverCrashRecovered("after-current-published", "current-published", "afterCurrentPublished", true);
   }

    private void assertObserverCrashRecovered(String name, String crashPoint, String marker, boolean expectB1) throws Exception {
      Fixture f = fixture(temp.resolve(name));
      Path state = temp.resolve(name + "/state");
      Files.createDirectories(state);
      BaselineBundleStore store = new BaselineBundleStore(state);
      store.stageBundle(f.b0());
      store.writeCurrentAtomic(f.b0().baselineId());
       Path[] txDir = new Path[1];
       List<String> fired = new ArrayList<>();
       MixedTransactionCore.Observer observer = new MixedTransactionCore.Observer() {
          @Override public void afterPrepared(Path dir) { txDir[0] = dir; fired.add("afterPrepared"); }
         @Override public void afterBundlePublished(Path bundle) throws IOException {
             if (crashPoint.equals("bundle")) { fired.add("afterBundlePublished"); throw new IOException("injected stop after B1 bundle"); }
         }
         @Override public void afterCurrentNew(Path currentNew) throws IOException {
             if (crashPoint.equals("current-new")) { fired.add("afterCurrentNew"); throw new IOException("injected stop after CURRENT.new"); }
         }
         @Override public void afterCurrentPublished(Path current) throws IOException {
             if (crashPoint.equals("current-published")) { fired.add("afterCurrentPublished"); throw new IOException("injected stop after CURRENT publication"); }
         }
      };
      MixedTransactionCore.Result failed = new MixedTransactionCore(store, f.output(), f.b0(), f.b1(), "a".repeat(64),
          "sir://synthetic-subject", f.candidateBytes(), observer).execute();
       assertFalse(failed.published(), failed.error());
       assertTrue(failed.error().contains("injected stop"), name + " failure evidence identifies injected stop: " + failed.error());
       assertTrue(fired.contains("afterPrepared"), name + " afterPrepared fired");
       assertTrue(fired.contains(marker), name + " targeted hook fired");
       assertNotNull(txDir[0]);
      assertEquals(new BaselineBundleStore.CurrentReadResult.Present(expectB1 ? f.b1().baselineId() : f.b0().baselineId()), store.readCurrent());
      MixedTransactionJournal.Snapshot snapshot = assertInstanceOf(MixedTransactionJournal.ParseOk.class,
          MixedTransactionJournal.parse(failed.journalPath())).snapshot();
      ChangeRecoveryResult recovered = new MixedTransactionRecoveryEngine(store, f.output()).execute(snapshot,
          Optional.of(expectB1 ? f.b1().baselineId() : f.b0().baselineId()));
      if (expectB1) {
         assertInstanceOf(ChangeRecoveryResult.Recovered.class, recovered, recovered.toString());
         assertB1Exact(f, store, txDir[0], name);
      } else {
         assertInstanceOf(ChangeRecoveryResult.RolledBack.class, recovered, recovered.toString());
         assertB0Exact(f, store, txDir[0], name);
      }
      ChangeRecoveryResult repeated = new ChangeRecoveryEngine(store, f.output(), io.kcg.sir.application.api.RecoveryHandle.any()).execute();
      assertInstanceOf(ChangeRecoveryResult.Recovered.class, repeated, name + " repeat: " + repeated);
      if (expectB1) assertB1Exact(f, store, txDir[0], name + " repeat");
      else assertB0Exact(f, store, txDir[0], name + " repeat");
   }

    private static Map<String, byte[]> treeBytes(Path root) throws IOException {
       Map<String, byte[]> result = new HashMap<>();
       try (Stream<Path> paths = Files.walk(root)) {
          for (Path path : paths.toList()) {
             String relative = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
             if (relative.equals("journal")) continue; // Recovery may durably append rollback progress before refusing cleanup.
             result.put(relative, Files.isDirectory(path) ? new byte[0] : Files.readAllBytes(path));
          }
       }
       return result;
    }

    private static void assertTreeBytesEquals(Map<String, byte[]> expected, Map<String, byte[]> actual, String context) {
       assertEquals(expected.keySet(), actual.keySet(), context + " tree paths");
       for (String path : expected.keySet()) assertArrayEquals(expected.get(path), actual.get(path), context + " " + path);
    }

    private static MixedTransactionJournal journal(Path txDir, MixedTransactionJournal.Snapshot snapshot) {
      return new MixedTransactionJournal(txDir, snapshot.transactionId(), snapshot.boundOutputRoot(), snapshot.b0BaselineId(),
          snapshot.b1BaselineId(), snapshot.b0ManifestDigest(), snapshot.b1ManifestDigest(), snapshot.planDigest(),
          snapshot.subjectSymbol(), snapshot.files());
   }

   private static void assertB0Exact(Fixture f, BaselineBundleStore store, Path txDir, String context) throws Exception {
      assertManifestExact(f.output(), f.b0());
      assertEquals(new BaselineBundleStore.CurrentReadResult.Present(f.b0().baselineId()), store.readCurrent(), context);
      assertFalse(Files.exists(txDir), context + " transaction directory must be removed");
   }

   private static void assertB1Exact(Fixture f, BaselineBundleStore store, Path txDir, String context) throws Exception {
      assertManifestExact(f.output(), f.b1());
      assertEquals(new BaselineBundleStore.CurrentReadResult.Present(f.b1().baselineId()), store.readCurrent(), context);
      assertFalse(Files.exists(txDir), context + " transaction directory must be removed");
   }

   private static void assertManifestExact(Path output, BaselineBundle expected) throws Exception {
      Set<String> manifestPaths = expected.descriptor().manifest().stream().map(BaselineManifestEntry::relativePath).collect(java.util.stream.Collectors.toSet());
      Set<String> actualFiles;
      try (Stream<Path> paths = Files.walk(output)) {
         actualFiles = paths.filter(Files::isRegularFile).map(path -> output.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/"))
             .collect(java.util.stream.Collectors.toSet());
      }
      assertEquals(manifestPaths, actualFiles, "output file set must exactly match baseline manifest");
      for (BaselineManifestEntry entry : expected.descriptor().manifest()) {
         byte[] bytes = Files.readAllBytes(output.resolve(entry.relativePath()));
         assertEquals(entry.byteCount(), bytes.length, entry.relativePath());
         assertEquals(entry.sha256Hex(), Sha256.hexDigest(bytes), entry.relativePath());
      }
   }

    @Test void recoveryDoesNotDeleteForeignOrModifiedTransactionArtifacts() throws Exception {
       assertEquals(3, FOREIGN_ARTIFACTS.size(), "foreign-artifact tree inventory size is pinned");
       for (String artifact : FOREIGN_ARTIFACTS) {
          assertEquals(2, ARTIFACT_VARIANTS.size(), "artifact variant inventory size is pinned");
          for (boolean modified : ARTIFACT_VARIANTS) assertB0ExternalArtifact(artifact, modified);
       }
    }

    private void assertB0ExternalArtifact(String artifact, boolean modified) throws Exception {
       String scenario = "b0-" + artifact.replace('/', '-') + (modified ? "-modified" : "-unknown");
       Fixture f = fixture(temp.resolve(scenario));
       Prepared prepared = prepared(f, temp.resolve(scenario + "/state"));
       Path txDir = prepared.result().journalPath().getParent();
       Path offending;
       if (modified) {
          offending = switch (artifact.substring(0, artifact.indexOf('/'))) {
             case "backup" -> prepareExpectedBackup(f, prepared);
             case "staging" -> txDir.resolve("staging").resolve(f.updatedPath());
             case "candidate-baseline" -> txDir.resolve("candidate-baseline/descriptor.kcg-baseline");
             default -> throw new IllegalArgumentException(artifact);
          };
          Files.write(offending, "modified expected bytes".getBytes(StandardCharsets.UTF_8));
       } else {
          offending = txDir.resolve(artifact + "/foreign");
          Files.createDirectories(offending.getParent());
          Files.write(offending, "unknown bytes".getBytes(StandardCharsets.UTF_8));
       }
       byte[] offendingBytes = Files.readAllBytes(offending);
       Map<String, byte[]> before = treeBytes(txDir);
       MixedTransactionJournal.Snapshot snapshot = assertInstanceOf(MixedTransactionJournal.ParseOk.class,
           MixedTransactionJournal.parse(prepared.result().journalPath())).snapshot();
       ChangeRecoveryResult result = new MixedTransactionRecoveryEngine(prepared.store(), f.output()).execute(snapshot,
           Optional.of(f.b0().baselineId()));
       assertInstanceOf(ChangeRecoveryResult.RecoveryRequired.class, result, scenario + ": " + result);
       assertArrayEquals(offendingBytes, Files.readAllBytes(offending), scenario + " offending bytes unchanged");
       assertTreeBytesEquals(before, treeBytes(txDir), scenario + " no transaction-tree bytes changed");
       assertTrue(Files.exists(prepared.result().journalPath()), scenario + " journal retained");
    }

    private Path prepareExpectedBackup(Fixture f, Prepared prepared) throws Exception {
       Path txDir = prepared.result().journalPath().getParent();
       MixedTransactionJournal.Snapshot snapshot = assertInstanceOf(MixedTransactionJournal.ParseOk.class,
           MixedTransactionJournal.parse(prepared.result().journalPath())).snapshot();
       MixedTransactionJournal journal = journal(txDir, snapshot);
       journal.appendOverallTransition(MixedTransactionJournal.OverallState.COMMITTING);
       for (int i = 0; i < snapshot.files().size(); i++) {
          MixedTransactionJournal.FileEntry file = snapshot.files().get(i);
          if (file.kind() == MixedTransactionJournal.Kind.UPDATE) {
             Path backup = txDir.resolve("backup").resolve(Integer.toString(i));
             Path target = f.output().resolve(file.relativePath());
             journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE);
             Files.createLink(backup, target);
             journal.appendFileTransition(i, file.relativePath(), MixedTransactionJournal.FileState.BACKUP_LINKED_DURABLE);
             Files.delete(backup);
             return backup;
          }
       }
       throw new AssertionError("fixture must include an update backup");
    }

    @Test void b1RecoveryRejectsForeignAndModifiedTransactionArtifactsWithoutDeletingThem() throws Exception {
       for (String artifact : FOREIGN_ARTIFACTS) {
          assertEquals(2, ARTIFACT_VARIANTS.size(), "artifact variant inventory size is pinned");
          for (boolean modified : ARTIFACT_VARIANTS) assertB1ExternalArtifact(artifact, modified);
       }
    }

    private void assertB1ExternalArtifact(String artifact, boolean modified) throws Exception {
       String name = "b1-" + artifact.replace('/', '-') + (modified ? "-modified" : "-unknown");
       Fixture f = fixture(temp.resolve(name));
       Path state = temp.resolve(name + "/state");
       Files.createDirectories(state);
       BaselineBundleStore store = new BaselineBundleStore(state);
       store.stageBundle(f.b0());
       store.writeCurrentAtomic(f.b0().baselineId());
       Path[] txDir = new Path[1];
       Path[] offending = new Path[1];
       MixedTransactionCore.Observer inject = new MixedTransactionCore.Observer() {
          @Override public void afterPrepared(Path dir) { txDir[0] = dir; }
          @Override public void afterCurrentPublished(Path current) throws IOException {
             Path dir = txDir[0].resolve(artifact.substring(0, artifact.indexOf('/')));
             if (modified) {
                try (Stream<Path> paths = Files.walk(dir)) {
                   offending[0] = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
                }
                Files.delete(offending[0]);
             } else {
                offending[0] = dir.resolve("foreign");
                Files.createDirectories(offending[0].getParent());
             }
             Files.write(offending[0], (modified ? "modified expected bytes" : "unknown bytes").getBytes(StandardCharsets.UTF_8));
          }
       };
       MixedTransactionCore.Result failed = new MixedTransactionCore(store, f.output(), f.b0(), f.b1(), "a".repeat(64),
           "sir://synthetic-subject", f.candidateBytes(), inject).execute();
       assertFalse(failed.published(), failed.error());
       assertNotNull(offending[0], name + " afterCurrentPublished artifact mutation hook fired");
       assertEquals(new BaselineBundleStore.CurrentReadResult.Present(f.b1().baselineId()), store.readCurrent(), name + " is on CURRENT=B1 cleanup side");
       byte[] offendingBytes = Files.readAllBytes(offending[0]);
       Map<String, byte[]> before = treeBytes(txDir[0]);
       MixedTransactionJournal.Snapshot snapshot = assertInstanceOf(MixedTransactionJournal.ParseOk.class,
           MixedTransactionJournal.parse(failed.journalPath())).snapshot();
       ChangeRecoveryResult result = new MixedTransactionRecoveryEngine(store, f.output()).execute(snapshot, Optional.of(f.b1().baselineId()));
       assertInstanceOf(ChangeRecoveryResult.RecoveryRequired.class, result, result.toString());
       assertArrayEquals(offendingBytes, Files.readAllBytes(offending[0]), name + " offending bytes unchanged");
       assertTreeBytesEquals(before, treeBytes(txDir[0]), name + " no transaction-tree bytes changed");
       assertTrue(Files.exists(failed.journalPath()), name + " journal retained");
    }

   private Prepared prepared(Fixture fixture, Path state) throws Exception {
      Files.createDirectories(state);
      BaselineBundleStore store = new BaselineBundleStore(state);
      store.stageBundle(fixture.b0());
      store.writeCurrentAtomic(fixture.b0().baselineId());
      MixedTransactionCore.Observer stop = new MixedTransactionCore.Observer() {
         @Override public void afterPrepared(Path tx) throws IOException { throw new IOException("injected process stop"); }
      };
      MixedTransactionCore.Result result = new MixedTransactionCore(store, fixture.output(), fixture.b0(), fixture.b1(), "a".repeat(64),
          "sir://synthetic-subject", fixture.candidateBytes(), stop).execute();
       assertFalse(result.published());
       assertTrue(result.error().contains("injected process stop"), "prepared crash hook must fire: " + result.error());
       return new Prepared(store, result);
   }

    private record CrashScenario(String name, MixedTransactionJournal.Kind kind, MixedTransactionJournal.FileState fileState,
                                 MixedTransactionJournal.OverallState overallState) {}

    private record Prepared(BaselineBundleStore store, MixedTransactionCore.Result result) {}

   @Test void establishmentOccupierAppearingAfterPrepareIsNeverOverwrittenAndJournalRemains() throws Exception {
      Fixture f = fixture(temp.resolve("occupier"));
      Path state = temp.resolve("occupier/state");
      Files.createDirectories(state);
      BaselineBundleStore store = new BaselineBundleStore(state);
      store.writeCurrentAtomic(f.b0().baselineId());
      byte[] external = "external occupant".getBytes(StandardCharsets.UTF_8);
      MixedTransactionCore.Observer observer = new MixedTransactionCore.Observer() {
         @Override public void afterPrepared(Path transactionDir) throws IOException {
            Path target = f.output().resolve(f.establishedPath());
            Files.write(target, external);
         }
      };
      MixedTransactionCore.Result result = new MixedTransactionCore(store, f.output(), f.b0(), f.b1(), "a".repeat(64),
          "sir://synthetic-subject", f.candidateBytes(), observer).execute();
      assertFalse(result.published());
      assertNotNull(result.journalPath());
      assertEquals("external occupant", Files.readString(f.output().resolve(f.establishedPath())));
      assertTrue(Files.exists(result.journalPath()), "partial commit remains for the not-yet-implemented recovery path");
      assertEquals(MixedTransactionJournal.OverallState.COMMITTING,
          assertInstanceOf(MixedTransactionJournal.ParseOk.class, MixedTransactionJournal.parse(result.journalPath())).snapshot().overallState());
   }

   private Fixture fixture(Path root) throws Exception {
      Path output = root.resolve("output").toAbsolutePath().normalize();
      Files.createDirectories(output);
      String source = resource("/valid/rename-course-search.sir");
      byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
      SourceId sourceId = SourceId.of("q17-synthetic.sir");
      List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
      SirCompiler.CompiledProject compiled = SirCompiler.compile(source, sourceId, diagnostics)
          .orElseThrow(() -> new AssertionError("fixture compile failed: " + diagnostics));
      byte[] graphBytes = io.kcg.sir.application.internal.bundle.BaselineBuilder.serializeGraph(compiled.graph());
      List<BaselineManifestEntry> baseManifest = io.kcg.sir.application.internal.bundle.BaselineBuilder.buildManifest(compiled.generatedFiles());
      Map<String, byte[]> baseBytes = new HashMap<>();
      for (var generated : compiled.generatedFiles()) {
         byte[] bytes = generated.content().getBytes(StandardCharsets.UTF_8);
         baseBytes.put(generated.relativePath(), bytes);
         Path target = output.resolve(generated.relativePath());
         Files.createDirectories(target.getParent());
         Files.write(target, bytes);
      }
      BaselineDescriptor baseDescriptor = descriptor(compiled, output, sourceId, sourceBytes, graphBytes, baseManifest);
      BaselineBundle b0 = BaselineBundle.materialize(baseDescriptor, sourceBytes, graphBytes);

      List<BaselineManifestEntry> manifest = new ArrayList<>(baseManifest);
      BaselineManifestEntry updateEntry = manifest.get(0);
      BaselineManifestEntry withdrawn = manifest.get(1);
      BaselineManifestEntry establishSource = manifest.get(2);
      byte[] updatedBytes = (new String(baseBytes.get(updateEntry.relativePath()), StandardCharsets.UTF_8) + "\n// synthetic update\n")
          .getBytes(StandardCharsets.UTF_8);
      manifest.set(0, new BaselineManifestEntry(updateEntry.relativePath(), updatedBytes.length, Sha256.hexDigest(updatedBytes),
          updateEntry.artifactId(), updateEntry.ownerSymbol()));
      manifest.remove(withdrawn);
      String establishPath = establishSource.relativePath() + ".q17-new";
      byte[] establishedBytes = baseBytes.get(establishSource.relativePath()).clone();
      manifest.add(new BaselineManifestEntry(establishPath, establishedBytes.length, Sha256.hexDigest(establishedBytes),
          establishSource.artifactId(), establishSource.ownerSymbol()));
      Map<String, byte[]> candidateBytes = new HashMap<>(baseBytes);
      candidateBytes.put(updateEntry.relativePath(), updatedBytes);
      candidateBytes.remove(withdrawn.relativePath());
      candidateBytes.put(establishPath, establishedBytes);
      byte[] candidateSource = (source + "\n").getBytes(StandardCharsets.UTF_8);
      BaselineDescriptor candidateDescriptor = descriptor(compiled, output, sourceId, candidateSource, graphBytes, manifest);
      BaselineBundle b1 = BaselineBundle.materialize(candidateDescriptor, candidateSource, graphBytes);
      return new Fixture(output, b0, b1, Map.copyOf(candidateBytes), Map.copyOf(baseBytes), updateEntry.relativePath(),
          withdrawn.relativePath(), establishPath);
   }

   private static BaselineDescriptor descriptor(SirCompiler.CompiledProject compiled, Path output, SourceId sourceId,
                                                byte[] sourceBytes, byte[] graphBytes, List<BaselineManifestEntry> manifest) {
      String digest = BaselineDescriptorCodec.computeManifestDigest(manifest);
      return new BaselineDescriptor(ChangeExecutionBaselineFormatVersion.V1, output, sourceId, sourceBytes.length,
          Sha256.hexDigest(sourceBytes), compiled.graph().version(), compiled.graph().canonicalDigest(),
          ProjectGraphCanonicalFormatVersion.V1, graphBytes.length, Sha256.hexDigest(graphBytes),
          compiled.loweredModel().targetId(), compiled.loweredModel().irVersion(), manifest, digest);
   }

   private static String resource(String path) throws IOException {
      try (var input = MixedTransactionCoreTest.class.getResourceAsStream(path)) {
         if (input == null) throw new IOException("resource not found: " + path);
         return new String(input.readAllBytes(), StandardCharsets.UTF_8);
      }
   }

   private record Fixture(Path output, BaselineBundle b0, BaselineBundle b1, Map<String, byte[]> candidateBytes,
                          Map<String, byte[]> originalBaseBytes, String updatedPath, String withdrawnPath, String establishedPath) {}
}
