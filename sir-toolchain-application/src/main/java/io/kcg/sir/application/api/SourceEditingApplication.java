package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.CompilationService;
import io.kcg.sir.application.internal.ProjectSourceReader;
import io.kcg.sir.application.internal.SourceCandidatePublisher;
import io.kcg.sir.change.edit.EditResult;
import io.kcg.sir.change.edit.SourceEditor;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.SoftwareSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Thin source workflow: shared reads/analysis, existing command editor, independent candidate publication. */
public final class SourceEditingApplication {
    public static final int MAX_COMMANDS = 1000;

    public SourceInspectionResult inspect(SourceInspectionRequest request) {
        Objects.requireNonNull(request);
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var loaded = ProjectSourceReader.read(request.sourceRoot(), request.entry(), diagnostics);
        if (loaded.isEmpty()) return new SourceInspectionResult.Failure(diagnostics);
        var result = CompilationService.parsed(loaded.get().semanticInput(), ValidationStopAfter.SEMANTIC,
            model -> { throw new IllegalStateException("inspection must stop before generation"); },
            diagnostics, stage -> {}, new ArrayList<>());
        if (!result.success()) return new SourceInspectionResult.Failure(diagnostics);
        var model = result.semanticModel();
        return new SourceInspectionResult.Success(loaded.get().snapshot(), SoftwareSchema.normalizedModel(),
            SoftwareDraft.of(model), expanded(model), diagnostics);
    }

    /** The declarations a use expanded into, in the model's order, each with the use it came from. */
    private static List<SourceInspectionResult.Expanded> expanded(io.kcg.sir.semantic.api.NormalizedSemanticModel model) {
        var uses = new java.util.HashMap<io.kcg.sir.semantic.symbol.SymbolId, io.kcg.sir.semantic.model.NormalizedFeatureUse>();
        model.declarations().stream().filter(io.kcg.sir.semantic.model.NormalizedFeatureUse.class::isInstance)
            .map(io.kcg.sir.semantic.model.NormalizedFeatureUse.class::cast).forEach(use -> uses.put(use.id(), use));
        var expanded = new ArrayList<SourceInspectionResult.Expanded>();
        for (var declaration : model.declarations()) {
            var origin = model.expandedFrom(declaration.id());
            if (origin.isEmpty()) continue;
            var use = uses.get(origin.get().feature());
            var arguments = new java.util.TreeMap<String, String>();
            if (use != null) use.arguments().forEach(a -> arguments.put(a.name(), a.value()));
            expanded.add(new SourceInspectionResult.Expanded(declaration.getClass().getSimpleName(), declaration.name(),
                use == null ? origin.get().feature().value() : use.name(), arguments, origin.get().role()));
        }
        return expanded;
    }

    public SourceEditResult edit(SourceEditRequest request) {
        Objects.requireNonNull(request);
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        Path source = request.sourceRoot(), candidate = request.candidateRoot();
        if (!normalized(source) || !normalized(candidate) || candidate.getParent() == null
                || source.startsWith(candidate) || candidate.startsWith(source)) {
            diagnostics.add(error("SIR-APP-EDIT-REQUEST-001", ExecutionStage.PREFLIGHT,
                "source/candidate roots must be absolute normalized paths and must not overlap"));
            return new SourceEditResult.Failure(diagnostics);
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            diagnostics.add(error("SIR-APP-EDIT-OUTPUT-001", ExecutionStage.PREFLIGHT, "candidate root already exists"));
            return new SourceEditResult.Failure(diagnostics);
        }
        if (request.batch().commands().size() > MAX_COMMANDS) {
            diagnostics.add(error("SIR-APP-EDIT-LIMIT-001", ExecutionStage.PREFLIGHT, "batch exceeds 1000 commands"));
            return new SourceEditResult.Failure(diagnostics);
        }
        var loaded = ProjectSourceReader.read(source, request.entry(), diagnostics);
        if (loaded.isEmpty()) return new SourceEditResult.Failure(diagnostics);
        var result = new SourceEditor().edit(loaded.get().snapshot(), request.batch());
        if (result instanceof EditResult.Refused refused) return new SourceEditResult.Refused(refused, diagnostics);
        var edited = (EditResult.Edited) result;
        try {
            SourceCandidatePublisher.publish(candidate, edited.sources());
            return new SourceEditResult.Saved(candidate, edited.sources(), edited.delta(), edited.changes(), diagnostics);
        } catch (IOException e) {
            diagnostics.add(error("SIR-APP-EDIT-WRITE-001", ExecutionStage.WRITE, "candidate publication failed: " + e.getMessage()));
            return new SourceEditResult.Failure(diagnostics);
        }
    }

    private static boolean normalized(Path path) { return path.isAbsolute() && path.equals(path.normalize()); }
    private static ExecutionDiagnostic error(String code, ExecutionStage stage, String message) {
        return ExecutionDiagnostic.error(code, stage, message);
    }
}
