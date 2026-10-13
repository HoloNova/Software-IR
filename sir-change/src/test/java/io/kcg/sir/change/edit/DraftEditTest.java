package io.kcg.sir.change.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.SirSource;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.semantic.schema.SlotKind;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.SoftwareSchema;
import io.kcg.sir.semantic.schema.StructNode;
import io.kcg.sir.semantic.schema.StructValue;
import io.kcg.sir.source.SourceId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The three commands, exercised on a real draft and on slots of very different kinds, to show that
 * nothing in them depends on what a slot means: only on the shape the schema gives it.
 */
class DraftEditTest {
    private static final DraftEdit EDIT = new DraftEdit(SoftwareSchema.normalizedModel());

    private static SoftwareDraft draft() {
        String source = EditFixtures.courseCatalog();
        var parsed = SirParser.create().parse(new SirSource(SourceId.of("e"), source));
        NormalizedSemanticModel model = new SirSemanticAnalyzer().analyze(parsed.document().orElseThrow()).model().orElseThrow();
        return SoftwareDraft.of(model);
    }

    private static StructValue text(String value) {
        return new StructValue.Text(value);
    }

    private static StructNode node(String kind, Object... keyValueRole) {
        List<StructNode.Slot> slots = new java.util.ArrayList<>();
        for (int i = 0; i < keyValueRole.length; i += 3) {
            slots.add(new StructNode.Slot((String)keyValueRole[i], (SlotKind)keyValueRole[i + 2], (StructValue)keyValueRole[i + 1]));
        }

        return new StructNode(kind, slots);
    }

    private static DraftEdit.Applied applied(DraftEdit.Result result) {
        return assertInstanceOf(DraftEdit.Applied.class, result, () -> String.valueOf(result));
    }

    private static DraftEdit.Rejected rejected(DraftEdit.Result result) {
        return assertInstanceOf(DraftEdit.Rejected.class, result, () -> String.valueOf(result));
    }

    @Test
    void addUpdateAndDeleteWorkOnDeclarationsFieldsAndAttributesAlike() {
        SoftwareDraft base = draft();
        StructNode newError = node(
            "NormalizedError", "name", text("DuplicateCode"), SlotKind.FACT, "httpStatus", text("409"), SlotKind.FACT
        );
        Selector declarations = Selector.root().slot("declarations");

        SoftwareDraft edited = applied(EDIT.apply(base, List.of(
            new Command.Add(declarations, new StructValue.Child(newError)),
            new Command.Update(Selector.root().named("declarations", "StaleVersion").slot("httpStatus"), text("410")),
            new Command.Delete(Selector.root().named("declarations", "EmptyChange"))
        ))).draft();

        assertTrue(edited.declaration("NormalizedError", "DuplicateCode").isPresent(), "added");
        assertTrue(edited.declaration("NormalizedError", "EmptyChange").isEmpty(), "deleted");
        assertEquals(text("410"), edited.declaration("NormalizedError", "StaleVersion").orElseThrow().slot("httpStatus").value(), "updated");
        assertEquals(base.declaration("NormalizedEntity", "Course"), edited.declaration("NormalizedEntity", "Course"), "untouched stays identical");
    }

    @Test
    void theOriginalDraftIsNeverChanged() {
        SoftwareDraft base = draft();
        SoftwareDraft snapshot = draft();

        applied(EDIT.apply(base, List.of(new Command.Delete(Selector.root().named("declarations", "EmptyChange")))));

        assertEquals(snapshot, base);
    }

    @Test
    void aWholeBatchFailsAtTheFirstCommandThatCannotBeApplied() {
        SoftwareDraft base = draft();
        StructNode dup = node("NormalizedError", "name", text("EmptyChange"), SlotKind.FACT, "httpStatus", text("400"), SlotKind.FACT);

        DraftEdit.Rejected rejection = rejected(EDIT.apply(base, List.of(
            new Command.Delete(Selector.root().named("declarations", "StaleVersion")),
            new Command.Add(Selector.root().slot("declarations"), new StructValue.Child(dup)),
            new Command.Delete(Selector.root().named("declarations", "InvalidPage"))
        )));

        assertEquals(1, rejection.commandIndex());
        assertTrue(rejection.reason().contains("already has an element named 'EmptyChange'"), rejection.reason());
    }

    @Test
    void expectedOldValueGuardsAgainstAStaleTarget() {
        SoftwareDraft base = draft();
        Selector status = Selector.root().named("declarations", "StaleVersion").slot("httpStatus");

        applied(EDIT.apply(base, List.of(new Command.Update(status, text("410"), Optional.of(text("400"))))));
        DraftEdit.Rejected stale = rejected(EDIT.apply(base, List.of(new Command.Update(status, text("410"), Optional.of(text("999"))))));

        assertTrue(stale.reason().contains("not what the command expected"), stale.reason());
    }

