package io.kcg.sir.application.api;

import java.util.List;
import java.util.Objects;

/** The outcome of recovering a stopped version change. */
public sealed interface VersionRecoveryResult permits VersionRecoveryResult.Recovered, VersionRecoveryResult.Failure {
    List<ExecutionDiagnostic> diagnostics();

    enum Outcome {
        /** The new version had been published: verified and cleaned up. */
        COMMITTED_AND_CLEANED,
        /** The new version had not been published: everything put back as it was. */
        ROLLED_BACK,
        /** The transaction had already finished; nothing to do. */
        ALREADY_CLEAN
    }

    record Recovered(Outcome outcome, ProjectBaselineReceipt current, List<ExecutionDiagnostic> diagnostics) implements VersionRecoveryResult {
        public Recovered {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(current, "current");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Recovery found something it could not prove and changed nothing further; the evidence is kept. */
    record Failure(VersionRecoveryHandle handle, List<ExecutionDiagnostic> diagnostics) implements VersionRecoveryResult {
        public Failure {
            Objects.requireNonNull(handle, "handle");
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.stream().noneMatch(ExecutionDiagnostic::isError)) {
                throw new IllegalArgumentException("a failure needs an ERROR diagnostic");
            }
        }
    }
}
