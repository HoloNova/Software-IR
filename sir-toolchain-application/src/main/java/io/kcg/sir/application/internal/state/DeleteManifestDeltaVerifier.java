package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeExecutionStage;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import io.kcg.sir.change.api.FileDeletion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Map.Entry;

public final class DeleteManifestDeltaVerifier {
   private DeleteManifestDeltaVerifier() {
   }

   public static DeleteManifestDeltaVerifier.Result verify(
      List<BaselineManifestEntry> b0Manifest, List<BaselineManifestEntry> b1Manifest, List<FileDeletion> plannedDeletions
   ) {
      Objects.requireNonNull(b0Manifest, "b0Manifest");
      Objects.requireNonNull(b1Manifest, "b1Manifest");
      Objects.requireNonNull(plannedDeletions, "plannedDeletions");
      Map<String, BaselineManifestEntry> b0Map = toMap(b0Manifest);
      Map<String, BaselineManifestEntry> b1Map = toMap(b1Manifest);
      if (b0Map.size() != b0Manifest.size()) {
         return new DeleteManifestDeltaVerifier.Result.Failure(diag("SIR-APP-CHANGE-BIND-005", "duplicate relativePath in B0 manifest"));
      }

      if (b1Map.size() != b1Manifest.size()) {
         return new DeleteManifestDeltaVerifier.Result.Failure(diag("SIR-APP-CHANGE-BIND-005", "duplicate relativePath in B1 manifest"));
      }

      List<String> b1Only = new ArrayList<>();

      for (Entry<String, BaselineManifestEntry> e : b1Map.entrySet()) {
         String rel = e.getKey();
         BaselineManifestEntry b1Entry = e.getValue();
         BaselineManifestEntry b0Entry = b0Map.get(rel);
         if (b0Entry == null) {
            b1Only.add(rel);
         } else if (!entriesEqual(b0Entry, b1Entry)) {
            return new DeleteManifestDeltaVerifier.Result.Failure(
               diag(
                  "SIR-APP-CHANGE-BIND-005",
                  "B1 entry drifted from B0 for survivor " + rel + ": B0=" + describeEntry(b0Entry) + " B1=" + describeEntry(b1Entry),
                  rel
               )
            );
         }
      }

      if (!b1Only.isEmpty()) {
         b1Only.sort(Comparator.naturalOrder());
         return new DeleteManifestDeltaVerifier.Result.Failure(diag("SIR-APP-CHANGE-BIND-005", "B1-only paths in B0 (must be pure deletion): " + b1Only));
      }

      List<String> b0Only = new ArrayList<>();

      for (String rel : b0Map.keySet()) {
         if (!b1Map.containsKey(rel)) {
            b0Only.add(rel);
         }
      }

      b0Only.sort(Comparator.naturalOrder());
      Map<String, FileDeletion> plannedMap = new LinkedHashMap<>();

      for (FileDeletion fd : plannedDeletions) {
         if (plannedMap.put(fd.relativePath(), fd) != null) {
            return new DeleteManifestDeltaVerifier.Result.Failure(
               diag("SIR-APP-CHANGE-BIND-005", "duplicate relativePath in planned deletions: " + fd.relativePath(), fd.relativePath())
            );
         }
      }

      List<String> plannedSorted = new ArrayList<>(plannedMap.keySet());
      plannedSorted.sort(Comparator.naturalOrder());
      if (!b0Only.equals(plannedSorted)) {
         return new DeleteManifestDeltaVerifier.Result.Failure(
            diag("SIR-APP-CHANGE-BIND-005", "B0-only paths do not match planned deletions: b0Only=" + b0Only + " planned=" + plannedSorted)
         );
      }

      for (FileDeletion fd : plannedDeletions) {
         BaselineManifestEntry b0Entry = b0Map.get(fd.relativePath());
         if (b0Entry == null) {
            return new DeleteManifestDeltaVerifier.Result.Failure(
               diag("SIR-APP-CHANGE-BIND-005", "planned deletion has no B0 manifest entry: " + fd.relativePath(), fd.relativePath())
            );
         }

         if (!b0Entry.artifactId().equals(fd.artifactId())) {
            return new DeleteManifestDeltaVerifier.Result.Failure(
               diag(
                  "SIR-APP-CHANGE-BIND-005",
                  "artifactId mismatch for planned deletion " + fd.relativePath() + ": plan=" + fd.artifactId() + " b0=" + b0Entry.artifactId(),
                  fd.relativePath()
               )
            );
         }

         if (b0Entry.ownerSymbol().isEmpty() || !b0Entry.ownerSymbol().get().equals(fd.ownerSymbol())) {
            return new DeleteManifestDeltaVerifier.Result.Failure(
               diag(
                  "SIR-APP-CHANGE-BIND-005",
                  "ownerSymbol mismatch for planned deletion " + fd.relativePath() + ": plan=" + fd.ownerSymbol() + " b0=" + b0Entry.ownerSymbol(),
                  fd.relativePath()
               )
            );
         }

         if (b0Entry.byteCount() != fd.baseByteCount()) {
            return new DeleteManifestDeltaVerifier.Result.Failure(
               diag(
                  "SIR-APP-CHANGE-BIND-005",
                  "baseByteCount mismatch for planned deletion " + fd.relativePath() + ": plan=" + fd.baseByteCount() + " b0=" + b0Entry.byteCount(),
                  fd.relativePath()
               )
            );
         }

         if (!b0Entry.sha256Hex().equals(fd.baseSha256Hex())) {
            return new DeleteManifestDeltaVerifier.Result.Failure(
               diag(
                  "SIR-APP-CHANGE-BIND-005",
                  "baseSha256 mismatch for planned deletion " + fd.relativePath() + ": plan=" + fd.baseSha256Hex() + " b0=" + b0Entry.sha256Hex(),
                  fd.relativePath()
               )
            );
         }
      }

      return new DeleteManifestDeltaVerifier.Result.Success(List.copyOf(b0Only));
   }

