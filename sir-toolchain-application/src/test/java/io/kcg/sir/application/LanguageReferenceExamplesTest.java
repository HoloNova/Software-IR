package io.kcg.sir.application;

import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.semantic.model.NormalizedCapability;
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
        sir 0.3

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

    @Test void sumExampleReallyAggregatesOnlyTheActorsRowsWithTheExactPlan() throws Exception {
        var examples = examples().stream().filter(e -> e.body().contains("capability TotalOfMine {")).toList();
        assertEquals(1, examples.size(), "the accepted SUM example must remain in the executable reference corpus");
        var example = examples.getFirst();
        assertTrue(List.of("sir", "sir-declarations").contains(example.kind()), example.label());
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var compiled = ProjectCompilation.compile(ProjectTestSupport.oneFile("reference.sir", example.document()),
                new SpringBootGenerator()::generate, diagnostics);
        assertTrue(compiled.isPresent(), () -> example.label() + ": " + diagnostics);
        var model = compiled.orElseThrow();
        var source = model.semanticModel().declarations().stream().filter(NormalizedCapability.class::isInstance)
                .map(NormalizedCapability.class::cast).filter(c -> c.name().equals("TotalOfMine")).findFirst().orElseThrow();
        var capability = model.loweredModel().declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(c -> c.sourceSymbol().equals(source.id())).findFirst().orElseThrow();
        var sums = capability.workflow().steps().stream().filter(SpringBootWorkflow.SumStep.class::isInstance)
                .map(SpringBootWorkflow.SumStep.class::cast).toList();
        assertEquals(1, sums.size());
        var sum = sums.getFirst();
        assertEquals(SpringBootDeclaration.TransactionMode.READ_ONLY, capability.transactionMode());
        assertEquals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.BIG_DECIMAL), sum.result().type());
        assertEquals("selectObjs", sum.plan().mapperMethod());
        assertEquals(1, sum.plan().statements());
        assertTrue(sum.plan().emptyAsZero());
        assertTrue(sum.plan().rejectWarnings());
        var entity = model.loweredModel().declarations().stream().filter(SpringBootDeclaration.EntityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.EntityDeclaration.class::cast)
                .filter(e -> e.sourceSymbol().equals(sum.entitySymbol())).findFirst().orElseThrow();
        var owner = entity.fields().stream().filter(f -> f.javaName().equals("ownerId")).findFirst().orElseThrow();
        var equality = assertInstanceOf(SpringExpression.BinaryExpression.class, sum.predicate());
        assertEquals(SpringExpression.BinaryOperator.EQ, equality.operator());
        var member = assertInstanceOf(SpringExpression.MemberExpression.class, equality.left());
        assertEquals(owner.sourceSymbol(), member.resolvedMember());
        assertEquals(sum.itemVariable().symbol(), assertInstanceOf(SpringExpression.NameExpression.class, member.receiver()).resolvedSymbol());
        assertEquals(source.actorSymbol().orElseThrow(), assertInstanceOf(SpringExpression.NameExpression.class, equality.right()).resolvedSymbol());
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
