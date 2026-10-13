package io.kcg.sir.change.edit;

import static io.kcg.sir.change.edit.DraftNodes.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.change.edit.EditResult.Reason;
import io.kcg.sir.semantic.schema.StructValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End to end: source text in, batch of add/delete/update, source text out.
 *
 * <p>The baseline is a small course catalog. It deliberately has a comment inside one entity, so the
 * protected-comment rule is exercised against real text.
 */
class SourceEditorTest {
    private static final SourceEditor EDITOR = new SourceEditor();
    private static final Selector DECLARATIONS = Selector.root().slot("declarations");

    private static String source() {
        return EditFixtures.courseCatalog();
    }

    private static CommandBatch batch(String source, Command... commands) {
        return new CommandBatch(CommandBatch.PROTOCOL, "ProjectionParity", SourceEditor.sha256(source), List.of(commands));
    }

    private static EditResult.Edited edited(EditResult result) {
        return assertInstanceOf(EditResult.Edited.class, result, () -> String.valueOf(result));
    }

    private static EditResult.Refused refused(EditResult result) {
        return assertInstanceOf(EditResult.Refused.class, result, () -> String.valueOf(result));
    }

    /** Adds a search input and a query that uses it, and tightens an existing capability, in one batch. */
    private static Command[] searchByNameBatch() {
        StructValue newInput = child(input("SearchByNameInput", field("name", primitive("STRING"), constraint("notBlank"))));
        StructValue newQuery = child(queryCapability(
            "SearchCoursesByName",
            "SearchByNameInput",
            listOfRef("Course"),
            find("Course", binary(member(name("item"), "name"), "EQ", member(name("input"), "name")), "courses"),
            returnStep(name("courses"))
        ));
        // UpdateCourse's first check now also accepts a capacity-only change
        StructValue relaxed = child(binary(
            node("PresentExpression", "target", FACT, child(member(name("input"), "name"))),
            "OR",
            node("PresentExpression", "target", FACT, child(member(name("input"), "capacity")))
        ));
        return new Command[] {
            new Command.Add(DECLARATIONS, newInput),
            new Command.Add(DECLARATIONS, newQuery),
            new Command.Update(Selector.root().named("declarations", "UpdateCourse").slot("workflow").at("steps", 0).slot("condition"), relaxed)
        };
    }

    @Test
    void oneBatchAddsAnInputAddsACapabilityThatUsesItAndUpdatesAnOldWorkflow() {
        String base = source();

        EditResult.Edited result = edited(EDITOR.edit(base, batch(base, searchByNameBatch())));

        String text = result.source();
        assertTrue(text.contains("input SearchByNameInput {"), text);
        assertTrue(text.contains("capability SearchCoursesByName {"), text);
        assertTrue(text.contains("validate input.name.present or input.capacity.present else EmptyChange;"), text);
        assertEquals(2, result.delta().changes().stream().filter(c -> c.kind() == SemanticDelta.Kind.ADD).count(), result.delta().toString());
        assertEquals(1, result.delta().changes().stream().filter(c -> c.kind() == SemanticDelta.Kind.UPDATE).count(), result.delta().toString());
        assertEquals(0, result.delta().changes().stream().filter(c -> c.kind() == SemanticDelta.Kind.DELETE).count(), result.delta().toString());
        assertTrue(result.delta().changes().stream().anyMatch(c -> c.kind() == SemanticDelta.Kind.ADD && c.after().contains("SearchByNameInput")), result.delta().toString());
        assertTrue(result.delta().changes().stream().anyMatch(c -> c.kind() == SemanticDelta.Kind.ADD && c.after().contains("SearchCoursesByName")), result.delta().toString());
        assertTrue(result.delta().changes().stream().anyMatch(c -> c.kind() == SemanticDelta.Kind.UPDATE && c.path().contains("UpdateCourse")), result.delta().toString());
    }

