package io.kcg.sir.lowering.springboot;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.lowering.api.LoweringAnalysis;
import io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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
