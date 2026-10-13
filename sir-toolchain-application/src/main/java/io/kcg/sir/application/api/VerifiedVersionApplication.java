package io.kcg.sir.application.api;

import java.util.Objects;

/**
 * "Verify, then update": a stored version is replaced only if the candidate it is replaced with has the evidence
 * the caller asked for.
 *
 * <p>The evidence must be about exactly this candidate (the same source set and the same generated file list the
 * stored version would carry), every level up to the required one must have passed, and the verification must have
 * cleaned up after itself. Anything else is refused before the version store or the generated project is read or
 * written. Once the evidence is accepted, the ordinary version apply runs unchanged, with all of its own checks: a
 * report never makes a stale plan current, and it is not consumed, because it describes the candidate, not the project.
 */
public final class VerifiedVersionApplication {
    private final VersionApplyApplication apply;

    public VerifiedVersionApplication() {
        this(new VersionApplyApplication());
    }

    VerifiedVersionApplication(VersionApplyApplication apply) {
        this.apply = Objects.requireNonNull(apply, "apply");
    }

    /** The version change, the verification of its candidate, and the highest level that verification must have reached. */
    public record Request(VersionApplyRequest change, VerificationReport verification, VerificationStage required) {
        public Request {
            Objects.requireNonNull(change, "change");
            Objects.requireNonNull(verification, "verification");
            Objects.requireNonNull(required, "required");
        }
    }

    public sealed interface Result permits Result.Refused, Result.Completed {
        /** The evidence was not accepted; nothing was read from or written to the project or its state. */
        record Refused(String code, String message) implements Result {
            public Refused {
                Objects.requireNonNull(code, "code");
                Objects.requireNonNull(message, "message");
            }
        }

        /** The evidence was accepted and the ordinary version apply ran; its own result says how it ended. */
        record Completed(VersionApplyResult result) implements Result {
            public Completed {
                Objects.requireNonNull(result, "result");
            }
        }
    }

    public Result apply(Request request) {
        Objects.requireNonNull(request, "request");
        if (!(request.change().expected() instanceof VersionPlanningResult.Planned planned)) {
            // No change to publish: there is no candidate whose evidence could matter.
            return new Result.Completed(this.apply.apply(request.change()));
        }

        VerificationReport report = request.verification();
        VerificationBinding binding = report.binding().orElse(null);
        if (binding == null) {
            return new Result.Refused("VERIFICATION_NOT_BOUND", "the verification did not generate a project, so it is evidence about no candidate");
        }

        VersionPlan plan = planned.plan();
        String sourceSet = request.change().planning().candidate().sha256Hex();
        if (!binding.sourceSetSha256().equals(sourceSet) || !binding.sourceSetSha256().equals(plan.candidate().sourceSetSha256Hex())
                || !binding.manifestDigest().equals(plan.candidate().manifestDigest())) {
            return new Result.Refused("VERIFICATION_CANDIDATE_MISMATCH",
                    "the verification is about a different source set or a different generated file list than the candidate being published");
        }

        for (VerificationStage stage : VerificationStage.values()) {
            if (stage.ordinal() > request.required().ordinal()) {
                break;
            }

            StageOutcome outcome = report.stage(stage).outcome();
            if (outcome.status() != StageStatus.PASSED) {
                return new Result.Refused("VERIFICATION_NOT_PASSED", stage + " is " + outcome.status() + " (" + outcome.code()
                        + "), but " + request.required() + " is required");
            }
        }

        if (!report.cleanup().proved()) {
            return new Result.Refused("VERIFICATION_CLEANUP_NOT_PROVED", "the verification could not prove it left nothing behind");
        }

        return new Result.Completed(this.apply.apply(request.change()));
    }
}
