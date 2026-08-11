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

public final class TransactionJournal {
   public static final byte[] MAGIC = "KCG-CHANGE-TRANSACTION-JOURNAL-V1\n".getBytes(StandardCharsets.US_ASCII);
   public static final String TRANSACTIONS_DIR = "transactions";
   public static final String JOURNAL_FILE = "journal";
   private final Path journalPath;
   private final String transactionId;
   private final Path boundOutputRoot;
   private final String b0BaselineId;
   private final String b1BaselineId;
   private final List<String> plannedFiles;

   public TransactionJournal(
      Path transactionDir, String transactionId, Path boundOutputRoot, String b0BaselineId, String b1BaselineId, List<String> plannedFiles
   ) {
      this.journalPath = Objects.requireNonNull(transactionDir, "transactionDir").resolve("journal").toAbsolutePath().normalize();
      this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
      this.boundOutputRoot = Objects.requireNonNull(boundOutputRoot, "boundOutputRoot").toAbsolutePath().normalize();
      this.b0BaselineId = Objects.requireNonNull(b0BaselineId, "b0BaselineId");
      this.b1BaselineId = Objects.requireNonNull(b1BaselineId, "b1BaselineId");
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

   public List<String> plannedFiles() {
      return this.plannedFiles;
   }

   public void create() throws IOException {
      Files.createDirectories(this.journalPath.getParent());
      if (Files.exists(this.journalPath, LinkOption.NOFOLLOW_LINKS)) {
         throw new IOException("journal already exists: " + this.journalPath);
      }

      StringBuilder sb = new StringBuilder();
      sb.append(new String(MAGIC, StandardCharsets.US_ASCII));
      sb.append("transactionId=").append(this.transactionId).append('\n');
      sb.append("boundOutputRoot=").append(this.boundOutputRoot).append('\n');
      sb.append("b0BaselineId=").append(this.b0BaselineId).append('\n');
      sb.append("b1BaselineId=").append(this.b1BaselineId).append('\n');
      sb.append("fileCount=").append(this.plannedFiles.size()).append('\n');

      for (int i = 0; i < this.plannedFiles.size(); i++) {
         sb.append("file ").append(i).append(' ').append(this.plannedFiles.get(i)).append(" state=PREPARING\n");
      }

      sb.append("overallState=PREPARING\n");
      Files.write(
         this.journalPath, sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC
      );
   }

   public void appendFileTransition(int fileIndex, String relativePath, TransactionJournal.FileTransitionState newState) throws IOException {
      Objects.requireNonNull(newState, "newState");
      String line = "file " + fileIndex + " " + relativePath + " state=" + newState.name() + "\n";
      Files.write(this.journalPath, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
   }

   public void appendOverallTransition(TransactionJournal.OverallState newState) throws IOException {
      Objects.requireNonNull(newState, "newState");
      String line = "overallState=" + newState.name() + "\n";
      Files.write(this.journalPath, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
   }

   public static TransactionJournal.ParseResult parse(Path path) {
      Objects.requireNonNull(path, "path");

      BasicFileAttributes attrs;
      try {
         attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException | SecurityException e) {
         return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "failed to read journal attributes: " + e.getMessage());
      }

      if (attrs.isSymbolicLink()) {
         return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "journal must not be a symlink: " + path);
      }

      if (!attrs.isRegularFile()) {
         return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "journal must be a regular file: " + path);
      }

      byte[] bytes;
      try {
         bytes = Files.readAllBytes(path);
      } catch (IOException | SecurityException e) {
         return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "failed to read journal bytes: " + e.getMessage());
      }

