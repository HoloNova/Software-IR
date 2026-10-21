package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.api.SpringBootDeploymentPlanner;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Eight backends of the kind a student or a small team builds (a second-hand market, a library, course selection, a
 * blog, a task board, a shop, room booking, a ledger), written in SIR to see what the language cannot say yet. Each
 * must stay a valid software that can be generated and delivered; what each could not say is listed in
 * docs/qualification/TOPIC_TRIALS.md. Whether the generated Java compiles was checked by hand on 2026-10-20 (see
 * there), not here.
 */
class TopicSamplesTest {
    private static final List<String> TOPICS = List.of("market", "library", "course", "blog", "tasks", "shop", "booking", "ledger");

    @Test void everyTopicCompilesIsGeneratedAndCanBeDelivered() throws IOException {
        for (String topic : TOPICS) {
            SourceId entry = SourceId.of("project.sir");
            var diagnostics = new ArrayList<ExecutionDiagnostic>();
            byte[] text;
            try (InputStream in = TopicSamplesTest.class.getResourceAsStream("/topics/" + topic + "/project.sir")) {
                text = Objects.requireNonNull(in, topic).readAllBytes();
            }
            var compiled = ProjectCompilation.compile(new SourceSnapshot(entry, Map.of(entry, text)),
                model -> new SpringBootGenerator().generate(model), diagnostics);
            assertTrue(compiled.isPresent(), () -> topic + ": " + diagnostics);
            assertTrue(compiled.get().generatedFiles().size() > 40, topic);
            new SpringBootDeploymentPlanner().plan(compiled.get().semanticModel(), compiled.get().loweredModel());
            long sirLines = new String(text, java.nio.charset.StandardCharsets.UTF_8).lines().count();
            long javaLines = compiled.get().generatedFiles().stream().filter(f -> f.relativePath().endsWith(".java"))
                    .mapToLong(f -> f.content().lines().count()).sum();
            long endpoints = compiled.get().generatedFiles().stream().filter(f -> f.relativePath().endsWith("Controller.java")).count();
            System.out.println("[TOPIC-SIZE] " + topic + " " + sirLines + " " + javaLines + " " + endpoints);
        }
    }
}
