package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.source.SirDeclarationWriter;
import io.kcg.sir.semantic.type.OptionalType;
import io.kcg.sir.semantic.type.SemanticType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A field is declared with what the value means; the meaning brings its rules and decides its representation. */
class SemanticTypeSemanticsTest {
    private static final String ENTITY = """
            entity Account persistent {
              identity id: Id generated auto;
              field name: Name;
              field code: Code where length(1, 16);
              field nickname: Optional<Remark>;
              field passwordHash: Secret;
              field version: Version;
              field credit: Quantity where max(9);
            }
            """;

    private static NormalizedEntity account(String declarations) {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir(declarations));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        return (NormalizedEntity) result.model().orElseThrow().declarations().get(0);
    }

    private static NormalizedField field(NormalizedEntity entity, String name) {
        return entity.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> rules(NormalizedField field) {
        return field.constraints().stream().map(c -> c.name() + c.arguments().size()).toList();
    }

    @Test
    void aTypeBringsItsRulesAndFlagsAndTheModelKeepsTheTypeTheAuthorNamed() {
        NormalizedEntity entity = account(ENTITY);
        assertEquals(io.kcg.sir.semantic.feature.SemanticTypes.builtin().type("Name"), field(entity, "name").type());
        assertEquals(List.of("notBlank0", "length2"), rules(field(entity, "name")));
        assertEquals(new OptionalType(io.kcg.sir.semantic.feature.SemanticTypes.builtin().type("Remark")), field(entity, "nickname").type());
        assertEquals(List.of("length2"), rules(field(entity, "nickname")));
        assertTrue(field(entity, "passwordHash").secret());
        assertTrue(field(entity, "version").versioned());
        assertFalse(field(entity, "name").secret());
    }

    @Test
    void aRuleWrittenOnTheFieldReplacesTheRuleOfTheSameNameTheTypeBrings() {
        NormalizedEntity entity = account(ENTITY);
        // Code brings notBlank and length(1, 32); the field says length(1, 16): still not blank, but at most 16.
        assertEquals(List.of("notBlank0", "length2"), rules(field(entity, "code")));
        var length = field(entity, "code").constraints().stream().filter(c -> c.name().equals("length")).findFirst().orElseThrow();
        assertEquals("16", ((io.kcg.sir.semantic.model.NormalizedExpression.IntegerLiteral) length.arguments().get(1)).value().toString());
        // Quantity brings min(1); the field adds max(9) beside it.
        assertEquals(List.of("min1", "max1"), rules(field(entity, "credit")));
    }

    @Test
    void theWriterLeavesOutWhatTheTypeAlreadyBringsAndWritesWhatTheFieldAdded() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir(ENTITY));
        var model = result.model().orElseThrow();
        String written = new SirDeclarationWriter("").write(SoftwareDraft.of(model).declarations().get(0));
        assertTrue(written.contains("field name: Name;"), written);
        assertTrue(written.contains("field code: Code where length(1, 16);"), written);
        assertTrue(written.contains("field nickname: Optional<Remark>;"), written);
        assertTrue(written.contains("field passwordHash: Secret;"), written);
        assertTrue(written.contains("field version: Version;"), written);
        assertTrue(written.contains("field credit: Quantity where max(9);"), written);
    }

    @Test
    void machineTypeNamesAreNotTypesOfTheLanguage() {
        for (String machine : List.of("String", "Int32", "Int64", "Boolean", "Decimal", "Uuid", "Date", "DateTime")) {
            SemanticAnalysis result = TestSources.analyze(TestSources.sir("entity E persistent {\n identity id: Id generated auto;\n field x: " + machine + ";\n}\n"));
            assertFalse(result.isSuccess(), machine);
            assertTrue(TestSources.hasError(result, "SIR-SYMBOL-002"), machine + ": " + result.diagnostics());
        }
    }

    @Test
    void aDeclarationCannotTakeTheNameOfASemanticType() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir("entity Money persistent {\n identity id: Id generated auto;\n field x: Text;\n}\n"));
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-001"));
    }

    @Test
    void aTypeFitsOnlyWhatItsKindOfValueAllows() {
        // A rule that needs text is refused on a number, as it was on a machine integer.
        SemanticAnalysis result = TestSources.analyze(TestSources.sir("entity E persistent {\n identity id: Id generated auto;\n field x: Count where notBlank;\n}\n"));
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
        // The identity of an entity is a number or a token.
        assertFalse(TestSources.analyze(TestSources.sir("entity E persistent {\n identity id: Text generated auto;\n}\n")).isSuccess());
    }

    // ----- types the author declares ---------------------------------------------------------------

    private static final String DECLARED = """
            type Percent = Count where min(0), max(100);
            type ShortName = Name where length(1, 20);
            type Handle = ShortName;
            type Pin = Secret;
            entity Account persistent {
              identity id: Id generated auto;
              field rate: Percent;
              field label: Handle;
              field pin: Pin;
              field tight: Percent where max(10);
            }
            """;

    @Test
    void aDeclaredTypeIsItsBasesKindWithItsBasesRulesAndItsOwnReplacingThoseOfTheSameName() {
        NormalizedEntity entity = account(DECLARED);
        assertEquals("Percent", ((SemanticType) field(entity, "rate").type()).name());
        assertEquals(io.kcg.sir.semantic.type.PrimitiveType.INT32, ((SemanticType) field(entity, "rate").type()).kind());
        assertEquals(List.of("min1", "max1"), rules(field(entity, "rate")));
        // Handle is ShortName is Name: still not blank, now at most 20 characters.
        assertEquals(List.of("notBlank0", "length2"), rules(field(entity, "label")));
        var length = field(entity, "label").constraints().stream().filter(c -> c.name().equals("length")).findFirst().orElseThrow();
        assertEquals("20", ((io.kcg.sir.semantic.model.NormalizedExpression.IntegerLiteral) length.arguments().get(1)).value().toString());
        assertTrue(field(entity, "pin").secret());
        // A rule on the field replaces the one the declared type brings.
        var max = field(entity, "tight").constraints().stream().filter(c -> c.name().equals("max")).findFirst().orElseThrow();
        assertEquals("10", ((io.kcg.sir.semantic.model.NormalizedExpression.IntegerLiteral) max.arguments().get(0)).value().toString());
    }

    @Test
    void theModelKeepsTheDeclarationsAndTheWriterRepeatsNothingATypeBrings() {
        var model = TestSources.analyze(TestSources.sir(DECLARED)).model().orElseThrow();
        var declarations = SoftwareDraft.of(model).declarations();
        var types = SirDeclarationWriter.typesOf(declarations);
        assertEquals(List.of("min(0)", "max(100)"), types.get("Percent").rules());
        assertEquals(List.of("notBlank", "length(1, 20)"), types.get("Handle").rules());
        assertTrue(types.get("Pin").secret());
        var writer = new SirDeclarationWriter("", types);
        String entity = declarations.stream().filter(d -> d.kind().equals("NormalizedEntity")).map(writer::write).findFirst().orElseThrow();
        assertTrue(entity.contains("field rate: Percent;"), entity);
        assertTrue(entity.contains("field label: Handle;"), entity);
        assertTrue(entity.contains("field pin: Pin;"), entity);
        assertTrue(entity.contains("field tight: Percent where max(10);"), entity);
        String type = declarations.stream().filter(d -> SoftwareDraft.nameOf(d).equals("Percent")).map(writer::write).findFirst().orElseThrow();
        assertEquals("type Percent = Count where min(0), max(100);", type);
    }

    @Test
    void aDeclarationThatCannotBeATypeIsRefusedWhetherOrNotAnythingUsesIt() {
        for (var refusal : List.of(
                java.util.Map.entry("type Money = Count;", "SIR-TYPE-005"),                        // the language's own name
                java.util.Map.entry("type A = B;\ntype B = A;", "SIR-TYPE-005"),                  // a cycle
                java.util.Map.entry("type A = Unknown;", "SIR-SYMBOL-002"),                        // no such base
                java.util.Map.entry("type A = Count;\ntype A = Text;", "SIR-SYMBOL-001"),         // declared twice
                java.util.Map.entry("type A = Count where notBlank;", "SIR-TYPE-001"),             // a text rule on a number
                java.util.Map.entry("type A = Text where min(1);", "SIR-TYPE-001"),               // a number rule on text
                java.util.Map.entry("type A = Text where length(5, 2);", "SIR-TYPE-001"),         // an empty range
                java.util.Map.entry("type A = Text where frobnicate;", "SIR-SYMBOL-002"),          // no such rule
                java.util.Map.entry("type A = Count where min(1), min(2);", "SIR-SYMBOL-001"))) {  // the same rule twice
            SemanticAnalysis result = TestSources.analyze(TestSources.sir(refusal.getKey()));
            assertFalse(result.isSuccess(), refusal.getKey());
            assertTrue(TestSources.hasError(result, refusal.getValue()), refusal.getKey() + " -> " + result.diagnostics());
        }
    }

    @Test
    void aTypeAndAnotherDeclarationCannotShareAName() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir("type Rate = Count;\nentity Rate persistent {\n identity id: Id generated auto;\n}\n"));
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-001"), result.diagnostics().toString());
    }
}
