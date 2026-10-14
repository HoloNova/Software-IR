package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.ProjectSourceReader;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.Problem;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.application.internal.workspace.WorkspacePlanning;
import io.kcg.sir.application.internal.workspace.WorkspaceWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Plans and applies a change to a project whose current version is the SIR on disk and whose history is Git.
 *
 * <p>Why it exists: the earlier version change kept its own record of every version, a journal and a recovery
 * procedure, so that a half-finished change could be undone. With the sources on disk and the history in Git,
 * none of that is needed (ADR-027). Planning compiles the SIR on disk and the candidate and compares the two
 * complete file sets, exactly as before, and also requires the generated project to be the compiled SIR.
 * Applying saves the previous version in Git, writes the new one, and saves that too.
 *
 * <p>What the caller can rely on, and what it cannot:
 * <ul>
 * <li>Git is required. If it cannot be run, or the repository cannot be used, nothing is changed.</li>
 * <li>A change never overwrites a file that is neither the old nor the new version of that file.</li>
 * <li>If a run stops half way it says so ({@link WorkspaceApplyResult.RerunRequired}) and does not repair
 * itself. The same apply, run again, finishes the job; or Git returns to the saved previous version.</li>
 * <li>There is no lock between processes. Two applies on one project at the same time are the caller's
 * to prevent; Git's own index lock only protects the snapshot steps.</li>
 * </ul>
 */
public final class WorkspaceVersionApplication {
    private final VersionControl versionControl;
    private final Consumer<String> checkpoint;

    public WorkspaceVersionApplication(VersionControl versionControl) {
        this(versionControl, ignored -> { });
    }

    /** Tests stop a run at a named point by throwing from {@code checkpoint}. */
    WorkspaceVersionApplication(VersionControl versionControl, Consumer<String> checkpoint) {
        this.versionControl = Objects.requireNonNull(versionControl, "versionControl");
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
    }

