package io.kcg.sir.application;

import io.kcg.sir.application.api.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Keeps docs/SIR_LANGUAGE_REFERENCE.md honest: every example in it is a real program. {@code sir} blocks are whole
 * documents and must pass through generation; {@code sir-declarations} blocks are the inside of {@code declarations}
 * and must pass the same way; {@code sir-error CODE} blocks are declarations that must be rejected with that code.
 */
class LanguageReferenceExamplesTest {
    static final String SHELL_HEAD = """
        sir 0.2

        software Reference {
          metadata {
            displayName "Reference";
            namespace "tests.reference";
          }
          target {
            language java 21;
            framework spring_boot;
            persistence mybatis_plus;
            database mysql;
            build maven;
            interface rest;
          }
          sources { }
          imports { }
          declarations {
        """;
    static final String SHELL_TAIL = "  }\n}\n";

    record Example(String kind, String expectedCode, int line, String body) {
        String document() { return kind.equals("sir") || body.startsWith("sir ") ? body : SHELL_HEAD + body + SHELL_TAIL; }
        String label() { return kind + (expectedCode == null ? "" : " " + expectedCode) + " @line " + line; }
    }

    final SirValidationApplication app = new SirValidationApplication();

    static Path reference() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/SIR_LANGUAGE_REFERENCE.md");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new AssertionError("docs/SIR_LANGUAGE_REFERENCE.md not found above " + System.getProperty("user.dir"));
    }

    static List<Example> examples() throws Exception {
        List<String> lines = Files.readAllLines(reference(), StandardCharsets.UTF_8);
        var result = new ArrayList<Example>();
        for (int i = 0; i < lines.size(); i++) {
            String open = lines.get(i);
            if (!open.startsWith("```sir")) continue;
            String[] info = open.substring(3).trim().split("\\s+");
            var body = new StringBuilder();
            int start = i + 1;
            for (i++; i < lines.size() && !lines.get(i).equals("```"); i++) body.append(lines.get(i)).append('\n');
            assertTrue(i < lines.size(), "unterminated block opened at line " + (start));
            String kind = info[0];
            assertTrue(List.of("sir", "sir-declarations", "sir-error").contains(kind), "unknown block kind '" + kind + "' at line " + start);
            assertEquals(kind.equals("sir-error") ? 2 : 1, info.length, "block info at line " + start + ": " + open);
            result.add(new Example(kind, info.length > 1 ? info[1] : null, start, body.toString()));
        }
        return result;
    }

    @Test void referenceHasExamplesOfEveryKind() throws Exception {
        var all = examples();
        for (String kind : List.of("sir", "sir-declarations", "sir-error")) {
            assertTrue(all.stream().anyMatch(e -> e.kind().equals(kind)), "no '" + kind + "' example in the reference");
        }
    }

    @Test void everyAcceptedExampleCompilesThroughGeneration() throws Exception {
        var failures = new ArrayList<String>();
        for (var example : examples()) {
            if (example.kind().equals("sir-error")) continue;
            var result = app.check(new SirValidationRequest("reference-" + example.line(), example.document()));
            if (!result.ok() || result.stage() != ValidationStopAfter.GENERATION) failures.add(example.label() + ": " + result.stage() + " " + describe(result));
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    @Test void everyRejectedExampleIsRejectedWithItsDeclaredCode() throws Exception {
        var failures = new ArrayList<String>();
        for (var example : examples()) {
            if (!example.kind().equals("sir-error")) continue;
            var result = app.check(new SirValidationRequest("reference-" + example.line(), example.document()));
            if (result.ok()) failures.add(example.label() + ": was accepted");
            else if (result.diagnostics().stream().noneMatch(d -> d.code().equals(example.expectedCode()))) {
                failures.add(example.label() + ": rejected without that code: " + describe(result));
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    static String describe(SirValidationResult result) {
        var out = new StringBuilder();
        for (var d : result.diagnostics()) {
            out.append("\n    ").append(d.code()).append(' ').append(d.message());
            if (d.span() != null) out.append(" @").append(d.span().start().line()).append(':').append(d.span().start().column());
        }
        return out.toString();
    }
}