      return parseBytes(path, bytes);
   }

   static TransactionJournal.ParseResult parseBytes(Path path, byte[] bytes) {
      if (bytes.length < MAGIC.length) {
         return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "journal too short for magic");
      }

      for (int i = 0; i < MAGIC.length; i++) {
         if (bytes[i] != MAGIC[i]) {
            return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "journal magic mismatch");
         }
      }

      for (byte b : bytes) {
         if (b == 13) {
            return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "CR not allowed in journal");
         }
      }

      CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);

      String content;
      try {
         CharBuffer cb = decoder.decode(ByteBuffer.wrap(bytes, MAGIC.length, bytes.length - MAGIC.length));
         content = cb.toString();
      } catch (CharacterCodingException e) {
         return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "invalid UTF-8 in journal: " + e.getMessage());
      }

      String[] lines = content.split("\n", -1);
      if (lines.length != 0 && lines[lines.length - 1].isEmpty()) {
         String transactionId = null;
         Path boundOutputRoot = null;
         String b0BaselineId = null;
         String b1BaselineId = null;
         int fileCount = -1;
         Map<Integer, String> fileIndexToPath = new LinkedHashMap<>();
         Map<String, TransactionJournal.FileTransitionState> fileLatestState = new LinkedHashMap<>();
         Map<Integer, TransactionJournal.FileTransitionState> indexLatestState = new LinkedHashMap<>();
         TransactionJournal.OverallState overallState = null;
         TransactionJournal.OverallState prevOverallState = null;
         int expectedHeader = 0;
         int expectedFileIndex = 0;
         boolean initialOverallDone = false;

         try {
            for (int i = 0; i < lines.length - 1; i++) {
               String line = lines[i];
               if (line.isEmpty()) {
                  return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "empty line in journal at " + i);
               }

               boolean inTransitionPhase = initialOverallDone;
               if (line.startsWith("transactionId=")) {
                  if (inTransitionPhase) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "transactionId header after initial section at line " + i);
                  }

                  if (expectedHeader != 0) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "transactionId header out of order at line " + i);
                  }

                  if (transactionId != null) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate transactionId header at line " + i);
                  }

                  transactionId = line.substring("transactionId=".length());
                  expectedHeader = 1;
               } else if (line.startsWith("boundOutputRoot=")) {
                  if (inTransitionPhase) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "boundOutputRoot header after initial section at line " + i);
                  }

                  if (expectedHeader != 1) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "boundOutputRoot header out of order at line " + i);
                  }

                  if (boundOutputRoot != null) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate boundOutputRoot header at line " + i);
                  }

                  boundOutputRoot = Path.of(line.substring("boundOutputRoot=".length())).toAbsolutePath().normalize();
                  expectedHeader = 2;
               } else if (line.startsWith("b0BaselineId=")) {
                  if (inTransitionPhase) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "b0BaselineId header after initial section at line " + i);
                  }

                  if (expectedHeader != 2) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "b0BaselineId header out of order at line " + i);
                  }

                  if (b0BaselineId != null) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate b0BaselineId header at line " + i);
                  }

                  b0BaselineId = line.substring("b0BaselineId=".length());
                  expectedHeader = 3;
               } else if (line.startsWith("b1BaselineId=")) {
                  if (inTransitionPhase) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "b1BaselineId header after initial section at line " + i);
                  }

                  if (expectedHeader != 3) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "b1BaselineId header out of order at line " + i);
                  }

                  if (b1BaselineId != null) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate b1BaselineId header at line " + i);
                  }

                  b1BaselineId = line.substring("b1BaselineId=".length());
                  expectedHeader = 4;
               } else if (line.startsWith("fileCount=")) {
                  if (inTransitionPhase) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "fileCount header after initial section at line " + i);
                  }

                  if (expectedHeader != 4) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "fileCount header out of order at line " + i);
                  }

                  if (fileCount >= 0) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate fileCount header at line " + i);
                  }

                  fileCount = Integer.parseInt(line.substring("fileCount=".length()), 10);
                  if (fileCount < 0) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "fileCount must not be negative at line " + i);
                  }

                  expectedHeader = 5;
               } else if (line.startsWith("file ")) {
                  int stateEq = line.indexOf(" state=");
                  if (stateEq < 0) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "malformed file line at " + i + ": " + line);
                  }

                  String header = line.substring("file ".length(), stateEq);
                  String stateName = line.substring(stateEq + " state=".length());
                  int space = header.indexOf(32);
                  if (space < 0) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "malformed file header at " + i + ": " + line);
                  }

                  int fileIndex = Integer.parseInt(header.substring(0, space), 10);
                  String relPath = header.substring(space + 1);
                  TransactionJournal.FileTransitionState newState = TransactionJournal.FileTransitionState.valueOf(stateName);
                  String pathError = validateRelativePath(relPath);
                  if (pathError != null) {
                     return new TransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "unsafe relative path at line " + i + ": " + pathError + ": " + relPath
                     );
                  }

                  if (!inTransitionPhase) {
                     if (expectedHeader != 5) {
                        return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "file record before all headers complete at line " + i);
                     }

                     if (fileCount < 0) {
                        return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "file record before fileCount at line " + i);
                     }

                     if (fileIndex != expectedFileIndex) {
                        return new TransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file index out of order at line " + i + ": expected " + expectedFileIndex + " got " + fileIndex
                        );
                     }

                     if (newState != TransactionJournal.FileTransitionState.PREPARING) {
                        return new TransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " initial state must be PREPARING, got " + newState
                        );
                     }

                     if (fileLatestState.containsKey(relPath)) {
                        return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate relative path in journal: " + relPath);
                     }

                     fileIndexToPath.put(fileIndex, relPath);
                     fileLatestState.put(relPath, newState);
                     indexLatestState.put(fileIndex, newState);
                     expectedFileIndex++;
                  } else {
                     if (!indexLatestState.containsKey(fileIndex)) {
                        return new TransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " transition references unregistered index at line " + i
                        );
                     }

                     String registeredPath = fileIndexToPath.get(fileIndex);
                     if (!registeredPath.equals(relPath)) {
                        return new TransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " relative path mismatch: expected " + registeredPath + " got " + relPath
                        );
                     }

                     TransactionJournal.FileTransitionState prevState = indexLatestState.get(fileIndex);
                     if (prevState == newState) {
                        return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " duplicate transition to " + newState);
                     }

                     if (!TransactionJournal.FileTransitionState.isValidTransition(prevState, newState)) {
                        return new TransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " invalid transition: " + prevState + " -> " + newState
                        );
                     }

                     fileLatestState.put(relPath, newState);
                     indexLatestState.put(fileIndex, newState);
                  }
               } else {
                  if (!line.startsWith("overallState=")) {
                     return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "unknown journal line at " + i + ": " + line);
                  }

                  TransactionJournal.OverallState newOverall = TransactionJournal.OverallState.valueOf(line.substring("overallState=".length()));
                  if (!initialOverallDone) {
                     if (newOverall != TransactionJournal.OverallState.PREPARING) {
                        return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "first overallState must be PREPARING, got " + newOverall);
                     }

                     if (expectedHeader != 5) {
                        return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "overallState before all headers complete at line " + i);
                     }

                     if (fileCount < 0) {
                        return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "overallState before fileCount at line " + i);
                     }

                     if (expectedFileIndex != fileCount) {
                        return new TransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002",
                           "overallState before all initial file records at line " + i + ": expected " + fileCount + " files but saw " + expectedFileIndex
                        );
                     }

                     initialOverallDone = true;
                  } else if (prevOverallState != null && newOverall.ordinal() <= prevOverallState.ordinal()) {
                     return new TransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "overallState non-monotonic: " + prevOverallState + " -> " + newOverall
                     );
                  }

                  prevOverallState = newOverall;
                  overallState = newOverall;
               }
            }
         } catch (IllegalArgumentException e) {
            return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "journal parse failure: " + e.getMessage());
         }

         if (transactionId == null || boundOutputRoot == null || b0BaselineId == null || b1BaselineId == null || fileCount < 0 || overallState == null) {
            return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "journal missing required header field");
         }

         if (!isValidTransactionId(transactionId)) {
            return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "invalid transactionId format: " + transactionId);
         }

         if (!isValidBaselineId(b0BaselineId)) {
            return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "invalid b0BaselineId format: " + b0BaselineId);
         }

         if (!isValidBaselineId(b1BaselineId)) {
            return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "invalid b1BaselineId format: " + b1BaselineId);
         }

         if (fileIndexToPath.size() != fileCount) {
            return new TransactionJournal.ParseFailure(
               "SIR-APP-CHANGE-RECOVERY-002", "fileCount=" + fileCount + " but actual file records=" + fileIndexToPath.size()
            );
         }

         for (int i = 0; i < fileCount; i++) {
            if (!fileIndexToPath.containsKey(i)) {
               return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "missing file index " + i + " (fileCount=" + fileCount + ")");
            }
         }

         List<String> plannedFiles = new ArrayList<>(fileCount);

         for (int i = 0; i < fileCount; i++) {
            plannedFiles.add(fileIndexToPath.get(i));
         }

         return new TransactionJournal.ParseOk(
            new TransactionJournal.Snapshot(
               path, transactionId, boundOutputRoot, b0BaselineId, b1BaselineId, plannedFiles, overallState, Map.copyOf(fileLatestState)
            )
         );
      } else {
         return new TransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "journal must end with LF");
      }
   }

   static boolean isValidTransactionId(String id) {
      if (id != null && id.length() == 32) {
         for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   static boolean isValidBaselineId(String id) {
      if (id != null && id.length() == 64) {
         for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private static final java.util.Set<String> WINDOWS_RESERVED_NAMES =
         java.util.Set.of("CON", "PRN", "AUX", "NUL",
               "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
               "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

   static String validateRelativePath(String relPath) {
      if (relPath == null || relPath.isEmpty()) {
         return "relative path must not be null or empty";
      }

      if (relPath.startsWith("/")) {
         return "relative path must not start with '/'";
      }

      if (relPath.length() >= 2 && relPath.charAt(1) == ':') {
         return "relative path must not contain a drive letter";
      }

      if (relPath.contains("\\\\")) {
         return "relative path must not contain backslash";
      }

      if (!relPath.contains("\n") && !relPath.contains("\r")) {
         if (relPath.contains("\u0000")) {
            return "relative path must not contain NUL";
         }

         String[] segments = relPath.split("/", -1);

         for (String seg : segments) {
            if (seg.isEmpty()) {
               return "relative path must not contain empty segment (double slash)";
            }

            if (seg.equals(".")) {
               return "relative path must not contain '.' segment";
            }

            if (seg.equals("..")) {
               return "relative path must not contain '..' segment";
            }

            // [RQ-09] Review finding A: align with PathGuard's segment strength.
            // Windows normalizes trailing dot/space away ("file." -> "file"), so two
            // distinct plan paths could silently collide on one physical file; reserved
            // device names and illegal characters would either collide or fail
            // unpredictably at create time. Fail closed here instead.
            String segmentError = validateSegment(seg);
            if (segmentError != null) {
               return segmentError;
            }
         }

         return null;
      } else {
         return "relative path must not contain newline";
      }
   }

   private static String validateSegment(String seg) {
      char last = seg.charAt(seg.length() - 1);
      if (last == '.' || last == ' ') {
         return "segment must not end with dot or space: " + seg;
      }
      for (int i = 0; i < seg.length(); i++) {
         char c = seg.charAt(i);
         if (c < ' ' || c == 127) {
            return "segment contains control character: " + seg;
         }
         if ("<>:\"|?*".indexOf(c) >= 0) {
            return "segment contains illegal character '" + c + "': " + seg;
         }
      }
      String base = seg;
      int dot = seg.indexOf('.');
      if (dot > 0) {
         base = seg.substring(0, dot);
      }
      if (WINDOWS_RESERVED_NAMES.contains(base.toUpperCase(java.util.Locale.ROOT))) {
         return "segment uses Windows reserved name: " + base;
      }
      return null;
   }

   public enum FileTransitionState {
      PREPARING,
      BACKUP_INTENT_DURABLE,
      BACKED_UP_DURABLE,
      COMMIT_INTENT_DURABLE,
      COMMITTED_DURABLE,
      ROLLBACK_INTENT_DURABLE,
      ROLLED_BACK_DURABLE;

      static boolean isValidTransition(TransactionJournal.FileTransitionState from, TransactionJournal.FileTransitionState to) {
         return switch (from) {
            case PREPARING -> to == BACKUP_INTENT_DURABLE;
            case BACKUP_INTENT_DURABLE -> to == BACKED_UP_DURABLE || to == ROLLBACK_INTENT_DURABLE;
            case BACKED_UP_DURABLE -> to == COMMIT_INTENT_DURABLE || to == ROLLBACK_INTENT_DURABLE;
            case COMMIT_INTENT_DURABLE -> to == COMMITTED_DURABLE || to == ROLLBACK_INTENT_DURABLE;
            case COMMITTED_DURABLE -> to == ROLLBACK_INTENT_DURABLE;
            case ROLLBACK_INTENT_DURABLE -> to == ROLLED_BACK_DURABLE;
            case ROLLED_BACK_DURABLE -> false;
         };
      }

      public boolean isBackedUp() {
         return this == BACKED_UP_DURABLE || this == COMMIT_INTENT_DURABLE || this == COMMITTED_DURABLE;
      }

      public boolean isCommitted() {
         return this == COMMITTED_DURABLE;
      }

      public boolean isRolledBack() {
         return this == ROLLED_BACK_DURABLE;
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
      COMPLETED;
   }

   public record ParseFailure(String code, String message) implements TransactionJournal.ParseResult {
      public ParseFailure {
         Objects.requireNonNull(code, "code");
         Objects.requireNonNull(message, "message");
      }
   }

   public record ParseOk(TransactionJournal.Snapshot snapshot) implements TransactionJournal.ParseResult {
      public ParseOk {
         Objects.requireNonNull(snapshot, "snapshot");
      }
   }

   public sealed interface ParseResult permits TransactionJournal.ParseOk, TransactionJournal.ParseFailure {
   }

   public record Snapshot(
      Path journalPath,
      String transactionId,
      Path boundOutputRoot,
      String b0BaselineId,
      String b1BaselineId,
      List<String> plannedFiles,
      TransactionJournal.OverallState overallState,
      Map<String, TransactionJournal.FileTransitionState> fileStates
   ) {
      public Snapshot {
         Objects.requireNonNull(journalPath, "journalPath");
         Objects.requireNonNull(transactionId, "transactionId");
         Objects.requireNonNull(boundOutputRoot, "boundOutputRoot");
         Objects.requireNonNull(b0BaselineId, "b0BaselineId");
         Objects.requireNonNull(b1BaselineId, "b1BaselineId");
         plannedFiles = List.copyOf(Objects.requireNonNull(plannedFiles, "plannedFiles"));
         fileStates = Map.copyOf(Objects.requireNonNull(fileStates, "fileStates"));
      }

      public boolean isActive() {
         return this.overallState != TransactionJournal.OverallState.COMPLETED;
      }
   }
}
