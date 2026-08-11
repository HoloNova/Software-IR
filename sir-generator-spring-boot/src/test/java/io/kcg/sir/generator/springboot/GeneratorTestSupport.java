package io.kcg.sir.generator.springboot;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
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
import java.util.List;

final class GeneratorTestSupport {

    private GeneratorTestSupport() {
    }

    static SpringBootLoweredModel lowerSuccess(String resource) {
        LoweringAnalysis<SpringBootLoweredModel> result =
                new SpringBootTargetLowering().lower(normalized(resource));
        if (!result.isSuccess()) {
            throw new AssertionError("lowering failed: " + result.diagnostics());
        }
        return result.model().orElseThrow();
    }

    static List<GeneratedFile> generateSuccess(String resource) {
        return generateSuccess(lowerSuccess(resource));
    }

    static List<GeneratedFile> generateSuccess(SpringBootLoweredModel model) {
        GenerationResult result = new SpringBootGenerator().generate(model);
        if (result instanceof GenerationResult.Failure failure) {
            throw new AssertionError("generation failed: " + failure.diagnostics());
        }
        return ((GenerationResult.Success) result).files();
    }

    static String contentEndingWith(List<GeneratedFile> files, String suffix) {
        return files.stream()
                .filter(file -> file.relativePath().endsWith(suffix))
                .map(GeneratedFile::content)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing generated file: " + suffix));
    }

    static GeneratedFile fileAt(List<GeneratedFile> files, String relativePath) {
        return files.stream()
                .filter(file -> file.relativePath().equals(relativePath))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing generated file: " + relativePath));
    }

    private static NormalizedSemanticModel normalized(String resource) {
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

    private static String resource(String path) {
        try (InputStream stream = GeneratorTestSupport.class.getResourceAsStream("/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read resource: " + path, e);
        }
    }
}
