package io.kcg.sir.application.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.application.api.*;
import io.kcg.sir.generator.springboot.api.*;
import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.source.SourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** One in-memory pipeline. Legacy callers project diagnostics; validation retains the original information. */
public final class CompilationStages {
    private CompilationStages() {}
    public record Snapshot(boolean success, ValidationStopAfter stage, NormalizedSemanticModel semanticModel,
        SpringBootLoweredModel loweredModel, List<GeneratedFile> files, List<ValidationDiagnostic> diagnostics) {
        public Snapshot { files = List.copyOf(files); diagnostics = List.copyOf(diagnostics); }
    }
    public static Snapshot compile(String text, SourceId id, ValidationStopAfter stop,
        Function<SpringBootLoweredModel, GenerationResult> generation, List<ExecutionDiagnostic> legacy,
        Consumer<ValidationStopAfter> entered) {
        return compile(text, id, stop, generation, legacy, entered, new ArrayList<>());
    }
    /** Caller-owned diagnostic trace also preserves earlier stages if a later stage throws. */
    public static Snapshot compile(String text, SourceId id, ValidationStopAfter stop,
        Function<SpringBootLoweredModel, GenerationResult> generation, List<ExecutionDiagnostic> legacy,
        Consumer<ValidationStopAfter> entered, List<ValidationDiagnostic> raw) {
        entered.accept(ValidationStopAfter.PARSE);
        var parsed = SirParser.create().parse(new SirSource(id, text));
        appendParser(parsed.diagnostics(), ValidationStopAfter.PARSE, legacy, raw);
        if (!parsed.isSuccess() || stop == ValidationStopAfter.PARSE)
            return new Snapshot(parsed.isSuccess(), ValidationStopAfter.PARSE, null, null, List.of(), raw);
        entered.accept(ValidationStopAfter.SEMANTIC);
        var semantic = new SirSemanticAnalyzer().analyze(parsed.document().orElseThrow());
        appendParser(semantic.diagnostics(), ValidationStopAfter.SEMANTIC, legacy, raw);
        if (!semantic.isSuccess() || stop == ValidationStopAfter.SEMANTIC)
            return new Snapshot(semantic.isSuccess(), ValidationStopAfter.SEMANTIC, semantic.model().orElse(null), null, List.of(), raw);
        return lowerAndGenerate(semantic.model().orElseThrow(), stop, generation, legacy, entered, raw);
    }
    public static Snapshot lowerAndGenerate(NormalizedSemanticModel model, ValidationStopAfter stop,
        Function<SpringBootLoweredModel, GenerationResult> generation, List<ExecutionDiagnostic> legacy,
        Consumer<ValidationStopAfter> entered, List<ValidationDiagnostic> previous) {
        var raw = previous;
        entered.accept(ValidationStopAfter.LOWERING);
        var lower = new SpringBootTargetLowering().lower(model);
        legacy.addAll(DiagnosticMapper.fromLowering(lower.diagnostics(), ExecutionStage.LOWERING));
        for (var d : lower.diagnostics()) raw.add(fromLowering(d));
        if (!lower.isSuccess() || stop == ValidationStopAfter.LOWERING)
            return new Snapshot(lower.isSuccess(), ValidationStopAfter.LOWERING, model, lower.model().orElse(null), List.of(), raw);
        var lowered = lower.model().orElseThrow();
        entered.accept(ValidationStopAfter.GENERATION);
        var generated = generation.apply(lowered);
        if (generated instanceof GenerationResult.Failure failure) {
            legacy.addAll(DiagnosticMapper.fromGeneration(failure.diagnostics(), ExecutionStage.GENERATION));
            for (var d : failure.diagnostics()) raw.add(fromGeneration(d));
            return new Snapshot(false, ValidationStopAfter.GENERATION, model, lowered, List.of(), raw);
        }
        return new Snapshot(true, ValidationStopAfter.GENERATION, model, lowered, ((GenerationResult.Success) generated).files(), raw);
    }
    private static void appendParser(List<Diagnostic> ds, ValidationStopAfter stage,
        List<ExecutionDiagnostic> legacy, List<ValidationDiagnostic> raw) {
        legacy.addAll(DiagnosticMapper.fromParser(ds, ExecutionStage.valueOf(stage.name())));
        for (var d : ds) raw.add(fromParser(d, stage));
    }
    public static ValidationDiagnostic fromParser(Diagnostic d, ValidationStopAfter stage) {
        return new ValidationDiagnostic(d.code().value(), d.severity().name(), stage, d.message(), d.primarySpan(),
            d.related(), d.fixes(), null, null, null, null);
    }
    public static ValidationDiagnostic fromLowering(LoweringDiagnostic d) {
        return new ValidationDiagnostic(d.code().value(), d.severity().name(), ValidationStopAfter.LOWERING, d.message(),
            d.primarySpan(), List.of(), List.of(), d.sourceSymbol().map(s -> s.value()).orElse(null),
            d.sourceNodeId().map(n -> n.value()).orElse(null), null, null);
    }
    public static ValidationDiagnostic fromGeneration(GenerationDiagnostic d) {
        return new ValidationDiagnostic(d.code(), "ERROR", ValidationStopAfter.GENERATION, d.message(), null,
            List.of(), List.of(), null, null, d.nodeId().map(n -> n.value()).orElse(null), null);
    }
}
