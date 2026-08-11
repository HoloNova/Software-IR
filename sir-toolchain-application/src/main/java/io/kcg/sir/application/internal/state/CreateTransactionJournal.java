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

public final class CreateTransactionJournal {
   public static final byte[] MAGIC = "KCG-CHANGE-TRANSACTION-JOURNAL-V2\n".getBytes(StandardCharsets.US_ASCII);
   public static final String FAMILY = "CREATE";
   public static final String JOURNAL_FILE = "journal";
   private final Path journalPath;
   private final String transactionId;
   private final Path boundOutputRoot;
   private final String b0BaselineId;
   private final String b1BaselineId;
   private final List<String> plannedDirectories;
   private final List<String> plannedFiles;

   public CreateTransactionJournal(
      Path transactionDir,
      String transactionId,
      Path boundOutputRoot,
      String b0BaselineId,
      String b1BaselineId,
      List<String> plannedDirectories,
      List<String> plannedFiles
   ) {
      this.journalPath = Objects.requireNonNull(transactionDir, "transactionDir").resolve("journal").toAbsolutePath().normalize();
      this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
      this.boundOutputRoot = Objects.requireNonNull(boundOutputRoot, "boundOutputRoot").toAbsolutePath().normalize();
      this.b0BaselineId = Objects.requireNonNull(b0BaselineId, "b0BaselineId");
      this.b1BaselineId = Objects.requireNonNull(b1BaselineId, "b1BaselineId");
      this.plannedDirectories = List.copyOf(Objects.requireNonNull(plannedDirectories, "plannedDirectories"));
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

   public List<String> plannedDirectories() {
      return this.plannedDirectories;
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
      sb.append("family=").append("CREATE").append('\n');
      sb.append("boundOutputRoot=").append(this.boundOutputRoot).append('\n');
      sb.append("b0BaselineId=").append(this.b0BaselineId).append('\n');
      sb.append("b1BaselineId=").append(this.b1BaselineId).append('\n');
      sb.append("directoryCount=").append(this.plannedDirectories.size()).append('\n');

      for (int i = 0; i < this.plannedDirectories.size(); i++) {
         sb.append("directory ").append(i).append(' ').append(this.plannedDirectories.get(i)).append(" state=PREPARING\n");
      }

      sb.append("fileCount=").append(this.plannedFiles.size()).append('\n');

      for (int i = 0; i < this.plannedFiles.size(); i++) {
         sb.append("file ").append(i).append(' ').append(this.plannedFiles.get(i)).append(" state=PREPARING\n");
      }

      sb.append("overallState=PREPARING\n");
      Files.write(
         this.journalPath, sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC
      );
   }

   public void appendDirectoryTransition(int directoryIndex, String relativePath, CreateTransactionJournal.DirectoryTransitionState newState) throws IOException {
      Objects.requireNonNull(newState, "newState");
      String line = "directory " + directoryIndex + " " + relativePath + " state=" + newState.name() + "\n";
      Files.write(this.journalPath, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
   }

   public void appendFileTransition(int fileIndex, String relativePath, CreateTransactionJournal.FileTransitionState newState) throws IOException {
      Objects.requireNonNull(newState, "newState");
      String line = "file " + fileIndex + " " + relativePath + " state=" + newState.name() + "\n";
      Files.write(this.journalPath, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
   }

   public void appendOverallTransition(CreateTransactionJournal.OverallState newState) throws IOException {
      Objects.requireNonNull(newState, "newState");
      String line = "overallState=" + newState.name() + "\n";
      Files.write(this.journalPath, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
   }

   public static CreateTransactionJournal forRecovery(CreateTransactionJournal.Snapshot snapshot) {
      Objects.requireNonNull(snapshot, "snapshot");
      Path journalPath = snapshot.journalPath().toAbsolutePath().normalize();
      if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
         throw new IllegalArgumentException("forRecovery: journal file does not exist: " + journalPath);
      }

      Path transactionDir = journalPath.getParent().toAbsolutePath().normalize();
      return new CreateTransactionJournal(
         transactionDir,
         snapshot.transactionId(),
         snapshot.boundOutputRoot(),
         snapshot.b0BaselineId(),
         snapshot.b1BaselineId(),
         snapshot.plannedDirectories(),
         snapshot.plannedFiles()
      );
   }

   public static CreateTransactionJournal.ParseResult parse(Path path) {
      Objects.requireNonNull(path, "path");

      BasicFileAttributes attrs;
      try {
         attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException | SecurityException e) {
         return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "failed to read journal attributes: " + e.getMessage());
      }

      if (attrs.isSymbolicLink()) {
         return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "journal must not be a symlink: " + path);
      }

      if (!attrs.isRegularFile()) {
         return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "journal must be a regular file: " + path);
      }

      byte[] bytes;
      try {
         bytes = Files.readAllBytes(path);
      } catch (IOException | SecurityException e) {
         return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "failed to read journal bytes: " + e.getMessage());
      }