    /**
     * The same three kinds of command, on a different business. Nothing was added to the editor for
     * goods and prices: an input, a query that uses it and a changed workflow condition are the same
     * structures as for courses, only the names and one decimal literal differ.
     */
    @Test
    void theSameKindOfBatchWorksOnAnotherBusinessWithoutAnyNewCommand() {
        String base = EditFixtures.read("/edit/campus-market.sir");
        CommandBatch goods = new CommandBatch(
            CommandBatch.PROTOCOL,
            "CampusMarketMinimal",
            SourceEditor.sha256(base),
            List.of(
                new Command.Add(DECLARATIONS, child(input("SearchGoodsByTitleInput", field("title", primitive("STRING"), constraint("notBlank"))))),
                new Command.Add(DECLARATIONS, child(queryCapability(
                    "SearchGoodsByTitle",
                    "SearchGoodsByTitleInput",
                    listOfRef("Goods"),
                    find("Goods", binary(member(name("item"), "title"), "EQ", member(name("input"), "title")), "found"),
                    returnStep(name("found"))
                ))),
                new Command.Update(
                    Selector.root().named("declarations", "PublishGoods").slot("workflow").at("steps", 0).slot("condition"),
                    child(binary(member(name("input"), "price"), "GE", node("DecimalLiteral", "value", FACT, text("0.05"))))
                )
            )
        );

        String text = edited(EDITOR.edit(base, goods)).source();

        assertTrue(text.contains("capability SearchGoodsByTitle {"), text);
        assertTrue(text.contains("validate input.price >= 0.05 else InvalidGoodsPrice;"), text);
        assertTrue(text.contains("where item.title == input.title"), text);
    }

    @Test
    void everythingTheBatchDidNotTouchStaysByteForByte() {
        String base = source();

        String text = edited(EDITOR.edit(base, batch(base, searchByNameBatch()))).source();

        // the comment inside Student, untouched declarations and the header are identical bytes
        assertTrue(text.contains("/* Student has no patch input, so this versioned field isolates the versioned fact"));
        for (String untouched : List.of("entity Course persistent {", "view StudentSummaryAlt from Student {", "error CourseNotFound;", "capability SearchCourses {")) {
            assertEquals(declarationText(base, untouched), declarationText(text, untouched), untouched);
        }

        assertTrue(text.startsWith(base.substring(0, base.indexOf("declarations {"))), "the header is untouched");
    }

    @Test
    void theEditedSourceCanBeEditedAgainBecauseItIsAnOrdinaryValidSource() {
        String base = source();
        String once = edited(EDITOR.edit(base, batch(base, searchByNameBatch()))).source();

        EditResult.Edited twice = edited(EDITOR.edit(once, batch(once, new Command.Delete(Selector.root().named("declarations", "SearchCoursesByName")))));

        assertFalse(twice.source().contains("SearchCoursesByName"));
        assertTrue(twice.source().contains("input SearchByNameInput {"), "only the named capability was removed");
    }

    @Test
    void aBatchWrittenForAnotherSourceIsStale() {
        String base = source();
        CommandBatch forBase = batch(base, new Command.Delete(Selector.root().named("declarations", "EmptyChange")));

        EditResult.Refused result = refused(EDITOR.edit(base + "\n// moved on\n", forBase));

        assertEquals(Reason.STALE_BASELINE, result.reason());
    }

    @Test
    void aBatchForAnotherSoftwareIsRefused() {
        String base = source();
        CommandBatch other = new CommandBatch(CommandBatch.PROTOCOL, "SomethingElse", SourceEditor.sha256(base), List.of(new Command.Delete(Selector.root().named("declarations", "EmptyChange"))));

        assertEquals(Reason.STALE_BASELINE, refused(EDITOR.edit(base, other)).reason());
    }

    @Test
    void aBatchFailsAsAWholeAndNamesTheCommandThatBrokeIt() {
        String base = source();

        EditResult.Refused result = refused(EDITOR.edit(base, batch(
            base,
            new Command.Delete(Selector.root().named("declarations", "EmptyChange")),
            new Command.Delete(Selector.root().named("declarations", "NoSuchThing"))
        )));

        assertEquals(Reason.COMMAND_REJECTED, result.reason());
        assertTrue(result.message().startsWith("command 2 of 2"), result.message());
    }

