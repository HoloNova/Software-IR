package io.kcg.sir.generator.springboot.internal;

/** Conditional methods of the planned aggregate support; ordinary SUM files stay unchanged. */
final class GroupedAggregateMethods {
    private GroupedAggregateMethods() {}

    static String render(String pageType) {
        return """
                    public record Group<K>(K key, BigDecimal total) {
                        public Group { java.util.Objects.requireNonNull(key); java.util.Objects.requireNonNull(total); }
                    }

                    public static Long longKey(Object value) {
                        if (!(value instanceof Long key)) throw new IllegalStateException("Group key must be a signed BIGINT");
                        return key;
                    }

                    public static java.util.UUID uuidKey(Object value) {
                        if (value instanceof java.util.UUID uuid) return uuid;
                        if (value instanceof String text) {
                            var uuid = java.util.UUID.fromString(text);
                            if (uuid.toString().equalsIgnoreCase(text)) return uuid;
                        }
                        throw new IllegalStateException("Group key must be a UUID identity");
                    }

                    public static <T, K> GROUP_PAGE<Group<K>> grouped(Query<T> query,
                            java.util.function.Consumer<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<T>> filter,
                            Integer requestedPage, Integer requestedSize, int defaultSize, int maxSize, int maxPage,
                            java.util.function.Supplier<? extends RuntimeException> invalidPage,
                            String keyColumn, String dataProjection, java.util.function.Function<Object, K> key,
                            java.util.function.Function<Query<T>, Long> count,
                            java.util.function.Function<Query<T>, List<Map<String, Object>>> data) {
                        int page = requestedPage == null ? 1 : requestedPage;
                        int size = requestedSize == null ? defaultSize : requestedSize;
                        if (page < 1 || page > maxPage || size < 1 || size > maxSize) throw invalidPage.get();
                        filter.accept(query.lambda());
                        Long total = count.apply(query);
                        if (total == null || total < 0) throw new IllegalStateException("Invalid aggregate group count");
                        long offset = ((long) page - 1) * size;
                        query.select(dataProjection).groupBy(keyColumn).orderByAsc(keyColumn)
                            .last("LIMIT " + size + " OFFSET " + offset);
                        List<Map<String, Object>> rows = data.apply(query);
                        if (rows == null || rows.size() > size || rows.size() > Math.max(0, total - offset))
                            throw new IllegalStateException("Invalid aggregate page size");
                        List<Group<K>> groups = rows.stream().map(row -> group(row, key)).toList();
                        return new GROUP_PAGE<>(total, page, size, groups);
                    }

                    private static <K> Group<K> group(Map<String, Object> row, java.util.function.Function<Object, K> key) {
                        if (row == null || !row.keySet().equals(java.util.Set.of("g_key", "g_total"))
                                || !(row.get("g_total") instanceof BigDecimal total))
                            throw new IllegalStateException("Aggregate page must contain exactly its key and Decimal total");
                        return new Group<>(key.apply(row.get("g_key")), total);
                    }

                """.replace("GROUP_PAGE", pageType);
    }
}
