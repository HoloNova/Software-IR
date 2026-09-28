package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeExecutionStage;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import io.kcg.sir.change.api.RenameFileEstablishment;
import io.kcg.sir.change.api.RenameFileUpdate;
import io.kcg.sir.change.api.RenameFileWithdrawal;
import io.kcg.sir.change.api.RenamePlan;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Binds one verified {@link RenamePlan} to the two Bundle manifests the transaction will read.
 *
 * <p>The plan is a statement about managed files (Q16), and it is checked against the graph of the
 * revision it was computed from. The apply path works one level below that: it reads the B0 Bundle
 * manifest and builds the B1 manifest from the candidate's generated files. This verifier is the
 * bridge between the two. It rebuilds the four partitions of the manifest delta the same way the
 * planner reads the graph delta - a path only in B0 is a withdrawal, a path only in B1 is an
 * establishment, a path on both sides whose content differs is an update, a path on both sides whose
 * content is equal is unchanged - and then requires that the three plan sets are exactly that
 * partition, with every identity and content field equal on both sides.
 *
 * <p>Doing it here, before the transaction, keeps two guarantees out of the load layer: an entry
 * whose owner symbol is absent is refused with {@code SIR-APP-RENAME-BIND-001} instead of reaching a
 * V2/V3 payload constructor (the plan-entry records allow an empty owner, the payloads do not), and a
 * plan that does not describe these two manifests is refused before any path is touched. Nothing in
 * this class reads or writes the output tree; the on-disk preconditions are {@code PlanProtector}'s
 * plan-entry forms.
 *
 * <p>The plan's own identity (its {@code planDigest}, subject and {@code basedOn}) is deliberately
 * not checked here: it is what the journal header binds and what the recovery path must be able to
 * do without, so it belongs to the transaction, not to this manifest-level bridge.
 */
public final class RenamePlanBindingVerifier {
   private RenamePlanBindingVerifier() {
   }

   public static RenamePlanBindingVerifier.Result verify(List<BaselineManifestEntry> b0Manifest, List<BaselineManifestEntry> b1Manifest, RenamePlan plan) {
      Objects.requireNonNull(b0Manifest, "b0Manifest");
      Objects.requireNonNull(b1Manifest, "b1Manifest");
      Objects.requireNonNull(plan, "plan");
      Map<String, BaselineManifestEntry> b0Map = toMap(b0Manifest);
      Map<String, BaselineManifestEntry> b1Map = toMap(b1Manifest);
      if (b0Map.size() != b0Manifest.size()) {
         return new RenamePlanBindingVerifier.Result.Failure(diag("SIR-APP-RENAME-BIND-002", "duplicate relativePath in B0 manifest"));
      }

      if (b1Map.size() != b1Manifest.size()) {
         return new RenamePlanBindingVerifier.Result.Failure(diag("SIR-APP-RENAME-BIND-002", "duplicate relativePath in B1 manifest"));
      }

      ChangeExecutionDiagnostic error = checkPlanEntries(plan, b0Map, b1Map);
      if (error == null) {
         error = checkManifestDeltaIsCovered(plan, b0Map, b1Map);
      }

      return error != null ? new RenamePlanBindingVerifier.Result.Failure(error) : new RenamePlanBindingVerifier.Result.Success(touchedPaths(plan));
   }

