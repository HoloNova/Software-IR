package io.kcg.sir.change.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A read-only, typed rename of one declaration: the three disjoint managed-file sets an apply would
 * have to perform as one transaction, bound to the base revision and to both graph digests.
 *
 * <p>The plan is a statement, not an action. It has no apply entry point, it is not a
 * {@link ChangeOperation} and it is not a {@link ChangePlan}: the six existing operation families and
 * their one-family-per-plan invariant are untouched, because none of them can express
 * "withdraw these paths, establish those paths and update these paths at once".
 *
 * <p>Each of the three sets is ordered by relative path and they never overlap, so two runs over the
 * same inputs produce the same plan and the same {@link #planDigest()}. That digest covers everything
 * except itself, which is what makes a plan computed for one pair of revisions detectable as stale
 * against another. A revision is a source plus its graph, so both source digests are part of the
 * plan: a comment-only edit leaves the graph untouched but must still invalidate the plan.
 */
public record RenamePlan(
   ChangeBaseRevision basedOn,
   RenameSubject subject,
   List<RenameFileUpdate> updates,
   List<RenameFileWithdrawal> withdrawals,
   List<RenameFileEstablishment> establishments,
   String baseGraphCanonicalDigest,
   String candidateGraphCanonicalDigest,
   String baseSourceSha256Hex,
   String candidateSourceSha256Hex,
   String planDigest
) {
   public RenamePlan {
      Objects.requireNonNull(basedOn, "basedOn");
      Objects.requireNonNull(subject, "subject");
      updates = List.copyOf(Objects.requireNonNull(updates, "updates"));
      withdrawals = List.copyOf(Objects.requireNonNull(withdrawals, "withdrawals"));
      establishments = List.copyOf(Objects.requireNonNull(establishments, "establishments"));
      requireDigest(baseGraphCanonicalDigest, "baseGraphCanonicalDigest");
      requireDigest(candidateGraphCanonicalDigest, "candidateGraphCanonicalDigest");
      requireDigest(baseSourceSha256Hex, "baseSourceSha256Hex");
      requireDigest(candidateSourceSha256Hex, "candidateSourceSha256Hex");
      requireDigest(planDigest, "planDigest");
      requireSorted(updates.stream().map(RenameFileUpdate::relativePath).toList(), "updates");
      requireSorted(withdrawals.stream().map(RenameFileWithdrawal::relativePath).toList(), "withdrawals");
      requireSorted(establishments.stream().map(RenameFileEstablishment::relativePath).toList(), "establishments");
      Set<String> seen = new LinkedHashSet<>();
      requireExclusive(seen, updates.stream().map(RenameFileUpdate::relativePath).toList(), "updates");
      requireExclusive(seen, withdrawals.stream().map(RenameFileWithdrawal::relativePath).toList(), "withdrawals");
      requireExclusive(seen, establishments.stream().map(RenameFileEstablishment::relativePath).toList(), "establishments");
   }

   private static void requireDigest(String digest, String name) {
      if (digest == null || !digest.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException(name + " must be a 64-character lowercase hexadecimal SHA-256 digest");
      }
   }

   private static void requireSorted(List<String> paths, String name) {
      for (int index = 1; index < paths.size(); index++) {
         if (paths.get(index - 1).compareTo(paths.get(index)) >= 0) {
            throw new IllegalArgumentException(name + " must be sorted by relative path and free of duplicates: " + paths.get(index));
         }
      }
   }

   private static void requireExclusive(Set<String> seen, List<String> paths, String name) {
      for (String path : paths) {
         if (!seen.add(path)) {
            throw new IllegalArgumentException(name + " overlaps another set at: " + path);
         }
      }
   }
}