      return parseBytes(path, bytes);
   }

   static CreateTransactionJournal.ParseResult parseBytes(Path path, byte[] bytes) {
      if (bytes.length < MAGIC.length) {
         return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "V2 journal too short for magic");
      }

      for (int i = 0; i < MAGIC.length; i++) {
         if (bytes[i] != MAGIC[i]) {
            return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "V2 journal magic mismatch");
         }
      }

      for (byte b : bytes) {
         if (b == 13) {
            return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "CR not allowed in journal");
         }
      }

      CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);

      String content;
      try {
         CharBuffer cb = decoder.decode(ByteBuffer.wrap(bytes, MAGIC.length, bytes.length - MAGIC.length));
         content = cb.toString();
      } catch (CharacterCodingException e) {
         return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "invalid UTF-8 in journal: " + e.getMessage());
      }

      String[] lines = content.split("\n", -1);
      if (lines.length != 0 && lines[lines.length - 1].isEmpty()) {
         String transactionId = null;
         String family = null;
         Path boundOutputRoot = null;
         String b0BaselineId = null;
         String b1BaselineId = null;
         int directoryCount = -1;
         int fileCount = -1;
         Map<Integer, String> directoryIndexToPath = new LinkedHashMap<>();
         Map<String, CreateTransactionJournal.DirectoryTransitionState> directoryLatestState = new LinkedHashMap<>();
         Map<Integer, CreateTransactionJournal.DirectoryTransitionState> directoryIndexLatestState = new LinkedHashMap<>();
         Map<Integer, String> fileIndexToPath = new LinkedHashMap<>();
         Map<String, CreateTransactionJournal.FileTransitionState> fileLatestState = new LinkedHashMap<>();
         Map<Integer, CreateTransactionJournal.FileTransitionState> fileIndexLatestState = new LinkedHashMap<>();
         CreateTransactionJournal.OverallState overallState = null;
         CreateTransactionJournal.OverallState prevOverallState = null;
         int expectedHeader = 0;
         int expectedDirectoryIndex = 0;
         boolean directoryInitialDone = false;
         int expectedFileIndex = 0;
         boolean fileInitialDone = false;
         boolean initialOverallDone = false;

         try {
            for (int i = 0; i < lines.length - 1; i++) {
               String line = lines[i];
               if (line.isEmpty()) {
                  return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "empty line in journal at " + i);
               }

               boolean inTransitionPhase = initialOverallDone;
               if (line.startsWith("transactionId=")) {
                  if (inTransitionPhase) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "transactionId header after initial section at line " + i);
                  }

                  if (expectedHeader != 0) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "transactionId header out of order at line " + i);
                  }

                  if (transactionId != null) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate transactionId header at line " + i);
                  }

                  transactionId = line.substring("transactionId=".length());
                  expectedHeader = 1;
               } else if (line.startsWith("family=")) {
                  if (inTransitionPhase) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "family header after initial section at line " + i);
                  }

                  if (expectedHeader != 1) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "family header out of order at line " + i);
                  }

                  if (family != null) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate family header at line " + i);
                  }

                  family = line.substring("family=".length());
                  if (!"CREATE".equals(family)) {
                     return new CreateTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "unknown family '" + family + "' at line " + i + " (expected CREATE)"
                     );
                  }

                  expectedHeader = 2;
               } else if (line.startsWith("boundOutputRoot=")) {
                  if (inTransitionPhase) {
                     return new CreateTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "boundOutputRoot header after initial section at line " + i
                     );
                  }

                  if (expectedHeader != 2) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "boundOutputRoot header out of order at line " + i);
                  }

                  if (boundOutputRoot != null) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate boundOutputRoot header at line " + i);
                  }

                  boundOutputRoot = Path.of(line.substring("boundOutputRoot=".length())).toAbsolutePath().normalize();
                  expectedHeader = 3;
               } else if (line.startsWith("b0BaselineId=")) {
                  if (inTransitionPhase) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "b0BaselineId header after initial section at line " + i);
                  }

                  if (expectedHeader != 3) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "b0BaselineId header out of order at line " + i);
                  }

                  if (b0BaselineId != null) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate b0BaselineId header at line " + i);
                  }

                  b0BaselineId = line.substring("b0BaselineId=".length());
                  expectedHeader = 4;
               } else if (line.startsWith("b1BaselineId=")) {
                  if (inTransitionPhase) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "b1BaselineId header after initial section at line " + i);
                  }

                  if (expectedHeader != 4) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "b1BaselineId header out of order at line " + i);
                  }

                  if (b1BaselineId != null) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate b1BaselineId header at line " + i);
                  }

                  b1BaselineId = line.substring("b1BaselineId=".length());
                  expectedHeader = 5;
               } else if (line.startsWith("directoryCount=")) {
                  if (inTransitionPhase) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "directoryCount header after initial section at line " + i);
                  }

                  if (expectedHeader != 5) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "directoryCount header out of order at line " + i);
                  }

                  if (directoryCount >= 0) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate directoryCount header at line " + i);
                  }

                  directoryCount = Integer.parseInt(line.substring("directoryCount=".length()), 10);
                  if (directoryCount < 0) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "directoryCount must not be negative at line " + i);
                  }

                  expectedHeader = 6;
               } else if (line.startsWith("directory ")) {
                  int stateEq = line.indexOf(" state=");
                  if (stateEq < 0) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "malformed directory line at " + i + ": " + line);
                  }

                  String header = line.substring("directory ".length(), stateEq);
                  String stateName = line.substring(stateEq + " state=".length());
                  int space = header.indexOf(32);
                  if (space < 0) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "malformed directory header at " + i + ": " + line);
                  }

                  int dirIndex = Integer.parseInt(header.substring(0, space), 10);
                  String relPath = header.substring(space + 1);
                  CreateTransactionJournal.DirectoryTransitionState newState = CreateTransactionJournal.DirectoryTransitionState.valueOf(stateName);
                  String pathError = TransactionJournal.validateRelativePath(relPath);
                  if (pathError != null) {
                     return new CreateTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "unsafe directory relative path at line " + i + ": " + pathError + ": " + relPath
                     );
                  }

                  if (!inTransitionPhase) {
                     if (expectedHeader != 6) {
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "directory record before all headers complete at line " + i
                        );
                     }

                     if (directoryCount < 0) {
                        return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "directory record before directoryCount at line " + i);
                     }

                     if (dirIndex != expectedDirectoryIndex) {
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002",
                           "directory index out of order at line " + i + ": expected " + expectedDirectoryIndex + " got " + dirIndex
                        );
                     }

                     if (newState != CreateTransactionJournal.DirectoryTransitionState.PREPARING) {
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "directory " + dirIndex + " initial state must be PREPARING, got " + newState
                        );
                     }

                     if (directoryLatestState.containsKey(relPath)) {
                        return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate directory relative path: " + relPath);
                     }

                     if (expectedDirectoryIndex > 0) {
                        String prevPath = directoryIndexToPath.get(expectedDirectoryIndex - 1);
                        if (!isValidDirectoryOrder(prevPath, relPath)) {
                           return new CreateTransactionJournal.ParseFailure(
                              "SIR-APP-CHANGE-RECOVERY-002",
                              "directory " + dirIndex + " out of canonical order at line " + i + ": prev=" + prevPath + " cur=" + relPath
                           );
                        }
                     }

                     directoryIndexToPath.put(dirIndex, relPath);
                     directoryLatestState.put(relPath, newState);
                     directoryIndexLatestState.put(dirIndex, newState);
                     expectedDirectoryIndex++;
                  } else {
                     if (!directoryIndexLatestState.containsKey(dirIndex)) {
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "directory " + dirIndex + " transition references unregistered index at line " + i
                        );
                     }

                     String registeredPath = directoryIndexToPath.get(dirIndex);
                     if (!registeredPath.equals(relPath)) {
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "directory " + dirIndex + " relative path mismatch: expected " + registeredPath + " got " + relPath
                        );
                     }

                     CreateTransactionJournal.DirectoryTransitionState prevState = directoryIndexLatestState.get(dirIndex);
                     if (prevState == newState) {
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "directory " + dirIndex + " duplicate transition to " + newState
                        );
                     }

                     if (!CreateTransactionJournal.DirectoryTransitionState.isValidTransition(prevState, newState)) {
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "directory " + dirIndex + " invalid transition: " + prevState + " -> " + newState
                        );
                     }

                     directoryLatestState.put(relPath, newState);
                     directoryIndexLatestState.put(dirIndex, newState);
                  }

                  directoryInitialDone = true;
               } else if (line.startsWith("fileCount=")) {
                  if (inTransitionPhase) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "fileCount header after initial section at line " + i);
                  }

                  if (expectedHeader != 6) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "fileCount header out of order at line " + i);
                  }

                  if (directoryCount < 0) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "fileCount before directoryCount at line " + i);
                  }

                  if (expectedDirectoryIndex != directoryCount) {
                     return new CreateTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002",
                        "fileCount before all directory records at line "
                           + i
                           + ": expected "
                           + directoryCount
                           + " directories but saw "
                           + expectedDirectoryIndex
                     );
                  }

                  if (fileCount >= 0) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate fileCount header at line " + i);
                  }

                  fileCount = Integer.parseInt(line.substring("fileCount=".length()), 10);
                  if (fileCount < 0) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "fileCount must not be negative at line " + i);
                  }

                  expectedHeader = 7;
               } else if (line.startsWith("file ")) {
                  int stateEq = line.indexOf(" state=");
                  if (stateEq < 0) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "malformed file line at " + i + ": " + line);
                  }

                  String header = line.substring("file ".length(), stateEq);
                  String stateName = line.substring(stateEq + " state=".length());
                  int space = header.indexOf(32);
                  if (space < 0) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "malformed file header at " + i + ": " + line);
                  }

                  int fileIndex = Integer.parseInt(header.substring(0, space), 10);
                  String relPath = header.substring(space + 1);
                  CreateTransactionJournal.FileTransitionState newState = CreateTransactionJournal.FileTransitionState.valueOf(stateName);
                  String pathError = TransactionJournal.validateRelativePath(relPath);
                  if (pathError != null) {
                     return new CreateTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "unsafe file relative path at line " + i + ": " + pathError + ": " + relPath
                     );
                  }

                  if (!inTransitionPhase) {
                     if (expectedHeader != 7) {
                        return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "file record before all headers complete at line " + i);
                     }

                     if (fileCount < 0) {
                        return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "file record before fileCount at line " + i);
                     }

                     if (fileIndex != expectedFileIndex) {
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file index out of order at line " + i + ": expected " + expectedFileIndex + " got " + fileIndex
                        );
                     }

                     if (newState != CreateTransactionJournal.FileTransitionState.PREPARING) {
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " initial state must be PREPARING, got " + newState
                        );
                     }

                     if (fileLatestState.containsKey(relPath)) {
                        return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "duplicate file relative path in journal: " + relPath);
                     }

                     if (expectedFileIndex > 0) {
                        String prevPath = fileIndexToPath.get(expectedFileIndex - 1);
                        if (prevPath.compareTo(relPath) >= 0) {
                           return new CreateTransactionJournal.ParseFailure(
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
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " transition references unregistered index at line " + i
                        );
                     }

                     String registeredPath = fileIndexToPath.get(fileIndex);
                     if (!registeredPath.equals(relPath)) {
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " relative path mismatch: expected " + registeredPath + " got " + relPath
                        );
                     }

                     CreateTransactionJournal.FileTransitionState prevState = fileIndexLatestState.get(fileIndex);
                     if (prevState == newState) {
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " duplicate transition to " + newState
                        );
                     }

                     if (!CreateTransactionJournal.FileTransitionState.isValidTransition(prevState, newState)) {
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "file " + fileIndex + " invalid transition: " + prevState + " -> " + newState
                        );
                     }

                     fileLatestState.put(relPath, newState);
                     fileIndexLatestState.put(fileIndex, newState);
                  }

                  fileInitialDone = true;
               } else {
                  if (!line.startsWith("overallState=")) {
                     return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "unknown journal line at " + i + ": " + line);
                  }

                  CreateTransactionJournal.OverallState newOverall = CreateTransactionJournal.OverallState.valueOf(line.substring("overallState=".length()));
                  if (!initialOverallDone) {
                     if (newOverall != CreateTransactionJournal.OverallState.PREPARING) {
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002", "first overallState must be PREPARING, got " + newOverall
                        );
                     }

                     if (expectedHeader != 7) {
                        return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "overallState before all headers complete at line " + i);
                     }

                     if (fileCount < 0) {
                        return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "overallState before fileCount at line " + i);
                     }

                     if (expectedFileIndex != fileCount) {
                        return new CreateTransactionJournal.ParseFailure(
                           "SIR-APP-CHANGE-RECOVERY-002",
                           "overallState before all initial file records at line " + i + ": expected " + fileCount + " files but saw " + expectedFileIndex
                        );
                     }

                     initialOverallDone = true;
                  } else if (prevOverallState != null && newOverall.ordinal() <= prevOverallState.ordinal()) {
                     return new CreateTransactionJournal.ParseFailure(
                        "SIR-APP-CHANGE-RECOVERY-002", "overallState non-monotonic: " + prevOverallState + " -> " + newOverall
                     );
                  }

                  prevOverallState = newOverall;
                  overallState = newOverall;
               }
            }
         } catch (IllegalArgumentException e) {
            return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "V2 journal parse failure: " + e.getMessage());
         }

         if (transactionId == null
            || family == null
            || boundOutputRoot == null
            || b0BaselineId == null
            || b1BaselineId == null
            || directoryCount < 0
            || fileCount < 0
            || overallState == null) {
            return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "V2 journal missing required header field");
         }

         if (!TransactionJournal.isValidTransactionId(transactionId)) {
            return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "invalid transactionId format: " + transactionId);
         }

         if (!TransactionJournal.isValidBaselineId(b0BaselineId)) {
            return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "invalid b0BaselineId format: " + b0BaselineId);
         }

         if (!TransactionJournal.isValidBaselineId(b1BaselineId)) {
            return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "invalid b1BaselineId format: " + b1BaselineId);
         }

         if (directoryIndexToPath.size() != directoryCount) {
            return new CreateTransactionJournal.ParseFailure(
               "SIR-APP-CHANGE-RECOVERY-002", "directoryCount=" + directoryCount + " but actual directory records=" + directoryIndexToPath.size()
            );
         }

         if (fileIndexToPath.size() != fileCount) {
            return new CreateTransactionJournal.ParseFailure(
               "SIR-APP-CHANGE-RECOVERY-002", "fileCount=" + fileCount + " but actual file records=" + fileIndexToPath.size()
            );
         }

         List<String> plannedDirectories = new ArrayList<>(directoryCount);

         for (int i = 0; i < directoryCount; i++) {
            plannedDirectories.add(directoryIndexToPath.get(i));
         }

         List<String> plannedFiles = new ArrayList<>(fileCount);

         for (int i = 0; i < fileCount; i++) {
            plannedFiles.add(fileIndexToPath.get(i));
         }

         return new CreateTransactionJournal.ParseOk(
            new CreateTransactionJournal.Snapshot(
               path,
               transactionId,
               family,
               boundOutputRoot,
               b0BaselineId,
               b1BaselineId,
               plannedDirectories,
               plannedFiles,
               overallState,
               Map.copyOf(directoryLatestState),
               Map.copyOf(fileLatestState)
            )
         );
      } else {
         return new CreateTransactionJournal.ParseFailure("SIR-APP-CHANGE-RECOVERY-002", "journal must end with LF");
      }
   }

   static boolean isValidDirectoryOrder(String prev, String cur) {
      int prevDepth = depthOf(prev);
      int curDepth = depthOf(cur);
      return curDepth != prevDepth ? curDepth > prevDepth : cur.compareTo(prev) > 0;
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

   public enum DirectoryTransitionState {
      PREPARING,
      DIRECTORY_CREATE_INTENT_DURABLE,
      DIRECTORY_CREATED_DURABLE;

      static boolean isValidTransition(CreateTransactionJournal.DirectoryTransitionState from, CreateTransactionJournal.DirectoryTransitionState to) {
         return switch (from) {
            case PREPARING -> to == DIRECTORY_CREATE_INTENT_DURABLE;
            case DIRECTORY_CREATE_INTENT_DURABLE -> to == DIRECTORY_CREATED_DURABLE;
            case DIRECTORY_CREATED_DURABLE -> false;
         };
      }

      public boolean isCreated() {
         return this == DIRECTORY_CREATED_DURABLE;
      }
   }

   public enum FileTransitionState {
      PREPARING,
      CREATE_INTENT_DURABLE,
      CREATED_DURABLE,
      ROLLBACK_DELETE_INTENT_DURABLE,
      ROLLED_BACK_DURABLE;

      static boolean isValidTransition(CreateTransactionJournal.FileTransitionState from, CreateTransactionJournal.FileTransitionState to) {
         return switch (from) {
            case PREPARING -> to == CREATE_INTENT_DURABLE;
            case CREATE_INTENT_DURABLE -> to == CREATED_DURABLE || to == ROLLBACK_DELETE_INTENT_DURABLE;
            case CREATED_DURABLE -> to == ROLLBACK_DELETE_INTENT_DURABLE;
            case ROLLBACK_DELETE_INTENT_DURABLE -> to == ROLLED_BACK_DURABLE;
            case ROLLED_BACK_DURABLE -> false;
         };
      }

      public boolean isCreated() {
         return this == CREATED_DURABLE;
      }

      public boolean isRolledBack() {
         return this == ROLLED_BACK_DURABLE;
      }

      public boolean isCreateIntent() {
         return this == CREATE_INTENT_DURABLE;
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

   public record ParseFailure(String code, String message) implements CreateTransactionJournal.ParseResult {
      public ParseFailure {
         Objects.requireNonNull(code, "code");
         Objects.requireNonNull(message, "message");
      }
   }

   public record ParseOk(CreateTransactionJournal.Snapshot snapshot) implements CreateTransactionJournal.ParseResult {
      public ParseOk {
         Objects.requireNonNull(snapshot, "snapshot");
      }
   }

   public sealed interface ParseResult permits CreateTransactionJournal.ParseOk, CreateTransactionJournal.ParseFailure {
   }

   public record Snapshot(
      Path journalPath,
      String transactionId,
      String family,
      Path boundOutputRoot,
      String b0BaselineId,
      String b1BaselineId,
      List<String> plannedDirectories,
      List<String> plannedFiles,
      CreateTransactionJournal.OverallState overallState,
      Map<String, CreateTransactionJournal.DirectoryTransitionState> directoryStates,
      Map<String, CreateTransactionJournal.FileTransitionState> fileStates
   ) {
      public Snapshot {
         Objects.requireNonNull(journalPath, "journalPath");
         Objects.requireNonNull(transactionId, "transactionId");
         Objects.requireNonNull(family, "family");
         Objects.requireNonNull(boundOutputRoot, "boundOutputRoot");
         Objects.requireNonNull(b0BaselineId, "b0BaselineId");
         Objects.requireNonNull(b1BaselineId, "b1BaselineId");
         plannedDirectories = List.copyOf(Objects.requireNonNull(plannedDirectories, "plannedDirectories"));
         plannedFiles = List.copyOf(Objects.requireNonNull(plannedFiles, "plannedFiles"));
         Objects.requireNonNull(overallState, "overallState");
         directoryStates = Map.copyOf(Objects.requireNonNull(directoryStates, "directoryStates"));
         fileStates = Map.copyOf(Objects.requireNonNull(fileStates, "fileStates"));
      }

      public boolean isActive() {
         return this.overallState != CreateTransactionJournal.OverallState.COMPLETED;
      }
   }
}
