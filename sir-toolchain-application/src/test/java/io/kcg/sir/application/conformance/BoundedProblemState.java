package io.kcg.sir.application.conformance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** An immutable, complete JDBC snapshot. Allowed deltas never reuse the candidate's changed values. */
record BoundedProblemState(Map<String, List<BoundedProblemState.Row>> tables) {
    BoundedProblemState {
        var copy = new LinkedHashMap<String, List<Row>>();
        tables.forEach((table, rows) -> copy.put(table, List.copyOf(rows)));
        tables = Collections.unmodifiableMap(copy);
    }

    record Row(Map<String, Object> cells) {
        Row { cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells)); }
        static Row of(Object... pairs) { return new Row(pairs(pairs)); }
        Object value(String column) {
            if (!cells.containsKey(column)) throw new IllegalArgumentException("missing column " + column);
            return cells.get(column);
        }
        long number(String column) { return ((Number) value(column)).longValue(); }
        String text(String column) { return (String) value(column); }
        Row with(Object... pairs) {
            var next = new LinkedHashMap<>(cells);
            pairs(pairs).forEach((key, value) -> {
                if (!cells.containsKey(key)) throw new IllegalArgumentException("unknown column " + key);
                next.put(key, value);
            });
            return new Row(next);
        }
        private static Map<String, Object> pairs(Object[] pairs) {
            if (pairs.length % 2 != 0) throw new IllegalArgumentException("expected column/value pairs");
            var values = new LinkedHashMap<String, Object>();
            for (int i = 0; i < pairs.length; i += 2) {
                String key = (String) pairs[i];
                if (values.containsKey(key)) throw new IllegalArgumentException("duplicate column " + key);
                values.put(key, pairs[i + 1]);
            }
            return values;
        }
    }

    List<Row> table(String table) { return Objects.requireNonNull(tables.get(table), table); }
    Row row(String table, long id) {
        return table(table).stream().filter(r -> Objects.equals(r.value("id"), id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("missing " + table + " id=" + id));
    }
    boolean has(String table, long id) { return table(table).stream().anyMatch(r -> Objects.equals(r.value("id"), id)); }
    BoundedProblemState with(String table, long id, Object... pairs) {
        row(table, id);
        var next = new LinkedHashMap<>(tables);
        next.put(table, table(table).stream().map(r -> r.number("id") == id ? r.with(pairs) : r).toList());
        return new BoundedProblemState(next);
    }
    BoundedProblemState add(String table, Row row) {
        if (has(table, row.number("id"))) throw new IllegalArgumentException("duplicate expected id");
        var rows = new ArrayList<>(table(table));
        rows.add(row);
        rows.sort(Comparator.comparingLong(r -> r.number("id")));
        var next = new LinkedHashMap<>(tables);
        next.put(table, List.copyOf(rows));
        return new BoundedProblemState(next);
    }
    List<Row> addedSince(String table, BoundedProblemState before) {
        return table(table).stream().filter(r -> !before.has(table, r.number("id"))).toList();
    }
}
