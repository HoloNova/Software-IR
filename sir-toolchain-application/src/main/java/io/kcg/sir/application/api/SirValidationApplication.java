package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.CompilationStages;
import io.kcg.sir.application.internal.ValidationHashes;
import io.kcg.sir.application.internal.ValidationJsonLines;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Read-only static checking. No state, files, project graph, build or business qualification. */
public final class SirValidationApplication {
    public static final int MAX_SIR_BYTES = io.kcg.sir.application.internal.ProjectSourceReader.MAX_FILE_BYTES;
    public static final int MAX_ID_BYTES = 1024;
    public static final int MAX_LINE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_BATCH_LINES = 10_000;
    private final java.util.function.Function<io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel,
        io.kcg.sir.generator.springboot.api.GenerationResult> generation;
    public SirValidationApplication() { this(model -> new SpringBootGenerator().generate(model)); }
    SirValidationApplication(java.util.function.Function<io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel,
        io.kcg.sir.generator.springboot.api.GenerationResult> generation) {
        this.generation = java.util.Objects.requireNonNull(generation);
    }
    public SirValidationResult check(SirValidationRequest request) {
        if (request == null) return input(null, "KCG-CHECK-INPUT-001", "sample request is required", null);
        String sir = request.sir();
        int bytes = sir == null ? -1 : ValidationHashes.utf8Length(sir, MAX_SIR_BYTES);
        String sha = bytes >= 0 && bytes <= MAX_SIR_BYTES ? ValidationHashes.source(sir) : null;
        String id = request.id();
        if (id == null || ValidationHashes.utf8Length(id, MAX_ID_BYTES) < 0)
            return input(null, "KCG-CHECK-INPUT-002", "id must be a Unicode string", sha);
        if (ValidationHashes.utf8Length(id, MAX_ID_BYTES) > MAX_ID_BYTES)
            return input(null, "KCG-CHECK-LIMIT-001", "id exceeds 1024 UTF-8 bytes", sha);
        if (sir == null || bytes < 0) return input(id, "KCG-CHECK-INPUT-003", "sir must be a Unicode string", null);
        if (bytes > MAX_SIR_BYTES) return input(id, "KCG-CHECK-LIMIT-002", "sir exceeds 1048576 UTF-8 bytes", null);
        if (sir.isBlank()) return input(id, "KCG-CHECK-INPUT-004", "sir must not be blank", sha);
        if (request.stopAfter() == null) return input(id, "KCG-CHECK-INPUT-005", "stopAfter must name a supported stage", sha);
        var entered = new ValidationStopAfter[]{ValidationStopAfter.PARSE};
        var trace = new ArrayList<ValidationDiagnostic>();
        try {
            var result = CompilationStages.compile(sir, SourceId.of("sample.sir"), request.stopAfter(),
                generation, new ArrayList<>(), stage -> entered[0] = stage, trace);
            boolean ok = result.success() && result.diagnostics().stream().noneMatch(d -> "ERROR".equals(d.severity()));
            boolean generated = ok && result.stage() == ValidationStopAfter.GENERATION;
            return new SirValidationResult(id, ok, result.stage(), result.diagnostics(),
                generated ? result.files().size() : 0, generated ? ValidationHashes.generated(result.files()) : null, sha);
        } catch (RuntimeException | StackOverflowError e) {
            trace.add(ValidationDiagnostic.error(entered[0], "KCG-CHECK-INTERNAL-001", "static checking could not complete this sample"));
            return new SirValidationResult(id, false, entered[0], trace, 0, null, sha);
        }
    }
    public SirValidationBatchSummary checkBatch(SirValidationBatchRequest request) throws IOException {
        return ValidationJsonLines.check(this, request);
    }
    public static SirValidationResult input(String id, String code, String message, String sha) {
        return SirValidationResult.failure(id, ValidationStopAfter.PARSE, code, message, sha);
    }
}
