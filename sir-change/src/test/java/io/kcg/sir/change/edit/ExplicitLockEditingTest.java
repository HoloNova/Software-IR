package io.kcg.sir.change.edit;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.change.edit.DraftNodes.text;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExplicitLockEditingTest {
    @Test void lockFactCanBeAddedRemovedAndPreciselyRenamedThroughOrdinaryCommands() throws Exception {
        String source;
        try (var input = getClass().getResourceAsStream("/valid/explicit-lock.sir")) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace(" for update", "");
        }
        var entry = SourceId.of("project.sir");
        var original = new SourceSnapshot(entry, Map.of(entry, source.getBytes(StandardCharsets.UTF_8)));
        var selector = Selector.root().named("declarations", "Book").slot("workflow").at("steps", 1).slot("forUpdate");
        var locked = edit(original, new Command.Update(selector, text("true")));
        assertTrue(new String(locked.sources().bytes(entry), StandardCharsets.UTF_8).contains("as room for update else RoomMissing;"));
        var renamed = edit(locked.sources(), new Command.Rename(Selector.root().named("declarations", "Room"), "Venue"));
        assertTrue(new String(renamed.sources().bytes(entry), StandardCharsets.UTF_8).contains("load Venue by input.roomId as room for update"));
        var ordinary = edit(renamed.sources(), new Command.Update(selector, text("false")));
        assertFalse(new String(ordinary.sources().bytes(entry), StandardCharsets.UTF_8).contains("for update"));
        assertEquals(source, new String(original.bytes(entry), StandardCharsets.UTF_8));
    }
    private static EditResult.Edited edit(SourceSnapshot source, Command command) {
        var result = new SourceEditor().edit(source, new CommandBatch(CommandBatch.PROTOCOL, "Locking", SourceEditor.sha256(source), List.of(command)));
        return assertInstanceOf(EditResult.Edited.class, result, () -> String.valueOf(result));
    }
}
