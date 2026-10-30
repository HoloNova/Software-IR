package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.DiagnosticSeverity;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Ordered, binding-based check-then-insert protection, not a blanket ban on bare unique inserts. */
class PrecheckedUniqueInsertSemanticsTest {
    private static final String DECLARATIONS = """
            entity Entry persistent {
              identity id: Id generated auto;
              field name: Name;
              field bucket: Count;
            }
            entity Other persistent { identity id: Id generated auto; field name: Name; }
            input CommandInput { field id: Id; field name: Name; }
            error AlreadyExists http 409;
            error Missing http 404;
            error TooMany http 409;
            error Unexpected http 409;
            """;
    private static final String CHECK = """
            exists Entry where item.name == input.name as duplicate;
            validate not duplicate else AlreadyExists;
            """;
    private static final String CREATE = "create Entry as made { name: input.name; bucket: 1; }\n";
    private static final String BARE = "persist made;";

    @ParameterizedTest
    @ValueSource(strings = {"field", "composite"})
    void bothKindsOfUniqueConstraintRequireAnExplicitConflictFailureAfterExists(String unique) {
        rejectOnce(source(unique, CHECK + CREATE + BARE), BARE);
    }

    @Test
    void createBeforeExistsStillNeedsProtectionAtTheActualInsert() {
        rejectOnce(source("field", CREATE + CHECK + BARE), BARE);
    }

    @Test
    void anExplicitRowLockDoesNotWaiveTheConflictContract() {
        String workflow = "load Entry by input.id as locked for update else Missing;\n" + CHECK + CREATE + BARE;
        rejectOnce(source("field", workflow), BARE);
    }

    @Test
    void anEarlierPersistCannotHideBehindTheLastPersistOfAnotherEntity() {
        String workflow = CHECK + CREATE + BARE
                + "\ncreate Other as other { name: input.name; } persist other;";
        rejectOnce(source("field", workflow), BARE);
    }

    @Test
    void everyUnprotectedNewRowGetsOneDiagnosticInSourceOrder() {
        String workflow = CHECK + CREATE + BARE
                + "\ncreate Entry as next { name: input.name; bucket: 2; } persist next;";
        String source = source("field", workflow);
        var first = TestSources.analyze(source);
        var second = TestSources.analyze(source);
        assertEquals(List.of("SIR-VALID-004", "SIR-VALID-004"), TestSources.errorCodes(first));
        assertEquals(first.diagnostics(), second.diagnostics());
        assertEquals(List.of(BARE, "persist next;"), first.diagnostics().stream()
                .map(d -> source.substring(source.offsetByCodePoints(0, d.primarySpan().start().codePointOffset()),
                        source.offsetByCodePoints(0, d.primarySpan().end().codePointOffset()))).toList());
    }

    @ParameterizedTest(name = "{0}, inherited check={1}")
    @MethodSource("loops")
    void checksEveryLoopBodyExactlyOnceWithOnlyPrecedingOuterFacts(String kind, boolean inherited) {
        String body = (inherited ? "" : CHECK) + CREATE + BARE;
        String workflow = (inherited ? CHECK : "") + loop(kind, body);
        rejectOnce(source("field", workflow), BARE);
    }

