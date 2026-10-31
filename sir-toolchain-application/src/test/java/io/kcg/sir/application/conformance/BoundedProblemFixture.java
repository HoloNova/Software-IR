package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;

/** Shared mechanics for the two bounded-loop oracles; table sets and business invariants belong to each problem. */
final class BoundedProblemFixture implements AutoCloseable {
    private static final int JDBC_SECONDS = 5;
    record Definition(List<String> tables, Map<String, Integer> seedCounts, Predicate<BoundedProblemState> seedInvariant) {
        Definition {
            tables = List.copyOf(tables);
            seedCounts = Map.copyOf(seedCounts);
            if (!java.util.Set.copyOf(tables).equals(seedCounts.keySet())
                    || tables.stream().anyMatch(t -> !t.matches("[a-z][a-z_]*"))) {
                throw new IllegalArgumentException("invalid fixture table definition");
            }
        }
    }
    final BusinessSliceHarness slice;
    private final BusinessSliceHarness.Environment environment;
    private final BusinessSliceHarness.Fixture fixture;
    private final Definition definition;
    private final Connection control;
    private final Connection observer;

    BoundedProblemFixture(BusinessSliceHarness slice, BusinessSliceHarness.Environment environment,
                          BusinessSliceHarness.Fixture fixture, Definition definition) throws SQLException {
        this.slice = slice;
        this.environment = environment;
        this.fixture = fixture;
        this.definition = definition;
        control = connect(false);
        try { observer = connect(true); }
        catch (SQLException error) {
            try { control.close(); } catch (SQLException close) { error.addSuppressed(close); }
            throw error;
        }
    }

    private Connection connect(boolean readOnly) throws SQLException {
        var properties = new Properties();
        properties.setProperty("user", readOnly ? environment.runtimeUsername() : environment.controlUsername());
        properties.setProperty("password", readOnly ? environment.runtimePassword() : environment.controlPassword());
        properties.setProperty("autoReconnect", "false");
        properties.setProperty("connectTimeout", Integer.toString(JDBC_SECONDS * 1000));
        properties.setProperty("socketTimeout", Integer.toString(JDBC_SECONDS * 1000));
        Connection connection = DriverManager.getConnection(slice.runtimeUrl().rendered(), properties);
        try {
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement()) { statement.execute("SET time_zone = '+00:00'"); }
            connection.setReadOnly(readOnly);
            return connection;
        } catch (SQLException error) {
            try { connection.close(); } catch (SQLException close) { error.addSuppressed(close); }
            throw error;
        }
    }

    boolean reset() throws SQLException, IOException {
        // TEST_FIXTURE_SQL: replay only between fully completed sections, never during a request.
        control.setAutoCommit(false);
        try {
            for (String table : definition.tables().reversed()) statement("DELETE FROM `" + table + "`");
            String seed = BusinessSliceHarness.stripSqlComments(ConformanceFixtures.readResource(fixture.seedResource()));
            for (String sql : seed.split(";")) if (!sql.isBlank()) statement(sql.strip());
            control.commit();
        } catch (SQLException | IOException error) {
            try { control.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
            throw error;
        } finally { control.setAutoCommit(true); }
        var state = snapshot();
        var counts = new LinkedHashMap<String, Integer>();
        definition.tables().forEach(t -> counts.put(t, state.table(t).size()));
        boolean valid = definition.seedCounts().equals(counts) && definition.seedInvariant().test(state);
        slice.check("fixture seed counts, foreign keys and business invariants", valid, counts.toString());
        if (!valid) slice.setNotRunReason("fixture seed self-check failed; not a candidate verdict");
        return valid;
    }

    BoundedProblemState snapshot() throws SQLException {
        var tables = new LinkedHashMap<String, List<BoundedProblemState.Row>>();
        for (String table : definition.tables()) {
            var rows = new ArrayList<BoundedProblemState.Row>();
            try (var query = observer.prepareStatement("SELECT * FROM `" + table + "` ORDER BY `id`")) {
                query.setQueryTimeout(JDBC_SECONDS);
                try (var data = query.executeQuery()) { while (data.next()) rows.add(readRow(data)); }
            }
            tables.put(table, List.copyOf(rows));
        }
        return new BoundedProblemState(tables);
    }

    private static BoundedProblemState.Row readRow(ResultSet data) throws SQLException {
        var cells = new LinkedHashMap<String, Object>();
        var metadata = data.getMetaData();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            Object value = switch (metadata.getColumnType(i)) {
                case Types.TIMESTAMP -> {
                    var time = data.getObject(i, LocalDateTime.class);
                    yield time == null ? null : time.toInstant(ZoneOffset.UTC);
                }
                case Types.DATE -> data.getObject(i, LocalDate.class);
                default -> normalize(data.getObject(i));
            };
            cells.put(metadata.getColumnName(i), value);
        }
        return new BoundedProblemState.Row(cells);
    }

    static Object normalize(Object value) {
        if (value instanceof Boolean flag) return flag ? 1L : 0L;
        if (value instanceof Number n && !(n instanceof java.math.BigDecimal)) return n.longValue();
        return value;
    }

    boolean observabilityAvailable() throws SQLException {
        try {
            try (var query = control.prepareStatement("SELECT @@performance_schema"); var result = query.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    slice.setNotRunReason("concurrency requires enabled performance_schema");
                    return false;
                }
            }
            waits();
            return true;
        } catch (SQLException error) {
            if (!List.of(1044, 1142, 1143, 1227).contains(error.getErrorCode()) && !"42S02".equals(error.getSQLState())) throw error;
            slice.setNotRunReason("lock-observation privileges unavailable: " + BusinessSliceHarness.describe(error));
            return false;
        }
    }

    List<BlogLockWaits.Edge> waits() throws SQLException { return BlogLockWaits.read(control, environment.schemaName().value()); }
    String runtimeUsername() { return environment.runtimeUsername(); }

    Connection lock(String table, long id) throws SQLException {
        if (!definition.tables().contains(table)) throw new IllegalArgumentException("unowned lock table");
        Connection blocker = connect(false);
        try {
            blocker.setAutoCommit(false);
            try (var query = blocker.prepareStatement("SELECT id FROM `" + table + "` WHERE id=? FOR UPDATE")) {
                query.setLong(1, id);
                query.setQueryTimeout(JDBC_SECONDS);
                try (var result = query.executeQuery()) { if (!result.next()) throw new SQLException("missing barrier row"); }
            }
            return blocker;
        } catch (SQLException error) {
            try { blocker.close(); } catch (SQLException close) { error.addSuppressed(close); }
            throw error;
        }
    }

    static long connectionId(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("SELECT CONNECTION_ID()"); var result = query.executeQuery()) {
            if (!result.next()) throw new SQLException("missing connection id");
            return result.getLong(1);
        }
    }

    void statement(String sql) throws SQLException {
        // TEST_FIXTURE_SQL: callers supply only fixed, test-owned fixture changes.
        try (var statement = control.createStatement()) {
            statement.setQueryTimeout(JDBC_SECONDS);
            statement.executeUpdate(sql);
        }
    }

    @Override public void close() throws SQLException {
        SQLException failure = null;
        try { observer.close(); } catch (SQLException error) { failure = error; }
        try { control.close(); } catch (SQLException error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }
}
