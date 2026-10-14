package io.kcg.sir.application.api;

import java.util.Objects;
import java.util.Optional;

/**
 * The evidence check that every "verify, then update" entry shares, whichever way the update is then carried out.
 *
 * <p>Why it is separate: the check says nothing about how a version is stored or published. It only asks whether
 * a verification report is about exactly the candidate that is about to be published and reached the level the
 * caller asked for. Keeping it in one place means the older version entry and the Git-based one cannot drift
 * apart on what counts as acceptable evidence.
 */
public final class VerificationGate {
    private VerificationGate() {
    }

    /** Why the evidence was not accepted. */
    public record Refusal(String code, String message) {
        public Refusal {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Whether {@code report} is acceptable evidence for publishing {@code candidate}.
     *
     * @param candidateSources the SHA-256 of the candidate source set the caller is publishing
     * @param candidate the candidate version the plan publishes
     * @param required the highest level the verification must have passed
     * @return empty if the evidence is accepted
     */
    public static Optional<Refusal> check(VerificationReport report, VerificationStage required, String candidateSources, ProjectBaselineReceipt candidate) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(required, "required");
        VerificationBinding binding = report.binding().orElse(null);
        if (binding == null) {
            return Optional.of(new Refusal("VERIFICATION_NOT_BOUND", "the verification did not generate a project, so it is evidence about no candidate"));
        }

        if (!binding.sourceSetSha256().equals(candidateSources) || !binding.sourceSetSha256().equals(candidate.sourceSetSha256Hex())
                || !binding.manifestDigest().equals(candidate.manifestDigest())) {
            return Optional.of(new Refusal("VERIFICATION_CANDIDATE_MISMATCH",
                    "the verification is about a different source set or a different generated file list than the candidate being published"));
        }

        for (VerificationStage stage : VerificationStage.values()) {
            if (stage.ordinal() > required.ordinal()) {
                break;
            }

            StageOutcome outcome = report.stage(stage).outcome();
            if (outcome.status() != StageStatus.PASSED) {
                return Optional.of(new Refusal("VERIFICATION_NOT_PASSED", stage + " is " + outcome.status() + " (" + outcome.code()
                        + "), but " + required + " is required"));
            }
        }

        if (!report.cleanup().proved()) {
            return Optional.of(new Refusal("VERIFICATION_CLEANUP_NOT_PROVED", "the verification could not prove it left nothing behind"));
        }

        return Optional.empty();
    }
}
