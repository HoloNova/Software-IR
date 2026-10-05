package io.kcg.sir.application.internal;

import io.kcg.sir.api.*;
import io.kcg.sir.ast.AstSourceUnit;
import io.kcg.sir.application.api.*;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.semantic.api.*;
import io.kcg.sir.source.*;
import java.util.*;
import java.util.function.Function;

/** Shared target compilation; never reads a host source directory or writes generated files. */
public final class ProjectCompilation {
    private ProjectCompilation() {}

    public static Optional<SirCompilation.CompilationSnapshot> compile(ProjectSourceReader.Loaded loaded,
            Function<SpringBootLoweredModel, GenerationResult> generation, List<ExecutionDiagnostic> diagnostics) {
        var semantic = new SirSemanticAnalyzer().analyzeProject(loaded.semanticInput());
        diagnostics.addAll(DiagnosticMapper.fromProjectSource(semantic.diagnostics(), ExecutionStage.SEMANTIC));
        if (!semantic.isSuccess()) return Optional.empty();
        return SirCompilation.lowerAndGenerate(semantic.model().orElseThrow(), generation, diagnostics);
    }

    public static Optional<SirCompilation.CompilationSnapshot> compile(SourceSnapshot snapshot,
            Function<SpringBootLoweredModel, GenerationResult> generation, List<ExecutionDiagnostic> diagnostics) {
        Objects.requireNonNull(snapshot);
        long total = 0;
        for (var file : snapshot.manifest().files()) {
            total += file.byteCount();
            if (file.byteCount() > ProjectSourceReader.MAX_FILE_BYTES || total > ProjectSourceReader.MAX_TOTAL_BYTES) {
                diagnostics.add(ExecutionDiagnostic.error("SIR-APP-SOURCES-LIMIT-001",ExecutionStage.READ,"snapshot exceeds source byte budget"));
                return Optional.empty();
            }
            String issue = PathGuard.validateRelativePath(file.sourceId().value());
            if (issue != null) {
                diagnostics.add(ExecutionDiagnostic.error("SIR-APP-SOURCES-PATH-001",ExecutionStage.READ,issue));
                return Optional.empty();
            }
        }
        var parser = SirParser.create();
        var fragments = new ArrayList<AstSourceUnit.Fragment>();
        AstSourceUnit.Root root = null;
        for (var file : snapshot.manifest().files()) {
            String text;
            try { text = SourceReader.decodeStrictUtf8(snapshot.bytes(file.sourceId())); }
            catch (SourceReader.InvalidUtf8Exception e) {
                var zero = new SourcePosition(0,1,1);
                diagnostics.add(new ExecutionDiagnostic("SIR-APP-SOURCES-UTF8-001",ExecutionStage.READ,ExecutionSeverity.ERROR,
                    "source is not valid UTF-8",Optional.of(new SourceSpan(file.sourceId(),zero,zero)),Optional.empty(),Optional.empty()));
                return Optional.empty();
            }
            var source = new SirSource(file.sourceId(),text);
            var result = file.sourceId().equals(snapshot.entry()) ? parser.parseProject(source) : parser.parseFragment(source);
            diagnostics.addAll(DiagnosticMapper.fromProjectSource(result.diagnostics(),ExecutionStage.PARSE));
            if (!result.isSuccess()) return Optional.empty();
            if (result.unit().orElseThrow() instanceof AstSourceUnit.Root r) root = r;
            else fragments.add((AstSourceUnit.Fragment)result.unit().orElseThrow());
        }
        return compile(new ProjectSourceReader.Loaded(snapshot,new ProjectSemanticInput(Objects.requireNonNull(root),fragments)),generation,diagnostics);
    }
}
