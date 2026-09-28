package io.kcg.sir.application.internal.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MixedTransactionJournalTest {
   @TempDir Path temp;
   private static final String ID = "0123456789abcdef0123456789abcdef";
   private static final String DIGEST = "a".repeat(64);
   private static final String DIGEST2 = "b".repeat(64);

   private MixedTransactionJournal journal(Path stateRoot) {
      Path tx = stateRoot.resolve("transactions").resolve(ID);
      return new MixedTransactionJournal(tx, ID, temp.resolve("output"), DIGEST, DIGEST2, DIGEST, DIGEST2,
          DIGEST, "sir://subject", List.of(
              new MixedTransactionJournal.FileEntry("a/update.txt", MixedTransactionJournal.Kind.UPDATE),
              new MixedTransactionJournal.FileEntry("b/withdraw.txt", MixedTransactionJournal.Kind.WITHDRAW),
              new MixedTransactionJournal.FileEntry("c/establish.txt", MixedTransactionJournal.Kind.ESTABLISH)));
   }

   @Test void writerBytesParseBackWithKindsAndStrictStateTransitions() throws Exception {
      MixedTransactionJournal journal = journal(temp.resolve("state"));
      journal.create();
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.PREPARED);
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.COMMITTING);
      journal.appendFileTransition(0, "a/update.txt", MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE);
      journal.appendFileTransition(0, "a/update.txt", MixedTransactionJournal.FileState.BACKUP_LINKED_DURABLE);
      journal.appendFileTransition(0, "a/update.txt", MixedTransactionJournal.FileState.REPLACE_INTENT_DURABLE);
      journal.appendFileTransition(1, "b/withdraw.txt", MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE);
      journal.appendFileTransition(2, "c/establish.txt", MixedTransactionJournal.FileState.CREATE_INTENT_DURABLE);
      MixedTransactionJournal.ParseOk parsed = assertInstanceOf(MixedTransactionJournal.ParseOk.class,
          MixedTransactionJournal.parse(journal.journalPath()));
      assertEquals(List.of("UPDATE", "WITHDRAW", "ESTABLISH"), parsed.snapshot().files().stream().map(f -> f.kind().name()).toList());
      assertEquals(MixedTransactionJournal.FileState.REPLACE_INTENT_DURABLE, parsed.snapshot().fileStates().get(0));
      assertEquals(MixedTransactionJournal.OverallState.COMMITTING, parsed.snapshot().overallState());
   }

   @Test void malformedSerializedBytesRejectUnsafePathDuplicateIndexAndIllegalTransition() throws Exception {
      MixedTransactionJournal journal = journal(temp.resolve("bad"));
      journal.create();
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.PREPARED);
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.COMMITTING);
      journal.appendFileTransition(0, "a/update.txt", MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE);
      byte[] valid = Files.readAllBytes(journal.journalPath());
      assertRejectedByGate(journal, replace(valid, "path64=" + pathToken("a/update.txt"), "path64=" + pathToken("../escape.txt")));
      assertRejectedByGate(journal, replace(valid, "file 1 path64=" + pathToken("b/withdraw.txt"),
          "file 0 path64=" + pathToken("b/withdraw.txt")));
      assertRejectedByGate(journal, replace(valid, "state=PREPARING\nfile 1", "state=REPLACED_DURABLE\nfile 1"));
      assertRejectedByGate(journal, replace(valid, "state=BACKUP_LINK_INTENT_DURABLE", "state=REPLACED_DURABLE"));
   }

   @Test void overallStateParserRejectsSkippedPrematureRollbackAfterPublicationAndPostTerminalEvents() throws Exception {
      MixedTransactionJournal journal = journal(temp.resolve("overall-bad"));
      journal.create();
      byte[] initial = Files.readAllBytes(journal.journalPath());
      assertRejectedByGate(journal, replace(initial, "overallState=PREPARING", "overallState=COMMITTING"));
      assertRejectedByGate(journal, replace(initial, "overallState=PREPARING", "overallState=COMPLETED"));

      journal = journal(temp.resolve("overall-after-publish"));
      journal.create();
      commitAllFiles(journal);
      appendOverallThrough(journal, MixedTransactionJournal.OverallState.BASELINE_PUBLISHED);
      byte[] baselinePublished = Files.readAllBytes(journal.journalPath());
      assertRejectedByGate(journal, appendRaw(baselinePublished, "overallState=ROLLING_BACK\n"));
      assertRejectedByGate(journal, appendRaw(baselinePublished,
          "file 0 path64=" + pathToken("a/update.txt") + " kind=UPDATE state=ROLLBACK_REPLACE_INTENT_DURABLE\n"));

      journal = journal(temp.resolve("overall-incomplete-completed"));
      journal.create();
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.PREPARED);
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.COMMITTING);
      byte[] committingIncomplete = Files.readAllBytes(journal.journalPath());
      assertRejectedByGate(journal, appendRaw(committingIncomplete,
          "overallState=FILES_COMMITTED\noverallState=PUBLISHING\noverallState=BASELINE_PUBLISHED\n"
              + "overallState=CLEANUP_PENDING\noverallState=COMPLETED\n"));

      journal = journal(temp.resolve("overall-after-terminal"));
      journal.create();
      commitAllFiles(journal);
      appendOverallThrough(journal, MixedTransactionJournal.OverallState.COMPLETED);
      byte[] completed = Files.readAllBytes(journal.journalPath());
      assertRejectedByGate(journal, appendRaw(completed,
          "file 0 path64=" + pathToken("a/update.txt") + " kind=UPDATE state=ROLLBACK_REPLACE_INTENT_DURABLE\n"));
       assertRejectedByGate(journal, appendRaw(completed, "overallState=ROLLING_BACK\n"));

       journal = journal(temp.resolve("overall-incomplete-rolled-back"));
       journal.create();
       journal.appendOverallTransition(MixedTransactionJournal.OverallState.PREPARED);
       journal.appendOverallTransition(MixedTransactionJournal.OverallState.COMMITTING);
       journal.appendFileTransition(0, "a/update.txt", MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE);
       journal.appendOverallTransition(MixedTransactionJournal.OverallState.ROLLING_BACK);
       journal.appendFileTransition(0, "a/update.txt", MixedTransactionJournal.FileState.ROLLBACK_REPLACE_INTENT_DURABLE);
       byte[] rollbackInProgress = Files.readAllBytes(journal.journalPath());
       assertRejectedByGate(journal, appendRaw(rollbackInProgress, "overallState=ROLLED_BACK\n"));
    }

   @Test void appendEnforcesForwardAndRollbackGraphsAndRejectsTerminalWrites() throws Exception {
      MixedTransactionJournal journal = journal(temp.resolve("overall-writer"));
      journal.create();
      assertThrows(IOException.class, () -> journal.appendOverallTransition(MixedTransactionJournal.OverallState.COMMITTING));
      assertThrows(IOException.class, () -> journal.appendOverallTransition(MixedTransactionJournal.OverallState.COMPLETED));
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.ROLLING_BACK);
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.ROLLED_BACK);
      assertThrows(IOException.class, () -> journal.appendOverallTransition(MixedTransactionJournal.OverallState.COMPLETED));
      assertThrows(IOException.class, () -> journal.appendFileTransition(0, "a/update.txt", MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE));

      MixedTransactionJournal incomplete = journal(temp.resolve("overall-incomplete-writer"));
      incomplete.create();
      incomplete.appendOverallTransition(MixedTransactionJournal.OverallState.PREPARED);
      incomplete.appendOverallTransition(MixedTransactionJournal.OverallState.COMMITTING);
      assertThrows(IOException.class, () -> incomplete.appendOverallTransition(MixedTransactionJournal.OverallState.FILES_COMMITTED));

      MixedTransactionJournal forward = journal(temp.resolve("overall-forward"));
      forward.create();
      commitAllFiles(forward);
      appendOverallThrough(forward, MixedTransactionJournal.OverallState.COMPLETED);
      assertInstanceOf(MixedTransactionJournal.ParseOk.class, MixedTransactionJournal.parse(forward.journalPath()));
       assertThrows(IOException.class, () -> forward.appendOverallTransition(MixedTransactionJournal.OverallState.ROLLING_BACK));

       MixedTransactionJournal published = journal(temp.resolve("overall-published-writer"));
       published.create();
       commitAllFiles(published);
       appendOverallThrough(published, MixedTransactionJournal.OverallState.BASELINE_PUBLISHED);
       assertThrows(IOException.class, () -> published.appendFileTransition(0, "a/update.txt",
           MixedTransactionJournal.FileState.ROLLBACK_REPLACE_INTENT_DURABLE));

       MixedTransactionJournal rollbackTouched = journal(temp.resolve("overall-rollback-touched"));
       rollbackTouched.create();
       rollbackTouched.appendOverallTransition(MixedTransactionJournal.OverallState.PREPARED);
       rollbackTouched.appendOverallTransition(MixedTransactionJournal.OverallState.COMMITTING);
       rollbackTouched.appendFileTransition(0, "a/update.txt", MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE);
       rollbackTouched.appendOverallTransition(MixedTransactionJournal.OverallState.ROLLING_BACK);
       rollbackTouched.appendFileTransition(0, "a/update.txt", MixedTransactionJournal.FileState.ROLLBACK_REPLACE_INTENT_DURABLE);
       rollbackTouched.appendFileTransition(0, "a/update.txt", MixedTransactionJournal.FileState.ROLLED_BACK_DURABLE);
       rollbackTouched.appendOverallTransition(MixedTransactionJournal.OverallState.ROLLED_BACK);
       assertInstanceOf(MixedTransactionJournal.ParseOk.class, MixedTransactionJournal.parse(rollbackTouched.journalPath()));
   }

   @Test void constructorAndParserRejectMalformedHeaderFields() throws Exception {
      assertThrows(IllegalArgumentException.class, () -> new MixedTransactionJournal(
          temp.resolve("header-bad-digest"), ID, temp.resolve("output"), DIGEST, DIGEST2, "bad", DIGEST2,
          DIGEST, "sir://subject", List.of()));
      assertThrows(IllegalArgumentException.class, () -> new MixedTransactionJournal(
          temp.resolve("header-bad-transaction"), "bad-id", temp.resolve("output"), DIGEST, DIGEST2, DIGEST, DIGEST2,
          DIGEST, "sir://subject", List.of()));
      assertThrows(IllegalArgumentException.class, () -> new MixedTransactionJournal(
          temp.resolve("header-bad-baseline"), ID, temp.resolve("output"), "not-a-baseline", DIGEST2, DIGEST, DIGEST2,
          DIGEST, "sir://subject", List.of()));
      assertThrows(IllegalArgumentException.class, () -> new MixedTransactionJournal(
          temp.resolve("header-bad-subject"), ID, temp.resolve("output"), DIGEST, DIGEST2, DIGEST, DIGEST2,
          DIGEST, "sir://subject\nsecond-line", List.of()));
      assertThrows(IllegalArgumentException.class, () -> new MixedTransactionJournal(
          temp.resolve("header-bad-subject-cr"), ID, temp.resolve("output"), DIGEST, DIGEST2, DIGEST, DIGEST2,
          DIGEST, "sir://subject\rsecond-line", List.of()));
      assertThrows(IllegalArgumentException.class, () -> new MixedTransactionJournal(
          temp.resolve("header-bad-output"), ID, temp.resolve("output\nsecond-line"), DIGEST, DIGEST2, DIGEST, DIGEST2,
          DIGEST, "sir://subject", List.of()));

      MixedTransactionJournal journal = journal(temp.resolve("header-parse"));
      journal.create();
      byte[] bytes = Files.readAllBytes(journal.journalPath());
      assertRejectedByGate(journal, replace(bytes, "b0ManifestDigest=" + DIGEST, "b0ManifestDigest=bad"));
      assertRejectedByGate(journal, replace(bytes, "subjectSymbol=sir://subject", "subjectSymbol=sir://bad\nextra"));
   }

   @Test void journalGateRecognizesAndBlocksV4ButDoesNotConfuseUnknownMagic() throws Exception {
      Path root = temp.resolve("gate");
      MixedTransactionJournal journal = journal(root);
      journal.create();
      JournalGate.InspectionResult result = new JournalGate(root).inspect(Optional.of(DIGEST));
      assertFalse(result.isOpen());
      assertEquals("SIR-APP-CHANGE-RECOVERY-001", result.errors().getFirst().code());
      JournalGate.ActiveJournal.V4Mixed active = assertInstanceOf(JournalGate.ActiveJournal.V4Mixed.class, result.activeJournals().getFirst());
      assertEquals(JournalGate.JournalFamily.V4_MIXED, active.family());

      Files.write(journal.journalPath(), replace(Files.readAllBytes(journal.journalPath()),
          "KCG-CHANGE-TRANSACTION-JOURNAL-V4", "KCG-UNKNOWN-TRANSACTION-JOURNAL"));
      JournalGate.InspectionResult unknown = new JournalGate(root).inspect(Optional.of(DIGEST));
      assertFalse(unknown.isOpen());
      assertTrue(unknown.activeJournals().isEmpty());
      assertEquals("SIR-APP-CHANGE-RECOVERY-002", unknown.errors().getFirst().code());
      assertTrue(unknown.errors().getFirst().message().contains("unknown journal magic"));
   }

   @Test void delimiterBearingLegalPathRoundTripsAndCorruptEncodingFailsClosed() throws Exception {
      String relativePath = "a kind=establish/file state=preparing.txt";
      Path root = temp.resolve("delimiters");
      MixedTransactionJournal journal = new MixedTransactionJournal(root.resolve("transactions").resolve(ID), ID,
          temp.resolve("output"), DIGEST, DIGEST2, DIGEST, DIGEST2, DIGEST, "sir://subject",
          List.of(new MixedTransactionJournal.FileEntry(relativePath, MixedTransactionJournal.Kind.ESTABLISH)));
      journal.create();
      MixedTransactionJournal.ParseOk parsed = assertInstanceOf(MixedTransactionJournal.ParseOk.class,
          MixedTransactionJournal.parse(journal.journalPath()));
      assertEquals(relativePath, parsed.snapshot().files().getFirst().relativePath());
      byte[] bytes = Files.readAllBytes(journal.journalPath());
      assertRejectedByGate(journal, replace(bytes, "path64=" + pathToken(relativePath), "path64=%%%"));
   }

   @Test void terminalResidualJournalStillBlocksJournalGate() throws Exception {
      Path root = temp.resolve("terminal-residual");
      MixedTransactionJournal journal = journal(root);
      journal.create();
      commitAllFiles(journal);
      appendOverallThrough(journal, MixedTransactionJournal.OverallState.COMPLETED);
      JournalGate.InspectionResult result = new JournalGate(root).inspect(Optional.of(DIGEST));
      assertFalse(result.isOpen());
      JournalGate.ActiveJournal.V4Mixed active = assertInstanceOf(JournalGate.ActiveJournal.V4Mixed.class,
          result.activeJournals().getFirst());
      assertEquals(MixedTransactionJournal.OverallState.COMPLETED, active.snapshot().overallState());
      assertEquals("SIR-APP-CHANGE-RECOVERY-001", result.errors().getFirst().code());
   }

   @Test void journalGateStillDispatchesV1V2AndV3() throws Exception {
      Path root = temp.resolve("legacy");
      Path output = temp.resolve("output");
      Path v1 = root.resolve("transactions").resolve(ID);
      new TransactionJournal(v1, ID, output, DIGEST, DIGEST2, List.of("legacy/file.txt")).create();
      assertLegacyFamily(root, JournalGate.JournalFamily.V1_UPDATE);
      Files.delete(v1.resolve("journal"));
      Files.delete(v1);

      Path v2 = root.resolve("transactions").resolve("11111111111111111111111111111111");
      new CreateTransactionJournal(v2, "11111111111111111111111111111111", output, DIGEST, DIGEST2, List.of(), List.of("legacy/file.txt")).create();
      assertLegacyFamily(root, JournalGate.JournalFamily.V2_CREATE);
      Files.delete(v2.resolve("journal"));
      Files.delete(v2);

      Path v3 = root.resolve("transactions").resolve("22222222222222222222222222222222");
      new DeleteTransactionJournal(v3, "22222222222222222222222222222222", output, DIGEST, DIGEST2, DIGEST, DIGEST2, List.of("legacy/file.txt")).create();
      assertLegacyFamily(root, JournalGate.JournalFamily.V3_DELETE);
   }

   private void assertLegacyFamily(Path root, JournalGate.JournalFamily expected) {
      JournalGate.InspectionResult result = new JournalGate(root).inspect(Optional.of(DIGEST));
      assertEquals(expected, result.activeJournals().getFirst().family());
      assertEquals("SIR-APP-CHANGE-RECOVERY-001", result.errors().getFirst().code());
   }

   private void commitAllFiles(MixedTransactionJournal journal) throws Exception {
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.PREPARED);
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.COMMITTING);
      journal.appendFileTransition(0, "a/update.txt", MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE);
      journal.appendFileTransition(0, "a/update.txt", MixedTransactionJournal.FileState.BACKUP_LINKED_DURABLE);
      journal.appendFileTransition(0, "a/update.txt", MixedTransactionJournal.FileState.REPLACE_INTENT_DURABLE);
      journal.appendFileTransition(0, "a/update.txt", MixedTransactionJournal.FileState.REPLACED_DURABLE);
      journal.appendFileTransition(1, "b/withdraw.txt", MixedTransactionJournal.FileState.BACKUP_LINK_INTENT_DURABLE);
      journal.appendFileTransition(1, "b/withdraw.txt", MixedTransactionJournal.FileState.BACKUP_LINKED_DURABLE);
      journal.appendFileTransition(1, "b/withdraw.txt", MixedTransactionJournal.FileState.DELETE_INTENT_DURABLE);
      journal.appendFileTransition(1, "b/withdraw.txt", MixedTransactionJournal.FileState.DELETED_DURABLE);
      journal.appendFileTransition(2, "c/establish.txt", MixedTransactionJournal.FileState.CREATE_INTENT_DURABLE);
      journal.appendFileTransition(2, "c/establish.txt", MixedTransactionJournal.FileState.CREATED_DURABLE);
      journal.appendOverallTransition(MixedTransactionJournal.OverallState.FILES_COMMITTED);
   }

   private void appendOverallThrough(MixedTransactionJournal journal, MixedTransactionJournal.OverallState terminal) throws Exception {
      List<MixedTransactionJournal.OverallState> forward = List.of(MixedTransactionJournal.OverallState.PREPARING,
          MixedTransactionJournal.OverallState.PREPARED, MixedTransactionJournal.OverallState.COMMITTING,
          MixedTransactionJournal.OverallState.FILES_COMMITTED, MixedTransactionJournal.OverallState.PUBLISHING,
          MixedTransactionJournal.OverallState.BASELINE_PUBLISHED, MixedTransactionJournal.OverallState.CLEANUP_PENDING,
          MixedTransactionJournal.OverallState.COMPLETED);
      MixedTransactionJournal.ParseOk parsed = assertInstanceOf(MixedTransactionJournal.ParseOk.class,
          MixedTransactionJournal.parse(journal.journalPath()));
      int start = forward.indexOf(parsed.snapshot().overallState());
      int end = forward.indexOf(terminal);
      if (start < 0 || end < start) throw new IllegalArgumentException("not a forward state: " + terminal);
      for (int i = start + 1; i <= end; i++) journal.appendOverallTransition(forward.get(i));
   }

   private static String pathToken(String path) {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));
   }

   private static byte[] appendRaw(byte[] bytes, String suffix) {
      byte[] extra = suffix.getBytes(StandardCharsets.UTF_8);
      byte[] result = java.util.Arrays.copyOf(bytes, bytes.length + extra.length);
      System.arraycopy(extra, 0, result, bytes.length, extra.length);
      return result;
   }

   private void assertRejectedByGate(MixedTransactionJournal journal, byte[] bytes) throws Exception {
      assertRejected(bytes);
      Files.write(journal.journalPath(), bytes);
      JournalGate.InspectionResult result = new JournalGate(journal.journalPath().getParent().getParent().getParent()).inspect(Optional.of(DIGEST));
      assertFalse(result.isOpen());
      assertTrue(result.activeJournals().isEmpty());
      assertEquals("SIR-APP-CHANGE-RECOVERY-002", result.errors().getFirst().code());
   }

   private static void assertRejected(byte[] bytes) {
      assertInstanceOf(MixedTransactionJournal.ParseFailure.class, MixedTransactionJournal.parseBytes(Path.of("journal"), bytes));
   }
   private static byte[] replace(byte[] bytes, String from, String to) {
      String content = new String(bytes, StandardCharsets.UTF_8);
      assertTrue(content.contains(from), "fixture lacks mutation target: " + from);
      return content.replace(from, to).getBytes(StandardCharsets.UTF_8);
   }
}
