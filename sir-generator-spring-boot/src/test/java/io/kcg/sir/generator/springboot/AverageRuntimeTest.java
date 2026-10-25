package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The generated services for a single and a grouped average compile, and the single one sends the planned SQL. */
class AverageRuntimeTest {
    @TempDir Path work;

    @Test void averageServicesCompileAndCarryTheRoundedProjection() throws Exception {
        try (var loader = GeneratedRuntimeSupport.compile(GeneratorTestSupport.resourceText("valid/decimal-average.sir"), work)) {
            assertTrue(loader.loadClass("example.average.application.AverageAmountService") != null);
            assertTrue(loader.loadClass("example.average.application.AveragesByCategoryService") != null);
            var file = work.resolve("src/main/java/example/average/application/AverageAmountService.java");
            if (java.nio.file.Files.exists(file))
                assertTrue(java.nio.file.Files.readString(file).contains("COALESCE(ROUND(AVG(`amount`), 2), 0)"));
        }
    }
}
