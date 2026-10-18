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
        assertEquals(new SemanticType("Name"), field(entity, "name").type());
        assertEquals(List.of("notBlank0", "length2"), rules(field(entity, "name")));
        assertEquals(new OptionalType(new SemanticType("Remark")), field(entity, "nickname").type());
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
}
