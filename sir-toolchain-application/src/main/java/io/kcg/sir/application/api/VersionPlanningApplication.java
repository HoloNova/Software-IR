package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.Bundle;
import io.kcg.sir.application.internal.version.VersionPlans;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.Problem;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineVerification;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.application.internal.state.StateRootLock;
import io.kcg.sir.application.internal.state.StateRootPathGuard;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

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
    private static final int MAX_OUTPUT_FILE_BYTES = 8 * 1024 * 1024;

    public VersionPlanningResult plan(VersionPlanningRequest request) {
        Objects.requireNonNull(request, "request");
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        try {
            if (!request.stateRoot().isAbsolute() || !request.stateRoot().equals(request.stateRoot().normalize())
                || !request.outputRoot().isAbsolute() || !request.outputRoot().equals(request.outputRoot().normalize())) {
                return fail(diagnostics, "REQUEST-001", ExecutionStage.PREFLIGHT, "state and output roots must be absolute normalized paths");
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
                return this.planLocked(request, state, output, diagnostics);
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

        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        return fail(diagnostics, "VERIFY-001", ExecutionStage.PREFLIGHT, "the expected plan differs from a complete independent reconstruction");
    }

    private VersionPlanningResult planLocked(VersionPlanningRequest request, SecureFileAccess state, SecureFileAccess output, List<ExecutionDiagnostic> diagnostics) throws IOException {
        var base = ProjectBaselineVerification.read(state, request.expectedBaselineId(), request.outputRoot(), diagnostics);
        if (base == null) {
            return new VersionPlanningResult.Failure(lastErrorStage(diagnostics), diagnostics);
        }

        var candidate = ProjectBaselineVerification.compile(request.candidate(), request.outputRoot(), diagnostics);
        if (candidate == null) {
            return new VersionPlanningResult.Failure(lastErrorStage(diagnostics), diagnostics);
        }

        Bundle b = base.bundle();
        Bundle c = candidate.bundle();
        if (b.baselineId().equals(c.baselineId())) {
            return new VersionPlanningResult.NoChanges(VersionPlans.receipt(b), diagnostics);
        }

        FileDelta delta = FileDelta.compare(base.compilation().generatedFiles(), candidate.compilation().generatedFiles(), path -> observe(output, path));
        for (FileDelta.Entry conflict : delta.conflicts()) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-CONFLICT-001", ExecutionStage.PREFLIGHT,
                conflict.conflict().orElseThrow() + ": " + conflict.path()));
        }

        String shape = shapeChange(base.compilation().generatedFiles(), candidate.compilation().generatedFiles());
        if (shape != null) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-SHAPE-001", ExecutionStage.PREFLIGHT, shape));
        }

        if (diagnostics.stream().anyMatch(ExecutionDiagnostic::isError)) {
            return new VersionPlanningResult.Failure(ExecutionStage.PREFLIGHT, diagnostics);
        }

        VersionPlan plan = VersionPlans.compare(request.stateRoot(), base, candidate, delta);
        if (plan.files().size() > VersionPlan.MAX_FILE_OPS) {
            return fail(diagnostics, "LIMIT-001", ExecutionStage.PREFLIGHT, plan.files().size() + " file operations exceed the limit of " + VersionPlan.MAX_FILE_OPS);
        }

        ProjectBaselineVerification.revalidate(state, b);
        return new VersionPlanningResult.Planned(plan, diagnostics.stream().filter(d -> !d.isError()).toList());
    }

    /** A path that is a file in one version and a directory in the other cannot be replaced in place. */
    private static String shapeChange(List<GeneratedFile> before, List<GeneratedFile> after) {
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

    private static FileDelta.Observation observe(SecureFileAccess output, String path) {
        try {
            if (!output.exists(path)) {
                return new FileDelta.Observation.Absent();
            }

            return new FileDelta.Observation.Present(Sha256.hexDigest(output.read(path, MAX_OUTPUT_FILE_BYTES)));
        } catch (IOException | RuntimeException e) {
            return new FileDelta.Observation.Unreadable(String.valueOf(e.getMessage()));
        }
    }

    private static ExecutionStage lastErrorStage(List<ExecutionDiagnostic> diagnostics) {
        ExecutionStage last = ExecutionStage.PREFLIGHT;
        for (ExecutionDiagnostic d : diagnostics) {
            if (d.isError()) {
                last = d.stage();
            }
        }

        return last;
    }

    private static VersionPlanningResult.Failure fail(List<ExecutionDiagnostic> diagnostics, String suffix, ExecutionStage stage, String message) {
        diagnostics.add(ExecutionDiagnostic.error("SIR-APP-VERSION-" + suffix, stage, message));
        return new VersionPlanningResult.Failure(stage, diagnostics);
    }
}
