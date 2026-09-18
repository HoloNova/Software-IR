package io.kcg.sir.application.conformance;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The exact content delta between the B0 and B1 manifests, plus both key sets so
 * the delta can be proven to partition them.
 *
 * <p>Taken together, {@code unchanged}, {@code updates}, and {@code deletions} must
 * cover {@code b0Keys} exactly once, and {@code unchanged}, {@code updates}, and
 * {@code additions} must cover {@code b1Keys} exactly once. Carrying the key sets
 * makes that partition checkable from the delta alone, which is what lets the
 * closure-external assertion be a real check rather than a restatement of how the
 * delta was built.
 *
 * @param additions  paths present only in B1, with their B1 identity
 * @param updates    paths present in both with different identity, mapped to the B1 identity
 * @param deletions  paths present only in B0
 * @param unchanged  paths present in both with identical identity
 * @param b0Keys     every path present in B0
 * @param b1Keys     every path present in B1
 */
public record Delta(
        Map<String, FileDigest> additions,
        Map<String, FileDigest> updates,
        Set<String> deletions,
        Set<String> unchanged,
        Set<String> b0Keys,
        Set<String> b1Keys) {

    public Delta {
        Objects.requireNonNull(additions, "additions");
        Objects.requireNonNull(updates, "updates");
        Objects.requireNonNull(deletions, "deletions");
        Objects.requireNonNull(unchanged, "unchanged");
        Objects.requireNonNull(b0Keys, "b0Keys");
        Objects.requireNonNull(b1Keys, "b1Keys");
        additions = Map.copyOf(additions);
        updates = Map.copyOf(updates);
        deletions = Set.copyOf(deletions);
        unchanged = Set.copyOf(unchanged);
        b0Keys = Set.copyOf(b0Keys);
        b1Keys = Set.copyOf(b1Keys);
    }
}
