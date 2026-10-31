package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.api.SourceUnitParseResult;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourcePosition;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TargetedSyntaxDiagnosticTest {
    private static final String CONSTRAINTS = "若这里要追加约束，请用逗号分隔，例如 where min(1), max(7)；约束之间不用 and/or，也不能直接相邻。";
    private static final String PAGE = "分页子句须写 Page 页码, 大小 else 错误名 as 结果名；不能从大小直接接 as。";
    private final SirParser parser = SirParser.create();

    @ParameterizedTest
    @ValueSource(strings = {
            "load Thing by 1 as target for update else Missing;",
            "create Thing as target made { label: \"x\"; }",
            "find Thing where true as target rows;",
            "exists Thing where true as target found;",
            "count Thing where true as target total;"
    })
    void reservedResultNamesAreExplainedEvenWhenAntlrReportsAnExtraToken(String step) {
        var result = parse(document(workflow(step)));
        assertRejected(result);
        assertEquals("SIR-PARSE-003", first(result).code().value());
        assertEquals(keyword("target"), first(result).message());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "entity target Actual persistent { identity id: Id generated auto; }",
            "input target Actual { field label: Text; }",
            "view target Actual from Thing { field label: Text; }",
            "capability target Actual { output Unit; expose command; workflow { return unit; } }",
            "entity Thing persistent { identity id: Id generated auto; field target actual: Text; }"
    })
    void reservedDeclarationNamesKeepTheExtraTokenCode(String declarations) {
        var result = parse(document(declarations));
        assertRejected(result);
        assertEquals("SIR-PARSE-003", first(result).code().value());
        assertEquals(keyword("target"), first(result).message());
    }

    @Test
    void lockedLoadRecoveryKeepsItsSecondDiagnosticAndItsLocation() {
        String source = document(workflow("load Thing by 1 as target for update else Missing;"));
        var result = parse(source);
        assertEquals(List.of("SIR-PARSE-003", "SIR-PARSE-003"), codes(result));
        assertEquals(keyword("target"), first(result).message());
        assertEquals("存在多余的语法元素", result.diagnostics().get(1).message());
        assertEquals(offset(source, source.indexOf("update else")), result.diagnostics().get(1).primarySpan().start().codePointOffset());
    }

    @ParameterizedTest
    @MethodSource("constraintMistakes")
    void completedConstraintsExplainCommasRatherThanBooleanConjunctions(String declarations) {
        var result = parse(document(declarations));
        assertRejected(result);
        assertTrue(List.of("SIR-PARSE-001", "SIR-PARSE-003").contains(first(result).code().value()));
        assertEquals(CONSTRAINTS, first(result).message());
    }

    private static Stream<String> constraintMistakes() {
        var declarations = List.of(
                "entity Thing persistent { identity id: Id generated auto; field n: Serial where %s; }",
                "input Request { field n: Serial where %s; }",
                "view ThingView from Thing { field n: Serial where %s; }",
                "type Small = Count where %s;");
        return declarations.stream().flatMap(shape -> Stream.of(
                "min(1) max(7)", "min(1) and max(7)", "min(1) or max(7)", "notBlank length(1, 100)")
                .map(shape::formatted));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "entity Thing persistent { identity id: Id generated auto; field n: Serial where min(1) default 1; }",
            "entity Thing persistent { identity id: Id generated auto; field n: Serial where min(1) unique; }",
            "entity Thing persistent { identity id: Id generated auto; field label: Text where notBlank secret; }",
            "input Request { field n: Serial where min(1), max(7); }",
            "type Small = Count where min(1), max(7);"
    })
    void commaListsAndLegalModifiersStillParseWithoutDiagnostics(String declarations) {
        var result = parse(document(declarations));
        assertTrue(result.isSuccess(), result.diagnostics()::toString);
        assertTrue(result.diagnostics().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "input Request { field n: Serial where min(1) default 1; }",
            "input Request { field n: Serial where min(1) unique; }",
            "entity Thing persistent { identity id: Id generated auto; field n: Serial where min(1) field other: Serial; }",
            "entity Thing persistent { identity id: Id generated auto; field n: Serial where min(1) unique (id, n); }",
            "entity Thing persistent { identity id: Id generated auto; field n: Serial where min(1 and max(7); }",
            "entity Thing persistent { identity id: Id generated auto; field n: Serial where min(1, 2 max(7); }",
            "entity Thing persistent { identity id: Id generated auto; field n: Serial where min(1) where max(7); }",
            "enum State { ON and OFF }"
    })
    void otherSyntaxErrorsMustNotBeDiagnosedAsMissingConstraintSeparators(String declarations) {
        var result = parse(document(declarations));
        assertRejected(result);
        assertTrue(result.diagnostics().stream().noneMatch(d -> CONSTRAINTS.equals(d.message())), result.diagnostics()::toString);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "find Thing where true Page input.page, input.size as rows;",
            "find Thing where true order by id ascending Page input.page, input.size as rows;",
            "sum Thing.n by owner where true Page input.page, input.size as rows;",
            "avg Thing.n by owner where true Page input.page, input.size as rows;"
    })
    void aCompletePageFollowedByAsExplainsTheRequiredElse(String step) {
        var result = parse(document(query(step)));
        assertRejected(result);
        assertEquals("SIR-PARSE-001", first(result).code().value());
        assertEquals(PAGE, first(result).message());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "find Thing where true Page input.page input.size else BadPage as rows;",
            "find Thing where true Page input.page, else BadPage as rows;",
            "find Thing where true Page 1, 10 else as rows;",
            "find Thing where true Page 1, 10 BadPage as rows;",
            "find Thing where true Page , 10 else BadPage as rows;",
            "find Thing where true Page 1, (10 + ) as rows;"
    })
    void aDamagedPageExpressionOrErrorNameKeepsItsOriginalDiagnostic(String step) {
        var result = parse(document(query(step)));
        assertRejected(result);
        assertTrue(result.diagnostics().stream().noneMatch(d -> PAGE.equals(d.message())), result.diagnostics()::toString);
    }

    @Test
    void aPageContainerTypeIsNotMistakenForAPaginationClause() {
        var result = parse(document("capability Read { output Page; expose query; workflow { return unit; } }"));
        assertRejected(result);
        assertTrue(result.diagnostics().stream().noneMatch(d -> PAGE.equals(d.message())));
    }

    @Test
    void aNormalPredicateAndACompletePageRemainLegal() {
        var result = parse(document(query("find Thing where item.n > 0 and true order by id ascending Page input.page, input.size else BadPage as rows;")));
        assertTrue(result.isSuccess(), result.diagnostics()::toString);
        assertTrue(result.diagnostics().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"for", "unique", "default", "credential", "secret"})
    void contextualWordsAreNotTurnedIntoReservedNames(String name) {
        var result = parse(document(workflow("load Thing by 1 as " + name + " else Missing;")));
        assertTrue(result.isSuccess(), result.diagnostics()::toString);
    }

    @Test
    void anExplicitlyAllowedEntityFeatureParameterStillParses() {
        var result = parse(document("use Crud { entity: Thing; }"));
        assertTrue(result.isSuccess(), result.diagnostics()::toString);
    }

    @Test
    void anUnrelatedMissingOperandMustNotReceiveTheNewNameHint() {
        var result = parse(document(workflow("validate true and else Missing;")));
        assertEquals("SIR-PARSE-003", first(result).code().value());
        assertEquals("存在多余的语法元素", first(result).message());
    }

    @Test
    void anExistingKeywordNameDiagnosticRemainsExactlyTheSame() {
        var result = parse(document("entity Thing persistent { identity id: Id generated auto; field target: Text; }"));
        assertEquals("SIR-PARSE-001", first(result).code().value());
        assertEquals(keyword("target"), first(result).message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"project", "fragment", "expansion"})
    void allParserEntrypointsShareTheMessageWithoutInventingExpansionLocations(String entry) {
        String declarations = query("find Thing where true Page input.page, input.size as rows;");
        SourceSpan borrowed = new SourceSpan(SourceId.of("caller.sir"), new SourcePosition(10, 2, 3), new SourcePosition(20, 2, 13));
        List<Diagnostic> diagnostics = switch (entry) {
            case "project" -> parse(document(declarations)).diagnostics();
            case "fragment" -> parser.parseFragment(new SirSource(SourceId.of("fragment.sir"),
                    "sir 0.3\nimports { }\ndeclarations {\n" + declarations + "\n}\n")).diagnostics();
            case "expansion" -> parser.parseExpansion(declarations, new AstNodeId("use-feature"), borrowed).diagnostics();
            default -> throw new IllegalArgumentException(entry);
        };
        assertEquals(PAGE, diagnostics.getFirst().message());
        if (entry.equals("expansion")) assertTrue(diagnostics.stream().allMatch(d -> borrowed.equals(d.primarySpan())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    void theUnexpectedAsKeepsItsExactCodePointSpanAfterUnicodeAndEitherNewline(String newline) {
        String source = document(query("find Thing where true Page input.page, input.size as rows;")).replace("\n", newline);
        var diagnostic = first(parse(source));
        int index = source.indexOf("as rows");
        assertEquals(PAGE, diagnostic.message());
        assertEquals(offset(source, index), diagnostic.primarySpan().start().codePointOffset());
        assertEquals(offset(source, index + 2), diagnostic.primarySpan().end().codePointOffset());
        assertEquals(source.substring(0, index).chars().filter(c -> c == '\n').count() + 1, diagnostic.primarySpan().start().line());
        assertEquals(source.codePointCount(source.lastIndexOf('\n', index) + 1, index) + 1, diagnostic.primarySpan().start().column());
        assertTrue(diagnostic.related().isEmpty());
        assertTrue(diagnostic.fixes().isEmpty());
    }

    @Test
    void eofKeepsItsOwnCodeAndMessageRatherThanReceivingAPageSuggestion() {
        String source = document(query("find Thing where true Page input.page, input.size as rows;"));
        var result = parse(source.substring(0, source.indexOf("as rows")));
        assertEquals("SIR-PARSE-004", first(result).code().value());
        assertEquals("输入提前结束，SIR 结构尚未闭合", first(result).message());
    }

    @Test
    void newMessagesRespectTheOriginalFiftyDiagnosticLimit() {
        String fields = IntStream.range(0, 70).mapToObj(i -> "field target value" + i + ": Text;").collect(Collectors.joining("\n"));
        var result = parse(document("input Bad {\n" + fields + "\n}"));
        assertEquals(50, result.diagnostics().size());
        assertEquals(keyword("target"), first(result).message());
        assertEquals("SIR-PARSE-999", result.diagnostics().getLast().code().value());
        assertEquals("错误过多，停止继续分析", result.diagnostics().getLast().message());
    }

    private SourceUnitParseResult parse(String text) {
        return parser.parseProject(new SirSource(SourceId.of("tests/messages.sir"), text));
    }

    private static final String SHIFT = "时间平移的数量只能是整数字面量，例如 + 7 days；按变量天数平移请用 addDays(时间, 天数)。";

    @Test
    void questionMarkIsNamedAndExplainedAsAMissingConditionalExpression() {
        String source = document(workflow("load Thing by 1 as row else Missing; update row { label: row.label == \"a\" ? \"b\" : \"c\"; } persist row;"));
        var result = parse(source);
        assertRejected(result);
        assertEquals("SIR-LEX-001", first(result).code().value());
        assertEquals("存在 SIR 不允许的字符“?”：SIR 没有 ? : 条件表达式；局部更新的字段直接写 字段: input.字段;，没传会自动保留原值",
                first(result).message());
        assertEquals(offset(source, source.indexOf('?')), first(result).primarySpan().start().codePointOffset());
    }

    @ParameterizedTest
    @MethodSource("otherCharacters")
    void otherRejectedCharactersAreNamedWithoutAGuessedReason(String character, String shown) {
        String source = document(workflow("validate 1 " + character + " 1 else Missing;"));
        var result = parse(source);
        assertRejected(result);
        assertEquals("SIR-LEX-001", first(result).code().value());
        assertEquals("存在 SIR 不允许的字符“" + shown + "”", first(result).message());
        assertEquals(offset(source, source.indexOf(character)), first(result).primarySpan().start().codePointOffset());
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> otherCharacters() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("#", "#"),
                org.junit.jupiter.params.provider.Arguments.of("≥", "≥"),
                org.junit.jupiter.params.provider.Arguments.of("\u200B", "U+200B"),
                org.junit.jupiter.params.provider.Arguments.of("\u00A0", "U+00A0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"row.at + row.n days", "row.at + 2 * row.n days", "row.at + (row.n) days", "row.at - row.n hours",
            "row.at + row.n minutes"})
    void aUnitAfterAVariableAmountPointsToAddDays(String value) {
        String source = document(shift(value));
        var result = parse(source);
        assertRejected(result);
        assertEquals("SIR-PARSE-001", first(result).code().value());
        assertEquals(SHIFT, first(result).message());
        String unit = value.substring(value.lastIndexOf(' ') + 1);
        assertEquals(offset(source, source.lastIndexOf(unit)), first(result).primarySpan().start().codePointOffset());
    }

    @ParameterizedTest
    @ValueSource(strings = {"row.at + 7 days", "now() + 15 minutes", "addDays(row.at, row.n)"})
    void legalShiftsAndAddDaysStillParse(String value) {
        var result = parse(document(shift(value)));
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    @Test
    void aUnitWordThatStartsTheNextBindingKeepsTheOriginalMessage() {
        var result = parse(document(shift("row.at + row.n\n minutes")).replace("minutes;", "minutes: 1;"));
        assertRejected(result);
        assertFalse(result.diagnostics().stream().anyMatch(d -> d.message().equals(SHIFT)), () -> result.diagnostics().toString());
    }

    private static String shift(String value) {
        return "entity Slot persistent { identity id: Id generated auto; field at: Moment; field n: Count; }\n"
                + "error Missing; capability Move { output Unit; fails Missing; requires atomic; expose command; workflow { "
                + "load Slot by 1 as row else Missing; update row { at: " + value + "; } persist row; return unit; } }";
    }

    private static Diagnostic first(SourceUnitParseResult result) {
        return result.diagnostics().getFirst();
    }

    private static List<String> codes(SourceUnitParseResult result) {
        return result.diagnostics().stream().map(d -> d.code().value()).toList();
    }

    private static void assertRejected(SourceUnitParseResult result) {
        assertFalse(result.isSuccess());
        assertTrue(result.unit().isEmpty());
        assertFalse(result.diagnostics().isEmpty());
    }

    private static int offset(String text, int charIndex) {
        return text.codePointCount(0, charIndex);
    }

    private static String keyword(String name) {
        return "“" + name + "”是 SIR 的关键字，不能用作名字；换一个名字，例如 " + name + "Value";
    }

    private static String workflow(String steps) {
        return "entity Thing persistent { identity id: Id generated auto; field label: Text; }\n"
                + "error Missing; capability Work { output Unit; fails Missing; requires atomic; expose command; workflow { "
                + steps + " return unit; } }";
    }

    private static String query(String steps) {
        return "entity Thing persistent { identity id: Id generated auto; field n: Serial; field owner: Ref<Thing>; }\n"
                + "view ThingView from Thing { field n: Serial; }\n"
                + "input Request { field page: PageNo; field size: PageSize; }\n"
                + "error BadPage; capability Read { input Request; output Page<ThingView>; fails BadPage; requires readonly; expose query; workflow { "
                + steps + " return rows; } }";
    }

    private static String document(String declarations) {
        return "sir 0.3\nsoftware Messages {\n"
                + "metadata { displayName \"😀\"; namespace \"tests.parser.messages\"; }\n"
                + "target { language java 21; framework spring_boot; persistence mybatis_plus; database mysql; build maven; interface rest; }\n"
                + "sources { }\nimports { }\ndeclarations {\n" + declarations + "\n}\n}\n";
    }
}
