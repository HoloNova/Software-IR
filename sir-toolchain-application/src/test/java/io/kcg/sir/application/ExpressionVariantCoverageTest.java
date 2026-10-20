package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every place that dispatches over an expression must say what it does with every kind of expression.
 *
 * <p>The expression hierarchies are sealed, but most of the code that walks them switches with a
 * {@code default}, so a new kind of expression compiles everywhere and is then silently skipped by
 * whichever walker nobody remembered (a validator that never looks inside it, an import collector that
 * never sees what it uses). This test reads the sources and requires each walker to mention each kind,
 * unless the walker is listed here as deliberately not handling it, with the reason.
 *
 * <p>The list is checked in both directions: a kind a walker is listed as ignoring must really be
 * absent from it, so the list cannot go stale and quietly excuse a walker that has since started
 * handling the kind. Mentioning a kind in a file is a coarse test, not proof the right branch is
 * right; it catches the omission, which is the failure that cannot be seen by compiling.
 */
class ExpressionVariantCoverageTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();

    private static final String AST = "sir-parser/src/main/java/io/kcg/sir/ast/AstExpression.java";
    private static final String NORMALIZED = "sir-semantic/src/main/java/io/kcg/sir/semantic/model/NormalizedExpression.java";
    private static final String SPRING = "sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/model/SpringExpression.java";

    private static final String SEMANTIC = "sir-semantic/src/main/java/io/kcg/sir/semantic/";
    private static final String LOWERING = "sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/";
    private static final String GENERATOR = "sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/";

    /** Leaves of the source syntax: they hold no sub-expression and name nothing to resolve or check. */
    private static final Set<String> SYNTAX_LEAVES = Set.of(
            "AstBooleanLiteral", "AstIntegerLiteral", "AstDecimalLiteral", "AstStringLiteral", "AstUnitLiteral", "AstNowExpression");
    /** Leaves of the lowered expression: literals and the clock. */
    private static final Set<String> LOWERED_LEAVES = Set.of(
            "BooleanLiteral", "IntegerLiteral", "DecimalLiteral", "StringLiteral", "UnitLiteral", "NowExpression");

    @Test
    void everyWalkerOfSourceExpressionsMentionsEveryKind() throws IOException {
        Set<String> kinds = permits(AST);
        List<String> problems = new ArrayList<>();

        check(problems, kinds, SEMANTIC + "internal/ResolvePass.java", SYNTAX_LEAVES);
        check(problems, kinds, SEMANTIC + "internal/TypePass.java", Set.of());
        check(problems, kinds, SEMANTIC + "internal/NormalizePass.java", Set.of());
        // the validator only walks for presence tests and existence predicates, which leaves can contain neither of; it
        // names AstStringLiteral all the same, as the literal path of an expose clause
        check(problems, kinds, SEMANTIC + "internal/ValidatePass.java",
                Set.of("AstBooleanLiteral", "AstUnitLiteral", "AstNowExpression"));
        check(problems, kinds, "sir-parser/src/main/java/io/kcg/sir/internal/SirAstBuilder.java", Set.of());

        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void everyWalkerOfNormalizedExpressionsMentionsEveryKind() throws IOException {
        Set<String> kinds = permits(NORMALIZED);
        List<String> problems = new ArrayList<>();

        check(problems, kinds, SEMANTIC + "source/SirDeclarationWriter.java", Set.of());
        // does not descend into a presence test or look at the two leaves that carry no symbol
        check(problems, kinds, LOWERING + "SpringBootInputValidator.java", Set.of("UnitLiteral", "NowExpression", "PresentExpression"));

        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void everyWalkerOfLoweredExpressionsMentionsEveryKind() throws IOException {
        Set<String> kinds = permits(SPRING);
        List<String> problems = new ArrayList<>();

        check(problems, kinds, LOWERING + "SpringBootIrValidation.java", LOWERED_LEAVES);
        // an existence predicate is rendered as a query condition by the workflow renderer, never as a Java value
        check(problems, kinds, GENERATOR + "ExpressionRenderer.java", Set.of("ExistsPredicate"));
        // collects imports: the leaves and the presence test name nothing to import. A boolean literal is mentioned all the
        // same, because "where true" is rendered as a query condition of its own.
        Set<String> collectorLeaves = new LinkedHashSet<>(LOWERED_LEAVES);
        collectorLeaves.add("PayloadPresence");
        collectorLeaves.remove("BooleanLiteral");
        check(problems, kinds, GENERATOR + "WorkflowRenderer.java", collectorLeaves);

        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void theHierarchiesTheseChecksReadAreNotEmptyAndIncludeTheNewestKinds() throws IOException {
        assertTrue(permits(AST).contains("AstTimeShiftExpression"));
        assertTrue(permits(NORMALIZED).contains("TimeShiftExpression"));
        assertTrue(permits(SPRING).contains("TimeShiftExpression"));
        assertFalse(permits(AST).isEmpty() || permits(NORMALIZED).isEmpty() || permits(SPRING).isEmpty());
    }

    private static void check(List<String> problems, Set<String> kinds, String file, Set<String> ignored) throws IOException {
        String source = read(file);
        for (String kind : kinds) {
            boolean mentioned = Pattern.compile("\\b" + Pattern.quote(kind) + "\\b").matcher(source).find();
            if (ignored.contains(kind) && mentioned) {
                problems.add(file + " is listed as ignoring " + kind + " but now mentions it: update the list in this test");
            } else if (!ignored.contains(kind) && !mentioned) {
                problems.add(file + " never mentions " + kind + ": handle it, or list it here with the reason it needs no handling");
            }
        }
    }

    /** The kinds a sealed expression interface permits, as simple names. */
    private static Set<String> permits(String file) throws IOException {
        Matcher matcher = Pattern.compile("permits(.*?)\\{", Pattern.DOTALL).matcher(read(file));
        assertTrue(matcher.find(), "no permits clause in " + file);
        Set<String> kinds = new LinkedHashSet<>();
        for (String part : matcher.group(1).split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                kinds.add(name.substring(name.lastIndexOf('.') + 1));
            }
        }
        return kinds;
    }

    private static String read(String file) throws IOException {
        Path path = ROOT.resolve(file);
        assertTrue(Files.isRegularFile(path), "expected source file is missing (moved or renamed?): " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
