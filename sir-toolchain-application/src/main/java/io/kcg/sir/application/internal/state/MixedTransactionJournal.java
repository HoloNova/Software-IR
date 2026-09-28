package io.kcg.sir.application.internal.state;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict journal format for the not-yet-executable mixed UPDATE/WITHDRAW/ESTABLISH transaction. */
public final class MixedTransactionJournal {
   public static final byte[] MAGIC = "KCG-CHANGE-TRANSACTION-JOURNAL-V4\n".getBytes(StandardCharsets.US_ASCII);
   public static final String FAMILY = "MIXED";
   private final Path journalPath;
   private final String transactionId;
   private final Path boundOutputRoot;
   private final String b0BaselineId;
   private final String b1BaselineId;
   private final String b0ManifestDigest;
   private final String b1ManifestDigest;
   private final String planDigest;
   private final String subjectSymbol;
   private final List<FileEntry> files;

   public MixedTransactionJournal(Path transactionDir, String transactionId, Path boundOutputRoot, String b0BaselineId,
                                  String b1BaselineId, String b0ManifestDigest, String b1ManifestDigest,
                                  String planDigest, String subjectSymbol, List<FileEntry> files) {
      this.journalPath = Objects.requireNonNull(transactionDir, "transactionDir").resolve("journal").toAbsolutePath().normalize();
      this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
      this.boundOutputRoot = Objects.requireNonNull(boundOutputRoot, "boundOutputRoot").toAbsolutePath().normalize();
      this.b0BaselineId = Objects.requireNonNull(b0BaselineId, "b0BaselineId");
      this.b1BaselineId = Objects.requireNonNull(b1BaselineId, "b1BaselineId");
      this.b0ManifestDigest = Objects.requireNonNull(b0ManifestDigest, "b0ManifestDigest");
      this.b1ManifestDigest = Objects.requireNonNull(b1ManifestDigest, "b1ManifestDigest");
      this.planDigest = Objects.requireNonNull(planDigest, "planDigest");
      this.subjectSymbol = Objects.requireNonNull(subjectSymbol, "subjectSymbol");
      validateHeaderValues(this.transactionId, this.boundOutputRoot, this.b0BaselineId, this.b1BaselineId,
          this.b0ManifestDigest, this.b1ManifestDigest, this.planDigest, this.subjectSymbol);
      this.files = List.copyOf(Objects.requireNonNull(files));
      validateEntries(this.files);
   }

