package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Events-only fixture state: an independent read-only observer and a control connection that replays the seed between
 * sections. It never opens an application connection, so a wrong answer cannot agree with itself.
 */
final class EventsFixture implements AutoCloseable {
    static final List<String> TABLES = List.of("user", "access_token", "event", "registration");
    private static final List<String> DELETE_ORDER = List.of("registration", "access_token", "event", "user");
    private static final int JDBC_TIMEOUT_SECONDS = 5;
    private final BusinessSliceHarness slice;
    private final BusinessSliceHarness.Environment environment;
    private final Connection control;
    private final Connection observer;

    record Row(Map<String, Object> cells) {
        Row { cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells)); }
        long number(String column) { return ((Number) cells.get(column)).longValue(); }
        String text(String column) { return (String) cells.get(column); }
        Instant time(String column) { return (Instant) cells.get(column); }
        BigDecimal decimal(String column) { return (BigDecimal) cells.get(column); }
    }

    record State(Map<String, List<Row>> tables) {
        State {
            var copy = new LinkedHashMap<String, List<Row>>();
            tables.forEach((name, rows) -> copy.put(name, List.copyOf(rows)));
            tables = Collections.unmodifiableMap(copy);
        }
        List<Row> table(String name) { return Objects.requireNonNull(tables.get(name), name); }
        Row row(String table, long id) {
            return table(table).stream().filter(r -> r.number("id") == id)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("missing " + table + " id=" + id));
        }
        boolean has(String table, long id) { return table(table).stream().anyMatch(r -> r.number("id") == id); }
        State withEvent(long id, String column, Object value) {
            var next = new LinkedHashMap<>(tables);
            next.put("event", table("event").stream().map(r -> {
                if (r.number("id") != id) return r;
                var cells = new LinkedHashMap<>(r.cells());
                cells.put(column, value);
                return new Row(cells);
            }).toList());
            return new State(next);
        }
        State add(String table, Row row) {
            var next = new LinkedHashMap<>(tables);
            var rows = new ArrayList<>(table(table));
            rows.add(row);
            rows.sort(Comparator.comparingLong(r -> r.number("id")));
            next.put(table, List.copyOf(rows));
            return new State(next);
        }
        List<Row> addedSince(String table, State before) {
            return table(table).stream().filter(r -> before.table(table).stream()
                    .noneMatch(old -> Objects.equals(old.cells().get("id"), r.cells().get("id")))).toList();
        }
    }

    EventsFixture(BusinessSliceHarness slice, BusinessSliceHarness.Environment environment) throws SQLException {
        this.slice = slice;
        this.environment = environment;
        this.control = connect(false);
        try {
            this.observer = connect(true);
        } catch (SQLException error) {
            try { control.close(); } catch (SQLException closing) { error.addSuppressed(closing); }
            throw error;
        }
    }

    private Connection connect(boolean readOnly) throws SQLException {
        var properties = new Properties();
        properties.setProperty("user", readOnly ? environment.runtimeUsername() : environment.controlUsername());
        properties.setProperty("password", readOnly ? environment.runtimePassword() : environment.controlPassword());
        properties.setProperty("autoReconnect", "false");
        properties.setProperty("connectTimeout", Integer.toString(JDBC_TIMEOUT_SECONDS * 1000));
        properties.setProperty("socketTimeout", Integer.toString(JDBC_TIMEOUT_SECONDS * 1000));
        Connection connection = DriverManager.getConnection(slice.runtimeUrl().rendered(), properties);
        try {
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement()) { statement.execute("SET time_zone = '+00:00'"); }
            connection.setReadOnly(readOnly);
            return connection;
        } catch (SQLException error) {
            try { connection.close(); } catch (SQLException closing) { error.addSuppressed(closing); }
            throw error;
        }
    }

    boolean reset() throws SQLException, IOException {
        // TEST_FIXTURE_SQL: reset only between completed sections; never while HTTP requests are in flight.
        control.setAutoCommit(false);
        try {
            for (String table : DELETE_ORDER) {
                try (var statement = control.createStatement()) { statement.executeUpdate("DELETE FROM `" + table + "`"); }
            }
            String seed = BusinessSliceHarness.stripSqlComments(
                    ConformanceFixtures.readResource("/conformance/mysql/events-registration-seed.sql"));
            for (String sql : seed.split(";")) {
                if (sql.isBlank()) continue;
                try (var statement = control.createStatement()) { statement.execute(sql.strip()); }
            }
            control.commit();
        } catch (SQLException | IOException error) {
            try { control.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
            try { control.setAutoCommit(true); } catch (SQLException restoring) { error.addSuppressed(restoring); }
            throw error;
        }
        control.setAutoCommit(true);
        State state = snapshot();
        boolean valid = validSeed(state);
        slice.check("events fixture seed has complete rows, foreign keys, matching counters and unique pairs", valid, counts(state).toString());
        if (!valid) slice.setNotRunReason("events fixture seed self-check failed; no candidate business verdict");
        return valid;
    }

    private static Map<String, Integer> counts(State state) {
        var result = new LinkedHashMap<String, Integer>();
        TABLES.forEach(t -> result.put(t, state.table(t).size()));
        return Map.copyOf(result);
    }

    static boolean registeredMatchesRows(State state) {
        return state.table("event").stream().allMatch(e -> e.number("registered") <= e.number("capacity")
                && e.number("registered") == state.table("registration").stream().filter(r -> r.number("event_id") == e.number("id")).count());
    }

    private static boolean validSeed(State state) {
        if (!counts(state).equals(Map.of("user", 10, "access_token", 11, "event", 9, "registration", 9))) return false;
        var pairs = new java.util.HashSet<String>();
        return registeredMatchesRows(state)
                && state.table("registration").stream().allMatch(r -> state.has("event", r.number("event_id"))
                        && state.has("user", r.number("attendee_id"))
                        && pairs.add(r.number("event_id") + "/" + r.number("attendee_id")))
                && state.table("event").stream().allMatch(e -> state.has("user", e.number("organizer_id")))
                && state.table("access_token").stream().allMatch(t -> state.has("user", t.number("owner_id")));
    }

    State snapshot() throws SQLException {
        var result = new LinkedHashMap<String, List<Row>>();
        for (String table : TABLES) {
            var rows = new ArrayList<Row>();
            try (var query = observer.prepareStatement("SELECT * FROM `" + table + "` ORDER BY `id`")) {
                query.setQueryTimeout(JDBC_TIMEOUT_SECONDS);
                try (ResultSet data = query.executeQuery()) {
                    while (data.next()) rows.add(readRow(data));
                }
            }
            result.put(table, List.copyOf(rows));
        }
        return new State(result);
    }

    private static Row readRow(ResultSet data) throws SQLException {
        var values = new LinkedHashMap<String, Object>();
        var metadata = data.getMetaData();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            Object value;
            if (metadata.getColumnType(column) == Types.TIMESTAMP) {
                LocalDateTime local = data.getObject(column, LocalDateTime.class);
                value = local == null ? null : local.toInstant(ZoneOffset.UTC);
            } else {
                value = data.getObject(column);
                if (value instanceof Number n && !(value instanceof BigDecimal)) value = n.longValue();
            }
            values.put(metadata.getColumnName(column), value);
        }
        return new Row(values);
    }

    boolean observabilityAvailable() throws SQLException {
        try {
            if (scalar("SELECT @@performance_schema") != 1
                    || scalar("SELECT COUNT(*) FROM performance_schema.threads WHERE PROCESSLIST_ID=CONNECTION_ID()") != 1) {
                slice.setNotRunReason("events concurrency requires enabled performance_schema thread instrumentation");
                return false;
            }
            BlogLockWaits.read(control, environment.schemaName().value());
            return true;
        } catch (SQLException error) {
            if (!List.of(1044, 1142, 1143, 1227).contains(error.getErrorCode()) && !"42S02".equals(error.getSQLState())) throw error;
            slice.setNotRunReason("events lock observation privileges unavailable: " + BusinessSliceHarness.describe(error));
            return false;
        }
    }

    List<BlogLockWaits.Edge> waits() throws SQLException {
        return BlogLockWaits.read(control, environment.schemaName().value());
    }

    String runtimeUsername() { return environment.runtimeUsername(); }

    /** Holds the event row so that every competing request queues on it; the caller rolls the connection back. */
    Connection lockEvent(long id) throws SQLException {
        Connection blocker = connect(false);
        try {
            blocker.setAutoCommit(false);
            try (var query = blocker.prepareStatement("SELECT id FROM `event` WHERE id=? FOR UPDATE")) {
                query.setLong(1, id);
                query.setQueryTimeout(JDBC_TIMEOUT_SECONDS);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new SQLException("events barrier target is missing: " + id);
                }
            }
            return blocker;
        } catch (SQLException error) {
            try { blocker.close(); } catch (SQLException closing) { error.addSuppressed(closing); }
            throw error;
        }
    }

    static long connectionId(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("SELECT CONNECTION_ID()"); var rows = query.executeQuery()) {
            if (!rows.next()) throw new SQLException("missing MySQL connection id");
            return rows.getLong(1);
        }
    }

    /** TEST_FIXTURE_SQL: changes the fixture between requests, never while one is in flight. */
    void fixtureStatement(String sql) throws SQLException {
        try (var statement = control.createStatement()) { statement.executeUpdate(sql); }
    }

    private long scalar(String sql) throws SQLException {
        try (var query = control.prepareStatement(sql); var rows = query.executeQuery()) {
            if (!rows.next()) throw new SQLException("missing fixture scalar");
            return rows.getLong(1);
        }
    }

    @Override
    public void close() throws SQLException {
        SQLException failure = null;
        try { observer.close(); } catch (SQLException error) { failure = error; }
        try { control.close(); } catch (SQLException error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }
}
