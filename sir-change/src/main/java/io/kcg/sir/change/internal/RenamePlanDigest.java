package io.kcg.sir.change.internal;

import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.RenameFileEstablishment;
import io.kcg.sir.change.api.RenameFileUpdate;
import io.kcg.sir.change.api.RenameFileWithdrawal;
import io.kcg.sir.change.api.RenamePlan;
import io.kcg.sir.change.api.RenameSubject;
import java.util.List;
import java.util.Objects;

/**
 * The canonical digest of a rename plan.
 *
 * <p>One canonical text function serves both the planner (which stamps a plan) and
 * {@code RenamePlanner.verify} (which re-derives the stamp), so a plan cannot carry a digest that some
 * other serialization of the same content would contradict. The digest covers everything the plan
 * claims except the digest field itself.
 */
public final class RenamePlanDigest {
   private RenamePlanDigest() {
   }

   public static String of(RenamePlan plan) {
      Objects.requireNonNull(plan, "plan");
      return of(
         plan.basedOn(),
         plan.subject(),
         plan.updates(),
         plan.withdrawals(),
         plan.establishments(),
         plan.baseGraphCanonicalDigest(),
         plan.candidateGraphCanonicalDigest(),
         plan.baseSourceSha256Hex(),
         plan.candidateSourceSha256Hex()
      );
   }

   static String of(
      ChangeBaseRevision basedOn,
      RenameSubject subject,
      List<RenameFileUpdate> updates,
      List<RenameFileWithdrawal> withdrawals,
      List<RenameFileEstablishment> establishments,
      String baseGraphCanonicalDigest,
      String candidateGraphCanonicalDigest,
      String baseSourceSha256Hex,
      String candidateSourceSha256Hex
   ) {
      StringBuilder text = new StringBuilder();
      line(text, "basedOn.sourceId", basedOn.sourceId().value());
      line(text, "basedOn.baseSourceSha256Hex", basedOn.baseSourceSha256Hex());
      line(text, "basedOn.graphVersion", basedOn.graphVersion().name());
      line(text, "basedOn.graphCanonicalDigest", basedOn.graphCanonicalDigest());
      line(text, "basedOn.snapshotFormatVersion", basedOn.snapshotFormatVersion().name());
      line(text, "subject.declarationSymbol", subject.declarationSymbol().value());
      line(text, "subject.kind", subject.kind().name());
      line(text, "subject.baseName", subject.baseName());
      line(text, "subject.candidateName", subject.candidateName());
      line(text, "graphs.base", baseGraphCanonicalDigest);
      line(text, "graphs.candidate", candidateGraphCanonicalDigest);
      line(text, "sources.base", baseSourceSha256Hex);
      line(text, "sources.candidate", candidateSourceSha256Hex);

      for (RenameFileUpdate update : updates) {
         line(text, "update", String.join(
            "|",
            update.relativePath(),
            update.artifactId().value(),
            update.ownerSymbol().map(value -> value.value()).orElse(""),
            Long.toString(update.baseByteCount()),
            update.baseSha256Hex(),
            Long.toString(update.candidateByteCount()),
            update.candidateSha256Hex()
         ));
      }

      for (RenameFileWithdrawal withdrawal : withdrawals) {
         line(text, "withdrawal", String.join(
            "|",
            withdrawal.relativePath(),
            withdrawal.artifactId().value(),
            withdrawal.ownerSymbol().map(value -> value.value()).orElse(""),
            Long.toString(withdrawal.byteCount()),
            withdrawal.sha256Hex()
         ));
      }

      for (RenameFileEstablishment establishment : establishments) {
         line(text, "establishment", String.join(
            "|",
            establishment.relativePath(),
            establishment.artifactId().value(),
            establishment.ownerSymbol().map(value -> value.value()).orElse(""),
            Long.toString(establishment.byteCount()),
            establishment.sha256Hex()
         ));
      }

      return Sha256.hex(text.toString());
   }

   private static void line(StringBuilder text, String name, String value) {
      text.append(name).append('=').append(value).append('\n');
   }
}
