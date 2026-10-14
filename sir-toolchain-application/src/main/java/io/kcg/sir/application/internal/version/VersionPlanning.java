package io.kcg.sir.application.internal.version;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.application.api.FileDelta;
import io.kcg.sir.application.api.ProjectBaselineReceipt;
import io.kcg.sir.application.api.VersionPlan;
import io.kcg.sir.application.api.VersionPlanningRequest;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.Bundle;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineVerification;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.io.IOException;
import java.util.List;
import java.util.TreeMap;

/**
 * The planning steps that need the state lock, shared by the read-only planner and by the apply
 * entry, which must plan and run the transaction under one and the same lock.
 */
public final class VersionPlanning {
    private static final int MAX_OUTPUT_FILE_BYTES = 8 * 1024 * 1024;

    private VersionPlanning() {
    }

    public sealed interface Outcome permits Ready, Unchanged, Rejected {
        List<ExecutionDiagnostic> diagnostics();
    }

    /** A plan, with the two verified versions it was made from. */
    public record Ready(VersionPlan plan, ProjectBaselineVerification.Verified base, ProjectBaselineVerification.Verified candidate, List<ExecutionDiagnostic> diagnostics) implements Outcome {
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

    public static Outcome plan(VersionPlanningRequest request, SecureFileAccess state, SecureFileAccess output, List<ExecutionDiagnostic> diagnostics) throws IOException {
        var base = ProjectBaselineVerification.read(state, request.expectedBaselineId(), request.outputRoot(), diagnostics);
        if (base == null) {
            return new Rejected(lastErrorStage(diagnostics), diagnostics);
        }

        var candidate = ProjectBaselineVerification.compile(request.candidate(), request.outputRoot(), diagnostics);
        if (candidate == null) {
            return new Rejected(lastErrorStage(diagnostics), diagnostics);
        }

        Bundle b = base.bundle();
        Bundle c = candidate.bundle();
        if (b.baselineId().equals(c.baselineId())) {
            return new Unchanged(VersionPlans.receipt(b), diagnostics);
        }

        FileDelta delta = FileDelta.compare(base.compilation().generatedFiles(), candidate.compilation().generatedFiles(), path -> observe(output, path));
        for (FileDelta.Entry conflict : delta.conflicts()) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-CONFLICT-001", ExecutionStage.PREFLIGHT, conflict.conflict().orElseThrow() + ": " + conflict.path()));
        }

        String shape = shapeChange(base.compilation().generatedFiles(), candidate.compilation().generatedFiles());
        if (shape != null) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-SHAPE-001", ExecutionStage.PREFLIGHT, shape));
        }

        if (diagnostics.stream().anyMatch(ExecutionDiagnostic::isError)) {
            return new Rejected(ExecutionStage.PREFLIGHT, diagnostics);
        }

        VersionPlan plan = VersionPlans.compare(request.stateRoot(), base, candidate, delta);
        if (plan.files().size() > VersionPlan.MAX_FILE_OPS) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-LIMIT-001", ExecutionStage.PREFLIGHT, plan.files().size() + " file operations exceed the limit of " + VersionPlan.MAX_FILE_OPS));
            return new Rejected(ExecutionStage.PREFLIGHT, diagnostics);
        }

        ProjectBaselineVerification.revalidate(state, b);
        return new Ready(plan, base, candidate, diagnostics.stream().filter(d -> !d.isError()).toList());
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

    public static FileDelta.Observation observe(SecureFileAccess output, String path) {
        try {
            if (!output.exists(path)) {
                return new FileDelta.Observation.Absent();
            }

            return new FileDelta.Observation.Present(Sha256.hexDigest(output.read(path, MAX_OUTPUT_FILE_BYTES)));
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
}