   private static boolean entriesEqual(BaselineManifestEntry a, BaselineManifestEntry b) {
      return a.byteCount() == b.byteCount()
         && a.sha256Hex().equals(b.sha256Hex())
         && a.artifactId().equals(b.artifactId())
         && a.ownerSymbol().equals(b.ownerSymbol());
   }

   private static String describeEntry(BaselineManifestEntry e) {
      return "{bytes=" + e.byteCount() + ", sha=" + e.sha256Hex() + ", artifact=" + e.artifactId() + ", owner=" + e.ownerSymbol() + "}";
   }

   private static Map<String, BaselineManifestEntry> toMap(List<BaselineManifestEntry> manifest) {
      Map<String, BaselineManifestEntry> map = new LinkedHashMap<>();

      for (BaselineManifestEntry e : manifest) {
         map.put(e.relativePath(), e);
      }

      return map;
   }

   private static ChangeExecutionDiagnostic diag(String code, String message) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.PROTECT, ExecutionSeverity.ERROR, message, Optional.empty());
   }

   private static ChangeExecutionDiagnostic diag(String code, String message, String rel) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.PROTECT, ExecutionSeverity.ERROR, message, Optional.of(rel));
   }

   public sealed interface Result permits DeleteManifestDeltaVerifier.Result.Success, DeleteManifestDeltaVerifier.Result.Failure {
      record Failure(ChangeExecutionDiagnostic error) implements DeleteManifestDeltaVerifier.Result {
         public Failure {
            Objects.requireNonNull(error, "error");
         }
      }

      record Success(List<String> deletedPaths) implements DeleteManifestDeltaVerifier.Result {
         public Success {
            Objects.requireNonNull(deletedPaths, "deletedPaths");
            deletedPaths = List.copyOf(deletedPaths);
         }
      }
   }
}