   /**
    * Direction one: every path the plan names must exist on the side(s) that hold it, with the
    * artifact identity, owner and content the plan recorded.
    */
   private static ChangeExecutionDiagnostic checkPlanEntries(RenamePlan plan, Map<String, BaselineManifestEntry> b0Map, Map<String, BaselineManifestEntry> b1Map) {
      for (RenameFileUpdate update : plan.updates()) {
         String rel = update.relativePath();
         if (update.ownerSymbol().isEmpty()) {
            return diag("SIR-APP-RENAME-BIND-001", "planned update carries no owner symbol: " + rel, rel);
         }

         BaselineManifestEntry b0Entry = b0Map.get(rel);
         if (b0Entry == null) {
            return diag("SIR-APP-RENAME-BIND-003", "planned update has no B0 manifest entry: " + rel, rel);
         }

         BaselineManifestEntry b1Entry = b1Map.get(rel);
         if (b1Entry == null) {
            return diag("SIR-APP-RENAME-BIND-003", "planned update has no B1 manifest entry: " + rel, rel);
         }

         ChangeExecutionDiagnostic error = checkIdentity("planned update", rel, update.artifactId(), update.ownerSymbol().get(), "B0", b0Entry);
         if (error == null) {
            error = checkIdentity("planned update", rel, update.artifactId(), update.ownerSymbol().get(), "B1", b1Entry);
         }

         if (error == null) {
            error = checkContent("planned update base", rel, update.baseByteCount(), update.baseSha256Hex(), "B0", b0Entry);
         }

         if (error == null) {
            error = checkContent("planned update candidate", rel, update.candidateByteCount(), update.candidateSha256Hex(), "B1", b1Entry);
         }

         if (error != null) {
            return error;
         }
      }

      for (RenameFileWithdrawal withdrawal : plan.withdrawals()) {
         String rel = withdrawal.relativePath();
         if (withdrawal.ownerSymbol().isEmpty()) {
            return diag("SIR-APP-RENAME-BIND-001", "planned withdrawal carries no owner symbol: " + rel, rel);
         }

         BaselineManifestEntry b0Entry = b0Map.get(rel);
         if (b0Entry == null) {
            return diag("SIR-APP-RENAME-BIND-003", "planned withdrawal has no B0 manifest entry: " + rel, rel);
         }

         ChangeExecutionDiagnostic error = checkIdentity("planned withdrawal", rel, withdrawal.artifactId(), withdrawal.ownerSymbol().get(), "B0", b0Entry);
         if (error == null) {
            error = checkContent("planned withdrawal", rel, withdrawal.byteCount(), withdrawal.sha256Hex(), "B0", b0Entry);
         }

         if (error != null) {
            return error;
         }
      }

      for (RenameFileEstablishment establishment : plan.establishments()) {
         String rel = establishment.relativePath();
         if (establishment.ownerSymbol().isEmpty()) {
            return diag("SIR-APP-RENAME-BIND-001", "planned establishment carries no owner symbol: " + rel, rel);
         }

         BaselineManifestEntry b1Entry = b1Map.get(rel);
         if (b1Entry == null) {
            return diag("SIR-APP-RENAME-BIND-003", "planned establishment has no B1 manifest entry: " + rel, rel);
         }

         ChangeExecutionDiagnostic error = checkIdentity("planned establishment", rel, establishment.artifactId(), establishment.ownerSymbol().get(), "B1", b1Entry);
         if (error == null) {
            error = checkContent("planned establishment", rel, establishment.byteCount(), establishment.sha256Hex(), "B1", b1Entry);
         }

         if (error != null) {
            return error;
         }
      }

      return null;
   }

   /**
    * Direction two: every managed path that changed between the two manifests must be named by the
    * plan with the kind the manifests imply, and a path whose content is unchanged must not drift in
    * its identity or content either.
    */
   private static ChangeExecutionDiagnostic checkManifestDeltaIsCovered(RenamePlan plan, Map<String, BaselineManifestEntry> b0Map, Map<String, BaselineManifestEntry> b1Map) {
      List<String> b0Only = new ArrayList<>();
      List<String> b1Only = new ArrayList<>();
      List<String> changedInPlace = new ArrayList<>();

      for (Map.Entry<String, BaselineManifestEntry> entry : b1Map.entrySet()) {
         String rel = entry.getKey();
         BaselineManifestEntry b1Entry = entry.getValue();
         BaselineManifestEntry b0Entry = b0Map.get(rel);
         if (b0Entry == null) {
            b1Only.add(rel);
         } else if (b0Entry.sha256Hex().equals(b1Entry.sha256Hex())) {
            if (!entriesEqual(b0Entry, b1Entry)) {
               return diag(
                  "SIR-APP-RENAME-BIND-007",
                  "content-unchanged managed path drifted between B0 and B1: path=" + rel + " B0=" + describeEntry(b0Entry) + " B1=" + describeEntry(b1Entry),
                  rel
               );
            }
         } else {
            changedInPlace.add(rel);
         }
      }

      for (String rel : b0Map.keySet()) {
         if (!b1Map.containsKey(rel)) {
            b0Only.add(rel);
         }
      }

      b0Only.sort(Comparator.naturalOrder());
      b1Only.sort(Comparator.naturalOrder());
      changedInPlace.sort(Comparator.naturalOrder());
      List<String> plannedWithdrawals = withdrawalPaths(plan);
      List<String> plannedUpdates = updatePaths(plan);
      List<String> plannedEstablishments = establishmentPaths(plan);
      if (!plannedWithdrawals.equals(b0Only)) {
         return mismatch("B0-only managed paths do not match the planned withdrawals", plannedWithdrawals, b0Only);
      }

      if (!plannedUpdates.equals(changedInPlace)) {
         return mismatch("managed paths that changed in place do not match the planned updates", plannedUpdates, changedInPlace);
      }

      return plannedEstablishments.equals(b1Only)
         ? null
         : mismatch("B1-only managed paths do not match the planned establishments", plannedEstablishments, b1Only);
   }

