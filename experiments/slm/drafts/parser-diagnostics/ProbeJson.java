package io.kcg.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Local observation transport: reuses the CI CLI's actual decoder and string encoder. */
public final class ProbeJson {
    private ProbeJson() {}

    public static Object read(Path path) throws IOException {
        return StrictJson.parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    public static void write(Path path, Object value) throws IOException {
        Files.writeString(path, encode(value) + "\n", StandardCharsets.UTF_8);
    }

    private static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return JsonStringEncoder.encode(text);
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof List<?> list) return list.stream().map(ProbeJson::encode).collect(Collectors.joining(",", "[", "]"));
        if (value instanceof Map<?, ?> map) return map.entrySet().stream()
                .map(entry -> JsonStringEncoder.encode((String) entry.getKey()) + ":" + encode(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
        throw new IllegalArgumentException("Unsupported observation value: " + value.getClass().getName());
    }
}
