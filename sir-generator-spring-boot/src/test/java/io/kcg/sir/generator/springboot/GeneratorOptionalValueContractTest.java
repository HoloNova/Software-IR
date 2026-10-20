package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GeneratorOptionalValueContractTest {
    private final List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/optional-values.sir");

    @Test
    void queriesAndGuardsCollapseUnknownToFalseBeforeBooleanNegation() {
        String query = service("Search");
        assertTrue(query.contains("COALESCE(score >= {0}, FALSE)"), query);
        assertTrue(query.contains("COALESCE(score = {0}, FALSE)"), query);
        assertTrue(query.contains(".not("), query);
        assertTrue(query.contains(".isNotNull(Entry::getDueAt)"), query);
        assertTrue(query.contains("input.getMinimum()") && query.contains("\"1 = 1\"") && query.contains("\"1 = 0\""), query);
        String guard = service("SetScore");
        assertTrue(guard.contains(".isNull(Entry::getScore)"), guard);
        assertTrue(guard.contains("COALESCE(score < {0}, FALSE)"), guard);
    }

    @Test
    void emittedJavaComparisonIsExecutableAndMissingIsFalse(@TempDir Path dir) throws Exception {
        String service = service("Check");
        String line = service.lines().filter(text -> text.contains("if (!(" )).findFirst().orElseThrow().strip();
        String expression = line.substring("if (!(".length(), line.length() - ")) {".length())
                .replace("row.getScore()", "score").replace("input.getMinimum()", "minimum");
        Path source = dir.resolve("Probe.java");
        Files.writeString(source, "public class Probe { public static boolean test(Integer score, Integer minimum) { return " + expression + "; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), source.toString()));
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { dir.toUri().toURL() })) {
            var test = loader.loadClass("Probe").getMethod("test", Integer.class, Integer.class);
            assertEquals(false, test.invoke(null, new Object[] { null, null }));
            assertEquals(false, test.invoke(null, null, 2));
            assertEquals(false, test.invoke(null, 2, null));
            assertEquals(false, test.invoke(null, 1000, 1000));
            assertEquals(true, test.invoke(null, 1000, 1001));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"==", "!=", "<", "<=", ">", ">=", "not-equal", "none", "present", "or", "and"})
    void emittedJavaMatchesTheTwoValuedTruthTable(String operator, @TempDir Path dir) throws Exception {
        String condition = switch (operator) {
            case "not-equal" -> "not (row.score == input.minimum)";
            case "none" -> "row.score is none";
            case "present" -> "row.score is present";
            case "or" -> "input.minimum is none or row.score >= input.minimum";
            case "and" -> "row.score is present and row.score >= input.minimum";
            default -> "row.score " + operator + " input.minimum";
        };
        String sourceText = GeneratorTestSupport.resourceText("valid/optional-values.sir")
                .replace("row.score != input.minimum", condition);
        String service = GeneratorTestSupport.contentEndingWith(GeneratorTestSupport.generateSuccessFromText(sourceText), "/CheckService.java");
        String line = service.lines().filter(text -> text.contains("if (!(" )).findFirst().orElseThrow().strip();
        String expression = line.substring("if (!(".length(), line.length() - ")) {".length())
                .replace("row.getScore()", "score").replace("input.getMinimum()", "minimum");
        Path source = dir.resolve("Probe.java");
        Files.writeString(source, "public class Probe { public static boolean test(Integer score, Integer minimum) { return " + expression + "; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), source.toString()));
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { dir.toUri().toURL() })) {
            var method = loader.loadClass("Probe").getMethod("test", Integer.class, Integer.class);
            for (Integer left : new Integer[] { null, 1000, 1001 }) {
                for (Integer right : new Integer[] { null, 1000, 1001 }) {
                    boolean both = left != null && right != null;
                    boolean expected = switch (operator) {
                        case "==" -> both && left.intValue() == right.intValue();
                        case "!=" -> both && left.intValue() != right.intValue();
                        case "<" -> both && left < right;
                        case "<=" -> both && left <= right;
                        case ">" -> both && left > right;
                        case ">=" -> both && left >= right;
                        case "not-equal" -> !(both && left.intValue() == right.intValue());
                        case "none" -> left == null;
                        case "present" -> left != null;
                        case "or" -> right == null || (both && left >= right);
                        case "and" -> both && left >= right;
                        default -> throw new AssertionError(operator);
                    };
                    assertEquals(expected, method.invoke(null, left, right), operator + ": " + left + ", " + right);
                }
            }
        }
    }

    private String service(String capability) {
        return files.stream().filter(file -> file.relativePath().endsWith("/" + capability + "Service.java"))
                .findFirst().orElseThrow().content();
    }
}
