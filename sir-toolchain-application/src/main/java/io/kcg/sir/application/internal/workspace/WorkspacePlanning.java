package io.kcg.sir.application.internal.workspace;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.application.api.FileDelta;
import io.kcg.sir.application.api.ProjectBaselineReceipt;
import io.kcg.sir.application.api.ProjectToolchainRequest;
import io.kcg.sir.application.api.SourceDelta;
import io.kcg.sir.application.api.VersionPlan;
import io.kcg.sir.application.api.WorkspacePlan;
import io.kcg.sir.application.api.WorkspaceVersionRequest;
import io.kcg.sir.application.internal.ProjectSourceReader;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineVerification;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.application.internal.version.VersionPlanning;
import io.kcg.sir.application.internal.version.VersionPlans;
import io.kcg.sir.source.SourceSnapshot;
import java.io.IOException;
import java.util.List;

/**
 * Plans a change against the SIR on disk. There is no stored record of the current version: the
 * current version is whatever the SIR in the source directory compiles to, and the generated project
 * must be exactly that.
 *
 * <p>Why the whole project is checked, not only the files the change touches: with no stored record, the
 * compiled SIR is the only statement of what the generated project should contain. A file that differs
 * from it, whether edited by hand, left over from an older toolchain, or missing, means the project is not
 * the version the SIR describes, and a change planned against that would be planned against the wrong
 * thing. The answer is the same in every such case: regenerate, or restore from Git, and plan again.
 *
 * <p>The one tolerance is for a file the change itself would write. If it already holds the new bytes
 * (or is already gone, for a deletion), an earlier apply was interrupted after writing it, and running the
 * apply again finishes the job instead of reporting a conflict.
 */
public final class WorkspacePlanning {
    private static final String DRIFT = "SIR-APP-WORKSPACE-DRIFT-001";
    private static final String REGENERATE = "; the generated project does not match the SIR on disk, so regenerate it from the current SIR (or restore it from Git) and plan again";

    private WorkspacePlanning() {
    }

    public sealed interface Outcome permits Ready, Unchanged, Rejected {
        List<ExecutionDiagnostic> diagnostics();
    }

    /** A plan, with the compiled current and candidate versions it was made from. */
    public record Ready(WorkspacePlan plan, ProjectBaselineVerification.Verified base, ProjectBaselineVerification.Verified candidate, SourceSnapshot current,
            List<ExecutionDiagnostic> diagnostics) implements Outcome {
        public Ready {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record Unchanged(ProjectBaselineReceipt baseline, List<ExecutionDiagnostic> diagnostics) implements Outcome {
        public Unchanged {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record Rejected(ExecutionStage stage, List<ExecutionDiagnostic> diagnostics) implements Outcome {
        public Rejected {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public static Outcome plan(WorkspaceVersionRequest request, SecureFileAccess output, List<ExecutionDiagnostic> diagnostics) throws IOException {
        var loaded = ProjectSourceReader.read(new ProjectToolchainRequest(request.sourceRoot(), request.candidate().entry(), request.outputRoot()), diagnostics);
        if (loaded.isEmpty()) {
            return new Rejected(VersionPlanning.lastErrorStage(diagnostics), diagnostics);
        }

        SourceSnapshot current = loaded.get().snapshot();
        var base = ProjectBaselineVerification.compile(current, request.outputRoot(), diagnostics);
        if (base == null) {
            return new Rejected(VersionPlanning.lastErrorStage(diagnostics), diagnostics);
        }

        var candidate = ProjectBaselineVerification.compile(request.candidate(), request.outputRoot(), diagnostics);
        if (candidate == null) {
            return new Rejected(VersionPlanning.lastErrorStage(diagnostics), diagnostics);
        }

        // The pure difference first; what the disk holds is judged against it below.
        FileDelta delta = FileDelta.compare(base.compilation().generatedFiles(), candidate.compilation().generatedFiles(), null);
        for (FileDelta.Entry entry : delta.entries()) {
            String problem = judge(entry, VersionPlanning.observe(output, entry.path()));
            if (problem != null) {
                diagnostics.add(ExecutionDiagnostic.error(DRIFT, ExecutionStage.PREFLIGHT, entry.path() + ": " + problem + REGENERATE));
            }
        }

        String shape = VersionPlanning.shapeChange(base.compilation().generatedFiles(), candidate.compilation().generatedFiles());
        if (shape != null) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-SHAPE-001", ExecutionStage.PREFLIGHT, shape));
        }

        if (diagnostics.stream().anyMatch(ExecutionDiagnostic::isError)) {
            return new Rejected(ExecutionStage.PREFLIGHT, diagnostics);
        }

        if (base.bundle().baselineId().equals(candidate.bundle().baselineId())) {
            return new Unchanged(VersionPlans.receipt(base.bundle()), diagnostics);
        }

        List<VersionPlan.FileOp> files = VersionPlans.operations(base, candidate, delta);
        if (files.size() > VersionPlan.MAX_FILE_OPS) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-LIMIT-001", ExecutionStage.PREFLIGHT, files.size() + " file operations exceed the limit of " + VersionPlan.MAX_FILE_OPS));
            return new Rejected(ExecutionStage.PREFLIGHT, diagnostics);
        }

        SourceDelta sources = SourceDelta.between(base.bundle().sources().manifest(), candidate.bundle().sources().manifest());
        var plan = new WorkspacePlan(VersionPlans.receipt(base.bundle()), VersionPlans.receipt(candidate.bundle()), sources, files, (int)delta.count(FileDelta.Action.UNCHANGED));
        return new Ready(plan, base, candidate, current, diagnostics.stream().filter(d -> !d.isError()).toList());
    }

    /**
     * Whether the disk is acceptable for this entry; the reason if it is not. A file the change will write
     * is acceptable if it still holds the current bytes or already holds the new ones.
     */
    static String judge(FileDelta.Entry entry, FileDelta.Observation seen) {
        if (seen instanceof FileDelta.Observation.Unreadable unreadable) {
            return "cannot be read (" + unreadable.reason() + ")";
        }

        boolean absent = seen instanceof FileDelta.Observation.Absent;
        String sha = seen instanceof FileDelta.Observation.Present present ? present.sha256Hex() : "";
        return switch (entry.action()) {
            case UNCHANGED -> absent ? "a generated file is missing" : sha.equals(entry.beforeSha256()) ? null : "a generated file was changed";
            case CREATE -> absent || sha.equals(entry.afterSha256()) ? null : "a file the new version creates already exists";
            case UPDATE -> sha.equals(entry.beforeSha256()) || sha.equals(entry.afterSha256()) ? null : absent ? "a generated file is missing" : "a generated file was changed";
            case DELETE -> absent || sha.equals(entry.beforeSha256()) ? null : "a generated file was changed";
        };
    }
}
