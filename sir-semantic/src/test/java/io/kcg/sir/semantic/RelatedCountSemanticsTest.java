package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedView;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.source.SirDeclarationWriter;
import io.kcg.sir.semantic.type.DeclaredType;
import io.kcg.sir.semantic.type.PageType;
import org.junit.jupiter.api.Test;

class RelatedCountSemanticsTest {
    private static String source() { return TestSources.resource("valid/related-count.sir").replace("\r\n", "\n"); }

    @Test void aRelatedCountOwnsASeparateProjectionPlanAndFormalSourceBinding() {
        var result = TestSources.analyze(source());
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        var model = result.model().orElseThrow();
        var view = model.declarations().stream().filter(NormalizedView.class::isInstance).map(NormalizedView.class::cast)
                .filter(v -> v.name().equals("CatalogueBook")).findFirst().orElseThrow();
        var count = view.fields().stream().filter(f -> f.name().equals("availableCopies")).findFirst().orElseThrow();
        assertEquals("CountRelated", count.value().getClass().getSimpleName());
        assertTrue(model.referenceSiteBindings().all().stream().anyMatch(b -> b.site().role().name().equals("VIEW_COUNT_ENTITY")));
    }

    @Test void crudReadViewChangesOnlyTheReadOutputs() {
        // An ordinary local scalar makes this test independent of the new count grammar.
        var result = TestSources.analyze(source().replace("count(Copy, book == item and status == CopyStatus.AVAILABLE)", "0"));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        var model = result.model().orElseThrow();
        for (String name : new String[]{"GetBook", "ListBook", "CreateBook", "UpdateBook"}) {
            var cap = model.declarations().stream().filter(NormalizedCapability.class::isInstance).map(NormalizedCapability.class::cast)
                    .filter(c -> c.name().equals(name)).findFirst().orElseThrow();
            var output = cap.outputType() instanceof PageType page ? page.element() : cap.outputType();
            assertEquals(name.equals("GetBook") || name.equals("ListBook") ? "CatalogueBook" : "BookView",
                    assertInstanceOf(DeclaredType.class, output).name(), name);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"int32", "optional-result", "missing-join", "foreign-key", "optional-key",
        "root-capture", "clock", "nested-query", "secret", "arithmetic", "command", "unbounded", "missing-view", "wrong-view"})
    void rejectsCountsOutsideTheirTypedBoundedReadOnlyContract(String damage) {
        String text=source();
        text=switch(damage) {
            case "int32" -> text.replace("availableCopies: Serial", "availableCopies: Count");
            case "optional-result" -> text.replace("availableCopies: Serial", "availableCopies: Optional<Serial>");
            case "missing-join" -> text.replace("book == item and ", "");
            case "foreign-key" -> text.replace("field book: Ref<Book>", "field book: Ref<User>");
            case "optional-key" -> text.replace("field book: Ref<Book>", "field book: Optional<Ref<Book>>");
            case "root-capture" -> text.replace("status == CopyStatus.AVAILABLE", "item.title == \"x\"");
            case "clock" -> text.replace("status == CopyStatus.AVAILABLE", "now() == now()");
            case "nested-query" -> text.replace("status == CopyStatus.AVAILABLE", "any(Copy, book == item)");
            case "secret" -> text.replace("field status: CopyStatus;", "field status: CopyStatus; field hidden: Text secret;")
                    .replace("status == CopyStatus.AVAILABLE", "hidden == \"x\"");
            case "arithmetic" -> text.replace("CopyStatus.AVAILABLE);", "CopyStatus.AVAILABLE) + 1;");
            case "command" -> text.replace("requires readonly;\n      expose query at", "requires atomic;\n      expose command at");
            case "unbounded" -> text.replace("output Page<CatalogueBook>", "output List<CatalogueBook>")
                    .replace("Page input.page, input.size else BadPage as rows;", "as rows;");
            case "missing-view" -> text.replace("readView: CatalogueBook", "readView: MissingView");
            case "wrong-view" -> text.replace("view CatalogueBook from Book", "view WrongView from Copy { field id: Id; }\n    view CatalogueBook from Book")
                    .replace("readView: CatalogueBook", "readView: WrongView");
            default -> throw new AssertionError(damage);
        };
        var result=TestSources.analyze(text);
        assertFalse(result.isSuccess(),damage);
        assertFalse(result.diagnostics().isEmpty(),damage);
    }

    @Test void countCannotBeHiddenInAnEntityScope() {
        var result=TestSources.analyze(source().replace("scope removed == false;", "scope count(Copy, true) > 0;"));
        assertFalse(result.isSuccess(),()->result.diagnostics().toString());
    }

    @Test void theContextualWordDoesNotReserveExistingFieldNames() {
        var result=TestSources.analyze(source().replace("field title: Name; }", "field title: Name; field count: Serial; }"));
        assertTrue(result.isSuccess(),()->result.diagnostics().toString());
    }

    @Test void relatedCountsAndReadViewSurviveCanonicalSourceWriting() {
        var result = TestSources.analyze(source());
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        var declarations = SoftwareDraft.of(result.model().orElseThrow()).declarations();
        var writer = new SirDeclarationWriter("", SirDeclarationWriter.typesOf(declarations));
        String text = declarations.stream().filter(d -> d.kind().equals("NormalizedView")).map(writer::write)
                .filter(w -> w.startsWith("view CatalogueBook ")).findFirst().orElseThrow();
        assertTrue(text.contains("count(Copy,"), text);
        String original = source();
        int start = original.indexOf("view CatalogueBook "), end = original.indexOf('}', start) + 1;
        var roundTrip = TestSources.analyze(original.substring(0, start) + text + original.substring(end));
        assertTrue(roundTrip.isSuccess(), () -> roundTrip.diagnostics().toString());
        var second = SoftwareDraft.of(roundTrip.model().orElseThrow()).declarations();
        String again = second.stream().filter(d -> d.kind().equals("NormalizedView")).map(writer::write)
                .filter(w -> w.startsWith("view CatalogueBook ")).findFirst().orElseThrow();
        assertEquals(text, again, "canonical source must not accumulate automatic child scopes");
    }
}
