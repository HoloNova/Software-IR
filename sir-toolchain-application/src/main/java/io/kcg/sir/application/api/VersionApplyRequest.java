package io.kcg.sir.application.api;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Apply the plan the caller has already seen. {@code expected} is only what the caller expects: the
 * application plans again, under the lock, and refuses unless the result is exactly equal.
 * {@code transactionId} names this attempt; a finished transaction's ID is never reused.
 */
public record VersionApplyRequest(VersionPlanningRequest planning, VersionPlanningResult expected, String transactionId) {
    public VersionApplyRequest {
        Objects.requireNonNull(planning, "planning");
        Objects.requireNonNull(expected, "expected");
        if (!(expected instanceof VersionPlanningResult.Planned) && !(expected instanceof VersionPlanningResult.NoChanges)) {
            throw new IllegalArgumentException("expected must be a plan or no-changes");
        }

        if (transactionId == null || !transactionId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("transactionId must be lowercase SHA-256 hex");
        }
    }

    /** A request with a fresh random transaction ID. */
    public VersionApplyRequest(VersionPlanningRequest planning, VersionPlanningResult expected) {
        this(planning, expected, io.kcg.sir.application.internal.Sha256.hexDigest(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII)));
    }
}
