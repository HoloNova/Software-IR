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

        VersionPlan plan = planned.plan();
        var refusal = VerificationGate.check(request.verification(), request.required(), request.change().planning().candidate().sha256Hex(), plan.candidate());
        if (refusal.isPresent()) {
            return new Result.Refused(refusal.get().code(), refusal.get().message());
        }

        return new Result.Completed(this.apply.apply(request.change()));
    }
}
