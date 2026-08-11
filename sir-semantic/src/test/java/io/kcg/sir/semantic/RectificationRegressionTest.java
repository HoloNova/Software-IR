package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.RelatedLocation;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstCreateStep;
import io.kcg.sir.ast.AstFindStep;
import io.kcg.sir.ast.AstLoadStep;
import io.kcg.sir.ast.AstPersistStep;
import io.kcg.sir.ast.AstRefTypeRef;
import io.kcg.sir.ast.AstUpdateStep;
import io.kcg.sir.ast.AstValidateStep;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.semantic.symbol.SymbolTable;
import io.kcg.sir.semantic.type.RefType;
import io.kcg.sir.semantic.type.SirType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RectificationRegressionTest {

    private static String sir(String declarations) {
        return TestSources.sir(declarations);
    }

    private static Diagnostic firstError(SemanticAnalysis result, String code) {
        return result.diagnostics().stream()
                .filter(d -> d.code().value().equals(code) && d.isError())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected error " + code + " but not found"));
    }

    private static long errorCount(SemanticAnalysis result, String code) {
        return result.diagnostics().stream()
                .filter(d -> d.code().value().equals(code) && d.isError())
                .count();
    }

    private static boolean hasNonEmptySpan(io.kcg.sir.source.SourceSpan span) {
        return span != null && span.start() != null && span.end() != null;
    }

    private static final String USER_ENTITY = """
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
              field age: Int32;
            }
            """;

    private static final String CREATE_INPUT = """
            input CreateInput {
              field name: String;
              field age: Int32;
            }
            """;

    @Test
    void r01_variableUseBeforeDeclarationProducesFlowError() {
        String source = sir(USER_ENTITY + CREATE_INPUT + """
                capability Create {
                  input CreateInput;
                  output Ref<User>;
                  requires atomic;
                  expose command;
                  workflow {
                    update u { name: input.name; }
                    create User as u { name: input.name; age: input.age; }
                    persist u;
                    return u;
                  }
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-FLOW-001"));
        assertTrue(result.model().isEmpty());
        Diagnostic err = firstError(result, "SIR-FLOW-001");
        assertTrue(hasNonEmptySpan(err.primarySpan()));
    }

    @Test
    void r02_itemUsedOutsideFindProducesFlowError() {
        String source = sir(USER_ENTITY + CREATE_INPUT + """
                capability Create {
                  input CreateInput;
                  output Ref<User>;
                  requires atomic;
                  expose command;
                  workflow {
                    create User as u { name: item.name; age: input.age; }
                    persist u;
                    return u;
                  }
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-FLOW-001"));
        assertTrue(result.model().isEmpty());
    }

    @Test
    void r03_twoFindItemsHaveDifferentSymbolIds() {
        String source = sir(USER_ENTITY + """
                input LookupInput {
                  field minAge: Int32;
                }
                capability Find {
                  input LookupInput;
                  output List<Ref<User>>;
                  requires readonly;
                  expose query;
                  workflow {
                    find User where item.age >= input.minAge as first;
                    find User where item.age >= input.minAge as second;
                    return first;
                  }
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertTrue(result.isSuccess(), () -> "Expected success: " + TestSources.errorCodes(result));

        Set<SymbolId> itemSymbolIds = result.model().orElseThrow().symbols().all().stream()
                .filter(s -> s.name().equals("item"))
                .map(Symbol::id)
                .collect(Collectors.toSet());
        assertEquals(2, itemSymbolIds.size(), "Two Find steps must produce distinct item Symbol IDs");
    }

    @Test
    void r04_unknownCreateFieldProducesSymbolError() {
        String source = sir(USER_ENTITY + CREATE_INPUT + """
                capability Create {
                  input CreateInput;
                  output Ref<User>;
                  requires atomic;
                  expose command;
                  workflow {
                    create User as u { name: input.name; ghost: input.name; age: input.age; }
                    persist u;
                    return u;
                  }
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-002"));
        assertTrue(result.model().isEmpty());
        Diagnostic err = firstError(result, "SIR-SYMBOL-002");
        assertTrue(hasNonEmptySpan(err.primarySpan()));
        assertEquals(1, errorCount(result, "SIR-SYMBOL-002"),
                "An unknown create field must produce exactly one diagnostic");
    }

    @Test
    void r05_unknownUpdateFieldProducesSymbolError() {
        String source = sir(USER_ENTITY + """
                input UpdateInput {
                  field id: Int64;
                  field name: String;
                }
                error NotFound;
                capability Update {
                  input UpdateInput;
                  output Ref<User>;
                  requires atomic;
                  expose command;
                  workflow {
                    load User by input.id as u else NotFound;
                    update u { ghost: input.name; }
                    persist u;
                    return u;
                  }
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-002"));
        assertTrue(result.model().isEmpty());
        assertEquals(1, errorCount(result, "SIR-SYMBOL-002"),
                "An unknown update field must produce exactly one diagnostic");
    }

    @Test
    void r06_updateAssignmentTypeMismatchProducesTypeError() {
        String source = sir(USER_ENTITY + """
                input UpdateInput {
                  field id: Int64;
                }
                error NotFound;
                capability Update {
                  input UpdateInput;
                  output Ref<User>;
                  requires atomic;
                  expose command;
                  workflow {
                    load User by input.id as u else NotFound;
                    update u { name: 1; }
                    persist u;
                    return u;
                  }
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
        assertTrue(result.model().isEmpty());
    }

    @Test
    void r07_failsPointingToNonErrorProducesTypeError() {
        String source = sir(USER_ENTITY + CREATE_INPUT + """
                capability Create {
                  input CreateInput;
                  output Ref<User>;
                  fails User;
                  requires atomic;
                  expose command;
                  workflow {
                    create User as u { name: input.name; age: input.age; }
                    persist u;
                    return u;
                  }
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
        assertTrue(result.model().isEmpty());
    }

    @Test
    void r08_outputEntityProducesTypeError() {
        String source = sir(USER_ENTITY + CREATE_INPUT + """
                capability Create {
                  input CreateInput;
                  output User;
                  requires atomic;
                  expose command;
                  workflow {
                    create User as u { name: input.name; age: input.age; }
                    persist u;
                    return u;
                  }
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
        assertTrue(result.model().isEmpty());
    }

    @Test
    void r09_entityNameAsExpressionProducesTypeError() {
        String source = sir(USER_ENTITY + """
                capability Ping {
                  output Ref<User>;
                  requires readonly;
                  expose query;
                  workflow { return User; }
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
        assertTrue(result.model().isEmpty());
    }

    @Test
    void r10_multipleReturnsProduceFlowError() {
        String source = sir("""
                capability Ping {
                  output Unit;
                  requires readonly;
                  expose query;
                  workflow { return unit; return unit; }
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-FLOW-002"));
        assertTrue(result.model().isEmpty());
    }

    @Test
    void r11_stepsAfterReturnProduceFlowError() {
        String source = sir(USER_ENTITY + CREATE_INPUT + """
                capability Create {
                  input CreateInput;
                  output Ref<User>;
                  requires atomic;
                  expose command;
                  workflow {
                    return u;
                    create User as u { name: input.name; age: input.age; }
                    persist u;
                  }
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-FLOW-002"));
        assertTrue(result.model().isEmpty());
    }

    @Test
    void r12_booleanWithNotBlankProducesTypeError() {
        String source = sir("""
                entity User persistent {
                  identity id: Int64 generated auto;
                  field active: Boolean where notBlank;
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
        assertTrue(result.model().isEmpty());
    }

    @Test
    void r13_minMaxConflictProducesValidationError() {
        String source = sir("""
                entity User persistent {
                  identity id: Int64 generated auto;
                  field age: Int32 where min(10), max(5);
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-VALID-001"));
        assertTrue(result.model().isEmpty());
    }

    @Test
    void r14_constraintParamTypeInvalidProducesTypeError() {
        String source = sir("""
                entity User persistent {
                  identity id: Int64 generated auto;
                  field name: String where length("a", "b");
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
        assertTrue(result.model().isEmpty());
    }

    @Test
    void r15_groupedExpressionNotInNormalizedModel() {
        String source = sir(USER_ENTITY + """
                input CheckInput {
                  field active: Boolean;
                }
                error BadInput;
                capability Check {
                  input CheckInput;
                  output Unit;
                  fails BadInput;
                  requires readonly;
                  expose query;
                  workflow {
                    validate (input.active) else BadInput;
                    return unit;
                  }
                }
                """);
        SemanticAnalysis result = TestSources.analyze(source);
        assertTrue(result.isSuccess(), () -> "Expected success: " + TestSources.errorCodes(result));

        boolean hasGrouped = result.model().orElseThrow().declarations().stream()
                .filter(NormalizedCapability.class::isInstance)
                .map(NormalizedCapability.class::cast)
                .flatMap(c -> c.workflow().steps().stream())
                .anyMatch(this::stepContainsGroupedExpression);
        assertFalse(hasGrouped, "Normalized model must not contain GroupedExpression");
    }

    private boolean stepContainsGroupedExpression(NormalizedStep step) {
        return switch (step) {
            case NormalizedStep.ValidateStep s -> exprContainsGrouped(s.condition());
            case NormalizedStep.LoadStep s -> exprContainsGrouped(s.idExpression());
            case NormalizedStep.FindStep s -> exprContainsGrouped(s.predicate());
            case NormalizedStep.ReturnStep s -> exprContainsGrouped(s.value());
            default -> false;
        };
    }

    private boolean exprContainsGrouped(NormalizedExpression expr) {
        if (expr == null) return false;
        if (expr instanceof NormalizedExpression.MemberExpression me) {
            return exprContainsGrouped(me.receiver());
        }
        if (expr instanceof NormalizedExpression.UnaryExpression ue) {
            return exprContainsGrouped(ue.operand());
        }
        if (expr instanceof NormalizedExpression.BinaryExpression be) {
            return exprContainsGrouped(be.left()) || exprContainsGrouped(be.right());
        }
        return false;
    }

    @Test
    void r16_referenceBindingsContainAllReferenceTypes() {
        SemanticAnalysis result = TestSources.analyzeResource(TestSources.VALID_ALL_WORKFLOW);
        assertTrue(result.isSuccess(), () -> "Expected success: " + TestSources.errorCodes(result));

        Map<AstNodeId, SymbolId> bindings = result.model().orElseThrow().referenceBindings();
        assertFalse(bindings.isEmpty(), "referenceBindings must not be empty");

        Set<SymbolKind> boundKinds = new HashSet<>();
        for (SymbolId sid : bindings.values()) {
            result.model().orElseThrow().symbols().byId(sid).ifPresent(s -> boundKinds.add(s.kind()));
        }
        assertTrue(boundKinds.contains(SymbolKind.ERROR), "referenceBindings must contain Error symbol");
        assertTrue(boundKinds.contains(SymbolKind.VARIABLE), "referenceBindings must contain Variable symbol");
        assertTrue(boundKinds.contains(SymbolKind.FIELD), "referenceBindings must contain Field symbol");
        assertTrue(boundKinds.contains(SymbolKind.ENUM_MEMBER), "referenceBindings must contain Enum Member symbol");
    }

    @Test
    void r17_allSymbolsHaveUniqueSymbolIds() {
        SemanticAnalysis result = TestSources.analyzeResource(TestSources.VALID_CAMPUS_MARKET);
        assertTrue(result.isSuccess());

        List<Symbol> symbols = result.model().orElseThrow().symbols().all();
        Set<SymbolId> ids = new HashSet<>();
        for (Symbol s : symbols) {
            assertTrue(ids.add(s.id()), "Duplicate SymbolId detected: " + s.id() + " (" + s.name() + ")");
        }
    }

    @Test
    void r18_turkishLocaleProducesConsistentResult() {
        String source = TestSources.REQUIRES_DUPLICATE;
        Locale original = Locale.getDefault();
        SemanticAnalysis before = TestSources.analyze(source);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            SemanticAnalysis after = TestSources.analyze(source);
            assertEquals(before.diagnostics(), after.diagnostics(),
                    "Complete diagnostics must be consistent under Turkish locale");
            assertEquals(before.isSuccess(), after.isSuccess());
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void r19_duplicateDefinitionContainsRelatedLocation() {
        SemanticAnalysis result = TestSources.analyze(TestSources.DUPLICATE_ENTITY);
        assertFalse(result.isSuccess());
        Diagnostic err = firstError(result, "SIR-SYMBOL-001");
        assertFalse(err.related().isEmpty(), "Duplicate definition diagnostic must contain RelatedLocation");
        RelatedLocation related = err.related().get(0);
        assertTrue(hasNonEmptySpan(related.span()));
    }

    @Test
    void r20_publicCollectionsUnmodifiableAndStableOrder() {
        SemanticAnalysis result = TestSources.analyzeResource(TestSources.VALID_CAMPUS_MARKET);
        assertTrue(result.isSuccess());
        var model = result.model().orElseThrow();

        assertThrows(UnsupportedOperationException.class,
                () -> model.declarations().add(null),
                "declarations must be unmodifiable");
        assertThrows(UnsupportedOperationException.class,
                () -> model.referenceBindings().put(null, null),
                "referenceBindings must be unmodifiable");
        assertThrows(UnsupportedOperationException.class,
                () -> model.expressionTypes().put(null, null),
                "expressionTypes must be unmodifiable");
        assertThrows(UnsupportedOperationException.class,
                () -> model.symbols().all().add(null),
                "symbols().all() must be unmodifiable");

        SemanticAnalysis second = TestSources.analyzeResource(TestSources.VALID_CAMPUS_MARKET);
        var model2 = second.model().orElseThrow();

        assertEquals(
                model.referenceBindings(), model2.referenceBindings(),
                "Complete referenceBindings must be stable across runs");
        assertEquals(
                model.expressionTypes(), model2.expressionTypes(),
                "Complete expressionTypes must be stable across runs");
        assertEquals(
                model.symbols().all(), model2.symbols().all(),
                "Complete Symbol sequence must be stable across runs");
        assertEquals(model.declarations(), model2.declarations(),
                "Complete normalized declarations must be stable across runs");
        assertEquals(model.diagnostics(), model2.diagnostics(),
                "Complete diagnostics must be stable across runs");
    }

    @Test
    void r21_unknownFindEntityReturnsFailureInsteadOfThrowing() {
        String source = sir("""
                capability Search {
                  output Unit;
                  requires readonly;
                  expose query;
                  workflow {
                    find Missing where true as results;
                    return unit;
                  }
                }
                """);

        SemanticAnalysis result = assertDoesNotThrow(() -> TestSources.analyze(source));
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-002"));
        assertTrue(result.model().isEmpty());
    }

    @Test
    void r22_negativeMinAndMaxAreNumericConstantsAndSurviveNormalization() {
        String source = sir("""
                entity User persistent {
                  identity id: Int64 generated auto;
                  field age: Int32 where min(-2), max(-1);
                }
                """);

        SemanticAnalysis result = TestSources.analyze(source);
        assertTrue(result.isSuccess(), () -> "Expected success: " + result.diagnostics());

        NormalizedEntity entity = result.model().orElseThrow().declarations().stream()
                .filter(NormalizedEntity.class::isInstance)
                .map(NormalizedEntity.class::cast)
                .findFirst()
                .orElseThrow();
        var constraints = entity.fields().getFirst().constraints();
        assertEquals(List.of("min", "max"), constraints.stream().map(c -> c.name()).toList());
        assertEquals(List.of(1, 1), constraints.stream().map(c -> c.arguments().size()).toList(),
                "negative numeric arguments must not disappear during normalization");

        var min = assertInstanceOf(NormalizedExpression.UnaryExpression.class,
                constraints.getFirst().arguments().getFirst());
        var max = assertInstanceOf(NormalizedExpression.UnaryExpression.class,
                constraints.getLast().arguments().getFirst());
        assertEquals("NEGATE", min.operator().name());
        assertEquals("NEGATE", max.operator().name());
        assertEquals("2", assertInstanceOf(NormalizedExpression.IntegerLiteral.class, min.operand())
                .value().toString());
        assertEquals("1", assertInstanceOf(NormalizedExpression.IntegerLiteral.class, max.operand())
                .value().toString());
    }

    @Test
    void r23_negativeMinMaxConflictProducesValidationError() {
        String source = sir("""
                entity User persistent {
                  identity id: Int64 generated auto;
                  field age: Int32 where min(-1), max(-2);
                }
                """);

        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertEquals(1, errorCount(result, "SIR-VALID-001"));
        assertEquals(0, errorCount(result, "SIR-TYPE-001"));
    }

    @Test
    void r24_lengthRejectsNegativeAndDecimalArguments() {
        SemanticAnalysis negative = TestSources.analyze(sir("""
                entity User persistent {
                  identity id: Int64 generated auto;
                  field name: String where length(-1, 5);
                }
                """));
        SemanticAnalysis decimal = TestSources.analyze(sir("""
                entity User persistent {
                  identity id: Int64 generated auto;
                  field name: String where length(1.5, 2.5);
                }
                """));

        assertFalse(negative.isSuccess());
        assertFalse(decimal.isSuccess());
        assertEquals(1, errorCount(negative, "SIR-TYPE-001"));
        assertEquals(1, errorCount(decimal, "SIR-TYPE-001"));
    }

    @Test
    void r25_symbolTableRejectsEvenTheSameSymbolInstanceTwice() {
        SemanticAnalysis result = TestSources.analyzeResource(TestSources.VALID_CAMPUS_MARKET);
        Symbol symbol = result.model().orElseThrow().symbols().all().getFirst();

        assertThrows(IllegalStateException.class,
                () -> SymbolTable.of(List.of(symbol, symbol), Map.of(), Map.of()));
    }

    @Test
    void r26_everyWorkflowNameReferenceIsBoundBeforeNormalization() {
        String source = sir(USER_ENTITY + """
                input ChangeInput {
                  field id: Int64;
                  field name: String;
                  field age: Int32;
                }
                error Invalid;
                capability Change {
                  input ChangeInput;
                  output Ref<User>;
                  fails Invalid;
                  requires atomic;
                  expose command;
                  workflow {
                    validate input.age >= 0 else Invalid;
                    load User by input.id as loaded else Invalid;
                    update loaded { name: input.name; }
                    persist loaded;
                    create User as created { name: input.name; age: input.age; }
                    persist created;
                    find User where item.age >= input.age as matches;
                    return created;
                  }
                }
                """);

        var document = TestSources.parse(source);
        SemanticAnalysis analysis = new io.kcg.sir.semantic.api.SirSemanticAnalyzer().analyze(document);
        assertTrue(analysis.isSuccess(), () -> "Expected success: " + analysis.diagnostics());
        Map<AstNodeId, SymbolId> bindings = analysis.model().orElseThrow().referenceBindings();
        AstCapabilityDecl capability = document.software().declarations().stream()
                .filter(AstCapabilityDecl.class::isInstance)
                .map(AstCapabilityDecl.class::cast)
                .findFirst()
                .orElseThrow();

        List<AstNodeId> requiredBindings = new ArrayList<>();
        requiredBindings.add(((AstRefTypeRef) capability.output().type()).targetName().id());
        requiredBindings.add(capability.failures().getFirst().error().id());
        capability.workflow().steps().forEach(step -> {
            switch (step) {
                case AstValidateStep s -> requiredBindings.add(s.error().id());
                case AstLoadStep s -> {
                    requiredBindings.add(s.entity().id());
                    requiredBindings.add(s.error().id());
                    requiredBindings.add(s.id());
                }
                case AstFindStep s -> {
                    requiredBindings.add(s.entity().id());
                    requiredBindings.add(s.id());
                }
                case AstCreateStep s -> {
                    requiredBindings.add(s.entity().id());
                    requiredBindings.add(s.id());
                    s.bindings().forEach(b -> requiredBindings.add(b.fieldName().id()));
                }
                case AstUpdateStep s -> {
                    requiredBindings.add(s.target().id());
                    s.bindings().forEach(b -> requiredBindings.add(b.fieldName().id()));
                }
                case AstPersistStep s -> requiredBindings.add(s.target().id());
                default -> { }
            }
        });

        for (AstNodeId referenceId : requiredBindings) {
            assertTrue(bindings.containsKey(referenceId), "Missing binding for " + referenceId);
            assertNotEquals("sir://unknown", bindings.get(referenceId).value());
        }
    }

    @Test
    void r27_updateIdentityIsKnownButForbidden() {
        String source = sir(USER_ENTITY + """
                input UpdateInput { field id: Int64; }
                error NotFound;
                capability Update {
                  input UpdateInput;
                  output Ref<User>;
                  fails NotFound;
                  requires atomic;
                  expose command;
                  workflow {
                    load User by input.id as user else NotFound;
                    update user { id: input.id; }
                    return user;
                  }
                }
                """);

        SemanticAnalysis result = TestSources.analyze(source);
        assertFalse(result.isSuccess());
        assertEquals(0, errorCount(result, "SIR-SYMBOL-002"));
        assertEquals(1, errorCount(result, "SIR-VALID-001"));
    }

    @Test
    void r28_chainedMemberExpressionResolvesThroughFieldTypes() {
        String source = sir(USER_ENTITY + """
                input CheckInput { field user: Ref<User>; }
                error Invalid;
                capability Check {
                  input CheckInput;
                  output Unit;
                  fails Invalid;
                  requires readonly;
                  expose query;
                  workflow {
                    validate input.user.name == "Alice" else Invalid;
                    return unit;
                  }
                }
                """);

        SemanticAnalysis result = TestSources.analyze(source);
        assertTrue(result.isSuccess(), () -> "Expected success: " + result.diagnostics());
    }
}
