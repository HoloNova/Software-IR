package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.CompilationService;
import io.kcg.sir.application.internal.CompilationStages;
import io.kcg.sir.application.internal.SourceReader;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.change.edit.SemanticDelta;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.SoftwareSchema;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Previews an update from one revision of a software to another without touching anything.
 *
 * <p>Both revisions are compiled from scratch with the one compilation service, so the preview is the
 * difference between two complete, honest results and never a guess from a partial one. The output
 * directory, when given, is read through a no-follow handle and never written. The preview is not a
 * plan the existing apply entries understand; applying and recovery are separate, later work.
 */
public final class UpdatePreviewApplication {
    private static final int MAX_OUTPUT_FILE_BYTES = 8 * 1024 * 1024;

    private final Function<SpringBootLoweredModel, GenerationResult> generation;

    public UpdatePreviewApplication() {
        this(model -> new SpringBootGenerator().generate(model));
    }

    UpdatePreviewApplication(Function<SpringBootLoweredModel, GenerationResult> generation) {
        this.generation = Objects.requireNonNull(generation, "generation");
    }

    public UpdatePreviewResult preview(UpdatePreviewRequest request) {
        Objects.requireNonNull(request, "request");
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();

        List<ExecutionDiagnostic> baseDiagnostics = new ArrayList<>();
        CompilationStages.Snapshot base = this.compile(request.base(), baseDiagnostics);
        if (!ok(base)) {
            return new UpdatePreviewResult.Failure(UpdatePreviewResult.Side.BASE, lastErrorStage(baseDiagnostics), baseDiagnostics);
        }

        List<ExecutionDiagnostic> candidateDiagnostics = new ArrayList<>();
        CompilationStages.Snapshot candidate = this.compile(request.candidate(), candidateDiagnostics);
        if (!ok(candidate)) {
            return new UpdatePreviewResult.Failure(UpdatePreviewResult.Side.CANDIDATE, lastErrorStage(candidateDiagnostics), candidateDiagnostics);
        }

        diagnostics.addAll(candidateDiagnostics);

        FileDelta files;
        if (request.outputRoot().isPresent()) {
            try (SecureFileAccess output = new SecureFileAccess(request.outputRoot().get())) {
                files = FileDelta.compare(base.files(), candidate.files(), path -> observe(output, path));
            } catch (IOException | RuntimeException e) {
                return new UpdatePreviewResult.Failure(UpdatePreviewResult.Side.OUTPUT_ROOT, ExecutionStage.READ,
                    List.of(ExecutionDiagnostic.error("SIR-APP-PREVIEW-OUTPUT-001", ExecutionStage.READ, "output root cannot be read safely: " + e.getMessage())));
            }
        } else {
            files = FileDelta.compare(base.files(), candidate.files(), null);
        }

        SoftwareSchema schema = SoftwareSchema.normalizedModel();
        SemanticDelta software = SemanticDelta.between(SoftwareDraft.of(base.semanticModel()), SoftwareDraft.of(candidate.semanticModel()), schema);
        SourceDelta source = SourceDelta.between(request.base().sources().manifest(), request.candidate().sources().manifest());
        return new UpdatePreviewResult.Success(source, software, files, diagnostics.stream().filter(d -> !d.isError()).toList());
    }

    private CompilationStages.Snapshot compile(CompilationInput input, List<ExecutionDiagnostic> legacy) {
        List<ValidationDiagnostic> raw = new ArrayList<>();
        if (input.kind() == CompilationInput.Kind.PROJECT) {
            return CompilationService.project(input.sources(), ValidationStopAfter.GENERATION, this.generation, legacy, stage -> {}, raw);
        }

        var id = input.sources().entry();
        String text;
        try {
            text = SourceReader.decodeStrictUtf8(input.sources().bytes(id));
        } catch (SourceReader.InvalidUtf8Exception e) {
            legacy.add(ExecutionDiagnostic.error("SIR-APP-SOURCES-UTF8-001", ExecutionStage.READ, "source is not valid UTF-8"));
            return new CompilationStages.Snapshot(false, ValidationStopAfter.PARSE, null, null, List.of(), raw);
        }

        return CompilationService.single(text, id, ValidationStopAfter.GENERATION, this.generation, legacy, stage -> {}, raw);
    }

    private static boolean ok(CompilationStages.Snapshot snapshot) {
        return snapshot.success() && snapshot.stage() == ValidationStopAfter.GENERATION
            && snapshot.diagnostics().stream().noneMatch(d -> "ERROR".equals(d.severity()));
    }

    private static FileDelta.Observation observe(SecureFileAccess output, String path) {
        try {
            if (!output.exists(path)) {
                return new FileDelta.Observation.Absent();
            }

            return new FileDelta.Observation.Present(io.kcg.sir.application.internal.Sha256.hexDigest(output.read(path, MAX_OUTPUT_FILE_BYTES)));
        } catch (IOException | RuntimeException e) {
            return new FileDelta.Observation.Unreadable(String.valueOf(e.getMessage()));
        }
    }

    private static ExecutionStage lastErrorStage(List<ExecutionDiagnostic> diagnostics) {
        ExecutionStage last = ExecutionStage.PARSE;
        for (ExecutionDiagnostic d : diagnostics) {
            if (d.isError()) {
                last = d.stage();
            }
        }

        return last;
    }
}