   private static ChangeExecutionDiagnostic mismatch(String label, List<String> planned, List<String> actual) {
      List<String> unexpected = new ArrayList<>(actual);
      unexpected.removeAll(planned);
      List<String> missing = new ArrayList<>(planned);
      missing.removeAll(actual);
      String affected = !unexpected.isEmpty() ? unexpected.get(0) : missing.isEmpty() ? null : missing.get(0);
      String message = label + ": planned=" + planned + " actual=" + actual;
      return affected == null ? diag("SIR-APP-RENAME-BIND-006", message) : diag("SIR-APP-RENAME-BIND-006", message, affected);
   }

   private static ChangeExecutionDiagnostic checkIdentity(
      String label, String rel, io.kcg.sir.lowering.api.LoweredNodeId artifactId, SymbolId ownerSymbol, String side, BaselineManifestEntry entry
   ) {
      if (entry.ownerSymbol().isEmpty()) {
         return diag("SIR-APP-RENAME-BIND-004", label + ": " + side + " manifest entry carries no owner symbol: " + rel, rel);
      }

      return entry.artifactId().equals(artifactId) && entry.ownerSymbol().get().equals(ownerSymbol)
         ? null
         : diag(
            "SIR-APP-RENAME-BIND-004",
            label
               + ": "
               + side
               + " identity mismatch: path="
               + rel
               + " planArtifact="
               + artifactId
               + " planOwner="
               + ownerSymbol
               + " manifestArtifact="
               + entry.artifactId()
               + " manifestOwner="
               + entry.ownerSymbol().get(),
            rel
         );
   }

   private static ChangeExecutionDiagnostic checkContent(
      String label, String rel, long byteCount, String sha256Hex, String side, BaselineManifestEntry entry
   ) {
      return entry.byteCount() == byteCount && entry.sha256Hex().equals(sha256Hex)
         ? null
         : diag(
            "SIR-APP-RENAME-BIND-005",
            label
               + ": "
               + side
               + " content mismatch: path="
               + rel
               + " planBytes="
               + byteCount
               + " planSha="
               + sha256Hex
               + " manifestBytes="
               + entry.byteCount()
               + " manifestSha="
               + entry.sha256Hex(),
            rel
         );
   }

   private static List<String> touchedPaths(RenamePlan plan) {
      TreeSet<String> paths = new TreeSet<>();
      plan.updates().forEach(update -> paths.add(update.relativePath()));
      plan.withdrawals().forEach(withdrawal -> paths.add(withdrawal.relativePath()));
      plan.establishments().forEach(establishment -> paths.add(establishment.relativePath()));
      return List.copyOf(paths);
   }

   private static List<String> updatePaths(RenamePlan plan) {
      return plan.updates().stream().map(RenameFileUpdate::relativePath).toList();
   }

   private static List<String> withdrawalPaths(RenamePlan plan) {
      return plan.withdrawals().stream().map(RenameFileWithdrawal::relativePath).toList();
   }

   private static List<String> establishmentPaths(RenamePlan plan) {
      return plan.establishments().stream().map(RenameFileEstablishment::relativePath).toList();
   }

   private static boolean entriesEqual(BaselineManifestEntry a, BaselineManifestEntry b) {
      return a.byteCount() == b.byteCount()
         && a.sha256Hex().equals(b.sha256Hex())
         && a.artifactId().equals(b.artifactId())
         && a.ownerSymbol().equals(b.ownerSymbol());
   }

   private static String describeEntry(BaselineManifestEntry entry) {
      return "{bytes=" + entry.byteCount() + ", sha=" + entry.sha256Hex() + ", artifact=" + entry.artifactId() + ", owner=" + entry.ownerSymbol() + "}";
   }

   private static Map<String, BaselineManifestEntry> toMap(List<BaselineManifestEntry> manifest) {
      Map<String, BaselineManifestEntry> map = new LinkedHashMap<>();

      for (BaselineManifestEntry entry : manifest) {
         map.put(entry.relativePath(), entry);
      }

      return map;
   }

   private static ChangeExecutionDiagnostic diag(String code, String message) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.PROTECT, ExecutionSeverity.ERROR, message, Optional.empty());
   }

   private static ChangeExecutionDiagnostic diag(String code, String message, String rel) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.PROTECT, ExecutionSeverity.ERROR, message, Optional.of(rel));
   }

   public sealed interface Result permits RenamePlanBindingVerifier.Result.Success, RenamePlanBindingVerifier.Result.Failure {
      record Failure(ChangeExecutionDiagnostic error) implements RenamePlanBindingVerifier.Result {
         public Failure {
            Objects.requireNonNull(error, "error");
         }
      }

      record Success(List<String> touchedPaths) implements RenamePlanBindingVerifier.Result {
         public Success {
            Objects.requireNonNull(touchedPaths, "touchedPaths");
            touchedPaths = List.copyOf(touchedPaths);
         }
      }
   }
}
