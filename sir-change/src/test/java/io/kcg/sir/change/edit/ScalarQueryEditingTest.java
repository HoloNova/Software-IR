package io.kcg.sir.change.edit;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.change.edit.DraftNodes.*;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScalarQueryEditingTest {
    @Test
    void ordinaryUpdateEditsQueryPredicateAndReanalyzesWithoutDedicatedCommands() {
        String text = EditFixtures.read("/valid/scalar-queries.sir");
        SourceId entry = SourceId.of("project.sir");
        SourceSnapshot source = new SourceSnapshot(entry, Map.of(entry, text.getBytes(StandardCharsets.UTF_8)));
        var target = Selector.root().named("declarations", "Check").slot("workflow").at("steps", 1).slot("predicate");
        var predicate = child(node("BooleanLiteral", "value", FACT, text("true")));
        var batch = new CommandBatch(CommandBatch.PROTOCOL, "ScalarQueries", SourceEditor.sha256(source), List.of(new Command.Update(target, predicate)));
        var edited = assertInstanceOf(EditResult.Edited.class, new SourceEditor().edit(source, batch));
        String rewritten = new String(edited.sources().bytes(entry), StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("count Entry where true as total;"), rewritten);
        assertTrue(rewritten.contains("exists Entry"), rewritten);
        assertEquals(text, new String(source.bytes(entry), StandardCharsets.UTF_8));
    }
}
