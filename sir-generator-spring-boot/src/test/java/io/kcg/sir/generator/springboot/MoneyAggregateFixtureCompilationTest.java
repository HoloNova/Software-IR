package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MoneyAggregateFixtureCompilationTest {
    @Test void theActualMoneyItAndItsIsolatedWarningProbeCompileTogether(@TempDir Path output) throws Exception {
        Path resources = Path.of(System.getProperty("basedir", ".")).resolveSibling("sir-toolchain-application").resolve("src/test/resources");
        String source = Files.readString(resources.resolve("valid/money.sir"));
        String probe = Files.readString(resources.resolve("conformance/mysql/ExactAggregateWarningFixture.java.txt"));
        var file = output.resolve("ExactAggregateWarningFixture.java");
        Files.writeString(file, probe);
        try (var loader = GeneratedRuntimeSupport.compile(source, output, List.of(file))) {
            assertNotNull(loader.loadClass("example.money.fixture.ExactAggregateWarningFixture"));
            assertNotNull(loader.loadClass("example.money.application.SumMeasuredService"));
            assertNotNull(loader.loadClass("example.money.application.TaggedSumService"));
            assertNotNull(loader.loadClass("example.money.application.LiteralSumService"));
        }
        assertFalse(GeneratorTestSupport.generateSuccessFromText(source).stream().anyMatch(f -> f.relativePath().contains("WarningFixture")),
                "the fixed SQL warning probe is not a generator output");
    }
}
