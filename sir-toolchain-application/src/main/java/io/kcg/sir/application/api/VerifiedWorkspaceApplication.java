package io.kcg.sir.application.api;

import java.util.Objects;

/**
 * "Verify, then update" for a project whose current version is the SIR on disk and whose history is Git: the
 * change is carried out only if the candidate has the evidence the caller asked for.
 *
 * <p>It is the same gate as {@link VerifiedVersionApplication}, in front of {@link WorkspaceVersionApplication}.
 * The evidence must be about exactly this candidate and every level up to the required one must have passed
 * and cleaned up after itself ({@link VerificationGate}); anything else is refused before the project, its
 * sources or Git are read or written. Once the evidence is accepted the ordinary apply runs unchanged with all
 * of its own checks: a report never makes a stale plan current.
 */
public final class VerifiedWorkspaceApplication {
    private final WorkspaceVersionApplication apply;

    public VerifiedWorkspaceApplication(VersionControl versionControl) {
        this(new WorkspaceVersionApplication(versionControl));
    }

    VerifiedWorkspaceApplication(WorkspaceVersionApplication apply) {
        this.apply = Objects.requireNonNull(apply, "apply");
    }

    /** The change and the plan the caller has seen, the verification of its candidate, and the level it must have reached. */
    public record Request(WorkspaceVersionRequest change, WorkspacePlanningResult expected, VerificationReport verification, VerificationStage required) {
        public Request {
            Objects.requireNonNull(change, "change");
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(verification, "verification");
            Objects.requireNonNull(required, "required");
        }
    }

    public sealed interface Result permits Result.Refused, Result.Completed {
        /** The evidence was not accepted; nothing was read from or written to the project, its sources or Git. */
        record Refused(String code, String message) implements Result {
            public Refused {
                Objects.requireNonNull(code, "code");
                Objects.requireNonNull(message, "message");
            }
        }

        /** The evidence was accepted and the ordinary apply ran; its own result says how it ended. */
        record Completed(WorkspaceApplyResult result) implements Result {
            public Completed {
                Objects.requireNonNull(result, "result");
            }
        }
    }

    public Result apply(Request request) {
        Objects.requireNonNull(request, "request");
        if (!(request.expected() instanceof WorkspacePlanningResult.Planned planned)) {
            // No change to publish: there is no candidate whose evidence could matter.
            return new Result.Completed(this.apply.apply(request.change(), request.expected()));
        }

        var refusal = VerificationGate.check(request.verification(), request.required(), request.change().candidate().sha256Hex(), planned.plan().candidate());
        if (refusal.isPresent()) {
            return new Result.Refused(refusal.get().code(), refusal.get().message());
        }

        return new Result.Completed(this.apply.apply(request.change(), request.expected()));
    }
}
