package io.kcg.sir.semantic.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.TestSources;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the structure view must see, and what it must ignore.
 *
 * <p>Each row edits one thing in a real, valid source. A fact edit must change the fact view; a
 * position-only edit must not. The old change projection missed {@code httpStatus}; row one is that
 * case, and the rest cover the other places the language keeps facts (ordering, paging, patch
 * presence, relation existence, constraint arguments, step order, target settings).
 */
class StructureViewSensitivityTest {
    private static final String ADMIN = "valid/course-admin.sir";
    private static final String ENROLLMENT = "valid/course-enrollment.sir";
    private static final String STEPS = "valid/all-workflow-steps.sir";

    record Edit(String name, String resource, String from, String to) {
        @Override
        public String toString() {
            return this.name;
        }
    }

    static List<Edit> factEdits() {
        return List.of(
            new Edit("sum predicate", "valid/decimal-sum.sir", "item.amount >= input.minimum", "item.amount > input.minimum"),
            new Edit("computed value operator", "valid/computed-views.sir", "item.capacity - item.enrolled", "item.capacity + item.enrolled"),
            new Edit("error http status", ADMIN, "error CourseNotFound http 404;", "error CourseNotFound http 409;"),
            new Edit("error status dropped to default", ADMIN, "error CourseNotFound http 404;", "error CourseNotFound;"),
            new Edit("constraint argument", ADMIN, "field code: Code;\n      field name: Name;\n      field description: Optional<Remark>;\n      field capacity: Quantity;\n      field version", "field code: Name where length(1, 64);\n      field name: Name;\n      field description: Optional<Remark>;\n      field capacity: Quantity;\n      field version"),
            new Edit("constraint kept but minimum changed", ADMIN, "field capacity: Quantity;\n      field version", "field capacity: Count where min(2);\n      field version"),
            new Edit("order direction", ENROLLMENT, "order by code ascending", "order by code descending"),
            new Edit("order key", ENROLLMENT, "order by code ascending", "order by name ascending"),
            new Edit("page source", ENROLLMENT, "Page input.page, input.size", "Page input.size, input.page"),
            new Edit("relation condition constant", ENROLLMENT, "status == EnrollmentStatus.ACTIVE", "status == EnrollmentStatus.CANCELLED"),
            new Edit("patch presence test", ADMIN, "input.name.present or input.description.present or input.capacity.present", "input.name.present or input.description.present"),
            new Edit("step order", ADMIN, "validate input.name.present or input.description.present or input.capacity.present else EmptyChange;\n        load Course by input.id as course else CourseNotFound;", "load Course by input.id as course else CourseNotFound;\n        validate input.name.present or input.description.present or input.capacity.present else EmptyChange;"),
            new Edit("input field added", ADMIN, "input GetCourseInput {\n      field id: Id;", "input GetCourseInput {\n      field id: Id;\n      field extra: Text;"),
            new Edit("requirement added", ADMIN, "requires readonly;\n      expose query;\n      workflow {\n        load Course", "requires authenticated;\n      requires readonly;\n      expose query;\n      workflow {\n        load Course"),
            new Edit("enum member renamed", STEPS, "enum State { ACTIVE, INACTIVE }", "enum State { ACTIVE, DISABLED }"),
            new Edit("enum members reordered", STEPS, "enum State { ACTIVE, INACTIVE }", "enum State { INACTIVE, ACTIVE }"),
            new Edit("operator", STEPS, "item.state == State.ACTIVE", "item.state != State.ACTIVE"),
            new Edit("declaration renamed everywhere", ADMIN, "EmptyChange", "NoChange"),
            new Edit("display name", ADMIN, "displayName \"", "displayName \"X "),
            new Edit("namespace", ADMIN, "namespace \"", "namespace \"x.")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factEdits")
    void aFactEditChangesTheFactView(Edit edit) {
        String base = lfSource(edit.resource());
        assertTrue(base.contains(edit.from()), "edit anchor not found: " + edit.name());

        StructNode before = StructureView.facts(model(base));
        StructNode after = StructureView.facts(model(base.replace(edit.from(), edit.to())));

        assertNotEquals(before, after, "edit '" + edit.name() + "' changed the software but not the fact view");
    }

    @Test
    void commentsBlankLinesAndIndentationAreNotFacts() {
        String base = lfSource(ADMIN);
        String noisy = "// leading comment\n"
            + base.replace("    error EmptyChange;", "\n\n    /* why */   error EmptyChange;   // trailing\n")
                .replace("  declarations {", "  declarations {\n\n")
                .replace("      requires readonly;", "          requires   readonly ;");

        StructNode before = StructureView.facts(model(base));
        StructNode after = StructureView.facts(model(noisy));

        assertEquals(before, after, "positions must not read as a business change:\n" + before.render());
        assertNotEquals(StructureView.withSource(model(base)), StructureView.withSource(model(noisy)), "the source view must still see that things moved");
    }

    @Test
    void reorderingDeclarationsIsNotAChangeBecauseTheyAreMatchedByIdentity() {
        String base = lfSource(ADMIN);
        String swapped = base.replace("    error CourseNotFound http 404;\n    error EmptyChange;\n", "    error EmptyChange;\n    error CourseNotFound http 404;\n");
        assertNotEquals(base, swapped);

        assertEquals(StructureView.facts(model(base)), StructureView.facts(model(swapped)));
    }

    @Test
    void viewIsDeterministic() {
        String base = lfSource(ENROLLMENT);

        assertEquals(StructureView.facts(model(base)).render(), StructureView.facts(model(base)).render());
    }

    @Test
    void derivedAnalysisNeverAppearsInAnyView() {
        String rendered = StructureView.withSource(model(lfSource(ADMIN))).render();

        for (String derived : List.of("symbols", "referenceBindings", "findItemBindings", "expressionTypes", "diagnostics", "referenceSiteBindings")) {
            assertTrue(!rendered.contains(derived + " ["), derived + " is analysis output and must not be in the view");
        }
    }

    private static String lfSource(String resource) {
        return TestSources.resource(resource).replace("\r\n", "\n");
    }

    private static NormalizedSemanticModel model(String source) {
        SemanticAnalysis analysis = TestSources.analyze(source);
        assertTrue(analysis.isSuccess(), "fixture must stay valid: " + analysis.diagnostics());
        return analysis.model().orElseThrow();
    }
}