   public Path journalPath() { return journalPath; }
   public void create() throws IOException {
      Files.createDirectories(journalPath.getParent());
      StringBuilder text = new StringBuilder(new String(MAGIC, StandardCharsets.US_ASCII));
      text.append("transactionId=").append(transactionId).append('\n')
          .append("family=").append(FAMILY).append('\n')
          .append("boundOutputRoot=").append(boundOutputRoot).append('\n')
          .append("b0BaselineId=").append(b0BaselineId).append('\n')
          .append("b1BaselineId=").append(b1BaselineId).append('\n')
          .append("b0ManifestDigest=").append(b0ManifestDigest).append('\n')
          .append("b1ManifestDigest=").append(b1ManifestDigest).append('\n')
          .append("planDigest=").append(planDigest).append('\n')
          .append("subjectSymbol=").append(subjectSymbol).append('\n')
          .append("fileCount=").append(files.size()).append('\n');
      for (int i = 0; i < files.size(); i++) {
         FileEntry file = files.get(i);
          text.append("file ").append(i).append(" path64=").append(encodePath(file.relativePath())).append(" kind=")
              .append(file.kind()).append(" state=PREPARING\n");
      }
      text.append("overallState=PREPARING\n");
      Files.write(journalPath, text.toString().getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
   }

   public void appendFileTransition(int index, String relativePath, FileState state) throws IOException {
      Objects.requireNonNull(state);
      ParseResult parsed = parse(journalPath);
       if (!(parsed instanceof ParseOk ok) || isTerminal(ok.snapshot().overallState())
           || index < 0 || index >= ok.snapshot().files().size())
          throw new IOException("cannot append to invalid or terminal journal, or unknown file index");
       FileEntry entry = ok.snapshot().files().get(index);
       FileState old = ok.snapshot().fileStates().get(index);
       if (!entry.relativePath().equals(relativePath) || !validTransition(entry.kind(), old, state))
          throw new IOException("invalid V4 file transition");
       Map<Integer, FileState> nextStates = new HashMap<>(ok.snapshot().fileStates());
       nextStates.put(index, state);
       if (!isCompatible(ok.snapshot().overallState(), ok.snapshot().files(), nextStates))
          throw new IOException("file transition is incompatible with V4 overall state");
       append("file " + index + " path64=" + encodePath(relativePath) + " kind=" + entry.kind() + " state=" + state.name() + "\n");
   }

   public void appendOverallTransition(OverallState state) throws IOException {
      Objects.requireNonNull(state);
      ParseResult parsed = parse(journalPath);
       if (!(parsed instanceof ParseOk ok) || !validOverallTransition(ok.snapshot().overallState(), state)
           || !isCompatible(state, ok.snapshot().files(), ok.snapshot().fileStates()))
          throw new IOException("invalid V4 overall transition");
       append("overallState=" + state.name() + "\n");
   }

   private void append(String line) throws IOException {
      Files.write(journalPath, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE,
          StandardOpenOption.APPEND, StandardOpenOption.SYNC);
   }

   public static ParseResult parse(Path path) {
      Objects.requireNonNull(path);
      try {
         BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         if (attrs.isSymbolicLink() || !attrs.isRegularFile()) return failure("journal must be a regular non-symlink file");
         return parseBytes(path, Files.readAllBytes(path));
      } catch (IOException | SecurityException e) {
         return failure("failed to read journal: " + e.getMessage());
      }
   }

   static ParseResult parseBytes(Path path, byte[] bytes) {
      if (bytes.length < MAGIC.length) return failure("V4 journal too short for magic");
      for (int i = 0; i < MAGIC.length; i++) if (bytes[i] != MAGIC[i]) return failure("V4 journal magic mismatch");
      for (byte b : bytes) if (b == '\r') return failure("CR not allowed in journal");
      final String content;
      try {
         content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
             .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, MAGIC.length, bytes.length - MAGIC.length)).toString();
      } catch (CharacterCodingException e) { return failure("invalid UTF-8 in journal"); }
      if (!content.endsWith("\n")) return failure("journal must end with LF");
      String[] lines = content.split("\n", -1);
      String[] headerNames = {"transactionId", "family", "boundOutputRoot", "b0BaselineId", "b1BaselineId", "b0ManifestDigest", "b1ManifestDigest", "planDigest", "subjectSymbol", "fileCount"};
      if (lines.length < headerNames.length + 2) return failure("V4 journal missing records");
      try {
         String[] values = new String[headerNames.length];
         for (int i = 0; i < headerNames.length; i++) {
            String prefix = headerNames[i] + "=";
            if (!lines[i].startsWith(prefix) || lines[i].length() == prefix.length()) return failure("missing, empty, or out-of-order header " + headerNames[i]);
            values[i] = lines[i].substring(prefix.length());
         }
          if (!FAMILY.equals(values[1])) return failure("V4 family must be MIXED");
          if (!TransactionJournal.isValidTransactionId(values[0])) return failure("invalid transactionId");
          for (int i = 3; i <= 7; i++) if (!isDigest(values[i])) return failure("invalid digest header " + headerNames[i]);
          if (!isSingleLine(values[8])) return failure("invalid subjectSymbol header");
          Path output = Path.of(values[2]);
          if (!output.isAbsolute() || !output.equals(output.normalize()) || !isSingleLine(output.toString()))
             return failure("boundOutputRoot must be normalized, absolute, and single-line");
         int count = Integer.parseInt(values[9]);
          if (count < 0 || count > lines.length - headerNames.length - 2) return failure("invalid fileCount");
         List<FileEntry> files = new ArrayList<>(count);
         Map<Integer, FileState> states = new HashMap<>();
         Set<String> paths = new HashSet<>();
         String previousPath = null;
          for (int i = 0; i < count; i++) {
             String[] fields = lineFields(lines[headerNames.length + i]);
             if (fields == null) return failure("malformed file record " + i);
             int index = Integer.parseInt(fields[1]);
             if (index != i) return failure("file indices must be contiguous and ordered");
             String rel = decodePath(fields[2]);
             String pathError = TransactionJournal.validateRelativePath(rel);
             if (pathError != null) return failure("unsafe path: " + pathError);
             if (!paths.add(rel)) return failure("duplicate relative path " + rel);
             if (previousPath != null && previousPath.compareTo(rel) >= 0) return failure("file paths must be strictly sorted");
             previousPath = rel;
             Kind kind = Kind.valueOf(fields[3]);
             FileState state = FileState.valueOf(fields[4]);
             if (state != FileState.PREPARING) return failure("initial file state must be PREPARING");
             files.add(new FileEntry(rel, kind)); states.put(i, state);
          }
         int lineIndex = headerNames.length + count;
         if (!lines[lineIndex].equals("overallState=PREPARING")) return failure("first overallState must be PREPARING");
         OverallState overall = OverallState.PREPARING;
         for (int i = lineIndex + 1; i < lines.length - 1; i++) {
            String line = lines[i];
             if (line.startsWith("overallState=")) {
                OverallState next = OverallState.valueOf(line.substring(13));
                if (!validOverallTransition(overall, next)) return failure("invalid overallState transition: " + overall + " -> " + next);
                if (!isCompatible(next, files, states)) return failure("overallState is incompatible with per-file states: " + next);
                overall = next;
                continue;
             }
             if (isTerminal(overall)) return failure("file transition after terminal overallState " + overall);
             String[] fields = lineFields(line);
             if (fields == null) return failure("malformed file transition");
             int index = Integer.parseInt(fields[1]);
             if (index < 0 || index >= count) return failure("transition references unknown index");
             String rel = decodePath(fields[2]);
             Kind kind = Kind.valueOf(fields[3]);
             FileState next = FileState.valueOf(fields[4]);
             if (!files.get(index).relativePath().equals(rel) || files.get(index).kind() != kind) return failure("transition index/path/kind mismatch");
             if (!validTransition(kind, states.get(index), next)) return failure("illegal or duplicate " + kind + " transition");
             Map<Integer, FileState> nextStates = new HashMap<>(states);
             nextStates.put(index, next);
             if (!isCompatible(overall, files, nextStates)) return failure("file state is incompatible with overallState: " + overall);
             states.put(index, next);
         }
         validateEntries(files);
         return new ParseOk(new Snapshot(path.toAbsolutePath().normalize(), values[0], output,
             values[3], values[4], values[5], values[6], values[7], values[8], files, overall, states));
      } catch (RuntimeException e) { return failure("V4 journal parse failure: " + e.getMessage()); }
   }

   private static void validateEntries(List<FileEntry> files) {
      String prior = null;
      Set<String> seen = new HashSet<>();
      for (FileEntry file : files) {
         Objects.requireNonNull(file);
         String error = TransactionJournal.validateRelativePath(file.relativePath());
         if (error != null || !seen.add(file.relativePath()) || (prior != null && prior.compareTo(file.relativePath()) >= 0))
            throw new IllegalArgumentException("V4 file paths must be safe, unique, and sorted");
         prior = file.relativePath();
      }
   }

   private static boolean validTransition(Kind kind, FileState from, FileState to) {
      if (from == to) return false;
      return switch (kind) {
         case ESTABLISH -> switch (from) {
            case PREPARING -> to == FileState.CREATE_INTENT_DURABLE;
            case CREATE_INTENT_DURABLE -> to == FileState.CREATED_DURABLE || to == FileState.ROLLBACK_DELETE_INTENT_DURABLE;
            case CREATED_DURABLE -> to == FileState.ROLLBACK_DELETE_INTENT_DURABLE;
            case ROLLBACK_DELETE_INTENT_DURABLE -> to == FileState.ROLLED_BACK_DURABLE;
            default -> false;
         };
         case WITHDRAW -> switch (from) {
            case PREPARING -> to == FileState.BACKUP_LINK_INTENT_DURABLE;
            case BACKUP_LINK_INTENT_DURABLE -> to == FileState.BACKUP_LINKED_DURABLE || to == FileState.ROLLED_BACK_DURABLE;
            case BACKUP_LINKED_DURABLE -> to == FileState.DELETE_INTENT_DURABLE || to == FileState.ROLLED_BACK_DURABLE;
            case DELETE_INTENT_DURABLE -> to == FileState.DELETED_DURABLE || to == FileState.RESTORE_LINK_INTENT_DURABLE;
            case DELETED_DURABLE -> to == FileState.RESTORE_LINK_INTENT_DURABLE;
            case RESTORE_LINK_INTENT_DURABLE -> to == FileState.RESTORED_DURABLE;
            case RESTORED_DURABLE -> to == FileState.ROLLED_BACK_DURABLE;
            default -> false;
         };
         case UPDATE -> switch (from) {
            case PREPARING -> to == FileState.BACKUP_LINK_INTENT_DURABLE;
            case BACKUP_LINK_INTENT_DURABLE -> to == FileState.BACKUP_LINKED_DURABLE || to == FileState.ROLLBACK_REPLACE_INTENT_DURABLE;
            case BACKUP_LINKED_DURABLE -> to == FileState.REPLACE_INTENT_DURABLE || to == FileState.ROLLBACK_REPLACE_INTENT_DURABLE;
            case REPLACE_INTENT_DURABLE -> to == FileState.REPLACED_DURABLE || to == FileState.ROLLBACK_REPLACE_INTENT_DURABLE;
            case REPLACED_DURABLE -> to == FileState.ROLLBACK_REPLACE_INTENT_DURABLE;
            case ROLLBACK_REPLACE_INTENT_DURABLE -> to == FileState.ROLLED_BACK_DURABLE;
            default -> false;
         };
      };
   }

   private static String[] lineFields(String line) {
      String[] fields = line.split(" ", -1);
      if (fields.length != 5 || !fields[0].equals("file") || fields[1].isEmpty()
          || !fields[2].startsWith("path64=") || fields[2].length() == "path64=".length()
          || !fields[3].startsWith("kind=") || !fields[4].startsWith("state=")) return null;
      return new String[] {fields[0], fields[1], fields[2].substring("path64=".length()),
          fields[3].substring("kind=".length()), fields[4].substring("state=".length())};
   }

   private static String encodePath(String relativePath) {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(relativePath.getBytes(StandardCharsets.UTF_8));
   }

   private static String decodePath(String encoded) {
      byte[] bytes = Base64.getUrlDecoder().decode(encoded);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded))
         throw new IllegalArgumentException("non-canonical path64 encoding");
      try {
         return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
             .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
      } catch (CharacterCodingException e) {
         throw new IllegalArgumentException("path64 is not valid UTF-8", e);
      }
   }

   private static boolean isCompatible(OverallState overall, List<FileEntry> files, Map<Integer, FileState> states) {
      for (int i = 0; i < files.size(); i++) {
         FileEntry file = files.get(i);
         FileState state = states.get(i);
         if (state == null) return false;
         if (isRollbackFileState(file.kind(), state) && overall != OverallState.ROLLING_BACK
             && !(overall == OverallState.ROLLED_BACK && state == FileState.ROLLED_BACK_DURABLE)) return false;
         switch (overall) {
            case PREPARING, PREPARED -> { if (state != FileState.PREPARING) return false; }
            case COMMITTING -> { if (isRollbackFileState(file.kind(), state)) return false; }
            case FILES_COMMITTED, PUBLISHING, BASELINE_PUBLISHED, CLEANUP_PENDING, COMPLETED -> {
               if (!isCommitted(file.kind(), state)) return false;
            }
            case ROLLING_BACK -> { }
            case ROLLED_BACK -> {
               if (state != FileState.PREPARING && state != FileState.ROLLED_BACK_DURABLE) return false;
            }
         }
      }
      return true;
   }

   private static boolean isCommitted(Kind kind, FileState state) {
      return switch (kind) {
         case ESTABLISH -> state == FileState.CREATED_DURABLE;
         case WITHDRAW -> state == FileState.DELETED_DURABLE;
         case UPDATE -> state == FileState.REPLACED_DURABLE;
      };
   }

   private static boolean isRollbackFileState(Kind kind, FileState state) {
      return switch (kind) {
         case ESTABLISH -> state == FileState.ROLLBACK_DELETE_INTENT_DURABLE || state == FileState.ROLLED_BACK_DURABLE;
         case WITHDRAW -> state == FileState.RESTORE_LINK_INTENT_DURABLE || state == FileState.RESTORED_DURABLE
             || state == FileState.ROLLED_BACK_DURABLE;
         case UPDATE -> state == FileState.ROLLBACK_REPLACE_INTENT_DURABLE || state == FileState.ROLLED_BACK_DURABLE;
      };
   }

   private static boolean validOverallTransition(OverallState from, OverallState to) {
      return switch (from) {
         case PREPARING -> to == OverallState.PREPARED || to == OverallState.ROLLING_BACK;
         case PREPARED -> to == OverallState.COMMITTING || to == OverallState.ROLLING_BACK;
         case COMMITTING -> to == OverallState.FILES_COMMITTED || to == OverallState.ROLLING_BACK;
         case FILES_COMMITTED -> to == OverallState.PUBLISHING || to == OverallState.ROLLING_BACK;
         case PUBLISHING -> to == OverallState.BASELINE_PUBLISHED || to == OverallState.ROLLING_BACK;
         case BASELINE_PUBLISHED -> to == OverallState.CLEANUP_PENDING;
         case CLEANUP_PENDING -> to == OverallState.COMPLETED;
         case COMPLETED -> false;
         case ROLLING_BACK -> to == OverallState.ROLLED_BACK;
         case ROLLED_BACK -> false;
      };
   }

   private static boolean isTerminal(OverallState state) {
      return state == OverallState.COMPLETED || state == OverallState.ROLLED_BACK;
   }

   private static void validateHeaderValues(String transactionId, Path outputRoot, String b0BaselineId, String b1BaselineId,
                                            String b0ManifestDigest, String b1ManifestDigest, String planDigest, String subjectSymbol) {
      if (!TransactionJournal.isValidTransactionId(transactionId)) throw new IllegalArgumentException("invalid transactionId");
      if (!TransactionJournal.isValidBaselineId(b0BaselineId) || !TransactionJournal.isValidBaselineId(b1BaselineId))
         throw new IllegalArgumentException("invalid baseline id");
      if (!isDigest(b0ManifestDigest) || !isDigest(b1ManifestDigest) || !isDigest(planDigest))
         throw new IllegalArgumentException("invalid manifest or plan digest");
      if (!outputRoot.isAbsolute() || !outputRoot.equals(outputRoot.normalize()) || !isSingleLine(outputRoot.toString()))
         throw new IllegalArgumentException("boundOutputRoot must be normalized, absolute, and single-line");
      if (!isSingleLine(subjectSymbol)) throw new IllegalArgumentException("subjectSymbol must be nonempty and single-line");
   }

   private static boolean isSingleLine(String value) {
      return value != null && !value.isEmpty() && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
   }

   private static boolean isDigest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
   private static ParseFailure failure(String msg) { return new ParseFailure("SIR-APP-CHANGE-RECOVERY-002", msg); }
   public enum Kind { UPDATE, WITHDRAW, ESTABLISH }
   public record FileEntry(String relativePath, Kind kind) { public FileEntry { Objects.requireNonNull(relativePath); Objects.requireNonNull(kind); } }
   public enum FileState { PREPARING, CREATE_INTENT_DURABLE, CREATED_DURABLE, ROLLBACK_DELETE_INTENT_DURABLE,
      BACKUP_LINK_INTENT_DURABLE, BACKUP_LINKED_DURABLE, DELETE_INTENT_DURABLE, DELETED_DURABLE,
      RESTORE_LINK_INTENT_DURABLE, RESTORED_DURABLE, REPLACE_INTENT_DURABLE, REPLACED_DURABLE,
      ROLLBACK_REPLACE_INTENT_DURABLE, ROLLED_BACK_DURABLE }
   public enum OverallState { PREPARING, PREPARED, COMMITTING, FILES_COMMITTED, PUBLISHING, BASELINE_PUBLISHED,
      CLEANUP_PENDING, COMPLETED, ROLLING_BACK, ROLLED_BACK }
   public record Snapshot(Path journalPath, String transactionId, Path boundOutputRoot, String b0BaselineId, String b1BaselineId,
      String b0ManifestDigest, String b1ManifestDigest, String planDigest, String subjectSymbol, List<FileEntry> files,
      OverallState overallState, Map<Integer, FileState> fileStates) {
      public Snapshot { files = List.copyOf(files); fileStates = Map.copyOf(fileStates); }
      public boolean isActive() { return overallState != OverallState.COMPLETED && overallState != OverallState.ROLLED_BACK; }
   }
   public sealed interface ParseResult permits ParseOk, ParseFailure {}
   public record ParseOk(Snapshot snapshot) implements ParseResult {}
   public record ParseFailure(String code, String message) implements ParseResult {}
}
