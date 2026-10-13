package io.kcg.sir.application.internal;

import io.kcg.sir.application.api.*;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.source.*;
import java.util.*;
import java.util.function.Function;

/** Shared target compilation; never reads a host source directory or writes generated files. Wraps {@link CompilationService}. */
public final class ProjectCompilation {
    private ProjectCompilation() {}

    public static Optional<SirCompilation.CompilationSnapshot> compile(ProjectSourceReader.Loaded loaded,
            Function<SpringBootLoweredModel, GenerationResult> generation, List<ExecutionDiagnostic> diagnostics) {
        return legacy(CompilationService.parsed(loaded.semanticInput(), ValidationStopAfter.GENERATION, generation, diagnostics,
            stage -> {}, new ArrayList<>()), diagnostics);
    }

    public static Optional<SirCompilation.CompilationSnapshot> compile(SourceSnapshot snapshot,
            Function<SpringBootLoweredModel, GenerationResult> generation, List<ExecutionDiagnostic> diagnostics) {
        Objects.requireNonNull(snapshot);
        return legacy(CompilationService.project(snapshot, ValidationStopAfter.GENERATION, generation, diagnostics,
            stage -> {}, new ArrayList<>()), diagnostics);
    }

    private static Optional<SirCompilation.CompilationSnapshot> legacy(CompilationStages.Snapshot result, List<ExecutionDiagnostic> diagnostics) {
        if (!result.success()) return Optional.empty();
        return Optional.of(new SirCompilation.CompilationSnapshot(result.semanticModel(), result.loweredModel(), result.files(),
            diagnostics.stream().filter(d -> !d.isError()).toList()));
    }
}
