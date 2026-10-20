package io.kcg.cli;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.change.edit.Command;
import io.kcg.sir.semantic.schema.StructValue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CommandJsonTest {
    static String batch(String commands) {
        return "{\"protocolVersion\":\"" + CommandJson.PROTOCOL + "\",\"protocol\":\"sir-command/1\","
            + "\"softwareName\":\"Demo\",\"baselineSha256\":\"" + "a".repeat(64) + "\",\"commands\":[" + commands + "]}";
    }
    static final String TARGET = "{\"steps\":[{\"slot\":\"declarations\",\"name\":\"Invalid\"},{\"slot\":\"httpStatus\"}]}";
    static final String UPDATE = "{\"op\":\"update\",\"target\":" + TARGET + ",\"value\":{\"type\":\"text\",\"value\":\"422\"}}";

    @Test void decodesAllThreeOperationsAndAssignsNodeRolesFromSchema() {
        String child = "{\"type\":\"child\",\"kind\":\"NormalizedError\",\"slots\":{\"name\":{\"type\":\"text\",\"value\":\"NewError\"},\"httpStatus\":{\"type\":\"text\",\"value\":\"409\"}}}";
        var batch = CommandJson.decode(batch(UPDATE + ",{\"op\":\"delete\",\"target\":" + TARGET + "},"
            + "{\"op\":\"add\",\"container\":{\"steps\":[{\"slot\":\"declarations\"}]},\"value\":" + child + "}"));
        assertInstanceOf(Command.Update.class, batch.commands().get(0));
        assertInstanceOf(Command.Delete.class, batch.commands().get(1));
        var add = assertInstanceOf(Command.Add.class, batch.commands().get(2));
        var value = assertInstanceOf(StructValue.Child.class, add.value());
        assertEquals("NormalizedError", value.node().kind());
        var encoded = new StringBuilder(); CommandJson.writeValue(encoded, add.value());
        assertEquals(child, encoded.toString());
    }

    @Test void decodesARenameAndRefusesOneWithoutAName() {
        String target = "{\"steps\":[{\"slot\":\"declarations\",\"name\":\"Note\"}]}";
        var batch = CommandJson.decode(batch("{\"op\":\"rename\",\"target\":" + target + ",\"name\":\"Memo\"}"));
        var rename = assertInstanceOf(io.kcg.sir.change.edit.Command.Rename.class, batch.commands().getFirst());
        assertEquals("Memo", rename.name());
        assertThrows(IllegalArgumentException.class, () -> CommandJson.decode(batch("{\"op\":\"rename\",\"target\":" + target + "}")));
        assertThrows(IllegalArgumentException.class, () -> CommandJson.decode(batch("{\"op\":\"rename\",\"target\":" + target + ",\"name\":\"Memo\",\"value\":1}")));
    }

    @Test void rejectsAmbiguousUnknownAndNullFieldsInsteadOfIgnoringThem() {
        for (String bad : new String[] {
            batch(UPDATE).replace("\"commands\":", "\"commands\":[],\"commands\":"),
            batch(UPDATE).replace("\"op\":\"update\"", "\"op\":\"update\",\"typo\":true"),
            batch(UPDATE).replace("\"name\":\"Invalid\"", "\"name\":\"Invalid\",\"index\":0"),
            batch(UPDATE).replace("\"op\":\"update\"", "\"op\":\"update\",\"expectedOld\":null"),
            batch(UPDATE) + "{}",
            batch(""),
            batch(UPDATE).replace("\"steps\":[", "\"steps\":null,\"ignored\":[")
        }) assertThrows(IllegalArgumentException.class, () -> CommandJson.decode(bad), bad);
    }

    @Test void cannotSupplyIdentitySourceDerivedOrUnknownNodeSlots() {
        String base = "{\"op\":\"add\",\"container\":{\"steps\":[{\"slot\":\"declarations\"}]},\"value\":{\"type\":\"child\",\"kind\":\"NormalizedError\",\"slots\":%s}}";
        for (String slots : new String[] {"{}", "{\"id\":{\"type\":\"text\",\"value\":\"forged\"}}", "{\"span\":{\"type\":\"none\"}}", "{\"invented\":{\"type\":\"none\"}}"})
            assertThrows(IllegalArgumentException.class, () -> CommandJson.decode(batch(base.formatted(slots))));
        assertThrows(IllegalArgumentException.class, () -> CommandJson.decode(batch(base.formatted("{}").replace("NormalizedError", "java.lang.Runtime"))));
    }

    @Test void strictUnicodeNumbersAndBudgetsRejectBeforeCommandsRun() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> CommandJson.decode(batch(UPDATE).replace("422", "\\ud800")));
        assertThrows(IllegalArgumentException.class, () -> CommandJson.decode(batch(UPDATE).replace("\"name\":\"Invalid\"", "\"index\":2147483648")));
        assertThrows(IllegalArgumentException.class, () -> StrictJson.parse("[".repeat(66) + "0" + "]".repeat(66)));
        assertThrows(IllegalArgumentException.class, () -> StrictJson.parse("{\"a\":01}"));
        assertThrows(IllegalArgumentException.class, () -> CommandJson.read(new ByteArrayInputStream(new byte[] {(byte)0xff})));
        assertThrows(IllegalArgumentException.class, () -> CommandJson.read(new ByteArrayInputStream(new byte[CommandJson.MAX_BYTES + 1])));
        assertEquals(1, CommandJson.read(new ByteArrayInputStream(batch(UPDATE).getBytes(StandardCharsets.UTF_8))).commands().size());
    }
}
