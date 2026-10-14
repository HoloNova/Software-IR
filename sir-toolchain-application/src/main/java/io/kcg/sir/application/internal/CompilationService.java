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
 * The one entry that turns SIR source bytes into a compilation result.
 *
 * <p>The sources are a snapshot whose entry is the root document and whose other members are fragments;
 * a software written in a single file is a snapshot with one member. Every stage's original diagnostics
 * are kept, and the caller may stop after any stage. {@code ProjectCompilation} wraps this class for the
 * callers that want the finished models and files.
 *
 * <p>Pure and in-memory: it never reads a directory and never writes a file.
 */
public final class CompilationService {
    private CompilationService() {}

    /** The snapshot's entry is the root and every other member a fragment. */
    public static CompilationStages.Snapshot project(SourceSnapshot snapshot, ValidationStopAfter stop,
            Function<SpringBootLoweredModel, GenerationResult> generation, List<ExecutionDiagnostic> executionDiagnostics,
            Consumer<ValidationStopAfter> entered, List<ValidationDiagnostic> raw) {
        Objects.requireNonNull(snapshot, "snapshot");
        entered.accept(ValidationStopAfter.PARSE);
        if (!withinBudget(snapshot, executionDiagnostics, raw)) {
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
                addRead(executionDiagnostics, raw, "SIR-APP-SOURCES-UTF8-001", "source is not valid UTF-8", span);
                return new CompilationStages.Snapshot(false, ValidationStopAfter.PARSE, null, null, List.of(), raw);
            }

            var source = new SirSource(file.sourceId(), text);
            var result = file.sourceId().equals(snapshot.entry()) ? parser.parseProject(source) : parser.parseFragment(source);
            append(result.diagnostics(), ValidationStopAfter.PARSE, executionDiagnostics, raw);
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
            stop, generation, executionDiagnostics, entered, raw);
    }

    /** A project already parsed by the source reader; compilation continues at the semantic stage. */
    public static CompilationStages.Snapshot parsed(ProjectSemanticInput input, ValidationStopAfter stop,
            Function<SpringBootLoweredModel, GenerationResult> generation, List<ExecutionDiagnostic> executionDiagnostics,
            Consumer<ValidationStopAfter> entered, List<ValidationDiagnostic> raw) {
        entered.accept(ValidationStopAfter.SEMANTIC);
        var semantic = new SirSemanticAnalyzer().analyzeProject(input);
        append(semantic.diagnostics(), ValidationStopAfter.SEMANTIC, executionDiagnostics, raw);
        if (!semantic.isSuccess() || stop == ValidationStopAfter.SEMANTIC) {
            return new CompilationStages.Snapshot(semantic.isSuccess(), ValidationStopAfter.SEMANTIC, semantic.model().orElse(null), null, List.of(), raw);
        }

        return CompilationStages.lowerAndGenerate(semantic.model().orElseThrow(), stop, generation, executionDiagnostics, entered, raw);
    }

    private static boolean withinBudget(SourceSnapshot snapshot, List<ExecutionDiagnostic> executionDiagnostics, List<ValidationDiagnostic> raw) {
        long total = 0;
        for (var file : snapshot.manifest().files()) {
            total += file.byteCount();
            if (file.byteCount() > ProjectSourceReader.MAX_FILE_BYTES || total > ProjectSourceReader.MAX_TOTAL_BYTES) {
                addRead(executionDiagnostics, raw, "SIR-APP-SOURCES-LIMIT-001", "snapshot exceeds source byte budget", null);
                return false;
            }

            String issue = PathGuard.validateRelativePath(file.sourceId().value());
            if (issue != null) {
                addRead(executionDiagnostics, raw, "SIR-APP-SOURCES-PATH-001", issue, null);
                return false;
            }
        }

        return true;
    }

    /** Project parsing keeps cross-file related sites as adjacent INFO notes in the executionDiagnostics list, as before. */
    private static void append(List<Diagnostic> diagnostics, ValidationStopAfter stage, List<ExecutionDiagnostic> executionDiagnostics, List<ValidationDiagnostic> raw) {
        executionDiagnostics.addAll(DiagnosticMapper.fromProjectSource(diagnostics, ExecutionStage.valueOf(stage.name())));
        for (var d : diagnostics) {
            raw.add(CompilationStages.fromParser(d, stage));
        }
    }

    private static void addRead(List<ExecutionDiagnostic> executionDiagnostics, List<ValidationDiagnostic> raw, String code, String message, SourceSpan span) {
        executionDiagnostics.add(span == null
            ? ExecutionDiagnostic.error(code, ExecutionStage.READ, message)
            : new ExecutionDiagnostic(code, ExecutionStage.READ, io.kcg.sir.application.api.ExecutionSeverity.ERROR, message,
                java.util.Optional.of(span), java.util.Optional.empty(), java.util.Optional.empty()));
        raw.add(new ValidationDiagnostic(code, "ERROR", ValidationStopAfter.PARSE, message, span, List.of(), List.of(), null, null, null, null));
    }
}
