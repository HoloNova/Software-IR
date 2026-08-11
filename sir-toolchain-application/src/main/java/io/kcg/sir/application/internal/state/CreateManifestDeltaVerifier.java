package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeExecutionStage;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import io.kcg.sir.change.api.FileAddition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;

public final class CreateManifestDeltaVerifier {
   private CreateManifestDeltaVerifier() {
   }

   public static CreateManifestDeltaVerifier.Result verify(
      List<BaselineManifestEntry> b0Manifest, List<BaselineManifestEntry> b1Manifest, List<FileAddition> plannedAdditions
   ) {
      Objects.requireNonNull(b0Manifest, "b0Manifest");
      Objects.requireNonNull(b1Manifest, "b1Manifest");
      Objects.requireNonNull(plannedAdditions, "plannedAdditions");
      Map<String, BaselineManifestEntry> b0Map = toMap(b0Manifest);
      Map<String, BaselineManifestEntry> b1Map = toMap(b1Manifest);
      if (b0Map.size() != b0Manifest.size()) {
         return new CreateManifestDeltaVerifier.Result.Failure(diag("SIR-APP-CHANGE-BIND-005", "duplicate relativePath in B0 manifest"));
      }

      if (b1Map.size() != b1Manifest.size()) {
         return new CreateManifestDeltaVerifier.Result.Failure(diag("SIR-APP-CHANGE-BIND-005", "duplicate relativePath in B1 manifest"));
      }

      List<String> b0Only = new ArrayList<>();

      for (Entry<String, BaselineManifestEntry> e : b0Map.entrySet()) {
         String rel = e.getKey();
         BaselineManifestEntry b0Entry = e.getValue();
         BaselineManifestEntry b1Entry = b1Map.get(rel);
         if (b1Entry == null) {
            b0Only.add(rel);
         } else if (!entriesEqual(b0Entry, b1Entry)) {
            return new CreateManifestDeltaVerifier.Result.Failure(
               diag("SIR-APP-CHANGE-BIND-005", "B0 entry drifted in B1 for " + rel + ": B0=" + describeEntry(b0Entry) + " B1=" + describeEntry(b1Entry), rel)
            );
         }
      }

      if (!b0Only.isEmpty()) {
         b0Only.sort(Comparator.naturalOrder());
         return new CreateManifestDeltaVerifier.Result.Failure(diag("SIR-APP-CHANGE-BIND-005", "B0-only paths in B1 (must be pure addition): " + b0Only));
      }

      List<String> b1Only = new ArrayList<>();

      for (String rel : b1Map.keySet()) {
         if (!b0Map.containsKey(rel)) {
            b1Only.add(rel);
         }
      }

      b1Only.sort(Comparator.naturalOrder());
      Map<String, FileAddition> plannedMap = new LinkedHashMap<>();

      for (FileAddition fa : plannedAdditions) {
         if (plannedMap.put(fa.relativePath(), fa) != null) {
            return new CreateManifestDeltaVerifier.Result.Failure(
               diag("SIR-APP-CHANGE-BIND-005", "duplicate relativePath in planned additions: " + fa.relativePath(), fa.relativePath())
            );
         }
      }

      List<String> plannedSorted = new ArrayList<>(plannedMap.keySet());
      plannedSorted.sort(Comparator.naturalOrder());
      if (!b1Only.equals(plannedSorted)) {
         return new CreateManifestDeltaVerifier.Result.Failure(
            diag("SIR-APP-CHANGE-BIND-005", "B1-only paths do not match planned additions: b1Only=" + b1Only + " planned=" + plannedSorted)
         );
      }

      for (FileAddition fa : plannedAdditions) {
         BaselineManifestEntry b1Entry = b1Map.get(fa.relativePath());
         if (b1Entry == null) {
            return new CreateManifestDeltaVerifier.Result.Failure(
               diag("SIR-APP-CHANGE-BIND-005", "planned addition has no B1 manifest entry: " + fa.relativePath(), fa.relativePath())
            );
         }

         if (!b1Entry.artifactId().equals(fa.artifactId())) {
            return new CreateManifestDeltaVerifier.Result.Failure(
               diag(
                  "SIR-APP-CHANGE-BIND-005",
                  "artifactId mismatch for planned addition " + fa.relativePath() + ": plan=" + fa.artifactId() + " b1=" + b1Entry.artifactId(),
                  fa.relativePath()
               )
            );
         }

         if (b1Entry.ownerSymbol().isEmpty() || !b1Entry.ownerSymbol().get().equals(fa.ownerSymbol())) {
            return new CreateManifestDeltaVerifier.Result.Failure(
               diag(
                  "SIR-APP-CHANGE-BIND-005",
                  "ownerSymbol mismatch for planned addition " + fa.relativePath() + ": plan=" + fa.ownerSymbol() + " b1=" + b1Entry.ownerSymbol(),
                  fa.relativePath()
               )
            );
         }

         if (b1Entry.byteCount() != fa.candidateByteCount()) {
            return new CreateManifestDeltaVerifier.Result.Failure(
               diag(
                  "SIR-APP-CHANGE-BIND-005",
                  "byteCount mismatch for planned addition " + fa.relativePath() + ": plan=" + fa.candidateByteCount() + " b1=" + b1Entry.byteCount(),
                  fa.relativePath()
               )
            );
         }

         if (!b1Entry.sha256Hex().equals(fa.candidateSha256Hex())) {
            return new CreateManifestDeltaVerifier.Result.Failure(
               diag(
                  "SIR-APP-CHANGE-BIND-005",
                  "SHA-256 mismatch for planned addition " + fa.relativePath() + ": plan=" + fa.candidateSha256Hex() + " b1=" + b1Entry.sha256Hex(),
                  fa.relativePath()
               )
            );
         }
      }

      return new CreateManifestDeltaVerifier.Result.Success(List.copyOf(b1Only));
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

   public static Set<String> addedPathsOr(List<FileAddition> additions) {
      Set<String> set = new LinkedHashSet<>();

      for (FileAddition fa : additions) {
         set.add(fa.relativePath());
      }

      return Collections.unmodifiableSet(set);
   }

   public sealed interface Result permits CreateManifestDeltaVerifier.Result.Success, CreateManifestDeltaVerifier.Result.Failure {
      record Failure(ChangeExecutionDiagnostic error) implements CreateManifestDeltaVerifier.Result {
         public Failure {
            Objects.requireNonNull(error, "error");
         }
      }

      record Success(List<String> addedPaths) implements CreateManifestDeltaVerifier.Result {
         public Success {
            Objects.requireNonNull(addedPaths, "addedPaths");
            addedPaths = List.copyOf(addedPaths);
         }
      }
   }
}
