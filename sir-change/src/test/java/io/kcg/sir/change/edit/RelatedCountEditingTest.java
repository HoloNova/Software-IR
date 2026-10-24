package io.kcg.sir.change.edit;

import static io.kcg.sir.change.edit.DraftNodes.*;
import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Editing a count projection: its own output name, the counted entity, and the field that joins the child row to
 * the current one are three different things, and a rename must move exactly one of them.
 */
class RelatedCountEditingTest {
    private static final SourceId ENTRY = SourceId.of("project.sir");
    private static final String SOURCE = """
            sir 0.3
            software EditCounts {
              metadata { displayName "Edit Counts"; namespace "example.counts"; }
              target { language java 21; framework spring_boot; persistence mybatis_plus;
                database mysql; build maven; interface rest; }
              sources {} imports {}
              declarations {
                enum CopyStatus { AVAILABLE, BORROWED }
                entity Book persistent { identity id: Id generated auto; field title: Name; }
                entity Copy persistent {
                  identity id: Id generated auto;
                  field book: Ref<Book>;
                  field status: CopyStatus;
                }
                view CatalogueBook from Book {
                  field id: Id;
                  field availableCopies: Serial = count(Copy, book == item and status == CopyStatus.AVAILABLE);
                }
              }
            }
            """;

    @Test void renamingTheJoinFieldRewritesTheConditionButNotTheCountOutput() {
        var edited = edit(snapshot(), new Command.Rename(
                Selector.root().named("declarations", "Copy").named("fields", "status"), "state"));
        String text = textOf(edited);
        assertTrue(text.contains("field availableCopies: Serial = count(Copy, book == item and state == CopyStatus.AVAILABLE);"), text);
    }

    @Test void renamingTheCountedEntityRewritesTheCountTargetAndLeavesTheConditionAlone() {
        var edited = edit(snapshot(), new Command.Rename(Selector.root().named("declarations", "Copy"), "Volume"));
        String text = textOf(edited);
        assertTrue(text.contains("count(Volume, book == item and status == CopyStatus.AVAILABLE)"), text);
    }

    @Test void renamingTheCountOutputDoesNotRenameTheEntityFieldWithTheSameSpelling() {
        var edited = edit(snapshot(), new Command.Rename(
                Selector.root().named("declarations", "CatalogueBook").named("fields", "availableCopies"), "copies"));
        String text = textOf(edited);
        assertTrue(text.contains("field copies: Serial = count(Copy, book == item and status == CopyStatus.AVAILABLE);"), text);
        assertFalse(text.contains("availableCopies"), text);
    }

    @Test void renamingTheEnumUpdatesBothTheDeclaredTypeAndTheMemberQualifier() {
        var edited = edit(snapshot(), new Command.Rename(Selector.root().named("declarations", "CopyStatus"), "Availability"));
        String text = textOf(edited);
        assertTrue(text.contains("field status: Availability;"), text);
        assertTrue(text.contains("status == Availability.AVAILABLE"), text);
    }

    @Test void anOrdinaryAddAndDeleteCanAuthorACountProjection() {
        var conditions = binary(name("book"), "EQ", name("item"));
        var value = node("CountRelated", "entity", REF, text("Copy"), "conditions", FACT, child(conditions));
        var field = node("NormalizedViewField", "name", FACT, text("bookCount"), "type", FACT, child(semantic("Serial")),
                "value", FACT, child(value));
        var view = Selector.root().named("declarations", "CatalogueBook");
        String plain = SOURCE.lines().filter(line -> !line.contains("availableCopies")).collect(java.util.stream.Collectors.joining("\n"));
        var without = new SourceSnapshot(ENTRY, Map.of(ENTRY, plain.getBytes(StandardCharsets.UTF_8)));
        var added = edit(without, new Command.Add(view.slot("fields"), child(field)));
        assertTrue(textOf(added).contains("field bookCount: Serial = count(Copy, book == item);"), textOf(added));
        var removed = edit(added.sources(), new Command.Delete(view.named("fields", "bookCount")));
        assertFalse(textOf(removed).contains("bookCount"), textOf(removed));
    }

    private static SourceSnapshot snapshot() { return new SourceSnapshot(ENTRY, Map.of(ENTRY, SOURCE.getBytes(StandardCharsets.UTF_8))); }
    private static String textOf(EditResult.Edited edited) { return new String(edited.sources().bytes(ENTRY), StandardCharsets.UTF_8); }
    private static EditResult.Edited edit(SourceSnapshot snapshot, Command command) {
        var batch = new CommandBatch(CommandBatch.PROTOCOL, "EditCounts", SourceEditor.sha256(snapshot), List.of(command));
        var result = new SourceEditor().edit(snapshot, batch);
        return assertInstanceOf(EditResult.Edited.class, result, () -> String.valueOf(result));
    }
}
