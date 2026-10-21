package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.api.SpringBootDeploymentPlanner;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The CI smoke's G7 source must compile and plan even on a host that cannot securely export files. */
class OptionalDeploymentFixtureTest {
    @Test
    void theThirdDeploymentVersionContainsTheOptionalValueAcceptanceEndpoints() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/deployment/dual-token-v3/project.sir")) {
            bytes = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        SourceId entry = SourceId.of("project.sir");
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var compiled = ProjectCompilation.compile(new SourceSnapshot(entry, Map.of(entry, bytes)),
                model -> new SpringBootGenerator().generate(model), diagnostics);
        assertTrue(compiled.isPresent(), () -> diagnostics.toString());
        var project = compiled.orElseThrow();
        new SpringBootDeploymentPlanner().plan(project.semanticModel(), project.loweredModel());
        String raise = project.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/RaiseFavoriteScoreController.java"))
                .findFirst().orElseThrow().content();
        String create = project.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/FavoriteNoteController.java"))
                .findFirst().orElseThrow().content();
        assertFalse(raise.contains("HttpStatus.CREATED"), "a Unit update uses the default 200, not creation's 201");
        assertTrue(create.contains("HttpStatus.CREATED"), "the create-and-persist command still returns 201");
        for (String capability : java.util.List.of("FilterFavorites", "MissingFavoriteScores", "DifferentFavoriteScores",
                "NotEqualFavoriteScores", "RaiseFavoriteScore", "CheckFavoriteScore", "CheckFavoriteCount", "CheckVisibleNoteCount", "FavoritedNotes")) {
            assertTrue(project.generatedFiles().stream().anyMatch(file -> file.relativePath().endsWith("/" + capability + "Service.java")), capability);
        }
    }
}
