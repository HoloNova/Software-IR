package io.kcg.sir.internal;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.source.SourceId;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class AstIdFactory {
    private final String root;
    private final Map<String, Integer> counters = new HashMap<>();
    private final Map<String, Integer> occurrences = new HashMap<>();

    AstIdFactory(SourceId sourceId) {
        this.root = "ast://" + encode(sourceId.value()) + "#document";
    }

    String rootPath() {
        return root;
    }

    AstNodeId id(String path) {
        return new AstNodeId(path);
    }

    String fixed(String parent, String kind) {
        return parent + "/" + encode(kind);
    }

    String named(String parent, String kind, String name) {
        String base = parent + "/" + encode(kind) + "/" + encode(name);
        int occurrence = occurrences.merge(base, 1, Integer::sum);
        return occurrence == 1 ? base : base + "/occurrence/" + ordinal(occurrence);
    }

    String indexed(String parent, String kind) {
        String key = parent + "|" + kind;
        int index = counters.merge(key, 1, Integer::sum);
        return parent + "/" + encode(kind) + "/" + ordinal(index);
    }

    private static String ordinal(int value) {
        String digits = Integer.toString(value);
        return value < 10_000 ? "0".repeat(4 - digits.length()) + digits : digits;
    }

    static String encode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = current & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-' || unsigned == '_' || unsigned == '.' || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append("%02X".formatted(unsigned));
            }
        }
        return encoded.toString();
    }
}
