package io.kcg.sir.application.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** What was proved about leaving nothing behind (process gone, directory removed, schema dropped, lock released). */
public record CleanupProof(boolean proved, List<String> checks) {
    public static final int MAX_CHECKS = 32;

    public CleanupProof {
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        if (checks.size() > MAX_CHECKS) {
            throw new IllegalArgumentException("too many cleanup checks");
        }

        for (String check : checks) {
            if (check.length() > StageOutcome.MAX_DIAGNOSTIC_CHARS) {
                throw new IllegalArgumentException("cleanup check exceeds its budget");
            }
        }
    }

    public static CleanupProof nothingToClean() {
        return new CleanupProof(true, List.of("nothing was created"));
    }

    public static CleanupProof proved(String... checks) {
        return new CleanupProof(true, List.of(checks));
    }

    public static CleanupProof notProved(String... checks) {
        return new CleanupProof(false, List.of(checks));
    }

    /** Proved only if every part is proved; the checks keep their order. */
    public static CleanupProof combine(List<CleanupProof> parts) {
        boolean proved = true;
        List<String> checks = new ArrayList<>();
        for (CleanupProof part : parts) {
            proved &= part.proved();
            checks.addAll(part.checks());
        }

        return new CleanupProof(proved, checks.size() > MAX_CHECKS ? checks.subList(0, MAX_CHECKS) : checks);
    }
}
