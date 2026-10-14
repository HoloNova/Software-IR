package io.kcg.sir.application.internal.workspace;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.application.api.FileDelta;
import io.kcg.sir.application.api.ProjectBaselineReceipt;
import io.kcg.sir.application.api.ProjectToolchainRequest;
import io.kcg.sir.application.api.SourceDelta;
import io.kcg.sir.application.api.WorkspacePlan;
import io.kcg.sir.application.api.WorkspaceVersionRequest;
import io.kcg.sir.application.internal.ProjectSourceReader;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.Bundle;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineVerification;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.source.SourceSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
    private static final int MAX_OBSERVED_FILE_BYTES = 8 * 1024 * 1024;
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
            return new Rejected(WorkspacePlanning.lastErrorStage(diagnostics), diagnostics);
        }

        SourceSnapshot current = loaded.get().snapshot();
        var base = ProjectBaselineVerification.compile(current, request.outputRoot(), diagnostics);
        if (base == null) {
            return new Rejected(WorkspacePlanning.lastErrorStage(diagnostics), diagnostics);
        }

        var candidate = ProjectBaselineVerification.compile(request.candidate(), request.outputRoot(), diagnostics);
        if (candidate == null) {
            return new Rejected(WorkspacePlanning.lastErrorStage(diagnostics), diagnostics);
        }

        // The pure difference first; what the disk holds is judged against it below.
        FileDelta delta = FileDelta.compare(base.compilation().generatedFiles(), candidate.compilation().generatedFiles(), null);
        for (FileDelta.Entry entry : delta.entries()) {
            String problem = judge(entry, WorkspacePlanning.observe(output, entry.path()));
            if (problem != null) {
                diagnostics.add(ExecutionDiagnostic.error(DRIFT, ExecutionStage.PREFLIGHT, entry.path() + ": " + problem + REGENERATE));
            }
        }

        String shape = WorkspacePlanning.shapeChange(base.compilation().generatedFiles(), candidate.compilation().generatedFiles());
        if (shape != null) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-SHAPE-001", ExecutionStage.PREFLIGHT, shape));
        }

        if (diagnostics.stream().anyMatch(ExecutionDiagnostic::isError)) {
            return new Rejected(ExecutionStage.PREFLIGHT, diagnostics);
        }

        if (base.bundle().baselineId().equals(candidate.bundle().baselineId())) {
            return new Unchanged(WorkspacePlanning.receipt(base.bundle()), diagnostics);
        }

        List<WorkspacePlan.FileOp> files = WorkspacePlanning.operations(base, candidate, delta);
        if (files.size() > WorkspacePlan.MAX_FILE_OPS) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-LIMIT-001", ExecutionStage.PREFLIGHT, files.size() + " file operations exceed the limit of " + WorkspacePlan.MAX_FILE_OPS));
            return new Rejected(ExecutionStage.PREFLIGHT, diagnostics);
        }

        SourceDelta sources = SourceDelta.between(base.bundle().sources().manifest(), candidate.bundle().sources().manifest());
        var plan = new WorkspacePlan(WorkspacePlanning.receipt(base.bundle()), WorkspacePlanning.receipt(candidate.bundle()), sources, files, (int)delta.count(FileDelta.Action.UNCHANGED));
        return new Ready(plan, base, candidate, current, diagnostics.stream().filter(d -> !d.isError()).toList());
    }

    /** The generated-file operations of a delta, with the byte counts and digests of both sides. */
    public static List<WorkspacePlan.FileOp> operations(ProjectBaselineVerification.Verified base, ProjectBaselineVerification.Verified candidate, FileDelta delta) {
        Map<String, GeneratedFile> before = byPath(base.compilation().generatedFiles());
        Map<String, GeneratedFile> after = byPath(candidate.compilation().generatedFiles());
        List<WorkspacePlan.FileOp> operations = new ArrayList<>();
        for (FileDelta.Entry entry : delta.entries()) {
            if (entry.action() == FileDelta.Action.UNCHANGED) {
                continue;
            }

            GeneratedFile was = before.get(entry.path());
            GeneratedFile now = after.get(entry.path());
            operations.add(new WorkspacePlan.FileOp(entry.path(), WorkspacePlan.Action.valueOf(entry.action().name()), entry.owner(),
                was == null ? 0 : bytes(was), entry.beforeSha256(), now == null ? 0 : bytes(now), entry.afterSha256()));
        }

        return operations;
    }

    public static ProjectBaselineReceipt receipt(Bundle bundle) {
        var d = bundle.descriptor();
        return new ProjectBaselineReceipt(bundle.baselineId(), d.outputRoot(), d.entry(), d.sourceSetSha(), d.graphDigest(), d.manifestDigest());
    }

    private static long bytes(GeneratedFile file) {
        return file.content().getBytes(StandardCharsets.UTF_8).length;
    }

    private static Map<String, GeneratedFile> byPath(List<GeneratedFile> files) {
        Map<String, GeneratedFile> map = new TreeMap<>();
        files.forEach(f -> map.put(f.relativePath(), f));
        return map;
    }

    /** A path that is a file in one version and a directory in the other cannot be replaced in place. */
    public static String shapeChange(List<GeneratedFile> before, List<GeneratedFile> after) {
        TreeMap<String, Boolean> paths = new TreeMap<>();
        before.forEach(f -> paths.put(f.relativePath(), true));
        after.forEach(f -> paths.put(f.relativePath(), true));
        String previous = null;
        for (String path : paths.keySet()) {
            if (previous != null && path.startsWith(previous + "/")) {
                return previous + " is a file in one version and a directory (" + path + ") in the other";
            }

            previous = path;
        }

        return null;
    }

    /** What the disk holds at a path, as the plan needs to see it. */
    public static FileDelta.Observation observe(SecureFileAccess access, String path) {
        try {
            if (!access.exists(path)) {
                return new FileDelta.Observation.Absent();
            }

            return new FileDelta.Observation.Present(Sha256.hexDigest(access.read(path, MAX_OBSERVED_FILE_BYTES)));
        } catch (IOException | RuntimeException e) {
            return new FileDelta.Observation.Unreadable(String.valueOf(e.getMessage()));
        }
    }

    public static ExecutionStage lastErrorStage(List<ExecutionDiagnostic> diagnostics) {
        ExecutionStage last = ExecutionStage.PREFLIGHT;
        for (ExecutionDiagnostic d : diagnostics) {
            if (d.isError()) {
                last = d.stage();
            }
        }

        return last;
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
