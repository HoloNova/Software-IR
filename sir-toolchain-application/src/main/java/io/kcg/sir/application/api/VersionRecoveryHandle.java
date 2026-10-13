package io.kcg.sir.application.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * What a caller keeps when a version change stopped half way: enough to ask for recovery later,
 * even from a new process. It is only a pointer; recovery proves everything again from the state.
 */
public record VersionRecoveryHandle(Path stateRoot, Path outputRoot, String transactionId, String baselineId, String candidateId, Optional<String> bindingDigest) {
    public VersionRecoveryHandle {
        Objects.requireNonNull(stateRoot, "stateRoot");
        Objects.requireNonNull(outputRoot, "outputRoot");
        bindingDigest = Objects.requireNonNull(bindingDigest, "bindingDigest");
        for (String hash : new String[] {transactionId, baselineId, candidateId}) {
            if (hash == null || !hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid identifier");
            }
        }

        bindingDigest.ifPresent(d -> {
            if (!d.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid binding digest");
            }
        });
    }
}
