package io.kcg.sir.change;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Architecture gate for the sir-change module. The planner must be a pure
 * read-only function that locates the target capability by
 * {@code SymbolId}/{@code AstNodeId} triple only 鈥?never by text name,
 * scope lookup, SourceSpan adjacency, or identity-string parsing. It must
 * not touch disk (no {@code java.nio.file} usage), must not parse
 * {@code sir://} / {@code lir://} identity strings from raw text, and
 * must not call {@code SymbolTable.byName} / {@code lookupInScope}.
 */
class ChangePlannerArchitectureTest {

    @Test
    void noNameLookupOrIdentityStringParsingInMainSources() throws Exception {
        Path mainSrc = Path.of("src/main/java");
        if (!Files.exists(mainSrc)) {
            mainSrc = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        }
        assertTrue(Files.exists(mainSrc),
                "sir-change main source root must exist: " + mainSrc.toAbsolutePath());

        try (Stream<Path> walk = Files.walk(mainSrc)) {
            java.util.List<Path> javas = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            assertFalse(javas.isEmpty(), "expected sir-change .java sources");
            for (Path javaFile : javas) {
                String content = Files.readString(javaFile);
                // Forbidden: name-based symbol lookup.
                assertFalse(content.contains(".byName("),
                        "sir-change must not call SymbolTable.byName: " + javaFile);
                assertFalse(content.contains(".lookupInScope("),
                        "sir-change must not call SymbolTable.lookupInScope: " + javaFile);
                // Forbidden: fabricate sir:// or lir:// identity strings.
                assertFalse(content.contains("\"sir://"),
                        "sir-change must not fabricate sir:// SymbolId strings: " + javaFile);
                assertFalse(content.contains("\"lir://"),
                        "sir-change must not fabricate lir:// LoweredNodeId strings: " + javaFile);
                // Forbidden: java.nio.file disk access (planner is pure read-only).
                assertFalse(content.contains("java.nio.file"),
                        "sir-change main must not use java.nio.file: " + javaFile);
                // Forbidden: parsing identity strings from text.
                assertFalse(content.contains("SymbolId.parse"),
                        "sir-change must not parse SymbolId strings: " + javaFile);
                assertFalse(content.contains("LoweredNodeId.parse"),
                        "sir-change must not parse LoweredNodeId strings: " + javaFile);
            }
        }
    }

    @Test
    void noSpringBootLoweringOrGeneratorOrApplicationDependencyInMainSources() throws Exception {
        // The sir-change module must depend only on semantic, lowering-api
        // (public types), and project-graph. It must NOT depend on the
        // Spring Boot lowering implementation, the generator, or the
        // toolchain application. This is verified by scanning main sources
        // for forbidden import statements.
        Path mainSrc = Path.of("src/main/java");
        if (!Files.exists(mainSrc)) {
            mainSrc = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        }
        assertTrue(Files.exists(mainSrc),
                "sir-change main source root must exist: " + mainSrc.toAbsolutePath());

        try (Stream<Path> walk = Files.walk(mainSrc)) {
            java.util.List<Path> javas = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            for (Path javaFile : javas) {
                String content = Files.readString(javaFile);
                assertFalse(content.contains("io.kcg.sir.lowering.spring"),
                        "sir-change must not import spring boot lowering impl: " + javaFile);
                assertFalse(content.contains("io.kcg.sir.generator"),
                        "sir-change must not import generator: " + javaFile);
                assertFalse(content.contains("io.kcg.sir.application"),
                        "sir-change must not import toolchain application: " + javaFile);
            }
        }
    }

    @Test
    void noSourceSpanAdjacencyFallbackInTargetResolution() throws Exception {
        // The TARGET stage must use the ChangeTarget triple
        // (declarationSymbol, declarationNodeId, targetNodeId) only.
        // It must NOT use SourceSpan adjacency / line-column proximity
        // as a fallback. We forbid imports of SourcePosition (the only
        // position-bearing type besides SourceSpan) in the internal
        // planner core to ensure position-based matching is impossible.
        Path internalDir = Path.of("src/main/java/io/kcg/sir/change/internal");
        if (!Files.exists(internalDir)) {
            internalDir = Path.of(System.getProperty("user.dir"),
                    "src", "main", "java", "io", "kcg", "sir", "change", "internal");
        }
        assertTrue(Files.exists(internalDir),
                "sir-change internal package must exist: " + internalDir.toAbsolutePath());

        try (Stream<Path> walk = Files.walk(internalDir)) {
            java.util.List<Path> javas = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            for (Path javaFile : javas) {
                String content = Files.readString(javaFile);
                // SourcePosition is a position-only type; if the planner
                // does not import it, it cannot do adjacency checks.
                assertFalse(content.contains("import io.kcg.sir.source.SourcePosition"),
                        "internal planner must not use SourcePosition: " + javaFile);
            }
        }
    }

    private static void assertFalse(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertFalse(condition, message);
    }
}
