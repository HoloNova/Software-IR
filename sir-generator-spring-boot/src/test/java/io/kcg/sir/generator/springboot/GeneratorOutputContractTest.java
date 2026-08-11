package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratorOutputContractTest {

    private static final List<String> CAMPUS_MARKET_PATHS = List.of(
            "pom.xml",
            "src/main/java/com/example/campusmarket/Application.java",
            "src/main/java/com/example/campusmarket/domain/GoodsStatus.java",
            "src/main/java/com/example/campusmarket/domain/User.java",
            "src/main/java/com/example/campusmarket/persistence/UserMapper.java",
            "src/main/java/com/example/campusmarket/domain/Goods.java",
            "src/main/java/com/example/campusmarket/persistence/GoodsMapper.java",
            "src/main/java/com/example/campusmarket/api/PublishGoodsInput.java",
            "src/main/java/com/example/campusmarket/api/InvalidGoodsPriceException.java",
            "src/main/java/com/example/campusmarket/application/PublishGoodsService.java",
            "src/main/java/com/example/campusmarket/api/PublishGoodsController.java");

    private static final List<String> UNIT_OUTPUT_PATHS = List.of(
            "pom.xml",
            "src/main/java/example/unitoutput/Application.java",
            "src/main/java/example/unitoutput/application/PingService.java",
            "src/main/java/example/unitoutput/api/PingController.java");

    private static final List<String> COMPOUND_FIND_PATHS = List.of(
            "pom.xml",
            "src/main/java/example/compoundfind/Application.java",
            "src/main/java/example/compoundfind/domain/State.java",
            "src/main/java/example/compoundfind/domain/User.java",
            "src/main/java/example/compoundfind/persistence/UserMapper.java",
            "src/main/java/example/compoundfind/api/FilterInput.java",
            "src/main/java/example/compoundfind/application/FilterUsersService.java",
            "src/main/java/example/compoundfind/api/FilterUsersController.java");

    @Test
    void campusMarketGeneratesTheCompleteOrderedFileContract() {
        List<GeneratedFile> files = generate("valid/campus-market.sir");

        assertPathContract(CAMPUS_MARKET_PATHS, files);
        assertEquals(files.size(), new LinkedHashSet<>(paths(files)).size(),
                "generated paths must be unique");
    }

    @Test
    void unitOutputGeneratesItsMinimalFileShape() {
        assertPathContract(UNIT_OUTPUT_PATHS, generate("valid/unit-output.sir"));
    }

    @Test
    void compoundFindGeneratesItsMinimalFileShape() {
        assertPathContract(COMPOUND_FIND_PATHS, generate("valid/compound-find.sir"));
    }

    @Test
    void campusMarketFilesCarryContentAndOwnershipMetadata() {
        List<GeneratedFile> files = generate("valid/campus-market.sir");

        for (GeneratedFile file : files) {
            assertFalse(file.relativePath().isBlank(), "generated path must not be blank");
            assertFalse(file.content().isBlank(), () -> "generated content must not be blank: " + file.relativePath());
            assertTrue(file.content().endsWith("\n"),
                    () -> "generated content must end with LF: " + file.relativePath());
        }

        assertTrue(files.get(0).symbolId().isEmpty(), "pom.xml is a project artifact");
        assertTrue(files.get(1).symbolId().isEmpty(), "Application.java is a project artifact");
        assertTrue(files.subList(2, files.size()).stream().allMatch(file -> file.symbolId().isPresent()),
                "declaration-owned artifacts must retain their SymbolId");
        assertEquals(files.size(), files.stream().map(GeneratedFile::artifactId).distinct().count(),
                "every generated file must retain a unique LoweredNodeId");
    }

    @Test
    void canonicalFixturesGenerateIdenticallyWhenTheSameModelIsRepeated() {
        for (String resource : List.of(
                "valid/campus-market.sir",
                "valid/unit-output.sir",
                "valid/compound-find.sir")) {
            SpringBootLoweredModel model = GeneratorTestSupport.lowerSuccess(resource);

            List<GeneratedFile> first = generate(model);
            List<GeneratedFile> second = generate(model);

            assertEquals(first, second, () -> "generator output changed between runs for " + resource);
        }
    }

    private static void assertPathContract(List<String> expected, List<GeneratedFile> files) {
        List<String> actual = paths(files);
        assertEquals(expected, actual, () -> pathDifference(expected, actual));
    }

    private static List<String> paths(List<GeneratedFile> files) {
        return files.stream().map(GeneratedFile::relativePath).toList();
    }

    private static String pathDifference(List<String> expected, List<String> actual) {
        LinkedHashSet<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);
        LinkedHashSet<String> unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(expected);
        return "generated file contract changed; missing=" + missing
                + ", unexpected=" + unexpected
                + ", expectedOrder=" + expected
                + ", actualOrder=" + actual;
    }

    private static List<GeneratedFile> generate(String resource) {
        return GeneratorTestSupport.generateSuccess(resource);
    }

    private static List<GeneratedFile> generate(SpringBootLoweredModel model) {
        return GeneratorTestSupport.generateSuccess(model);
    }
}
