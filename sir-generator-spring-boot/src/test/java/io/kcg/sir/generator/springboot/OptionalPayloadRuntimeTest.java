package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compiles the actual emitted DTO: an Optional getter must not be replaced by a boxed-value stub. */
class OptionalPayloadRuntimeTest {
    @TempDir Path directory;

    @Test
    void sqlParametersFromTheActualPayloadAreNullOrScalarsNotOptionalObjects() throws Exception {
        var files = GeneratorTestSupport.generateSuccess("valid/optional-payload.sir");
        String service = GeneratorTestSupport.contentEndingWith(files, "/SearchService.java");
        String argument = trailingArgument(service, ".apply(\"COALESCE(score >= {0}, FALSE)\", ");
        assertBoundValue(GeneratorTestSupport.contentEndingWith(files, "/Filter.java"), argument);
    }

    @Test
    void correlatedArgumentsAlsoBindNullableValuesFromTheActualPayload() throws Exception {
        var files = GeneratorTestSupport.generateSuccess("valid/optional-payload.sir");
        String service = GeneratorTestSupport.contentEndingWith(files, "/SearchService.java");
        String call = service.lines().filter(line -> line.contains("EXISTS (SELECT 1 FROM link ")).findFirst().orElseThrow();
        String prefix = call.substring(0, call.indexOf("\", ") + 3);
        assertBoundValue(GeneratorTestSupport.contentEndingWith(files, "/Filter.java"), trailingArgument(call, prefix));
    }

    private void assertBoundValue(String payload, String argument) throws Exception {
        try (var loader = compile(payload, "public static Object parameter(Filter input) { return " + argument + "; }")) {
            var type = loader.loadClass("example.optionalpayload.api.Filter");
            var input = type.getConstructor().newInstance();
            var parameter = loader.loadClass("Probe").getMethod("parameter", type);
            assertNull(parameter.invoke(null, input), "an omitted request field must bind SQL NULL, never Optional.empty()");
            type.getMethod("setMinimum", Optional.class).invoke(input, Optional.of(1001L));
            assertEquals(1001L, parameter.invoke(null, input));
            type.getMethod("setMinimum", Optional.class).invoke(input, new Object[] {null});
            assertNull(parameter.invoke(null, input));
        }
    }

    @Test
    void missingAndPresentRequestsFollowTheTruthTableWithTheActualPayload() throws Exception {
        var files = GeneratorTestSupport.generateSuccess("valid/optional-payload.sir");
        String service = GeneratorTestSupport.contentEndingWith(files, "/CheckService.java");
        String line = service.lines().filter(s -> s.contains("if (!(" )).findFirst().orElseThrow().strip();
        String expression = line.substring("if (!(".length(), line.length() - ")) {".length()).replace("row.getScore()", "score");
        try (var loader = compile(GeneratorTestSupport.contentEndingWith(files, "/Filter.java"),
                "public static boolean accepts(Long score, Filter input) { return " + expression + "; }")) {
            var type = loader.loadClass("example.optionalpayload.api.Filter");
            var input = type.getConstructor().newInstance();
            var accepts = loader.loadClass("Probe").getMethod("accepts", Long.class, type);
            assertEquals(true, accepts.invoke(null, null, input));
            type.getMethod("setMinimum", Optional.class).invoke(input, Optional.of(1000L));
            assertEquals(false, accepts.invoke(null, null, input));
            assertEquals(false, accepts.invoke(null, 999L, input));
            assertEquals(true, accepts.invoke(null, 1000L, input));
            assertEquals(true, accepts.invoke(null, 1001L, input));
            type.getMethod("setMinimum", Optional.class).invoke(input, new Object[] {null});
            assertEquals(true, accepts.invoke(null, null, input));
        }
    }

    private URLClassLoader compile(String payload, String methods) throws Exception {
        Path dto = directory.resolve("Filter.java"), probe = directory.resolve("Probe.java");
        Files.writeString(dto, payload);
        Files.writeString(probe, "import example.optionalpayload.api.Filter; public class Probe {" + methods + "}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", directory.toString(), dto.toString(), probe.toString()));
        return new URLClassLoader(new java.net.URL[] {directory.toUri().toURL()});
    }

    private static String trailingArgument(String text, String prefix) {
        int start = text.indexOf(prefix);
        assertTrue(start >= 0, text);
        start += prefix.length();
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ',' && depth == 0) return text.substring(start, i);
            if (c == '(') depth++;
            if (c == ')' && depth-- == 0) return text.substring(start, i);
        }
        throw new AssertionError("unclosed SQL parameter expression");
    }
}
