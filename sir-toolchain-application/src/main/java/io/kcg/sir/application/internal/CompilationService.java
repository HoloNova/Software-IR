package io.kcg.sir.application.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.ast.AstSourceUnit;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.application.api.ValidationDiagnostic;
import io.kcg.sir.application.api.ValidationStopAfter;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.semantic.api.ProjectSemanticInput;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.source.SourcePosition;
import io.kcg.sir.source.SourceSnapshot;
import io.kcg.sir.source.SourceSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The one entry that turns SIR source bytes into a compilation result, for a single file and for a
 * multi-file project alike.
 *
 * <p>It exists so the two source forms stop owning two copies of the pipeline. Both forms now go
 * through {@link CompilationStages#lowerAndGenerate} after their own parsing, and both return the
 * same {@link CompilationStages.Snapshot}: every stage's original diagnostics are kept, and the
 * caller may stop after any stage. The older entry points ({@code SirCompilation},
 * {@code ProjectCompilation}) only wrap this class, so their published behaviour does not change.
 *
 * <p>Pure and in-memory: it never reads a directory and never writes a file.
 */
public final class CompilationService {
    private CompilationService() {}

    /** A single legacy source file: parsed with the legacy entry, which still accepts the older version rules. */
    public static CompilationStages.Snapshot single(String text, io.kcg.sir.source.SourceId id, ValidationStopAfter stop,
            Function<SpringBootLoweredModel, GenerationResult> generation, List<ExecutionDiagnostic> legacy,
            Consumer<ValidationStopAfter> entered, List<ValidationDiagnostic> raw) {
        return CompilationStages.compile(text, id, stop, generation, legacy, entered, raw);
    }

    /** An explicit multi-file project; the snapshot's entry is the root and every other member a fragment. */
    public static CompilationStages.Snapshot project(SourceSnapshot snapshot, ValidationStopAfter stop,
            Function<SpringBootLoweredModel, GenerationResult> generation, List<ExecutionDiagnostic> legacy,
            Consumer<ValidationStopAfter> entered, List<ValidationDiagnostic> raw) {
        Objects.requireNonNull(snapshot, "snapshot");
        entered.accept(ValidationStopAfter.PARSE);
        if (!withinBudget(snapshot, legacy, raw)) {
            return new CompilationStages.Snapshot(false, ValidationStopAfter.PARSE, null, null, List.of(), raw);
        }

        var parser = SirParser.create();
        var fragments = new ArrayList<AstSourceUnit.Fragment>();
        AstSourceUnit.Root root = null;
        for (var file : snapshot.manifest().files()) {
            String text;
            try {
                text = SourceReader.decodeStrictUtf8(snapshot.bytes(file.sourceId()));
            } catch (SourceReader.InvalidUtf8Exception e) {
                var zero = new SourcePosition(0, 1, 1);
                var span = new SourceSpan(file.sourceId(), zero, zero);
                addRead(legacy, raw, "SIR-APP-SOURCES-UTF8-001", "source is not valid UTF-8", span);
                return new CompilationStages.Snapshot(false, ValidationStopAfter.PARSE, null, null, List.of(), raw);
            }

            var source = new SirSource(file.sourceId(), text);
            var result = file.sourceId().equals(snapshot.entry()) ? parser.parseProject(source) : parser.parseFragment(source);
            append(result.diagnostics(), ValidationStopAfter.PARSE, legacy, raw);
            if (!result.isSuccess()) {
                // The first file that fails to parse ends the stage, exactly as the earlier project entry did.
                return new CompilationStages.Snapshot(false, ValidationStopAfter.PARSE, null, null, List.of(), raw);
            }

            if (result.unit().orElseThrow() instanceof AstSourceUnit.Root r) {
                root = r;
            } else {
                fragments.add((AstSourceUnit.Fragment)result.unit().orElseThrow());
            }
        }

        if (stop == ValidationStopAfter.PARSE) {
            return new CompilationStages.Snapshot(true, ValidationStopAfter.PARSE, null, null, List.of(), raw);
        }

        return parsed(new ProjectSemanticInput(Objects.requireNonNull(root, "entry source is missing from the snapshot"), fragments),
            stop, generation, legacy, entered, raw);
    }

    /** A project already parsed by the source reader; compilation continues at the semantic stage. */
    public static CompilationStages.Snapshot parsed(ProjectSemanticInput input, ValidationStopAfter stop,
            Function<SpringBootLoweredModel, GenerationResult> generation, List<ExecutionDiagnostic> legacy,
            Consumer<ValidationStopAfter> entered, List<ValidationDiagnostic> raw) {
        entered.accept(ValidationStopAfter.SEMANTIC);
        var semantic = new SirSemanticAnalyzer().analyzeProject(input);
        append(semantic.diagnostics(), ValidationStopAfter.SEMANTIC, legacy, raw);
        if (!semantic.isSuccess() || stop == ValidationStopAfter.SEMANTIC) {
            return new CompilationStages.Snapshot(semantic.isSuccess(), ValidationStopAfter.SEMANTIC, semantic.model().orElse(null), null, List.of(), raw);
        }

        return CompilationStages.lowerAndGenerate(semantic.model().orElseThrow(), stop, generation, legacy, entered, raw);
    }

    private static boolean withinBudget(SourceSnapshot snapshot, List<ExecutionDiagnostic> legacy, List<ValidationDiagnostic> raw) {
        long total = 0;
        for (var file : snapshot.manifest().files()) {
            total += file.byteCount();
            if (file.byteCount() > ProjectSourceReader.MAX_FILE_BYTES || total > ProjectSourceReader.MAX_TOTAL_BYTES) {
                addRead(legacy, raw, "SIR-APP-SOURCES-LIMIT-001", "snapshot exceeds source byte budget", null);
                return false;
            }

            String issue = PathGuard.validateRelativePath(file.sourceId().value());
            if (issue != null) {
                addRead(legacy, raw, "SIR-APP-SOURCES-PATH-001", issue, null);
                return false;
            }
        }

        return true;
    }

    /** Project parsing keeps cross-file related sites as adjacent INFO notes in the legacy list, as before. */
    private static void append(List<Diagnostic> diagnostics, ValidationStopAfter stage, List<ExecutionDiagnostic> legacy, List<ValidationDiagnostic> raw) {
        legacy.addAll(DiagnosticMapper.fromProjectSource(diagnostics, ExecutionStage.valueOf(stage.name())));
        for (var d : diagnostics) {
            raw.add(CompilationStages.fromParser(d, stage));
        }
    }

    private static void addRead(List<ExecutionDiagnostic> legacy, List<ValidationDiagnostic> raw, String code, String message, SourceSpan span) {
        legacy.add(span == null
            ? ExecutionDiagnostic.error(code, ExecutionStage.READ, message)
            : new ExecutionDiagnostic(code, ExecutionStage.READ, io.kcg.sir.application.api.ExecutionSeverity.ERROR, message,
                java.util.Optional.of(span), java.util.Optional.empty(), java.util.Optional.empty()));
        raw.add(new ValidationDiagnostic(code, "ERROR", ValidationStopAfter.PARSE, message, span, List.of(), List.of(), null, null, null, null));
    }
}
