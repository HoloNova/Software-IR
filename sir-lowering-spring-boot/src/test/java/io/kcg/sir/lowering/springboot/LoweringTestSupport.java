package io.kcg.sir.lowering.springboot;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.lowering.api.LoweringAnalysis;
import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering;
import io.kcg.sir.lowering.springboot.internal.SpringBootInputValidator;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class LoweringTestSupport {

    private LoweringTestSupport() {
    }

    static NormalizedSemanticModel normalized(String resource) {
        ParseResult parsed = new DefaultSirParser().parse(
                new SirSource(SourceId.of(resource), resource(resource)));
        if (!parsed.isSuccess()) {
            throw new AssertionError("parse failed: " + parsed.diagnostics());
        }
        SemanticAnalysis semantic = new SirSemanticAnalyzer().analyze(parsed.document().orElseThrow());
        if (!semantic.isSuccess()) {
            throw new AssertionError("semantic analysis failed: " + semantic.diagnostics());
        }
        return semantic.model().orElseThrow();
    }

    static SpringBootLoweredModel lowerSuccess(String resource) {
        LoweringAnalysis<SpringBootLoweredModel> result =
                new SpringBootTargetLowering().lower(normalized(resource));
        if (!result.isSuccess()) {
            throw new AssertionError("lowering failed: " + result.diagnostics());
        }
        return result.model().orElseThrow();
    }

    /** The lowered model of a source text, so a test can lower a stated variant of a legal program. */
    static SpringBootLoweredModel lowerSourceSuccess(String sourceText) {
        ParseResult parsed = new DefaultSirParser().parse(new SirSource(SourceId.of("tests"), sourceText));
        if (!parsed.isSuccess()) {
            throw new AssertionError("parse failed: " + parsed.diagnostics());
        }

        SemanticAnalysis semantic = new SirSemanticAnalyzer().analyze(parsed.document().orElseThrow());
        if (!semantic.isSuccess()) {
            throw new AssertionError("semantic analysis failed: " + semantic.diagnostics());
        }

        LoweringAnalysis<SpringBootLoweredModel> result =
                new SpringBootTargetLowering().lower(semantic.model().orElseThrow());
        if (!result.isSuccess()) {
            throw new AssertionError("lowering failed: " + result.diagnostics());
        }

        return result.model().orElseThrow();
    }

    /** The resource's source text, so a test can state a variant of a legal program. */
    static String source(String resource) {
        return resource(resource);
    }

    /** The target-capability diagnostics of a program, without lowering it. */
    static List<LoweringDiagnostic> inputDiagnostics(String sourceText) {
        ParseResult parsed = new DefaultSirParser().parse(new SirSource(SourceId.of("tests"), sourceText));
        if (!parsed.isSuccess()) {
            throw new AssertionError("parse failed: " + parsed.diagnostics());
        }
        SemanticAnalysis semantic = new SirSemanticAnalyzer().analyze(parsed.document().orElseThrow());
        if (!semantic.isSuccess()) {
            throw new AssertionError("semantic analysis failed: " + semantic.diagnostics());
        }
        return new SpringBootInputValidator(semantic.model().orElseThrow()).validate();
    }

    private static String resource(String path) {
        try (InputStream stream = LoweringTestSupport.class.getResourceAsStream("/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read resource: " + path, e);
        }
    }
}
