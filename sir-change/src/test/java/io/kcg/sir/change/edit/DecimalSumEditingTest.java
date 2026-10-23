package io.kcg.sir.change.edit;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.change.edit.DraftNodes.*;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DecimalSumEditingTest {
    private static final SourceId ENTRY = SourceId.of("project.sir");
    @ParameterizedTest @ValueSource(strings = {"\n", "\r\n"})
    void entityAndMeasurementRenameOnlyTheirBoundReferences(String newline) {
        var source = source(newline);
        var edited = edit(source, List.of(
                new Command.Rename(Selector.root().named("declarations", "Entry").named("fields", "amount"), "cost"),
                new Command.Rename(Selector.root().named("declarations", "Entry"), "Movement")));
        String text = new String(edited.sources().bytes(ENTRY), StandardCharsets.UTF_8);
        assertTrue(text.contains("sum Movement.cost where"), text);
        assertTrue(text.contains("item.cost >= input.minimum"), text);
        assertTrue(text.contains("name == \"amount\""), text);
        assertArrayEquals(source.bytes(ENTRY), source(newline).bytes(ENTRY));
    }
    @Test void addUpdateAndDeleteUseOrdinaryCommandsAndReparseTheWholeSoftware() {
        var step = child(node("SumStep", "entitySymbol", REF, text("Entry"),
                "fieldSymbol", REF, text("amount"), "predicate", FACT, child(node("BooleanLiteral", "value", FACT, text("true"))),
                "resultVariable", REF, text("extra")));
        var steps = Selector.root().named("declarations", "Total").slot("workflow").slot("steps");
        var added = edit(source("\n"), List.of(new Command.Add(steps, step, OptionalInt.of(0))));
        assertTrue(textOf(added).contains("sum Entry.amount where true as extra;"));
        var changed = edit(added.sources(), List.of(new Command.Update(
                Selector.root().named("declarations", "Total").slot("workflow").at("steps", 0).slot("predicate"),
                child(node("BooleanLiteral", "value", FACT, text("false"))))));
        assertTrue(textOf(changed).contains("sum Entry.amount where false as extra;"));
        var deleted = edit(changed.sources(), List.of(new Command.Delete(
                Selector.root().named("declarations", "Total").slot("workflow").at("steps", 0))));
        assertFalse(textOf(deleted).contains("as extra;"));
    }
    private static SourceSnapshot source(String newline) {
        String source = EditFixtures.read("/valid/decimal-sum.sir").replace("\r\n", "\n")
                .replace("name == \"target\"", "name == \"amount\"").replace("\n", newline);
        return new SourceSnapshot(ENTRY, Map.of(ENTRY, source.getBytes(StandardCharsets.UTF_8)));
    }
    private static String textOf(EditResult.Edited edited) { return new String(edited.sources().bytes(ENTRY), StandardCharsets.UTF_8); }
    private static EditResult.Edited edit(SourceSnapshot source, List<Command> commands) {
        var result = new SourceEditor().edit(source, new CommandBatch(CommandBatch.PROTOCOL, "DecimalSum", SourceEditor.sha256(source), commands));
        return assertInstanceOf(EditResult.Edited.class, result, () -> String.valueOf(result));
    }
}
