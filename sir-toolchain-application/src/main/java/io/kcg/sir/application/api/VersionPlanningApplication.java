package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.Problem;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.application.internal.state.StateRootLock;
import io.kcg.sir.application.internal.state.StateRootPathGuard;
import io.kcg.sir.application.internal.version.VersionPlanning;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Plans moving a stored project to a new set of sources, without changing anything.
 *
 * <p>Under the state lock it re-reads the stored version and replays its sources, checks the generated
 * files on disk are exactly that version's, compiles the candidate from scratch and compares the two
 * complete file sets. Nothing the caller hands in is trusted: {@link #verify} recomputes the whole plan
 * and requires it to be equal. Unlike the older update plan, the candidate may add, remove or move
 * source files and the plan may create, update and delete generated files together.
 */
public final class VersionPlanningApplication {
    public VersionPlanningResult plan(VersionPlanningRequest request) {
        Objects.requireNonNull(request, "request");
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        try {
            ExecutionDiagnostic invalid = checkRoots(request);
            if (invalid != null) {
                diagnostics.add(invalid);
                return new VersionPlanningResult.Failure(ExecutionStage.PREFLIGHT, diagnostics);
            }

            var paths = StateRootPathGuard.validate(request.stateRoot(), request.outputRoot(), true);
            for (var d : paths.errors()) {
                diagnostics.add(new ExecutionDiagnostic(d.code(), ExecutionStage.PREFLIGHT, d.severity(), d.message(), Optional.empty(), Optional.empty(), d.relativePath()));
            }

            if (diagnostics.stream().anyMatch(ExecutionDiagnostic::isError)) {
                return new VersionPlanningResult.Failure(ExecutionStage.PREFLIGHT, diagnostics);
            }

            var lock = new StateRootLock(request.stateRoot()).tryAcquireExisting();
            if (!(lock instanceof StateRootLock.HeldLock held)) {
                return fail(diagnostics, "LOCK-001", ExecutionStage.PREFLIGHT, ((StateRootLock.LockFailure)lock).message());
            }

            try (held; var state = new SecureFileAccess(request.stateRoot()); var output = new SecureFileAccess(request.outputRoot())) {
                return switch (VersionPlanning.plan(request, state, output, diagnostics)) {
                    case VersionPlanning.Ready ready -> new VersionPlanningResult.Planned(ready.plan(), ready.diagnostics());
                    case VersionPlanning.Unchanged same -> new VersionPlanningResult.NoChanges(same.baseline(), same.diagnostics());
                    case VersionPlanning.Rejected rejected -> new VersionPlanningResult.Failure(rejected.stage(), rejected.diagnostics());
                };
            }
        } catch (Problem e) {
            return fail(diagnostics, "READ-001", ExecutionStage.READ, e.getMessage());
        } catch (IOException | RuntimeException e) {
            return fail(diagnostics, "READ-002", ExecutionStage.READ, "version planning refused without disk mutation: " + e.getMessage());
        }
    }

    /** Recomputes the plan from scratch and requires it to equal {@code expected}. */
    public VersionPlanningResult verify(VersionPlanningRequest request, VersionPlanningResult.Planned expected) {
        Objects.requireNonNull(expected, "expected");
        VersionPlanningResult actual = this.plan(request);
        if (actual instanceof VersionPlanningResult.Planned planned && planned.plan().equals(expected.plan())) {
            return actual;
        }

        if (actual instanceof VersionPlanningResult.Failure) {
            return actual;
        }

        return fail(new ArrayList<>(), "VERIFY-001", ExecutionStage.PREFLIGHT, "the expected plan differs from a complete independent reconstruction");
    }

    static ExecutionDiagnostic checkRoots(VersionPlanningRequest request) {
        if (!request.stateRoot().isAbsolute() || !request.stateRoot().equals(request.stateRoot().normalize())
            || !request.outputRoot().isAbsolute() || !request.outputRoot().equals(request.outputRoot().normalize())) {
            return ExecutionDiagnostic.error("SIR-APP-VERSION-REQUEST-001", ExecutionStage.PREFLIGHT, "state and output roots must be absolute normalized paths");
        }

        return null;
    }

    private static VersionPlanningResult.Failure fail(List<ExecutionDiagnostic> diagnostics, String suffix, ExecutionStage stage, String message) {
        diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-" + suffix, stage, message));
        return new VersionPlanningResult.Failure(stage, diagnostics);
    }
}
