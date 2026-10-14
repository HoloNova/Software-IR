package io.kcg.sir.application.internal;

import io.kcg.sir.application.api.*;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.source.*;
import java.util.*;
import java.util.function.Function;

/** Shared target compilation; never reads a host source directory or writes generated files. Wraps {@link CompilationService}. */
public final class ProjectCompilation {
    private ProjectCompilation() {}

    /** What a successful compilation produced: the semantic and lowered models and the generated files. */
    public record Compiled(NormalizedSemanticModel semanticModel, SpringBootLoweredModel loweredModel, List<GeneratedFile> generatedFiles,
            List<ExecutionDiagnostic> diagnostics) {
        public Compiled {
            Objects.requireNonNull(semanticModel, "semanticModel");
            Objects.requireNonNull(loweredModel, "loweredModel");
            generatedFiles = List.copyOf(Objects.requireNonNull(generatedFiles, "generatedFiles"));
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        }
    }

    public static Optional<Compiled> compile(ProjectSourceReader.Loaded loaded,
            Function<SpringBootLoweredModel, GenerationResult> generation, List<ExecutionDiagnostic> diagnostics) {
        return toCompiled(CompilationService.parsed(loaded.semanticInput(), ValidationStopAfter.GENERATION, generation, diagnostics,
            stage -> {}, new ArrayList<>()), diagnostics);
    }

    public static Optional<Compiled> compile(SourceSnapshot snapshot,
            Function<SpringBootLoweredModel, GenerationResult> generation, List<ExecutionDiagnostic> diagnostics) {
        Objects.requireNonNull(snapshot);
        return toCompiled(CompilationService.project(snapshot, ValidationStopAfter.GENERATION, generation, diagnostics,
            stage -> {}, new ArrayList<>()), diagnostics);
    }

    private static Optional<Compiled> toCompiled(CompilationStages.Snapshot result, List<ExecutionDiagnostic> diagnostics) {
        if (!result.success()) return Optional.empty();
        return Optional.of(new Compiled(result.semanticModel(), result.loweredModel(), result.files(),
            diagnostics.stream().filter(d -> !d.isError()).toList()));
    }
}