    @Test
    void valuesThatDoNotMatchTheSchemaAreRefusedWithTheirPlace() {
        SoftwareDraft base = draft();
        Selector status = Selector.root().named("declarations", "StaleVersion").slot("httpStatus");

        assertTrue(rejected(EDIT.apply(base, List.of(new Command.Update(status, text("many"))))).reason().contains("not a valid"));
        assertTrue(rejected(EDIT.apply(base, List.of(new Command.Update(Selector.root().named("declarations", "Course").slot("fields"), text("x"))))).reason().contains("sequence"));
        assertTrue(rejected(EDIT.apply(base, List.of(new Command.Delete(Selector.root().named("declarations", "Nope"))))).reason().contains("no element named 'Nope'"));
        assertTrue(rejected(EDIT.apply(base, List.of(new Command.Update(Selector.root().named("declarations", "Course").slot("span"), text("x"))))).reason().contains("never edited"));
    }

    @Test
    void anIdentityIsNeverEditedAndAReplacementKeepsIt() {
        SoftwareDraft base = draft();
        Selector id = Selector.root().named("declarations", "StaleVersion").slot("id");
        assertTrue(rejected(EDIT.apply(base, List.of(new Command.Update(id, text("sir://x"))))).reason().contains("identity is never edited"));

        StructNode replacement = node("NormalizedError", "name", text("StaleVersion"), SlotKind.FACT, "httpStatus", text("409"), SlotKind.FACT);
        SoftwareDraft edited = applied(EDIT.apply(base, List.of(new Command.Update(Selector.root().named("declarations", "StaleVersion"), new StructValue.Child(replacement))))).draft();

        StructNode after = edited.declaration("NormalizedError", "StaleVersion").orElseThrow();
        assertEquals(SoftwareDraft.identityOf(base.declaration("NormalizedError", "StaleVersion").orElseThrow()), SoftwareDraft.identityOf(after));
        assertEquals(text("409"), after.slot("httpStatus").value());
    }

    @Test
    void walksIntoNestedSlotsToChangeOneStepOfOneWorkflow() {
        SoftwareDraft base = draft();
        Selector firstCondition = Selector.root().named("declarations", "UpdateCourse").slot("workflow").at("steps", 0).slot("condition");
        StructNode before = base.declaration("NormalizedCapability", "UpdateCourse").orElseThrow();

        StructNode literal = node("BooleanLiteral", "value", text("true"), SlotKind.FACT);
        SoftwareDraft edited = applied(EDIT.apply(base, List.of(new Command.Update(firstCondition, new StructValue.Child(literal))))).draft();
        StructNode after = edited.declaration("NormalizedCapability", "UpdateCourse").orElseThrow();

        assertNotEquals(before, after);
        assertTrue(after.render().contains("BooleanLiteral"));
        assertEquals(
            before.without(Set.of()).slot("exposure"), after.slot("exposure"), "nothing outside the edited subtree moved"
        );
    }

    @Test
    void positionedAddIntoAnOrderedCollectionAndDeleteOfOneStep() {
        SoftwareDraft base = draft();
        Selector steps = Selector.root().named("declarations", "UpdateCourse").slot("workflow").slot("steps");
        StructNode before = base.declaration("NormalizedCapability", "UpdateCourse").orElseThrow();
        int count = ((StructValue.Seq)((StructValue.Child)before.slot("workflow").value()).node().slot("steps").value()).elements().size();

        StructNode ret = node("ReturnStep", "value", new StructValue.Child(node("NameExpression", "name", text("course"), SlotKind.FACT)), SlotKind.FACT);
        SoftwareDraft added = applied(EDIT.apply(base, List.of(new Command.Add(steps, new StructValue.Child(ret), java.util.OptionalInt.of(0))))).draft();
        SoftwareDraft removed = applied(EDIT.apply(added, List.of(new Command.Delete(Selector.root().named("declarations", "UpdateCourse").slot("workflow").at("steps", 0))))).draft();

        assertEquals(count + 1, stepCount(added));
        assertEquals(base, removed, "add then delete of the same element restores the draft exactly");
    }

    private static int stepCount(SoftwareDraft draft) {
        StructNode capability = draft.declaration("NormalizedCapability", "UpdateCourse").orElseThrow();
        return ((StructValue.Seq)((StructValue.Child)capability.slot("workflow").value()).node().slot("steps").value()).elements().size();
    }
}
