package io.kcg.cli;

import io.kcg.sir.application.api.SourceEditingApplication;
import io.kcg.sir.change.edit.Command;
import io.kcg.sir.change.edit.CommandBatch;
import io.kcg.sir.change.edit.Selector;
import io.kcg.sir.semantic.schema.SlotKind;
import io.kcg.sir.semantic.schema.SoftwareSchema;
import io.kcg.sir.semantic.schema.StructNode;
import io.kcg.sir.semantic.schema.StructValue;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** The external envelope maps only the three commands and four generic values; node knowledge comes from the schema. */
final class CommandJson {
    static final String PROTOCOL = "KCG-CLI-COMMANDS-V1";
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final SoftwareSchema SCHEMA = SoftwareSchema.normalizedModel();
    private CommandJson() {}

    static CommandBatch read(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw StrictJson.bad("command JSON exceeds 8 MiB");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) { throw StrictJson.bad("command JSON must be valid UTF-8"); }
        return decode(text);
    }

    static CommandBatch decode(String json) {
        var object = object(StrictJson.parse(json));
        fields(object, Set.of("protocolVersion", "protocol", "softwareName", "baselineSha256", "commands"), Set.of());
        if (!PROTOCOL.equals(text(object.get("protocolVersion")))) throw StrictJson.bad("unsupported external command protocol");
        String hash = text(object.get("baselineSha256"));
        if (!hash.matches("[0-9a-f]{64}")) throw StrictJson.bad("baselineSha256 must be 64 lower-case hex digits");
        var raw = array(object.get("commands"));
        if (raw.isEmpty() || raw.size() > SourceEditingApplication.MAX_COMMANDS) throw StrictJson.bad("batch must contain 1..1000 commands");
        var commands = new ArrayList<Command>();
        for (Object entry : raw) commands.add(command(object(entry)));
        return new CommandBatch(text(object.get("protocol")), text(object.get("softwareName")), hash, commands);
    }

    private static Command command(Map<String, Object> object) {
        String op = text(object.get("op"));
        return switch (op) {
            case "add" -> {
                fields(object, Set.of("op", "container", "value"), Set.of("position"));
                yield new Command.Add(selector(object.get("container")), value(object.get("value")),
                    object.containsKey("position") ? OptionalInt.of(index(object.get("position"))) : OptionalInt.empty());
            }
            case "delete" -> {
                fields(object, Set.of("op", "target"), Set.of("expectedOld"));
                yield new Command.Delete(selector(object.get("target")), expected(object));
            }
            case "update" -> {
                fields(object, Set.of("op", "target", "value"), Set.of("expectedOld"));
                yield new Command.Update(selector(object.get("target")), value(object.get("value")), expected(object));
            }
            case "rename" -> {
                fields(object, Set.of("op", "target", "name"), Set.of());
                yield new Command.Rename(selector(object.get("target")), text(object.get("name")));
            }
            default -> throw StrictJson.bad("unknown operation; expected add, delete, update or rename");
        };
    }

    private static Optional<StructValue> expected(Map<String, Object> object) {
        return object.containsKey("expectedOld") ? Optional.of(value(object.get("expectedOld"))) : Optional.empty();
    }

    private static Selector selector(Object raw) {
        var object = object(raw);
        fields(object, Set.of("steps"), Set.of());
        var entries = array(object.get("steps"));
        if (entries.size() > 64) throw StrictJson.bad("selector exceeds 64 steps");
        var steps = new ArrayList<Selector.Step>();
        for (Object entry : entries) {
            var step = object(entry);
            fields(step, Set.of("slot"), Set.of("name", "index"));
            if (step.containsKey("name") && step.containsKey("index")) throw StrictJson.bad("selector uses name or index, not both");
            String slot = text(step.get("slot"));
            if (slot.isBlank()) throw StrictJson.bad("selector slot must not be blank");
            steps.add(new Selector.Step(slot, step.containsKey("name") ? Optional.of(text(step.get("name"))) : Optional.empty(),
                step.containsKey("index") ? Optional.of(index(step.get("index"))) : Optional.empty()));
        }
        return new Selector(steps);
    }

    private static StructValue value(Object raw) {
        var object = object(raw);
        return switch (text(object.get("type"))) {
            case "text" -> {
                fields(object, Set.of("type", "value"), Set.of());
                yield new StructValue.Text(text(object.get("value")));
            }
            case "none" -> {
                fields(object, Set.of("type"), Set.of());
                yield new StructValue.None();
            }
            case "seq" -> {
                fields(object, Set.of("type", "elements"), Set.of());
                yield new StructValue.Seq(array(object.get("elements")).stream().map(CommandJson::value).toList());
            }
            case "child" -> {
                fields(object, Set.of("type", "kind", "slots"), Set.of());
                String kind = text(object.get("kind"));
                var node = SCHEMA.byKind(kind).orElseThrow(() -> StrictJson.bad("unknown schema node: " + kind));
                var values = object(object.get("slots"));
                for (String key : values.keySet()) {
                    var slot = node.slots().stream().filter(s -> s.key().equals(key)).findFirst()
                        .orElseThrow(() -> StrictJson.bad("unknown slot: " + kind + "." + key));
                    if (!editable(slot.kind())) throw StrictJson.bad("commands cannot supply " + slot.kind() + " slot: " + key);
                }
                var slots = new ArrayList<StructNode.Slot>();
                for (var slot : node.slots()) {
                    if (!editable(slot.kind())) continue;
                    if (!values.containsKey(slot.key())) throw StrictJson.bad("missing authored slot: " + kind + "." + slot.key());
                    slots.add(new StructNode.Slot(slot.key(), slot.kind(), value(values.get(slot.key()))));
                }
                yield new StructValue.Child(new StructNode(kind, slots));
            }
            default -> throw StrictJson.bad("unknown value type; expected text, none, child or seq");
        };
    }

    static boolean editable(SlotKind role) { return role == SlotKind.FACT || role == SlotKind.REFERENCE; }
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw StrictJson.bad("expected a JSON object");
        return (Map<String, Object>) value;
    }
    private static List<?> array(Object value) {
        if (!(value instanceof List<?> list)) throw StrictJson.bad("expected a JSON array");
        return list;
    }
    private static String text(Object value) {
        if (!(value instanceof String text)) throw StrictJson.bad("expected a JSON string");
        return text;
    }
    private static int index(Object value) {
        if (!(value instanceof BigDecimal number)) throw StrictJson.bad("position/index must be an integer");
        try {
            int index = number.intValueExact();
            if (index < 0) throw StrictJson.bad("position/index must not be negative");
            return index;
        } catch (ArithmeticException e) { throw StrictJson.bad("position/index must be a 32-bit integer"); }
    }
    private static void fields(Map<String, Object> object, Set<String> required, Set<String> optional) {
        for (String field : object.keySet()) {
            if (!required.contains(field) && !optional.contains(field)) throw StrictJson.bad("unknown field: " + field);
        }
        // Sorted diagnostics do not depend on Set.of's per-process iteration order.
        for (String field : required.stream().sorted().toList()) {
            if (!object.containsKey(field)) throw StrictJson.bad("missing field: " + field);
        }
    }

    /** A batch of {@code add} commands, one per declaration, ready to be sent to {@code edit} as it is. */
    static String writeBatch(String softwareName, String baselineSha256, List<StructNode> declarations) {
        var out = new StringBuilder("{");
        JsonFields.field(out, "protocolVersion", PROTOCOL);
        out.append(','); JsonFields.field(out, "protocol", CommandBatch.PROTOCOL);
        out.append(','); JsonFields.field(out, "softwareName", softwareName);
        out.append(','); JsonFields.field(out, "baselineSha256", baselineSha256);
        out.append(",\"commands\":[");
        for (int i = 0; i < declarations.size(); i++) {
            if (i != 0) out.append(',');
            out.append("{\"op\":\"add\",\"container\":{\"steps\":[{\"slot\":\"declarations\"}]},\"value\":");
            writeValue(out, new StructValue.Child(declarations.get(i)));
            out.append('}');
        }
        return out.append("]}").toString();
    }

    /** Public-facing templates omit identities and computed/source slots; roles are assigned by the schema on read. */
    static void writeValue(StringBuilder out, StructValue value) {
        switch (value) {
            case StructValue.Text text -> { out.append("{\"type\":\"text\",\"value\":").append(JsonStringEncoder.encode(text.value())).append('}'); }
            case StructValue.None none -> out.append("{\"type\":\"none\"}");
            case StructValue.Seq seq -> {
                out.append("{\"type\":\"seq\",\"elements\":[");
                for (int i = 0; i < seq.elements().size(); i++) { if (i != 0) out.append(','); writeValue(out, seq.elements().get(i)); }
                out.append("]}");
            }
            case StructValue.Child child -> {
                out.append("{\"type\":\"child\",\"kind\":").append(JsonStringEncoder.encode(child.node().kind())).append(",\"slots\":{");
                boolean first = true;
                for (var slot : child.node().slots()) {
                    if (!editable(slot.kind())) continue;
                    if (!first) out.append(','); first = false;
                    out.append(JsonStringEncoder.encode(slot.key())).append(':'); writeValue(out, slot.value());
                }
                out.append("}}");
            }
        }
    }
}