    public WorkspacePlanningResult plan(WorkspaceVersionRequest request) {
        Objects.requireNonNull(request, "request");
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        try (var output = new SecureFileAccess(request.outputRoot())) {
            return switch (WorkspacePlanning.plan(request, output, diagnostics)) {
                case WorkspacePlanning.Ready ready -> new WorkspacePlanningResult.Planned(ready.plan(), ready.diagnostics());
                case WorkspacePlanning.Unchanged same -> new WorkspacePlanningResult.NoChanges(same.baseline(), same.diagnostics());
                case WorkspacePlanning.Rejected rejected -> new WorkspacePlanningResult.Failure(rejected.stage(), rejected.diagnostics());
            };
        } catch (Problem e) {
            return planningFailure(diagnostics, ExecutionStage.READ, "the generated project cannot be read: " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            return planningFailure(diagnostics, ExecutionStage.READ, "workspace planning refused without disk mutation: " + e.getMessage());
        }
    }

    /**
     * Applies the plan the caller has already seen. {@code expected} is only what the caller expects: the
     * application plans again and carries on only if the result is exactly equal.
     */
    public WorkspaceApplyResult apply(WorkspaceVersionRequest request, WorkspacePlanningResult expected) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(expected, "expected");
        if (!(expected instanceof WorkspacePlanningResult.Planned) && !(expected instanceof WorkspacePlanningResult.NoChanges)) {
            throw new IllegalArgumentException("expected must be a plan or no-changes");
        }

        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        Optional<String> beforeCommit = Optional.empty();
        boolean[] started = {false};
        try (var output = new SecureFileAccess(request.outputRoot()); var sources = new SecureFileAccess(request.sourceRoot())) {
            WorkspacePlanning.Outcome outcome = WorkspacePlanning.plan(request, output, diagnostics);
            if (outcome instanceof WorkspacePlanning.Rejected rejected) {
                return new WorkspaceApplyResult.Failure(rejected.stage(), rejected.diagnostics());
            }

            if (outcome instanceof WorkspacePlanning.Unchanged same) {
                if (expected instanceof WorkspacePlanningResult.NoChanges none && none.baseline().equals(same.baseline())) {
                    // A run that stopped after its last file was written finds nothing left to do, but its staging area is still there.
                    WorkspaceWriter.clearStaleStaging(output);
                    return new WorkspaceApplyResult.NoChanges(same.baseline(), same.diagnostics());
                }

                return failure(diagnostics, "VERIFY-001", "no change was expected to be needed, but the candidate differs, or the reverse");
            }

            var ready = (WorkspacePlanning.Ready)outcome;
            if (!(expected instanceof WorkspacePlanningResult.Planned planned) || !planned.plan().equals(ready.plan())) {
                return failure(diagnostics, "VERIFY-001", "the expected plan differs from a complete independent reconstruction");
            }

            ExecutionDiagnostic unusable = this.checkRepository(request);
            if (unusable != null) {
                diagnostics.add(unusable);
                return new WorkspaceApplyResult.Failure(ExecutionStage.PREFLIGHT, diagnostics);
            }

            List<String> paths = snapshotPaths(request, ready);
            String digest = ready.plan().sha256Hex().substring(0, 12);
            var before = this.versionControl.snapshot(request.repositoryRoot(), paths, "kcg: save the current version before applying plan " + digest);
            if (before instanceof VersionControl.Failed failed) {
                return failure(diagnostics, "GIT-002", "the current version could not be saved in Git, so nothing was changed: " + failed.reason());
            }

            beforeCommit = ((VersionControl.Taken)before).commit();
            // The first thing the writer does to the project is create its staging directory; before that nothing was changed.
            WorkspaceWriter.apply(ready, request.sourceRoot(), request.outputRoot(), output, sources, name -> {
                if (name.equals("staging-created")) {
                    started[0] = true;
                }

                this.checkpoint.accept(name);
            });

            var after = this.versionControl.snapshot(request.repositoryRoot(), paths, "kcg: apply plan " + digest + " (" + ready.plan().files().size() + " generated files, "
                + ready.plan().sources().entries().stream().filter(e -> e.action() != SourceDelta.Action.UNCHANGED).count() + " sources)");
            Optional<String> afterCommit = Optional.empty();
            List<ExecutionDiagnostic> notes = new ArrayList<>(ready.diagnostics());
            if (after instanceof VersionControl.Taken taken) {
                afterCommit = taken.commit();
            } else {
                notes.add(ExecutionDiagnostic.warning("SIR-APP-WORKSPACE-GIT-003", ExecutionStage.WRITE,
                    "the new version is written in full, but saving it in Git failed: " + ((VersionControl.Failed)after).reason()));
            }

            return new WorkspaceApplyResult.Applied(ready.plan().base(), ready.plan().candidate(), beforeCommit, afterCommit, notes);
        } catch (IOException | RuntimeException e) {
            if (!started[0]) {
                return failure(diagnostics, "APPLY-001", "the change was refused before any change: " + e.getMessage());
            }

            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-WORKSPACE-APPLY-001", ExecutionStage.WRITE,
                "the change stopped half way and nothing was repaired: " + e.getMessage() + ". Run the same apply again to finish it"
                    + beforeCommit.map(c -> ", or return to the previous version saved in Git as commit " + c).orElse("")));
            return new WorkspaceApplyResult.RerunRequired(beforeCommit, diagnostics);
        }
    }

    /** Plans a change whose candidate is a directory of SIR sources. */
    public WorkspacePlanningResult plan(WorkspaceDirectoryRequest request) {
        Objects.requireNonNull(request, "request");
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        WorkspaceVersionRequest resolved = resolve(request, diagnostics);
        if (resolved == null) {
            return new WorkspacePlanningResult.Failure(WorkspacePlanning.lastErrorStage(diagnostics), diagnostics);
        }

        return this.plan(resolved);
    }

    /**
     * Applies a change whose candidate is a directory of SIR sources, but only if the plan it produces now is
     * the one with {@code expectedPlanId} ({@link WorkspacePlan#sha256Hex()}), which is what the caller saw
     * when it planned. Nothing the caller names is trusted beyond that: the plan is rebuilt from the disk.
     */
    public WorkspaceApplyResult apply(WorkspaceDirectoryRequest request, String expectedPlanId) {
        Objects.requireNonNull(request, "request");
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        if (expectedPlanId == null || !expectedPlanId.matches("[0-9a-f]{64}")) {
            return failure(diagnostics, "REQUEST-001", "the expected plan id must be 64 lower-case hex characters");
        }

        WorkspaceVersionRequest resolved = resolve(request, diagnostics);
        if (resolved == null) {
            return new WorkspaceApplyResult.Failure(WorkspacePlanning.lastErrorStage(diagnostics), diagnostics);
        }

        return switch (this.plan(resolved)) {
            case WorkspacePlanningResult.Failure failed -> new WorkspaceApplyResult.Failure(failed.failedStage(), failed.diagnostics());
            case WorkspacePlanningResult.NoChanges none -> this.apply(resolved, none);
            case WorkspacePlanningResult.Planned planned -> planned.plan().sha256Hex().equals(expectedPlanId)
                ? this.apply(resolved, planned)
                : failure(diagnostics, "VERIFY-001", "the plan now is " + planned.plan().sha256Hex() + ", not the expected " + expectedPlanId + "; plan again");
        };
    }

    /** Reads the candidate directory; null (with the reason in {@code diagnostics}) if it cannot be used. */
    private static WorkspaceVersionRequest resolve(WorkspaceDirectoryRequest request, List<ExecutionDiagnostic> diagnostics) {
        try {
            var loaded = ProjectSourceReader.read(new ToolchainRequest(request.candidateRoot(), request.entry(), request.outputRoot()), diagnostics);
            if (loaded.isEmpty()) {
                return null;
            }

            return new WorkspaceVersionRequest(request.repositoryRoot(), request.sourceRoot(), request.outputRoot(), loaded.get().snapshot());
        } catch (IllegalArgumentException e) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-WORKSPACE-REQUEST-001", ExecutionStage.PREFLIGHT, e.getMessage()));
            return null;
        }
    }

    /** Git must be usable; a directory that is not a repository yet is made one. */
    private ExecutionDiagnostic checkRepository(WorkspaceVersionRequest request) throws IOException {
        VersionControl.Status status = this.versionControl.inspect(request.repositoryRoot());
        switch (status.state()) {
            case UNAVAILABLE:
                return ExecutionDiagnostic.error("SIR-APP-WORKSPACE-GIT-001", ExecutionStage.PREFLIGHT,
                    "git is required to apply a change and cannot be used, so nothing was changed: " + status.detail());
            case NOT_TOP_LEVEL:
                return ExecutionDiagnostic.error("SIR-APP-WORKSPACE-GIT-001", ExecutionStage.PREFLIGHT,
                    "the repository root must be the top level of a Git working tree, so nothing was changed: " + status.detail());
            case NO_REPOSITORY:
                Optional<String> failed = this.versionControl.initialize(request.repositoryRoot());
                if (failed.isPresent()) {
                    return ExecutionDiagnostic.error("SIR-APP-WORKSPACE-GIT-001", ExecutionStage.PREFLIGHT, "no Git repository could be created, so nothing was changed: " + failed.get());
                }

                break;
            default:
                break;
        }

        if (!Files.getFileStore(request.sourceRoot()).equals(Files.getFileStore(request.outputRoot()))) {
            return ExecutionDiagnostic.error("SIR-APP-WORKSPACE-ROOTS-001", ExecutionStage.PREFLIGHT, "the source root and the output root are on different file systems, so files cannot be moved into place atomically");
        }

        return null;
    }

    /** Every source file and generated file of both versions, relative to the repository root. */
    private static List<String> snapshotPaths(WorkspaceVersionRequest request, WorkspacePlanning.Ready ready) {
        String sourcePrefix = request.repositoryRoot().relativize(request.sourceRoot()).toString().replace('\\', '/') + "/";
        String outputPrefix = request.repositoryRoot().relativize(request.outputRoot()).toString().replace('\\', '/') + "/";
        TreeSet<String> paths = new TreeSet<>();
        for (var bundle : List.of(ready.base().bundle(), ready.candidate().bundle())) {
            bundle.sources().manifest().files().forEach(f -> paths.add(sourcePrefix + f.sourceId().value()));
            bundle.descriptor().manifest().forEach(f -> paths.add(outputPrefix + f.relativePath()));
        }

        return List.copyOf(paths);
    }

    private static WorkspaceApplyResult.Failure failure(List<ExecutionDiagnostic> diagnostics, String suffix, String message) {
        diagnostics.add(ExecutionDiagnostic.error("SIR-APP-WORKSPACE-" + suffix, ExecutionStage.PREFLIGHT, message));
        return new WorkspaceApplyResult.Failure(ExecutionStage.PREFLIGHT, diagnostics);
    }

    private static WorkspacePlanningResult.Failure planningFailure(List<ExecutionDiagnostic> diagnostics, ExecutionStage stage, String message) {
        diagnostics.add(ExecutionDiagnostic.error("SIR-APP-WORKSPACE-READ-001", stage, message));
        return new WorkspacePlanningResult.Failure(stage, diagnostics);
    }
}
