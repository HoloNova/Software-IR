package io.kcg.sir.change.edit;

import static io.kcg.sir.change.edit.DraftNodes.*;
import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MultiplicationEditingTest {
    private static final SourceId ENTRY = SourceId.of("project.sir");
    private static final String SOURCE = """
            sir 0.3
            software EditMultiplication {
              metadata { displayName "Edit Multiplication"; namespace "example.edit"; }
              target { language java 21; framework spring_boot; persistence mybatis_plus;
                database mysql; build maven; interface rest; }
              sources {} imports {}
              declarations {
                input Numbers { field left: Count; field right: Count; field factor: Count; }
                input Other { field left: Count; }
                error Refused;
                capability Check {
                  input Numbers; output Unit; fails Refused; requires readonly; expose query;
                  workflow { validate input.left + input.right == input.factor else Refused; return unit; }
                }
              }
            }
            """;

    @Test void updateThenPreciseRenamePreservesTheRightGroupedEvaluationTree() {
        var snapshot = snapshot();
        var expression = binary(member(name("input"), "left"), "MUL",
                binary(member(name("input"), "right"), "MUL", member(name("input"), "factor")));
        var target = Selector.root().named("declarations", "Check").slot("workflow").at("steps", 0)
                .slot("condition").slot("left");
        var updated = edit(snapshot, new Command.Update(target, child(expression)));
        assertTrue(textOf(updated).contains("input.left * (input.right * input.factor)"), textOf(updated));
        var renamed = edit(updated.sources(), new Command.Rename(
                Selector.root().named("declarations", "Numbers").named("fields", "left"), "units"));
        assertTrue(textOf(renamed).contains("input.units * (input.right * input.factor)"), textOf(renamed));
        assertTrue(textOf(renamed).contains("input Other { field left: Count; }"), textOf(renamed));
        assertEquals(SOURCE, new String(snapshot.bytes(ENTRY), StandardCharsets.UTF_8));
    }

    @Test void anOrdinaryAddCanIntroduceMultiplicationAndTheResultCanBeEditedAgain() {
        var expression = binary(member(name("input"), "left"), "MUL", member(name("input"), "right"));
        var capability = queryCapability("Product", "Numbers", semantic("Count"), returnStep(expression));
        var added = edit(snapshot(), new Command.Add(Selector.root().slot("declarations"), child(capability)));
        assertTrue(textOf(added).contains("return input.left * input.right;"), textOf(added));
        var changed = edit(added.sources(), new Command.Update(Selector.root().named("declarations", "Product")
                .slot("workflow").at("steps", 0).slot("value").slot("operator"), text("ADD")));
        assertTrue(textOf(changed).contains("return input.left + input.right;"), textOf(changed));
    }

    private static SourceSnapshot snapshot() { return new SourceSnapshot(ENTRY, Map.of(ENTRY, SOURCE.getBytes(StandardCharsets.UTF_8))); }
    private static String textOf(EditResult.Edited edited) { return new String(edited.sources().bytes(ENTRY), StandardCharsets.UTF_8); }
    private static EditResult.Edited edit(SourceSnapshot snapshot, Command command) {
        var batch = new CommandBatch(CommandBatch.PROTOCOL, "EditMultiplication", SourceEditor.sha256(snapshot), List.of(command));
        var result = new SourceEditor().edit(snapshot, batch);
        return assertInstanceOf(EditResult.Edited.class, result, () -> String.valueOf(result));
    }
}
