package io.kcg.sir.semantic.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.TestSources;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A draft is what an edit works on, so it must (1) hold the authored facts and nothing computed,
 * (2) write references as names, and (3) be accepted by the shape check it will later be held to.
 */
class SoftwareDraftTest {
    private static final DraftValidator VALIDATOR = new DraftValidator(SoftwareSchema.normalizedModel());

    private static NormalizedSemanticModel model(String resource) {
        return TestSources.analyzeResource(resource).model().orElseThrow();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "valid/all-workflow-steps.sir", "valid/campus-market.sir", "valid/course-admin.sir", "valid/course-enrollment.sir", "valid/unit-capability.sir"
    })
    void everyDraftTakenFromARealModelPassesTheShapeCheck(String resource) {
        SoftwareDraft draft = SoftwareDraft.of(model(resource));

        for (StructNode declaration : draft.declarations()) {
            List<String> problems = VALIDATOR.validateNode(declaration, NormalizedDeclaration.class);
            assertTrue(problems.isEmpty(), declaration.kind() + " " + SoftwareDraft.nameOf(declaration) + " failed: " + problems);
        }
    }

    @Test
    void draftHoldsAuthoredFactsOnly() {
        String rendered = SoftwareDraft.of(model("valid/course-admin.sir")).root().render();

        assertFalse(rendered.contains("[SOURCE]"), "positions are not part of a draft");
        assertFalse(rendered.contains("[DERIVED]"), "analysis output is not part of a draft");
        assertFalse(rendered.contains("sir://unknown"));
    }

    @Test
    void referencesAreNamesTheAuthorWrote() {
        SoftwareDraft draft = SoftwareDraft.of(model("valid/course-admin.sir"));
        String update = draft.declaration("NormalizedCapability", "UpdateCourse").orElseThrow().render();

        assertTrue(update.contains("inputSymbol [REFERENCE] = UpdateCourseInput"), update);
        assertTrue(update.contains("= CourseNotFound"), "fails/else references are error names");
        assertTrue(update.contains("resultVariable [REFERENCE] = course"), "a workflow variable is referred to by its written name");
        for (String line : update.lines().filter(l -> l.contains("sir://")).toList()) {
            assertTrue(line.contains("[IDENTITY]"), "only identity slots hold resolved ids, found: " + line);
        }
    }

    @Test
    void baselineNodesKeepTheirIdentityAndNewNodesHaveNone() {
        SoftwareDraft draft = SoftwareDraft.of(model("valid/course-admin.sir"));
        StructNode course = draft.declaration("NormalizedEntity", "Course").orElseThrow();

        assertTrue(SoftwareDraft.identityOf(course).orElseThrow().startsWith("sir://CourseAdmin/entity/Course"));

        StructNode withoutIdentity = course.without(Set.of(SlotKind.IDENTITY));
        assertTrue(SoftwareDraft.identityOf(withoutIdentity).isEmpty());
        assertTrue(VALIDATOR.validateNode(withoutIdentity, NormalizedDeclaration.class).isEmpty(), "a node an edit adds has no identity yet");
    }

    @Test
    void sameSourceGivesTheSameDraft() {
        assertEquals(SoftwareDraft.of(model("valid/course-admin.sir")), SoftwareDraft.of(model("valid/course-admin.sir")));
        assertNotEquals(SoftwareDraft.of(model("valid/course-admin.sir")), SoftwareDraft.of(model("valid/course-enrollment.sir")));
    }

    @Test
    void shapeCheckNamesWhatIsWrong() {
        StructNode error = SoftwareDraft.of(model("valid/course-admin.sir")).declaration("NormalizedError", "EmptyChange").orElseThrow();

        // missing authored slot
        StructNode noStatus = new StructNode(error.kind(), error.slots().stream().filter(s -> !s.key().equals("httpStatus")).toList());
        assertTrue(VALIDATOR.validateNode(noStatus, NormalizedDeclaration.class).get(0).contains("httpStatus: missing FACT slot"));

        // scalar that does not parse
        StructNode badStatus = error.withSlot("httpStatus", new StructValue.Text("four-hundred"));
        assertTrue(VALIDATOR.validateNode(badStatus, NormalizedDeclaration.class).get(0).contains("not a valid"));

        // a source position may not be written by a command
        StructNode withSource = new StructNode(error.kind(), new java.util.ArrayList<>(error.slots()));
        withSource.slots();
        List<StructNode.Slot> slots = new java.util.ArrayList<>(error.slots());
        slots.add(new StructNode.Slot("span", SlotKind.SOURCE, new StructValue.Text("x")));
        assertTrue(VALIDATOR.validateNode(new StructNode(error.kind(), slots), NormalizedDeclaration.class).get(0).contains("never written"));

        // unknown kind and a kind that is not allowed in the place
        assertTrue(VALIDATOR.validateNode(new StructNode("Nonsense", List.of()), NormalizedDeclaration.class).get(0).contains("unknown node kind"));
        StructNode field = SoftwareDraft.of(model("valid/course-admin.sir")).declaration("NormalizedEntity", "Course").orElseThrow();
        assertTrue(VALIDATOR.validateNode(field, io.kcg.sir.semantic.model.NormalizedStep.class).get(0).contains("not allowed where NormalizedStep"));
    }

    @Test
    void referenceMustBeAPlainName() {
        StructNode capability = SoftwareDraft.of(model("valid/course-admin.sir")).declaration("NormalizedCapability", "GetCourse").orElseThrow();
        StructNode badInput = capability.withSlot("inputSymbol", new StructValue.Text("sir://x/y"));

        assertTrue(VALIDATOR.validateNode(badInput, NormalizedDeclaration.class).get(0).contains("a reference is a plain name"));
    }
}
