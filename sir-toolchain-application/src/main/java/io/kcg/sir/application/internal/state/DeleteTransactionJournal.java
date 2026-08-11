package io.kcg.sir.application.internal.state;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DeleteTransactionJournal {
   public static final byte[] MAGIC = "KCG-CHANGE-TRANSACTION-JOURNAL-V3\n".getBytes(StandardCharsets.US_ASCII);
   public static final String FAMILY = "DELETE";
   public static final String JOURNAL_FILE = "journal";
   public static final String BACKUP_DIR = "backup";
   public static final String CANDIDATE_BASELINE_DIR = "candidate-baseline";
   public static final String BACKUP_LAYOUT = "INDEXED_B0_V1";
   public static final String STAGING_REF = "NONE";
   public static final int BACKUP_INDEX_WIDTH = 8;
   private final Path journalPath;
   private final String transactionId;
   private final Path boundOutputRoot;
   private final String b0BaselineId;
   private final String b1BaselineId;
   private final String b0ManifestDigest;
   private final String b1ManifestDigest;
   private final List<String> plannedFiles;

   public DeleteTransactionJournal(
      Path transactionDir,
      String transactionId,
      Path boundOutputRoot,
      String b0BaselineId,
      String b1BaselineId,
      String b0ManifestDigest,
      String b1ManifestDigest,
      List<String> plannedFiles
   ) {
      this.journalPath = Objects.requireNonNull(transactionDir, "transactionDir").resolve("journal").toAbsolutePath().normalize();
      this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
      this.boundOutputRoot = Objects.requireNonNull(boundOutputRoot, "boundOutputRoot").toAbsolutePath().normalize();
      this.b0BaselineId = Objects.requireNonNull(b0BaselineId, "b0BaselineId");
      this.b1BaselineId = Objects.requireNonNull(b1BaselineId, "b1BaselineId");
      this.b0ManifestDigest = Objects.requireNonNull(b0ManifestDigest, "b0ManifestDigest");
      this.b1ManifestDigest = Objects.requireNonNull(b1ManifestDigest, "b1ManifestDigest");
      this.plannedFiles = List.copyOf(Objects.requireNonNull(plannedFiles, "plannedFiles"));
   }

   public Path journalPath() {
      return this.journalPath;
   }

   public String transactionId() {
      return this.transactionId;
   }

   public Path boundOutputRoot() {
      return this.boundOutputRoot;
   }

   public String b0BaselineId() {
      return this.b0BaselineId;
   }

   public String b1BaselineId() {
      return this.b1BaselineId;
   }

   public String b0ManifestDigest() {
      return this.b0ManifestDigest;
   }

   public String b1ManifestDigest() {
      return this.b1ManifestDigest;
   }

   public List<String> plannedFiles() {
      return this.plannedFiles;
   }

   public static String backupFileName(int index) {
      if (index < 0) {
         throw new IllegalArgumentException("index must not be negative: " + index);
      } else {
         return String.format("%08d.b0", index);
      }
   }

   public void create() throws IOException {
      Files.createDirectories(this.journalPath.getParent());
      if (Files.exists(this.journalPath, LinkOption.NOFOLLOW_LINKS)) {
         throw new IOException("journal already exists: " + this.journalPath);
      }

      StringBuilder sb = new StringBuilder();
      sb.append(new String(MAGIC, StandardCharsets.US_ASCII));
      sb.append("transactionId=").append(this.transactionId).append('\n');
      sb.append("family=").append("DELETE").append('\n');
      sb.append("boundOutputRoot=").append(this.boundOutputRoot).append('\n');
      sb.append("b0BaselineId=").append(this.b0BaselineId).append('\n');
      sb.append("b1BaselineId=").append(this.b1BaselineId).append('\n');
      sb.append("b0ManifestDigest=").append(this.b0ManifestDigest).append('\n');
      sb.append("b1ManifestDigest=").append(this.b1ManifestDigest).append('\n');
      sb.append("stagingRef=").append("NONE").append('\n');
      sb.append("backupRootRef=").append("backup").append('\n');
      sb.append("backupLayout=").append("INDEXED_B0_V1").append('\n');
      sb.append("candidateBaselineRef=").append("candidate-baseline").append('\n');
      sb.append("fileCount=").append(this.plannedFiles.size()).append('\n');

      for (int i = 0; i < this.plannedFiles.size(); i++) {
         sb.append("file ").append(i).append(' ').append(this.plannedFiles.get(i)).append(" state=PREPARING\n");
      }

      sb.append("overallState=PREPARING\n");
      Files.write(
         this.journalPath, sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC
      );
   }

   public void appendFileTransition(int fileIndex, String relativePath, DeleteTransactionJournal.FileTransitionState newState) throws IOException {
      Objects.requireNonNull(newState, "newState");
      String line = "file " + fileIndex + " " + relativePath + " state=" + newState.name() + "\n";
      Files.write(this.journalPath, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
   }

   public void appendOverallTransition(DeleteTransactionJournal.OverallState newState) throws IOException {
      Objects.requireNonNull(newState, "newState");
      String line = "overallState=" + newState.name() + "\n";
      Files.write(this.journalPath, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
   }

   public static DeleteTransactionJournal forRecovery(DeleteTransactionJournal.Snapshot snapshot) {
      Objects.requireNonNull(snapshot, "snapshot");
      Path journalPath = snapshot.journalPath().toAbsolutePath().normalize();
      if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
         throw new IllegalArgumentException("forRecovery: journal file does not exist: " + journalPath);
      }

      Path transactionDir = journalPath.getParent().toAbsolutePath().normalize();
      return new DeleteTransactionJournal(
         transactionDir,
         snapshot.transactionId(),
         snapshot.boundOutputRoot(),
         snapshot.b0BaselineId(),
         snapshot.b1BaselineId(),
         snapshot.b0ManifestDigest(),
         snapshot.b1ManifestDigest(),
         snapshot.plannedFiles()
      );
   }

   public static DeleteTransactionJournal.ParseResult parse(Path path) {
      Objects.requireNonNull(path, "path");

      BasicFileAttributes attrs;
      try {
         attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException | SecurityException e) {
         return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "failed to read journal attributes: " + e.getMessage());
      }

      if (attrs.isSymbolicLink()) {
         return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "journal must not be a symlink: " + path);
      }

      if (!attrs.isRegularFile()) {
         return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "journal must be a regular file: " + path);
      }

      byte[] bytes;
      try {
         bytes = Files.readAllBytes(path);
      } catch (IOException | SecurityException e) {
         return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "failed to read journal bytes: " + e.getMessage());
      }

      return parseBytes(path, bytes);
   }

   static DeleteTransactionJournal.ParseResult parseBytes(Path path, byte[] bytes) {
      if (bytes.length < MAGIC.length) {
         return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "V3 journal too short for magic");
      }

      for (int i = 0; i < MAGIC.length; i++) {
         if (bytes[i] != MAGIC[i]) {
            return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "V3 journal magic mismatch");
         }
      }

      for (byte b : bytes) {
         if (b == 13) {
            return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "CR not allowed in journal");
         }
      }

      CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);

      String content;
      try {
         CharBuffer cb = decoder.decode(ByteBuffer.wrap(bytes, MAGIC.length, bytes.length - MAGIC.length));
         content = cb.toString();
      } catch (CharacterCodingException e) {
         return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "invalid UTF-8 in journal: " + e.getMessage());
      }

      String[] lines = content.split("\n", -1);
      if (lines.length != 0 && lines[lines.length - 1].isEmpty()) {
         String transactionId = null;
         String family = null;
         Path boundOutputRoot = null;
         String b0BaselineId = null;
         String b1BaselineId = null;
         String b0ManifestDigest = null;
         String b1ManifestDigest = null;
         String stagingRef = null;
         String backupRootRef = null;
         String backupLayout = null;
         String candidateBaselineRef = null;
         int fileCount = -1;
         Map<Integer, String> fileIndexToPath = new LinkedHashMap<>();
         Map<String, DeleteTransactionJournal.FileTransitionState> fileLatestState = new LinkedHashMap<>();
         Map<Integer, DeleteTransactionJournal.FileTransitionState> fileIndexLatestState = new LinkedHashMap<>();
         DeleteTransactionJournal.OverallState overallState = null;
         DeleteTransactionJournal.OverallState prevOverallState = null;
         int expectedHeader = 0;
         int expectedFileIndex = 0;
         boolean initialOverallDone = false;

         try {
            for (int i = 0; i < lines.length - 1; i++) {
               String line = lines[i];
               if (line.isEmpty()) {
                  return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "empty line in journal at " + i);
               }

               boolean inTransitionPhase = initialOverallDone;
               if (line.startsWith("transactionId=")) {
                  if (inTransitionPhase) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "transactionId header after initial section at line " + i);
                  }

                  if (expectedHeader != 0) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "transactionId header out of order at line " + i);
                  }

                  if (transactionId != null) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate transactionId header at line " + i);
                  }

                  transactionId = line.substring("transactionId=".length());
                  expectedHeader = 1;
               } else if (line.startsWith("family=")) {
                  if (inTransitionPhase) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "family header after initial section at line " + i);
                  }

                  if (expectedHeader != 1) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "family header out of order at line " + i);
                  }

                  if (family != null) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate family header at line " + i);
                  }

                  family = line.substring("family=".length());
                  if (!"DELETE".equals(family)) {
                     return new DeleteTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "unknown family '" + family + "' at line " + i + " (expected DELETE)"
                     );
                  }

                  expectedHeader = 2;
               } else if (line.startsWith("boundOutputRoot=")) {
                  if (inTransitionPhase) {
                     return new DeleteTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "boundOutputRoot header after initial section at line " + i
                     );
                  }

                  if (expectedHeader != 2) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "boundOutputRoot header out of order at line " + i);
                  }

                  if (boundOutputRoot != null) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate boundOutputRoot header at line " + i);
                  }

                  boundOutputRoot = Path.of(line.substring("boundOutputRoot=".length())).toAbsolutePath().normalize();
                  expectedHeader = 3;
               } else if (line.startsWith("b0BaselineId=")) {
                  if (inTransitionPhase) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "b0BaselineId header after initial section at line " + i);
                  }

                  if (expectedHeader != 3) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "b0BaselineId header out of order at line " + i);
                  }

                  if (b0BaselineId != null) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate b0BaselineId header at line " + i);
                  }

                  b0BaselineId = line.substring("b0BaselineId=".length());
                  expectedHeader = 4;
               } else if (line.startsWith("b1BaselineId=")) {
                  if (inTransitionPhase) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "b1BaselineId header after initial section at line " + i);
                  }

                  if (expectedHeader != 4) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "b1BaselineId header out of order at line " + i);
                  }

                  if (b1BaselineId != null) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate b1BaselineId header at line " + i);
                  }

                  b1BaselineId = line.substring("b1BaselineId=".length());
                  expectedHeader = 5;
               } else if (line.startsWith("b0ManifestDigest=")) {
                  if (inTransitionPhase) {
                     return new DeleteTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "b0ManifestDigest header after initial section at line " + i
                     );
                  }

                  if (expectedHeader != 5) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "b0ManifestDigest header out of order at line " + i);
                  }

                  if (b0ManifestDigest != null) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate b0ManifestDigest header at line " + i);
                  }

                  b0ManifestDigest = line.substring("b0ManifestDigest=".length());
                  expectedHeader = 6;
               } else if (line.startsWith("b1ManifestDigest=")) {
                  if (inTransitionPhase) {
                     return new DeleteTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "b1ManifestDigest header after initial section at line " + i
                     );
                  }

                  if (expectedHeader != 6) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "b1ManifestDigest header out of order at line " + i);
                  }

                  if (b1ManifestDigest != null) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate b1ManifestDigest header at line " + i);
                  }

                  b1ManifestDigest = line.substring("b1ManifestDigest=".length());
                  expectedHeader = 7;
               } else if (line.startsWith("stagingRef=")) {
                  if (inTransitionPhase) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "stagingRef header after initial section at line " + i);
                  }

                  if (expectedHeader != 7) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "stagingRef header out of order at line " + i);
                  }

                  if (stagingRef != null) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate stagingRef header at line " + i);
                  }

                  stagingRef = line.substring("stagingRef=".length());
                  if (!"NONE".equals(stagingRef)) {
                     return new DeleteTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "stagingRef must be 'NONE' at line " + i + " (got '" + stagingRef + "')"
                     );
                  }

                  expectedHeader = 8;
               } else if (line.startsWith("backupRootRef=")) {
                  if (inTransitionPhase) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "backupRootRef header after initial section at line " + i);
                  }

                  if (expectedHeader != 8) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "backupRootRef header out of order at line " + i);
                  }

                  if (backupRootRef != null) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate backupRootRef header at line " + i);
                  }

                  backupRootRef = line.substring("backupRootRef=".length());
                  if (!"backup".equals(backupRootRef)) {
                     return new DeleteTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "backupRootRef must be 'backup' at line " + i + " (got '" + backupRootRef + "')"
                     );
                  }

                  expectedHeader = 9;
               } else if (line.startsWith("backupLayout=")) {
                  if (inTransitionPhase) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "backupLayout header after initial section at line " + i);
                  }

                  if (expectedHeader != 9) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "backupLayout header out of order at line " + i);
                  }

                  if (backupLayout != null) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate backupLayout header at line " + i);
                  }

                  backupLayout = line.substring("backupLayout=".length());
                  if (!"INDEXED_B0_V1".equals(backupLayout)) {
                     return new DeleteTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "backupLayout must be 'INDEXED_B0_V1' at line " + i + " (got '" + backupLayout + "')"
                     );
                  }

                  expectedHeader = 10;
               } else if (line.startsWith("candidateBaselineRef=")) {
                  if (inTransitionPhase) {
                     return new DeleteTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "candidateBaselineRef header after initial section at line " + i
                     );
                  }

                  if (expectedHeader != 10) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "candidateBaselineRef header out of order at line " + i);
                  }

                  if (candidateBaselineRef != null) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate candidateBaselineRef header at line " + i);
                  }

                  candidateBaselineRef = line.substring("candidateBaselineRef=".length());
                  if (!"candidate-baseline".equals(candidateBaselineRef)) {
                     return new DeleteTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002",
                        "candidateBaselineRef must be 'candidate-baseline' at line " + i + " (got '" + candidateBaselineRef + "')"
                     );
                  }

                  expectedHeader = 11;
               } else if (line.startsWith("fileCount=")) {
                  if (inTransitionPhase) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "fileCount header after initial section at line " + i);
                  }

                  if (expectedHeader != 11) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "fileCount header out of order at line " + i);
                  }

                  if (fileCount >= 0) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate fileCount header at line " + i);
                  }

                  fileCount = Integer.parseInt(line.substring("fileCount=".length()), 10);
                  if (fileCount < 0) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "fileCount must not be negative at line " + i);
                  }

                  expectedHeader = 12;
               } else if (line.startsWith("file ")) {
                  int stateEq = line.indexOf(" state=");
                  if (stateEq < 0) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "malformed file line at " + i + ": " + line);
                  }

                  String header = line.substring("file ".length(), stateEq);
                  String stateName = line.substring(stateEq + " state=".length());
                  int space = header.indexOf(32);
                  if (space < 0) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "malformed file header at " + i + ": " + line);
                  }

                  int fileIndex = Integer.parseInt(header.substring(0, space), 10);
                  String relPath = header.substring(space + 1);
                  DeleteTransactionJournal.FileTransitionState newState = DeleteTransactionJournal.FileTransitionState.valueOf(stateName);
                  String pathError = TransactionJournal.validateRelativePath(relPath);
                  if (pathError != null) {
                     return new DeleteTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "unsafe file relative path at line " + i + ": " + pathError + ": " + relPath
                     );
                  }

                  if (!inTransitionPhase) {
                     if (expectedHeader != 12) {
                        return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "file record before all headers complete at line " + i);
                     }

                     if (fileCount < 0) {
                        return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "file record before fileCount at line " + i);
                     }

                     if (fileIndex != expectedFileIndex) {
                        return new DeleteTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file index out of order at line " + i + ": expected " + expectedFileIndex + " got " + fileIndex
                        );
                     }

                     if (newState != DeleteTransactionJournal.FileTransitionState.PREPARING) {
                        return new DeleteTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " initial state must be PREPARING, got " + newState
                        );
                     }

                     if (fileLatestState.containsKey(relPath)) {
                        return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate file relative path in journal: " + relPath);
                     }

                     if (expectedFileIndex > 0) {
                        String prevPath = fileIndexToPath.get(expectedFileIndex - 1);
                        if (prevPath.compareTo(relPath) >= 0) {
                           return new DeleteTransactionJournal.ParseFailure(
                              "SIR-APP-CHANGE-RECOVERY-002",
                              "file " + fileIndex + " out of canonical order at line " + i + ": prev=" + prevPath + " cur=" + relPath
                           );
                        }
                     }

                     fileIndexToPath.put(fileIndex, relPath);
                     fileLatestState.put(relPath, newState);
                     fileIndexLatestState.put(fileIndex, newState);
                     expectedFileIndex++;
                  } else {
                     if (!fileIndexLatestState.containsKey(fileIndex)) {
                        return new DeleteTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " transition references unregistered index at line " + i
                        );
                     }

                     String registeredPath = fileIndexToPath.get(fileIndex);
                     if (!registeredPath.equals(relPath)) {
                        return new DeleteTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " relative path mismatch: expected " + registeredPath + " got " + relPath
                        );
                     }

                     DeleteTransactionJournal.FileTransitionState prevState = fileIndexLatestState.get(fileIndex);
                     if (prevState == newState) {
                        return new DeleteTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " duplicate transition to " + newState
                        );
                     }

                     if (!DeleteTransactionJournal.FileTransitionState.isValidTransition(prevState, newState)) {
                        return new DeleteTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " invalid transition: " + prevState + " -> " + newState
                        );
                     }

                     fileLatestState.put(relPath, newState);
                     fileIndexLatestState.put(fileIndex, newState);
                  }
               } else {
                  if (!line.startsWith("overallState=")) {
                     return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "unknown journal line at " + i + ": " + line);
                  }

                  DeleteTransactionJournal.OverallState newOverall = DeleteTransactionJournal.OverallState.valueOf(line.substring("overallState=".length()));
                  if (!initialOverallDone) {
                     if (newOverall != DeleteTransactionJournal.OverallState.PREPARING) {
                        return new DeleteTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "first overallState must be PREPARING, got " + newOverall
                        );
                     }

                     if (expectedHeader != 12) {
                        return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "overallState before all headers complete at line " + i);
                     }

                     if (fileCount < 0) {
                        return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "overallState before fileCount at line " + i);
                     }

                     if (expectedFileIndex != fileCount) {
                        return new DeleteTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002",
                           "overallState before all initial file records at line " + i + ": expected " + fileCount + " files but saw " + expectedFileIndex
                        );
                     }

                     initialOverallDone = true;
                  } else if (prevOverallState != null && newOverall.ordinal() <= prevOverallState.ordinal()) {
                     return new DeleteTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "overallState non-monotonic: " + prevOverallState + " -> " + newOverall
                     );
                  }

                  prevOverallState = newOverall;
                  overallState = newOverall;
               }
            }
         } catch (IllegalArgumentException e) {
            return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "V3 journal parse failure: " + e.getMessage());
         }

         if (transactionId == null
            || family == null
            || boundOutputRoot == null
            || b0BaselineId == null
            || b1BaselineId == null
            || b0ManifestDigest == null
            || b1ManifestDigest == null
            || stagingRef == null
            || backupRootRef == null
            || backupLayout == null
            || candidateBaselineRef == null
            || fileCount < 0
            || overallState == null) {
            return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "V3 journal missing required header field");
         }

         if (!TransactionJournal.isValidTransactionId(transactionId)) {
            return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "invalid transactionId format: " + transactionId);
         }

         if (!TransactionJournal.isValidBaselineId(b0BaselineId)) {
            return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "invalid b0BaselineId format: " + b0BaselineId);
         }

         if (!TransactionJournal.isValidBaselineId(b1BaselineId)) {
            return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "invalid b1BaselineId format: " + b1BaselineId);
         }

         if (!TransactionJournal.isValidBaselineId(b0ManifestDigest)) {
            return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "invalid b0ManifestDigest format: " + b0ManifestDigest);
         }

         if (!TransactionJournal.isValidBaselineId(b1ManifestDigest)) {
            return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "invalid b1ManifestDigest format: " + b1ManifestDigest);
         }

         if (fileIndexToPath.size() != fileCount) {
            return new DeleteTransactionJournal.ParseFailure(
               "SIR-APP-CHANGE-RECOVERY-002", "fileCount=" + fileCount + " but actual file records=" + fileIndexToPath.size()
            );
         }

         List<String> plannedFiles = new ArrayList<>(fileCount);

         for (int i = 0; i < fileCount; i++) {
            plannedFiles.add(fileIndexToPath.get(i));
         }

         return new DeleteTransactionJournal.ParseOk(
            new DeleteTransactionJournal.Snapshot(
               path,
               transactionId,
               family,
               boundOutputRoot,
               b0BaselineId,
               b1BaselineId,
               b0ManifestDigest,
               b1ManifestDigest,
               plannedFiles,
               overallState,
               Map.copyOf(fileLatestState)
            )
         );
      } else {
         return new DeleteTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "journal must end with LF");
      }
   }

   public enum FileTransitionState {
      PREPARING,
      BACKUP_LINK_INTENT_DURABLE,
      BACKUP_LINKED_DURABLE,
      DELETE_INTENT_DURABLE,
      DELETED_DURABLE,
      RESTORE_LINK_INTENT_DURABLE,
      RESTORED_DURABLE,
      ROLLED_BACK_DURABLE;

      /**
       * The transition table is deliberately strict: every durable
       * journal state is reached through exactly one legal predecessor, so a
       * parsed journal can never be ambiguous about how far a file got.
       * ROLLED_BACK_DURABLE is terminal. Verified by
       * RecoveryStateMachineTest.journalFileStateTransitionsAreStrict.
       */
      static boolean isValidTransition(DeleteTransactionJournal.FileTransitionState from, DeleteTransactionJournal.FileTransitionState to) {
         return switch (from) {
            case PREPARING -> to == BACKUP_LINK_INTENT_DURABLE;
            case BACKUP_LINK_INTENT_DURABLE -> to == BACKUP_LINKED_DURABLE;
            case BACKUP_LINKED_DURABLE -> to == DELETE_INTENT_DURABLE;
            case DELETE_INTENT_DURABLE -> to == DELETED_DURABLE || to == RESTORE_LINK_INTENT_DURABLE;
            case DELETED_DURABLE -> to == RESTORE_LINK_INTENT_DURABLE;
            case RESTORE_LINK_INTENT_DURABLE -> to == RESTORED_DURABLE;
            case RESTORED_DURABLE -> to == ROLLED_BACK_DURABLE;
            case ROLLED_BACK_DURABLE -> false;
         };
      }

      public boolean isBackupLinked() {
         return this == BACKUP_LINKED_DURABLE
            || this == DELETE_INTENT_DURABLE
            || this == DELETED_DURABLE
            || this == RESTORE_LINK_INTENT_DURABLE
            || this == RESTORED_DURABLE
            || this == ROLLED_BACK_DURABLE;
      }

      public boolean isDeleted() {
         return this == DELETED_DURABLE || this == RESTORE_LINK_INTENT_DURABLE || this == RESTORED_DURABLE || this == ROLLED_BACK_DURABLE;
      }

      public boolean isRestored() {
         return this == RESTORED_DURABLE || this == ROLLED_BACK_DURABLE;
      }

      public boolean isRolledBack() {
         return this == ROLLED_BACK_DURABLE;
      }

      public boolean isDeleteIntent() {
         return this == DELETE_INTENT_DURABLE;
      }

      public boolean isBackupLinkIntent() {
         return this == BACKUP_LINK_INTENT_DURABLE;
      }
   }

   public enum OverallState {
      PREPARING,
      PREPARED,
      COMMITTING,
      FILES_COMMITTED,
      PUBLISHING,
      BASELINE_PUBLISHED,
      CLEANUP_PENDING,
      COMPLETED,
      ROLLING_BACK,
      ROLLED_BACK;
   }

   public record ParseFailure(String code, String message) implements DeleteTransactionJournal.ParseResult {
      public ParseFailure {
         Objects.requireNonNull(code, "code");
         Objects.requireNonNull(message, "message");
      }
   }

   public record ParseOk(DeleteTransactionJournal.Snapshot snapshot) implements DeleteTransactionJournal.ParseResult {
      public ParseOk {
         Objects.requireNonNull(snapshot, "snapshot");
      }
   }

   public sealed interface ParseResult permits DeleteTransactionJournal.ParseOk, DeleteTransactionJournal.ParseFailure {
   }

   public record Snapshot(
      Path journalPath,
      String transactionId,
      String family,
      Path boundOutputRoot,
      String b0BaselineId,
      String b1BaselineId,
      String b0ManifestDigest,
      String b1ManifestDigest,
      List<String> plannedFiles,
      DeleteTransactionJournal.OverallState overallState,
      Map<String, DeleteTransactionJournal.FileTransitionState> fileStates
   ) {
      public Snapshot {
         Objects.requireNonNull(journalPath, "journalPath");
         Objects.requireNonNull(transactionId, "transactionId");
         Objects.requireNonNull(family, "family");
         Objects.requireNonNull(boundOutputRoot, "boundOutputRoot");
         Objects.requireNonNull(b0BaselineId, "b0BaselineId");
         Objects.requireNonNull(b1BaselineId, "b1BaselineId");
         Objects.requireNonNull(b0ManifestDigest, "b0ManifestDigest");
         Objects.requireNonNull(b1ManifestDigest, "b1ManifestDigest");
         plannedFiles = List.copyOf(Objects.requireNonNull(plannedFiles, "plannedFiles"));
         Objects.requireNonNull(overallState, "overallState");
         fileStates = Map.copyOf(Objects.requireNonNull(fileStates, "fileStates"));
      }

      public boolean isActive() {
         return this.overallState != DeleteTransactionJournal.OverallState.COMPLETED;
      }
   }
}