    static Stream<Arguments> loops() {
        return Stream.of(Arguments.of("each", false), Arguments.of("each", true),
                Arguments.of("repeat", false), Arguments.of("repeat", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"field", "composite"})
    void aDeclaredPersistFailureHandlesTheConflict(String unique) {
        assertClean(source(unique, CHECK + CREATE + "persist made else AlreadyExists;"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"field", "composite"})
    void uniqueInsertsWithoutAnyPriorExistsKeepTheirExistingBehavior(String unique) {
        assertClean(source(unique, CREATE + BARE));
    }

    @Test
    void anExistsAfterPersistCannotRetroactivelyRequireElse() {
        assertClean(source("field", CREATE + BARE + "\n" + CHECK));
    }

    @Test
    void countIsNotTreatedAsExists() {
        String count = "count Entry where item.name == input.name as total; validate total == 0 else AlreadyExists;\n";
        assertClean(source("field", count + CREATE + BARE));
    }

    @Test
    void aPagedFindKeepsItsExistingQueryContractErrorRatherThanInventingAnExists() {
        String find = "find Entry where item.name == input.name Page 1, 10 else Missing as rows;\n";
        var result = TestSources.analyze(source("field", find + CREATE + BARE));
        assertEquals(List.of("SIR-VALID-001"), TestSources.errorCodes(result));
        assertTrue(result.diagnostics().getFirst().message().contains("带分页的 find 需要 Page<view> 输出"));
    }

    @Test
    void anExistsOfAnotherEntityDoesNotMatchTheInsertedEntity() {
        String check = "exists Other where item.name == input.name as duplicate; validate not duplicate else AlreadyExists;\n";
        assertClean(source("field", check + CREATE + BARE));
    }

    @Test
    void loadingThenUpdatingAUniqueEntityIsNotANewRowInsert() {
        String workflow = CHECK + "load Entry by input.id as made else Missing;\n"
                + "update made { name: input.name; }\n" + BARE;
        assertClean(source("field", workflow));
    }

    @Test
    void anEntityWithoutUniqueConstraintsIsNotSubjectToTheNewError() {
        assertClean(source("none", CHECK + CREATE + BARE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"each", "repeat"})
    void aLoopLocalCheckDoesNotBecomeAMandatoryOuterFact(String kind) {
        assertClean(source("field", loop(kind, CHECK) + CREATE + BARE));
    }

    @Test
    void aLaterOuterCheckIsNotInheritedByAnEarlierLoop() {
        assertClean(source("field", loop("repeat", CREATE + BARE) + CHECK));
    }

    @Test
    void anExistsInAnotherCapabilityDoesNotLeakIntoTheWriter() {
        String query = """
                capability Probe {
                  input CommandInput; output Flag; requires readonly; expose query;
                  workflow { exists Entry where item.name == input.name as found; return found; }
                }
                """;
        assertClean(TestSources.sir(declarations("field") + query + capability(CREATE + BARE)));
    }

    @Test
    void theFailureMayDifferFromTheEarlierValidateButMustStillBeDeclared() {
        assertClean(source("field", CHECK + CREATE + "persist made else Missing;"));
        var undeclared = TestSources.analyze(source("field", CHECK + CREATE + "persist made else Unexpected;"));
        assertEquals(List.of("SIR-FLOW-003"), TestSources.errorCodes(undeclared));
    }

    @Test
    void codePointSpansRemainCorrectAfterNonBmpText() {
        rejectOnce("// 插入请求 😀\n" + source("field", CHECK + CREATE + BARE), BARE);
    }

    private static String loop(String kind, String body) {
        String head = switch (kind) {
            case "each" -> "each Other where true order by id ascending limit 2 else TooMany as row {\n";
            case "repeat" -> "repeat 1 times limit 2 else TooMany as turn {\n";
            default -> throw new IllegalArgumentException(kind);
        };
        return head + body + "\n}\n";
    }

    private static String source(String unique, String workflow) {
        return TestSources.sir(declarations(unique) + capability(workflow));
    }

    private static String declarations(String unique) {
        return switch (unique) {
            case "field" -> DECLARATIONS.replace("field name: Name;\n  field bucket", "field name: Name unique;\n  field bucket");
            case "composite" -> DECLARATIONS.replace("field bucket: Count;", "field bucket: Count;\n  unique (name, bucket);");
            case "none" -> DECLARATIONS;
            default -> throw new IllegalArgumentException(unique);
        };
    }

    private static String capability(String workflow) {
        return "\ncapability Write { input CommandInput; output Unit; fails AlreadyExists; fails Missing; fails TooMany; "
                + "requires atomic; expose command; workflow {\n" + workflow + "\nreturn unit; } }";
    }

    private static void assertClean(String source) {
        var result = TestSources.analyze(source);
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    private static void rejectOnce(String source, String persist) {
        var result = TestSources.analyze(source);
        assertFalse(result.isSuccess(), () -> "expected rejection: " + source);
        assertEquals(List.of("SIR-VALID-004"), TestSources.errorCodes(result), () -> result.diagnostics().toString());
        assertEquals(1, result.diagnostics().size());
        var diagnostic = result.diagnostics().getFirst();
        int at = source.indexOf(persist);
        assertTrue(at >= 0);
        assertEquals(source.codePointCount(0, at), diagnostic.primarySpan().start().codePointOffset());
        assertEquals(source.codePointCount(0, at + persist.length()), diagnostic.primarySpan().end().codePointOffset());
        assertEquals("test", diagnostic.primarySpan().source().value());
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity());
        assertTrue(diagnostic.message().contains("exists 检查实体 Entry"), diagnostic::message);
        assertTrue(diagnostic.message().contains("persist made 必须声明 else"), diagnostic::message);
        assertTrue(diagnostic.message().contains("同一个错误"), diagnostic::message);
        assertTrue(diagnostic.related().isEmpty());
        assertTrue(diagnostic.fixes().isEmpty());
    }
}
