package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeExecutionStage;
import io.kcg.sir.application.api.ExecutionSeverity;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JournalGate {
   private final Path stateRoot;

   public JournalGate(Path normalizedStateRoot) {
      this.stateRoot = Objects.requireNonNull(normalizedStateRoot, "normalizedStateRoot");
   }

   public Path transactionsDir() {
      return this.stateRoot.resolve("transactions");
   }

   public JournalGate.InspectionResult inspect(Optional<String> currentBaselineId) {
      Objects.requireNonNull(currentBaselineId, "currentBaselineId");
      Path transactions = this.transactionsDir();
      if (!Files.exists(transactions, LinkOption.NOFOLLOW_LINKS)) {
         return new JournalGate.InspectionResult(List.of(), List.of());
      }

      BasicFileAttributes attrs;
      try {
         attrs = Files.readAttributes(transactions, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException | SecurityException e) {
         return new JournalGate.InspectionResult(
            List.of(), List.of(diag("SIR-APP-CHANGE-RECOVERY-002", "failed to read transactions directory: " + e.getMessage()))
         );
      }

      if (attrs.isSymbolicLink()) {
         return new JournalGate.InspectionResult(
            List.of(), List.of(diag("SIR-APP-CHANGE-RECOVERY-002", "transactions directory must not be a symlink: " + transactions))
         );
      }

      if (!attrs.isDirectory()) {
         return new JournalGate.InspectionResult(
            List.of(), List.of(diag("SIR-APP-CHANGE-RECOVERY-002", "transactions path is not a directory: " + transactions))
         );
      }

      List<Path> transactionDirs = new ArrayList<>();

      try (DirectoryStream<Path> stream = Files.newDirectoryStream(transactions)) {
         stream.forEach(transactionDirs::add);
      } catch (IOException | SecurityException e) {
         return new JournalGate.InspectionResult(
            List.of(), List.of(diag("SIR-APP-CHANGE-RECOVERY-002", "failed to list transactions directory: " + e.getMessage()))
         );
      }

      List<JournalGate.ActiveJournal> snapshots = new ArrayList<>();
      List<ChangeExecutionDiagnostic> errors = new ArrayList<>();

      for (Path txDir : transactionDirs) {
         String txName = txDir.getFileName().toString();
         if (txName.isBlank()) {
            errors.add(diag("SIR-APP-CHANGE-RECOVERY-002", "blank transaction directory name: " + txDir));
         } else {
            BasicFileAttributes txAttrs;
            try {
               txAttrs = Files.readAttributes(txDir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | SecurityException e) {
               errors.add(diag("SIR-APP-CHANGE-RECOVERY-002", "failed to read transaction dir attributes: " + txDir + ": " + e.getMessage()));
               continue;
            }

            if (txAttrs.isSymbolicLink()) {
               errors.add(diag("SIR-APP-CHANGE-RECOVERY-002", "transaction dir must not be a symlink: " + txDir));
            } else if (!txAttrs.isDirectory()) {
               errors.add(diag("SIR-APP-CHANGE-RECOVERY-002", "transaction path is not a directory: " + txDir));
            } else {
               List<Path> children = new ArrayList<>();

               try (DirectoryStream<Path> stream = Files.newDirectoryStream(txDir)) {
                  stream.forEach(children::add);
               } catch (IOException | SecurityException e) {
                  errors.add(diag("SIR-APP-CHANGE-RECOVERY-002", "failed to list transaction directory: " + txDir + ": " + e.getMessage()));
                  continue;
               }

               if (children.isEmpty()) {
                  try {
                     Files.delete(txDir);
                  } catch (IOException | SecurityException e) {
                     errors.add(diag("SIR-APP-CHANGE-RECOVERY-002", "failed to delete empty transaction directory: " + txDir + ": " + e.getMessage()));
                  }
               } else {
                  Path journalFile = txDir.resolve("journal");
                  boolean hasJournal = children.stream().anyMatch(p -> p.getFileName().toString().equals("journal"));
                  if (!hasJournal) {
                     errors.add(diag("SIR-APP-CHANGE-RECOVERY-002", "transaction " + txName + " has material but no journal: " + txDir));
                  } else {
                     JournalGate.ActiveJournal parsed = parseByMagic(journalFile, txName, errors);
                     if (parsed != null) {
                        if (currentBaselineId.isPresent()) {
                           String current = currentBaselineId.get();
                           if (!current.equals(parsed.b0BaselineId()) && !current.equals(parsed.b1BaselineId())) {
                              errors.add(
                                 diag(
                                    "SIR-APP-CHANGE-RECOVERY-002",
                                    "transaction "
                                       + txName
                                       + " B0/B1 does not match CURRENT "
                                       + current
                                       + ": B0="
                                       + parsed.b0BaselineId()
                                       + " B1="
                                       + parsed.b1BaselineId()
                                 )
                              );
                              continue;
                           }
                        }

                        snapshots.add(parsed);
                     }
                  }
               }
            }
         }
      }

      if (!errors.isEmpty()) {
         return new JournalGate.InspectionResult(List.of(), List.copyOf(errors));
      }

      List<JournalGate.ActiveJournal> active = new ArrayList<>();

      for (JournalGate.ActiveJournal s : snapshots) {
         active.add(s);
      }

      if (active.isEmpty()) {
         return new JournalGate.InspectionResult(List.of(), List.of());
      }

      if (active.size() == 1) {
         JournalGate.ActiveJournal s = active.get(0);
         return new JournalGate.InspectionResult(
            List.of(s),
            List.of(
               diag(
                  "SIR-APP-CHANGE-RECOVERY-001",
                  "one active transaction journal blocks the operation: "
                     + s.transactionId()
                     + " (family="
                     + s.family()
                     + " overallState="
                     + s.overallStateName()
                     + ")"
               )
            )
         );
      }

      List<String> ids = new ArrayList<>();

      for (JournalGate.ActiveJournal s : active) {
         ids.add(s.transactionId());
      }

      return new JournalGate.InspectionResult(List.copyOf(active), List.of(diag("SIR-APP-CHANGE-RECOVERY-002", "multiple active transaction journals: " + ids)));
   }

   private static JournalGate.ActiveJournal parseByMagic(Path journalFile, String txName, List<ChangeExecutionDiagnostic> errors) {
      byte[] header;
      try {
         header = readHeaderBytes(journalFile);
      } catch (IOException | SecurityException e) {
         errors.add(diag("SIR-APP-CHANGE-RECOVERY-002", "failed to read journal header of transaction " + txName + ": " + e.getMessage()));
         return null;
      }

      if (startsWith(header, DeleteTransactionJournal.MAGIC)) {
         DeleteTransactionJournal.ParseResult parse = DeleteTransactionJournal.parse(journalFile);
         if (parse instanceof DeleteTransactionJournal.ParseFailure f) {
            errors.add(diag(f.code(), "transaction " + txName + " (V3 DELETE): " + f.message()));
            return null;
         } else {
            return new JournalGate.ActiveJournal.V3Delete(((DeleteTransactionJournal.ParseOk)parse).snapshot());
         }
      } else if (startsWith(header, CreateTransactionJournal.MAGIC)) {
         CreateTransactionJournal.ParseResult parse = CreateTransactionJournal.parse(journalFile);
         if (parse instanceof CreateTransactionJournal.ParseFailure f) {
            errors.add(diag(f.code(), "transaction " + txName + " (V2 CREATE): " + f.message()));
            return null;
         } else {
            return new JournalGate.ActiveJournal.V2Create(((CreateTransactionJournal.ParseOk)parse).snapshot());
         }
      } else if (startsWith(header, TransactionJournal.MAGIC)) {
         TransactionJournal.ParseResult parse = TransactionJournal.parse(journalFile);
         if (parse instanceof TransactionJournal.ParseFailure f) {
            errors.add(diag(f.code(), "transaction " + txName + " (V1 UPDATE): " + f.message()));
            return null;
         } else {
            return new JournalGate.ActiveJournal.V1Update(((TransactionJournal.ParseOk)parse).snapshot());
         }
      } else {
         errors.add(diag("SIR-APP-CHANGE-RECOVERY-002", "transaction " + txName + " has unknown journal magic: " + describeHeader(header)));
         return null;
      }
   }

   private static byte[] readHeaderBytes(Path file) throws IOException {
      BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attrs.isSymbolicLink()) {
         throw new IOException("journal must not be a symlink: " + file);
      }

      if (!attrs.isRegularFile()) {
         throw new IOException("journal must be a regular file: " + file);
      }

      int maxLen = (int)Math.min(attrs.size(), 64L);
      byte[] buf = new byte[maxLen];

      try (SeekableByteChannel ch = Files.newByteChannel(file)) {
         int read = 0;

         while (read < maxLen) {
            int n = ch.read(ByteBuffer.wrap(buf, read, maxLen - read));
            if (n < 0) {
               break;
            }

            read += n;
         }

         if (read < maxLen) {
            byte[] trimmed = new byte[read];
            System.arraycopy(buf, 0, trimmed, 0, read);
            return trimmed;
         }
      }

      return buf;
   }

   private static boolean startsWith(byte[] data, byte[] prefix) {
      if (data.length < prefix.length) {
         return false;
      }

      for (int i = 0; i < prefix.length; i++) {
         if (data[i] != prefix[i]) {
            return false;
         }
      }

      return true;
   }

   private static String describeHeader(byte[] header) {
      int len = Math.min(header.length, 48);
      StringBuilder sb = new StringBuilder(len);

      for (int i = 0; i < len; i++) {
         byte b = header[i];
         if (b >= 32 && b < 127) {
            sb.append((char)b);
         } else if (b == 10) {
            sb.append("\\n");
         } else if (b == 13) {
            sb.append("\\r");
         } else {
            sb.append('?');
         }
      }

      return sb.toString();
   }

   private static ChangeExecutionDiagnostic diag(String code, String message) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.RECOVERY, ExecutionSeverity.ERROR, message, Optional.empty());
   }

   public sealed interface ActiveJournal permits JournalGate.ActiveJournal.V1Update, JournalGate.ActiveJournal.V2Create, JournalGate.ActiveJournal.V3Delete {
      String transactionId();

      String b0BaselineId();

      String b1BaselineId();

      Path journalPath();

      JournalGate.JournalFamily family();

      String overallStateName();

      record V1Update(TransactionJournal.Snapshot snapshot) implements JournalGate.ActiveJournal {
         public V1Update {
            Objects.requireNonNull(snapshot, "snapshot");
         }

         @Override
         public String transactionId() {
            return this.snapshot.transactionId();
         }

         @Override
         public String b0BaselineId() {
            return this.snapshot.b0BaselineId();
         }

         @Override
         public String b1BaselineId() {
            return this.snapshot.b1BaselineId();
         }

         @Override
         public Path journalPath() {
            return this.snapshot.journalPath();
         }

         @Override
         public JournalGate.JournalFamily family() {
            return JournalGate.JournalFamily.V1_UPDATE;
         }

         @Override
         public String overallStateName() {
            return this.snapshot.overallState().name();
         }
      }

      record V2Create(CreateTransactionJournal.Snapshot snapshot) implements JournalGate.ActiveJournal {
         public V2Create {
            Objects.requireNonNull(snapshot, "snapshot");
         }

         @Override
         public String transactionId() {
            return this.snapshot.transactionId();
         }

         @Override
         public String b0BaselineId() {
            return this.snapshot.b0BaselineId();
         }

         @Override
         public String b1BaselineId() {
            return this.snapshot.b1BaselineId();
         }

         @Override
         public Path journalPath() {
            return this.snapshot.journalPath();
         }

         @Override
         public JournalGate.JournalFamily family() {
            return JournalGate.JournalFamily.V2_CREATE;
         }

         @Override
         public String overallStateName() {
            return this.snapshot.overallState().name();
         }
      }

      record V3Delete(DeleteTransactionJournal.Snapshot snapshot) implements JournalGate.ActiveJournal {
         public V3Delete {
            Objects.requireNonNull(snapshot, "snapshot");
         }

         @Override
         public String transactionId() {
            return this.snapshot.transactionId();
         }

         @Override
         public String b0BaselineId() {
            return this.snapshot.b0BaselineId();
         }

         @Override
         public String b1BaselineId() {
            return this.snapshot.b1BaselineId();
         }

         @Override
         public Path journalPath() {
            return this.snapshot.journalPath();
         }

         @Override
         public JournalGate.JournalFamily family() {
            return JournalGate.JournalFamily.V3_DELETE;
         }

         @Override
         public String overallStateName() {
            return this.snapshot.overallState().name();
         }
      }
   }

   public record InspectionResult(List<JournalGate.ActiveJournal> activeJournals, List<ChangeExecutionDiagnostic> errors) {
      public InspectionResult {
         activeJournals = List.copyOf(Objects.requireNonNull(activeJournals, "activeJournals"));
         errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
      }

      public boolean isOpen() {
         return this.errors.isEmpty();
      }
   }

   public enum JournalFamily {
      V1_UPDATE,
      V2_CREATE,
      V3_DELETE;
   }
}
