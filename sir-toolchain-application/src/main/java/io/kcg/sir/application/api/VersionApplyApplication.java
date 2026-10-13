package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.Problem;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineStore;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.application.internal.state.StateRootLock;
import io.kcg.sir.application.internal.state.StateRootPathGuard;
import io.kcg.sir.application.internal.version.VersionBinding;
import io.kcg.sir.application.internal.version.VersionPlanning;
import io.kcg.sir.application.internal.version.VersionPlans;
import io.kcg.sir.application.internal.version.VersionReceipt;
import io.kcg.sir.application.internal.version.VersionTransaction;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Saves a new version of a stored project and updates the generated project to match, as one
 * operation, and recovers it when it was stopped half way.
 *
 * <p>The plan is rebuilt under the state lock and must equal the one the caller expects; the same
 * lock is held while the transaction runs, so nothing can change between the check and the change.
 * Applying never starts a recovery by itself: a stopped transaction is reported with a handle and
 * recovered only by an explicit {@link #recover} call.
 */
public final class VersionApplyApplication {
    private final Consumer<String> checkpoint;

    public VersionApplyApplication() {
        this(ignored -> { });
    }

    VersionApplyApplication(Consumer<String> checkpoint) {
        this.checkpoint = Objects.requireNonNull(checkpoint);
    }

    public VersionApplyResult apply(VersionApplyRequest request) {
        Objects.requireNonNull(request, "request");
        VersionPlanningRequest planning = request.planning();
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        VersionTransaction transaction = null;
        String baseId = null;
        String candidateId = null;
        try {
            ExecutionDiagnostic invalid = VersionPlanningApplication.checkRoots(planning);
            if (invalid != null) {
                diagnostics.add(invalid);
                return new VersionApplyResult.Failure(ExecutionStage.PREFLIGHT, diagnostics);
            }

            var paths = StateRootPathGuard.validate(planning.stateRoot(), planning.outputRoot(), true);
            for (var d : paths.errors()) {
                diagnostics.add(new ExecutionDiagnostic(d.code(), ExecutionStage.PREFLIGHT, d.severity(), d.message(), Optional.empty(), Optional.empty(), d.relativePath()));
            }

            if (diagnostics.stream().anyMatch(ExecutionDiagnostic::isError)) {
                return new VersionApplyResult.Failure(ExecutionStage.PREFLIGHT, diagnostics);
            }

            var lock = new StateRootLock(planning.stateRoot()).tryAcquireExisting();
            if (!(lock instanceof StateRootLock.HeldLock held)) {
                return failure(diagnostics, "LOCK-001", ExecutionStage.PREFLIGHT, ((StateRootLock.LockFailure)lock).message());
            }

            try (held; var state = new SecureFileAccess(planning.stateRoot()); var output = new SecureFileAccess(planning.outputRoot())) {
                VersionPlanning.Outcome outcome = VersionPlanning.plan(planning, state, output, diagnostics);
                if (outcome instanceof VersionPlanning.Rejected rejected) {
                    return new VersionApplyResult.Failure(rejected.stage(), rejected.diagnostics());
                }

                if (outcome instanceof VersionPlanning.Unchanged same) {
                    if (request.expected() instanceof VersionPlanningResult.NoChanges expected && expected.baseline().equals(same.baseline())) {
                        return new VersionApplyResult.NoChanges(same.baseline(), same.diagnostics());
                    }

                    return failure(diagnostics, "VERIFY-001", ExecutionStage.PREFLIGHT, "no change was expected to be needed, but the candidate differs, or the reverse");
                }

                var ready = (VersionPlanning.Ready)outcome;
                if (!(request.expected() instanceof VersionPlanningResult.Planned expected) || !expected.plan().equals(ready.plan())) {
                    return failure(diagnostics, "VERIFY-001", ExecutionStage.PREFLIGHT, "the expected plan differs from a complete independent reconstruction under the lock");
                }

                baseId = ready.base().bundle().baselineId();
                candidateId = ready.candidate().bundle().baselineId();
                transaction = new VersionTransaction(state, output, request.transactionId(), this.checkpoint);
                transaction.apply(ready.base(), ready.candidate(), ready.plan());
                return new VersionApplyResult.Applied(VersionPlans.receipt(ready.base().bundle()), VersionPlans.receipt(ready.candidate().bundle()), diagnostics);
            }
        } catch (IOException | RuntimeException e) {
            boolean changed = transaction != null && transaction.started();
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-APPLY-001", changed ? ExecutionStage.WRITE : ExecutionStage.PREFLIGHT,
                (changed ? "the version change stopped half way; explicit recovery is required: " : "the version change was refused before any change: ") + e.getMessage()));
            if (!changed) {
                return new VersionApplyResult.Failure(ExecutionStage.PREFLIGHT, diagnostics);
            }

            var handle = new VersionRecoveryHandle(planning.stateRoot(), planning.outputRoot(), request.transactionId(), baseId, candidateId, transaction.binding().map(VersionBinding::digest));
            return new VersionApplyResult.RecoveryRequired(handle, publication(handle), diagnostics);
        }
    }

    public VersionRecoveryResult recover(VersionRecoveryHandle handle) {
        Objects.requireNonNull(handle, "handle");
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        try {
            var paths = StateRootPathGuard.validate(handle.stateRoot(), handle.outputRoot(), true);
            for (var d : paths.errors()) {
                diagnostics.add(new ExecutionDiagnostic(d.code(), ExecutionStage.PREFLIGHT, d.severity(), d.message(), Optional.empty(), Optional.empty(), d.relativePath()));
            }

            if (diagnostics.stream().anyMatch(ExecutionDiagnostic::isError)) {
                return new VersionRecoveryResult.Failure(handle, diagnostics);
            }

            var lock = new StateRootLock(handle.stateRoot()).tryAcquireExisting();
            if (!(lock instanceof StateRootLock.HeldLock held)) {
                throw new IOException("the existing state lock is unavailable: " + ((StateRootLock.LockFailure)lock).message());
            }

            try (held; var state = new SecureFileAccess(handle.stateRoot()); var output = new SecureFileAccess(handle.outputRoot())) {
                var transaction = new VersionTransaction(state, output, handle.transactionId(), this.checkpoint);
                boolean active = state.exists(VersionTransaction.ACTIVE + "/" + handle.transactionId());
                VersionBinding binding = active ? VersionTransaction.loadBinding(state, handle.transactionId()) : VersionReceipt.read(state, handle.transactionId()).binding();
                if (!binding.publication().b0().equals(handle.baselineId()) || !binding.publication().b1().equals(handle.candidateId())) {
                    throw new IOException("the recovery handle names other versions than the transaction");
                }

                boolean published = transaction.recover(handle.bindingDigest());
                var current = new ProjectBaselineStore(state).load(published ? handle.candidateId() : handle.baselineId());
                var outcome = active
                    ? (published ? VersionRecoveryResult.Outcome.COMMITTED_AND_CLEANED : VersionRecoveryResult.Outcome.ROLLED_BACK)
                    : VersionRecoveryResult.Outcome.ALREADY_CLEAN;
                return new VersionRecoveryResult.Recovered(outcome, VersionPlans.receipt(current), diagnostics);
            }
        } catch (Problem | RuntimeException e) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-RECOVER-001", ExecutionStage.ROLLBACK, "recovery kept the unresolved evidence: " + e.getMessage()));
            return new VersionRecoveryResult.Failure(handle, diagnostics);
        } catch (IOException e) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-RECOVER-001", ExecutionStage.ROLLBACK, "recovery kept the unresolved evidence: " + e.getMessage()));
            return new VersionRecoveryResult.Failure(handle, diagnostics);
        }
    }

    private static VersionApplyResult.Publication publication(VersionRecoveryHandle handle) {
        try (var state = new SecureFileAccess(handle.stateRoot())) {
            var store = new ProjectBaselineStore(state);
            Optional<String> current = store.current();
            if (current.isEmpty()) {
                return VersionApplyResult.Publication.UNKNOWN;
            }

            store.verifyPointerIdentity(current.get(), false);
            if (current.get().equals(handle.candidateId())) {
                return VersionApplyResult.Publication.PUBLISHED;
            }

            if (current.get().equals(handle.baselineId())) {
                return VersionApplyResult.Publication.NOT_PUBLISHED;
            }
        } catch (IOException | RuntimeException ignored) {
            // an unreadable pointer is reported as unknown, never guessed
        }

        return VersionApplyResult.Publication.UNKNOWN;
    }

    private static VersionApplyResult.Failure failure(List<ExecutionDiagnostic> diagnostics, String suffix, ExecutionStage stage, String message) {
        diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-" + suffix, stage, message));
        return new VersionApplyResult.Failure(stage, diagnostics);
    }
}
