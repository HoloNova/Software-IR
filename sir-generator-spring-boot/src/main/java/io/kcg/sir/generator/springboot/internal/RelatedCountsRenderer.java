package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import java.util.Optional;

/** Plain integer counts do not acquire Decimal aggregation or a global MyBatis interceptor. */
final class RelatedCountsRenderer {
    private RelatedCountsRenderer() {}

    static GeneratedFile render(ProjectArtifact.RelatedCounts artifact) {
        String content="package "+artifact.packageName()+";\n\n"+"""
            import java.math.BigDecimal;
            import java.math.BigInteger;
            import java.util.HashMap;
            import java.util.HashSet;
            import java.util.List;
            import java.util.Map;
            import java.util.UUID;
            import java.util.function.Function;
            import java.util.function.Supplier;

            public final class RelatedCounts {
                private RelatedCounts() {}

                public static <K> Map<K, Long> read(List<K> keys, int maxKeys, String keyAlias, String countAlias,
                        Function<Object, K> decodeKey, Supplier<List<Map<String, Object>>> execute) {
                    if (keys.size() > maxKeys) throw new IllegalStateException("related count source is unbounded");
                    if (keys.isEmpty()) return Map.of();
                    if (keys.stream().anyMatch(java.util.Objects::isNull))
                        throw new IllegalStateException("related count source key is null");
                    var requested = new HashSet<>(keys);
                    var rows = execute.get();
                    if (rows == null) throw new IllegalStateException("related count returned no result carrier");
                    Map<K, Long> result = new HashMap<>();
                    for (Map<String, Object> row : rows) {
                        if (row == null || row.size() != 2 || !row.containsKey(keyAlias) || !row.containsKey(countAlias))
                            throw new IllegalStateException("related count returned an invalid row shape");
                        K key = decodeKey.apply(row.get(keyAlias));
                        if (!requested.contains(key)) throw new IllegalStateException("related count returned an unrequested key");
                        long count = integral(row.get(countAlias));
                        if (count < 0) throw new IllegalStateException("related count returned a negative value");
                        if (result.putIfAbsent(key, count) != null)
                            throw new IllegalStateException("related count returned a duplicate key");
                    }
                    return Map.copyOf(result);
                }

                public static Long longKey(Object value) { return integral(value); }

                public static UUID uuidKey(Object value) {
                    if (value instanceof UUID uuid) return uuid;
                    if (value instanceof String text) {
                        try {
                            UUID uuid = UUID.fromString(text);
                            if (uuid.toString().equalsIgnoreCase(text)) return uuid;
                        } catch (IllegalArgumentException invalid) {
                            throw new IllegalStateException("related count key is not a UUID", invalid);
                        }
                    }
                    throw new IllegalStateException("related count key is not a canonical UUID");
                }

                private static long integral(Object value) {
                    try {
                        if (value instanceof Long n) return n;
                        if (value instanceof Integer n) return n.longValue();
                        if (value instanceof Short n) return n.longValue();
                        if (value instanceof Byte n) return n.longValue();
                        if (value instanceof BigInteger n) return n.longValueExact();
                        if (value instanceof BigDecimal n) return n.longValueExact();
                    } catch (ArithmeticException invalid) {
                        throw new IllegalStateException("related count value is fractional or outside Int64", invalid);
                    }
                    throw new IllegalStateException("related count value is not an exact integer");
                }
            }
            """;
        return new GeneratedFile(artifact.path(),content,artifact.id(),Optional.empty());
    }
}
