package io.kcg.sir.change.edit;

import static io.kcg.sir.change.edit.DraftNodes.*;
import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComputedViewEditingTest {
    private static final SourceId ENTRY = SourceId.of("project.sir");
    private static final String SOURCE = """
            sir 0.3
            software EditViews {
              metadata { displayName "Edit Views"; namespace "example.edit"; }
              target { language java 21; framework spring_boot; persistence mybatis_plus;
                database mysql; build maven; interface rest; }
              sources {} imports {}
              declarations {
                enum State { ACTIVE, CLOSED }
                type Seats = Count;
                entity Course persistent {
                  identity id: Id generated auto;
                  field capacity: Quantity; field enrolled: Count; field remaining: Count;
                }
                entity Other persistent { identity id: Id generated auto; field capacity: Quantity; }
                view CourseView from Course {
                  field capacity: Quantity;
                  field remaining: Count = item.capacity - item.enrolled;
                  field state: State = State.ACTIVE;
                  field enrolled: Seats = item.enrolled;
                }
              }
            }
            """;

    @Test void entityRenameChangesBoundOperandsAndPlainFollowersButNotComputedOutput() {
        var edited = edit(snapshot(), new Command.Rename(
                Selector.root().named("declarations", "Course").named("fields", "capacity"), "limit"));
        String text = textOf(edited);
        assertTrue(text.contains("field limit: Quantity;"), text);
        assertTrue(text.contains("field remaining: Count = item.limit - item.enrolled;"), text);
        assertTrue(text.contains("entity Other persistent { identity id: Id generated auto; field capacity: Quantity; }"), text);
    }

    @Test void computedOutputRenameDoesNotRenameAnEntityFieldWithTheSameSpelling() {
        var edited = edit(snapshot(), new Command.Rename(view().named("fields", "remaining"), "available"));
        String text = textOf(edited);
        assertTrue(text.contains("field available: Count = item.capacity - item.enrolled;"), text);
        assertTrue(text.contains("field remaining: Count;"), text);
    }

    @Test void ordinaryAddUpdateDeleteCanAuthorAndReparseComputedValues() {
        var sum = binary(member(name("item"), "capacity"), "SUB", member(name("item"), "enrolled"));
        var value = node("Computed", "expression", FACT, child(sum));
        var field = node("NormalizedViewField", "name", FACT, text("spare"), "type", FACT, child(semantic("Count")),
                "value", FACT, child(value));
        String plain = SOURCE.lines().filter(line -> !(line.stripLeading().startsWith("field ") && line.contains(" = ")))
                .collect(java.util.stream.Collectors.joining("\n"));
        var uncomputed = new SourceSnapshot(ENTRY, Map.of(ENTRY, plain.getBytes(StandardCharsets.UTF_8)));
        var added = edit(uncomputed, new Command.Add(view().slot("fields"), child(field)));
        assertTrue(textOf(added).contains("field spare: Count = item.capacity - item.enrolled;"));
        var changed = edit(added.sources(), new Command.Update(view().named("fields", "spare").slot("value")
                .slot("expression").slot("operator"), text("ADD")));
        assertTrue(textOf(changed).contains("field spare: Count = item.capacity + item.enrolled;"));
        var removed = edit(changed.sources(), new Command.Delete(view().named("fields", "spare")));
        assertFalse(textOf(removed).contains("field spare:"));
        assertEquals(SOURCE, new String(snapshot().bytes(ENTRY), StandardCharsets.UTF_8));
    }

    @Test void computedEnumAndSemanticTypeNamesFollowTheirFormalDefinitions() {
        var renamed = edit(snapshot(), new Command.Rename(Selector.root().named("declarations", "State"), "Status"));
        assertTrue(textOf(renamed).contains("field state: Status = Status.ACTIVE;"), textOf(renamed));
        var type = edit(renamed.sources(), new Command.Rename(Selector.root().named("declarations", "Seats"), "SeatCount"));
        assertTrue(textOf(type).contains("field enrolled: SeatCount = item.enrolled;"), textOf(type));
    }

    @Test void changingAPlainProjectionToAComputedValueStopsFollowingTheOldColumnName() {
        var value = node("Computed", "expression", FACT, child(member(name("item"), "enrolled")));
        var updated = edit(snapshot(), new Command.Update(view().named("fields", "capacity").slot("value"), child(value)));
        var renamed = edit(updated.sources(), new Command.Rename(
                Selector.root().named("declarations", "Course").named("fields", "capacity"), "limit"));
        assertTrue(textOf(renamed).contains("field capacity: Quantity = item.enrolled;"), textOf(renamed));
        assertTrue(textOf(renamed).contains("field remaining: Count = item.limit - item.enrolled;"), textOf(renamed));
    }

    private static Selector view() { return Selector.root().named("declarations", "CourseView"); }
    private static SourceSnapshot snapshot() { return new SourceSnapshot(ENTRY, Map.of(ENTRY, SOURCE.getBytes(StandardCharsets.UTF_8))); }
    private static String textOf(EditResult.Edited edited) { return new String(edited.sources().bytes(ENTRY), StandardCharsets.UTF_8); }
    private static EditResult.Edited edit(SourceSnapshot snapshot, Command command) {
        var batch = new CommandBatch(CommandBatch.PROTOCOL, "EditViews", SourceEditor.sha256(snapshot), List.of(command));
        var result = new SourceEditor().edit(snapshot, batch);
        return assertInstanceOf(EditResult.Edited.class, result, () -> String.valueOf(result));
    }
}
