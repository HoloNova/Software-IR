package io.kcg.sir.application.internal;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.api.LoweringAnalysis;
import io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraphBuilder;
import io.kcg.sir.projectgraph.api.ProjectGraphDiagnostic;
import io.kcg.sir.projectgraph.api.ProjectGraphInput;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.source.SourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal SIR compilation pipeline helper. Compiles a SIR source string
 * into a {@link CompiledProject} carrying the {@link NormalizedSemanticModel},
 * {@link SpringBootLoweredModel}, {@link GeneratedFile} list and
 * {@link ProjectGraph}. Used by {@link io.kcg.sir.application.api.ChangePlanningApplication}
 * for read-only recompilation of base and candidate SIR documents.
 *
 * <p>The helper is pure in-memory: it never touches the filesystem. All
 * diagnostics produced during compilation are mapped to
 * {@link ExecutionDiagnostic}s tagged with the appropriate
 * {@link ExecutionStage} (PARSE, SEMANTIC, LOWERING, GENERATION, GRAPH)
 * and appended to the supplied {@code accumulatedDiagnostics} list.
 *
 * <p>This helper does <strong>not</strong> duplicate
 * {@code ToolchainApplication}'s orchestration logic; it only encapsulates
 * the compilation sub-flow so that the change-planning application can
 * recompile both base and candidate SIR documents without reimplementing
 * the pipeline. {@code ToolchainApplication} continues to own its own
 * inline pipeline for the full generation flow.
 */
public final class SirCompiler {

    /**
     * Immutable result of a successful compilation. Carries all four
     * compilation outputs the change-planning flow needs.
     */
    public record CompiledProject(
            NormalizedSemanticModel semanticModel,
            SpringBootLoweredModel loweredModel,
            List<GeneratedFile> generatedFiles,
            ProjectGraph graph,
            List<ExecutionDiagnostic> diagnostics
    ) {
        public CompiledProject {
            Objects.requireNonNull(semanticModel, "semanticModel");
            Objects.requireNonNull(loweredModel, "loweredModel");
            Objects.requireNonNull(generatedFiles, "generatedFiles");
            Objects.requireNonNull(graph, "graph");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        }
    }

    private SirCompiler() {
    }

    /**
     * Compile a SIR source string into a {@link CompiledProject}.
     *
     * @param sourceText            the SIR source text; must not be null
     * @param sourceId              the workspace-relative source identity; must not be null
     * @param accumulatedDiagnostics a mutable list to which all compilation
     *                               diagnostics (WARNING/INFO/ERROR) are
     *                               appended; must not be null
     * @return an {@code Optional} containing the {@link CompiledProject} on
     *         success, or {@code Optional.empty()} if any stage failed (in
     *         which case at least one ERROR diagnostic has been appended to
     *         {@code accumulatedDiagnostics})
     */
    public static Optional<CompiledProject> compile(
            String sourceText,
            SourceId sourceId,
            List<ExecutionDiagnostic> accumulatedDiagnostics
    ) {
        Objects.requireNonNull(sourceText, "sourceText");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(accumulatedDiagnostics, "accumulatedDiagnostics");

        // --- PARSE ---
        SirParser parser = SirParser.create();
        ParseResult parseResult = parser.parse(new SirSource(sourceId, sourceText));
        accumulatedDiagnostics.addAll(DiagnosticMapper.fromParser(
                parseResult.diagnostics(), ExecutionStage.PARSE));
        if (!parseResult.isSuccess()) {
            return Optional.empty();
        }

        // --- SEMANTIC ---
        SemanticAnalysis semantic = new SirSemanticAnalyzer().analyze(
                parseResult.document().orElseThrow());
        accumulatedDiagnostics.addAll(DiagnosticMapper.fromParser(
                semantic.diagnostics(), ExecutionStage.SEMANTIC));
        if (!semantic.isSuccess()) {
            return Optional.empty();
        }
        NormalizedSemanticModel normalizedModel = semantic.model().orElseThrow();

        // --- LOWERING ---
        LoweringAnalysis<SpringBootLoweredModel> lowering =
                new SpringBootTargetLowering().lower(normalizedModel);
        accumulatedDiagnostics.addAll(DiagnosticMapper.fromLowering(
                lowering.diagnostics(), ExecutionStage.LOWERING));
        if (!lowering.isSuccess()) {
            return Optional.empty();
        }
        SpringBootLoweredModel loweredModel = lowering.model().orElseThrow();

        // --- GENERATION ---
        GenerationResult generation = new SpringBootGenerator().generate(loweredModel);
        if (generation instanceof GenerationResult.Failure genFailure) {
            accumulatedDiagnostics.addAll(DiagnosticMapper.fromGeneration(
                    genFailure.diagnostics(), ExecutionStage.GENERATION));
            return Optional.empty();
        }
        List<GeneratedFile> files = ((GenerationResult.Success) generation).files();

        // --- GRAPH build ---
        ProjectGraphInput graphInput = new SpringBootProjectGraphInputFactory().build(
                normalizedModel, loweredModel, files, sourceId);
        ProjectGraphAnalysis graphAnalysis = new ProjectGraphBuilder().build(graphInput);
        if (graphAnalysis instanceof ProjectGraphAnalysis.Failure graphFailure) {
            accumulatedDiagnostics.addAll(mapGraphDiagnostics(
                    graphFailure.diagnostics(), ExecutionStage.GRAPH));
            return Optional.empty();
        }
        ProjectGraphAnalysis.Success graphSuccess = (ProjectGraphAnalysis.Success) graphAnalysis;
        accumulatedDiagnostics.addAll(mapGraphDiagnostics(
                graphSuccess.diagnostics(), ExecutionStage.GRAPH));
        ProjectGraph graph = graphSuccess.graph();

        // Collect all non-ERROR diagnostics as the compiled project's diagnostics.
        List<ExecutionDiagnostic> projectDiags = new ArrayList<>();
        for (ExecutionDiagnostic d : accumulatedDiagnostics) {
            if (!d.isError()) {
                projectDiags.add(d);
            }
        }
        return Optional.of(new CompiledProject(
                normalizedModel, loweredModel, files, graph, projectDiags));
    }

    private static List<ExecutionDiagnostic> mapGraphDiagnostics(
            List<ProjectGraphDiagnostic> diagnostics, ExecutionStage stage) {
        List<ExecutionDiagnostic> out = new ArrayList<>(diagnostics.size());
        for (ProjectGraphDiagnostic d : diagnostics) {
            io.kcg.sir.application.api.ExecutionSeverity severity = d.isError()
                    ? io.kcg.sir.application.api.ExecutionSeverity.ERROR
                    : io.kcg.sir.application.api.ExecutionSeverity.valueOf(d.severity().name());
            out.add(new ExecutionDiagnostic(
                    d.code(),
                    stage,
                    severity,
                    d.message(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
        }
        return out;
    }
}