    @Test
    void deletingAFieldThatIsStillUsedIsReportedByTheFormalAnalysisNotByTheEditor() {
        String base = source();

        EditResult.Refused result = refused(EDITOR.edit(base, batch(
            base, new Command.Delete(Selector.root().named("declarations", "Course").named("fields", "code"))
        )));

        assertEquals(Reason.INVALID_RESULT, result.reason());
        assertTrue(result.details().stream().anyMatch(d -> d.startsWith("SIR-")), result.details().toString());
        assertTrue(result.candidateSource().isPresent(), "the author can see the text that was tried");
    }

    @Test
    void anAddedCapabilityThatNamesAnInputNobodyDefinedIsInvalid() {
        String base = source();
        StructValue query = child(queryCapability("Orphan", "NoSuchInput", listOfRef("Course"), find("Course", binary(name("item"), "EQ", name("item")), "courses"), returnStep(name("courses"))));

        EditResult.Refused result = refused(EDITOR.edit(base, batch(base, new Command.Add(DECLARATIONS, query))));

        assertEquals(Reason.INVALID_RESULT, result.reason());
    }

    @Test
    void aRewriteThatWouldRemoveACommentIsRefusedAndNamesIt() {
        String base = source();

        EditResult.Refused result = refused(EDITOR.edit(base, batch(
            base, new Command.Update(Selector.root().named("declarations", "Student").named("fields", "name").slot("constraints"), seq())
        )));

        assertEquals(Reason.PROTECTED_COMMENT, result.reason());
        assertTrue(result.details().get(0).contains("'Student'"), result.details().toString());
        assertTrue(result.details().get(0).startsWith("line "), result.details().toString());
    }

    @Test
    void deletingADeclarationThatContainsACommentIsAlsoRefused() {
        String base = source();

        assertEquals(Reason.PROTECTED_COMMENT, refused(EDITOR.edit(base, batch(base, new Command.Delete(Selector.root().named("declarations", "Student"))))).reason());
    }

    @Test
    void theSoftwareHeaderCannotBeEditedYet() {
        String base = source();

        EditResult.Refused result = refused(EDITOR.edit(base, batch(
            base, new Command.Update(Selector.root().slot("metadata").slot("displayName"), text("Renamed"))
        )));

        assertEquals(Reason.NOT_SUPPORTED_YET, result.reason());
    }

    @Test
    void updatingToTheSameValueChangesNothing() {
        String base = source();

        EditResult.Edited result = edited(EDITOR.edit(base, batch(
            base, new Command.Update(Selector.root().named("declarations", "StaleVersion").slot("httpStatus"), text("400"))
        )));

        assertEquals(base, result.source());
        assertTrue(result.delta().isEmpty());
        assertTrue(result.sourceChanges().isEmpty());
    }

    @Test
    void lineEndingsOfTheSourceAreKept() {
        String base = source().replace("\n", "\r\n");

        String text = edited(EDITOR.edit(base, batch(base, searchByNameBatch()))).source();

        assertFalse(text.replace("\r\n", "").contains("\n"), "no bare line feed was introduced");
        assertTrue(text.contains("capability SearchCoursesByName {\r\n"));
    }

    @Test
    void aSourceWithAByteOrderMarkIsNotSupportedYet() {
        String base = "﻿" + source();

        assertEquals(Reason.UNSUPPORTED_SOURCE, refused(EDITOR.edit(base, batch(base, new Command.Delete(Selector.root().named("declarations", "EmptyChange"))))).reason());
    }

    @Test
    void anUnknownProtocolIsRefused() {
        String base = source();
        CommandBatch future = new CommandBatch("sir-command/9", "ProjectionParity", SourceEditor.sha256(base), List.of(new Command.Delete(Selector.root().named("declarations", "EmptyChange"))));

        assertEquals(Reason.UNSUPPORTED_BATCH, refused(EDITOR.edit(base, future)).reason());
    }

    /** The text of the declaration that starts with {@code opening}, through its closing line. */
    private static String declarationText(String text, String opening) {
        int start = text.indexOf(opening);
        assertTrue(start >= 0, opening + " not found");
        int end = text.indexOf("\n    }\n", start);
        int semi = text.indexOf(";\n", start);
        boolean single = !opening.endsWith("{");
        return text.substring(start, single ? semi + 1 : end + 6);
    }
}
