package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Architecture-level guard for the typed reference-site contract (ADR-005).
 *
 * <p>Scans the source of {@code TypePass}, {@code ValidatePass} and {@code NormalizePass} to ensure
 * they no longer perform name/scope lookup or {@code SymbolId.value()} path parsing. Only
 * {@code ResolvePass} is permitted to resolve names by text, and it is the sole stage that may
 * contain a {@code lookupInScope} definition or call.
 *
 * <p>This guard prevents regressions that would silently reintroduce "resolve-by-name" semantics
 * in later passes, which would break the single-resolver invariant that downstream PSG
 * {@code REFERENCES} edges depend on.
 */
class ResolveOnceArchitectureTest {

    private static final Path SEMANTIC_INTERNAL_DIR = resolveSemanticInternalDir();

    private static final List<String> TIGHTENED_PASSES = List.of(
            "TypePass.java",
            "ValidatePass.java",
            "NormalizePass.java");

    /**
     * Matches {@code .value()} chained immediately with {@code .contains(}, {@code .indexOf(} or
     * {@code .substring(}, allowing optional whitespace between the dots and parens. This is the
     * canonical "SymbolId value parsed as path" anti-pattern.
     */
    private static final Pattern SYMBOLID_VALUE_PATH_PARSING = Pattern.compile(
            "\\.value\\(\\)\\s*\\.\\s*(contains|indexOf|substring)\\s*\\(");

    /**
     * Matches a {@code lookupInScope} method definition, e.g. {@code Symbol lookupInScope(...)}.
     * Used to confirm that ResolvePass owns the sole scope-lookup definition.
     */
    private static final Pattern LOOKUP_IN_SCOPE_DEFINITION = Pattern.compile(
            "\\b\\w+\\s+lookupInScope\\s*\\(");

    private static Path resolveSemanticInternalDir() {
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
        return basedir.resolve("src/main/java/io/kcg/sir/semantic/internal");
    }

    private static String readSource(String fileName) throws IOException {
        Path path = SEMANTIC_INTERNAL_DIR.resolve(fileName);
        assertTrue(Files.exists(path),
                "Missing source file: " + path + " (working dir=" + Path.of(".").toAbsolutePath() + ")");
        return Files.readString(path);
    }

    private static long countOccurrences(String source, String substring) {
        long count = 0;
        int from = 0;
        while (true) {
            int idx = source.indexOf(substring, from);
            if (idx < 0) break;
            count++;
            from = idx + substring.length();
        }
        return count;
    }

    @Test
    void tightenedPassesDoNotCallByNameOnSymbolTable() throws IOException {
        for (String file : TIGHTENED_PASSES) {
            String source = readSource(file);
            long hits = countOccurrences(source, ".byName(");
            assertEqualsWithExplanation(0, hits, file,
                    ".byName(", "must not call SymbolTable.byName(...) 鈥?ResolvePass is the only stage permitted to resolve by name");
        }
    }

    @Test
    void tightenedPassesDoNotCallLookupInScope() throws IOException {
        for (String file : TIGHTENED_PASSES) {
            String source = readSource(file);
            long hits = countOccurrences(source, ".lookupInScope(");
            assertEqualsWithExplanation(0, hits, file,
                    ".lookupInScope(", "must not call lookupInScope(...) 鈥?ResolvePass is the only stage permitted to do scope lookup");
        }
    }

    @Test
    void tightenedPassesDoNotParseSymbolIdValueAsPath() throws IOException {
        for (String file : TIGHTENED_PASSES) {
            String source = readSource(file);
            Matcher matcher = SYMBOLID_VALUE_PATH_PARSING.matcher(source);
            assertFalse(matcher.find(),
                    file + " must not parse SymbolId.value() as a path "
                            + "(.value().contains/.indexOf/.substring are forbidden). "
                            + "Use SymbolTable.byId(SymbolId) and typed ReferenceSiteBindings instead.");
        }
    }

    @Test
    void tightenedPassesContainNoLookupInScopeInAnyForm() throws IOException {
        for (String file : TIGHTENED_PASSES) {
            String source = readSource(file);
            long anyForm = countOccurrences(source, "lookupInScope(");
            assertEqualsWithExplanation(0, anyForm, file,
                    "lookupInScope(", "only ResolvePass may contain lookupInScope (definition or call)");
        }
    }

    @Test
    void resolvePassIsTheSoleScopeLookupStage() throws IOException {
        String resolveSource = readSource("ResolvePass.java");
        Matcher defMatcher = LOOKUP_IN_SCOPE_DEFINITION.matcher(resolveSource);
        assertTrue(defMatcher.find(),
                "ResolvePass must define lookupInScope 鈥?it is the sole stage permitted to do scope lookup");

        for (String file : TIGHTENED_PASSES) {
            String source = readSource(file);
            long anyForm = countOccurrences(source, "lookupInScope(");
            assertEqualsWithExplanation(0, anyForm, file,
                    "lookupInScope(", "only ResolvePass may contain lookupInScope (definition or call)");
        }
    }

    @Test
    void resolvePassDoesNotParseSymbolIdValueAsPath() throws IOException {
        String source = readSource("ResolvePass.java");
        Matcher matcher = SYMBOLID_VALUE_PATH_PARSING.matcher(source);
        assertFalse(matcher.find(),
                "ResolvePass must not parse SymbolId.value() as a path "
                        + "(.value().contains/.indexOf/.substring are forbidden). "
                        + "SymbolId must be treated as an opaque identifier.");
    }

    @Test
    void tightenedPassesDoNotCallByNameEvenViaSymbolsAccessor() throws IOException {
        for (String file : TIGHTENED_PASSES) {
            String source = readSource(file);
            assertFalse(source.contains("symbols().byName("),
                    file + " must not call symbols().byName(...) 鈥?use declarationBindings + byId instead");
            assertFalse(source.contains("symbols.byName("),
                    file + " must not call symbols.byName(...) 鈥?use declarationBindings + byId instead");
        }
    }

    private static void assertEqualsWithExplanation(long expected, long actual, String file,
                                                    String pattern, String explanation) {
        if (expected != actual) {
            throw new AssertionError(file + ": expected " + expected + " occurrence(s) of " + pattern
                    + " but found " + actual + ". " + explanation);
        }
    }
}