package io.kcg.sir.application.conformance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Counts the reads one request issues and keeps the statements behind the count.
 *
 * <p>Two independent views of the same window are collected. The count comes from
 * {@code performance_schema.events_statements_summary_by_digest}, taken as the growth of the
 * normalized {@code SELECT} digests attributed to the owning schema, so transaction bookkeeping
 * ({@code SET autocommit}, {@code COMMIT}) and connection validation cannot inflate it. The literal
 * statements come from the general log, filtered to the runtime account, so the evidence shows what
 * the application actually sent rather than a summary of it.
 *
 * <p>Both need privileges the runtime account does not have (verified: it is denied
 * {@code SELECT} on {@code events_statements_summary_by_digest}), so this is opened with the control
 * credentials and used only from the test side. It is never given to the application.
 *
 * <p>Transaction boundaries come from {@code events_transactions_summary_global_by_event_name}, so a
 * window also shows how many transactions the request took and whether they were read-only — a
 * request whose reads spanned several transactions would start more than one.
 */
final class MysqlRuntimeTrace implements AutoCloseable {

    private final Connection control;
    private final String schemaName;
    private final String runtimeUsername;
    private final Map<String, Long> digestBaseline = new LinkedHashMap<>();
    private long transactionBaseline;
    private long readOnlyBaseline;

    private MysqlRuntimeTrace(Connection control, String schemaName, String runtimeUsername) {
        this.control = control;
        this.schemaName = schemaName;
        this.runtimeUsername = runtimeUsername;
    }

    static MysqlRuntimeTrace open(String jdbcUrl, String username, String password, SchemaName schemaName,
                                  String runtimeUsername) throws SQLException {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        Connection connection = DriverManager.getConnection(jdbcUrl, props);
        connection.setAutoCommit(true);
        return new MysqlRuntimeTrace(connection, schemaName.value(), runtimeUsername);
    }

    /**
     * Opens a fresh window: the log starts empty and the digest baseline is taken now.
     *
     * <p>The log is truncated rather than filtered by time, so a window can never inherit a
     * statement from an earlier request in the same run.
     */
    void start() throws SQLException {
        this.execute("SET GLOBAL log_output = 'TABLE'");
        this.execute("SET GLOBAL general_log = ON");
        this.execute("TRUNCATE TABLE mysql.general_log");
        this.digestBaseline.clear();
        this.digestBaseline.putAll(this.digestCounts());
        long[] transactions = this.transactionCounts();
        this.transactionBaseline = transactions[0];
        this.readOnlyBaseline = transactions[1];
    }

    /** Closes the window and reports what the runtime account did inside it. */
    Trace stop() throws SQLException {
        List<String> digests = new ArrayList<>();
        long selectCount = 0L;
        Map<String, Long> after = this.digestCounts();
        for (Map.Entry<String, Long> entry : after.entrySet()) {
            long before = this.digestBaseline.getOrDefault(entry.getKey(), 0L);
            long growth = entry.getValue() - before;
            if (growth > 0L) {
                selectCount += growth;
                for (int repeat = 0; repeat < growth; repeat++) {
                    digests.add(entry.getKey());
                }
            }
        }

        List<String> rawStatements = this.runtimeStatements();
        long[] transactions = this.transactionCounts();
        long started = transactions[0] - this.transactionBaseline;
        long readOnly = transactions[1] - this.readOnlyBaseline;
        this.execute("SET GLOBAL general_log = OFF");
        return new Trace(selectCount, List.copyOf(digests), rawStatements, started, readOnly);
    }

    /** Transactions started and read-only transactions since the server started, in that order. */
    private long[] transactionCounts() throws SQLException {
        String sql = "SELECT COUNT_STAR, COUNT_READ_ONLY FROM performance_schema"
                + ".events_transactions_summary_global_by_event_name WHERE EVENT_NAME = 'transaction'";
        try (PreparedStatement statement = this.control.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                return new long[]{rows.getLong(1), rows.getLong(2)};
            }
        }

        return new long[]{0L, 0L};
    }

    /** Normalized {@code SELECT} digests of the owned schema, keyed by their digest text. */
    private Map<String, Long> digestCounts() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        String sql = "SELECT DIGEST_TEXT, COUNT_STAR FROM performance_schema.events_statements_summary_by_digest"
                + " WHERE SCHEMA_NAME = ? AND DIGEST_TEXT LIKE 'SELECT%' AND DIGEST_TEXT IS NOT NULL";
        try (PreparedStatement statement = this.control.prepareStatement(sql)) {
            statement.setString(1, this.schemaName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    counts.put(rows.getString(1), rows.getLong(2));
                }
            }
        }

        return counts;
    }

    /** The statements the runtime account sent inside the window, in the order the server logged them. */
    private List<String> runtimeStatements() throws SQLException {
        List<String> statements = new ArrayList<>();
        String sql = "SELECT argument FROM mysql.general_log WHERE command_type = 'Query' AND user_host LIKE ?"
                + " ORDER BY event_time";
        try (PreparedStatement statement = this.control.prepareStatement(sql)) {
            statement.setString(1, this.runtimeUsername + "%");
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String argument = rows.getString(1);
                    if (argument != null && !argument.isBlank()) {
                        statements.add(argument);
                    }
                }
            }
        }

        return List.copyOf(statements);
    }

    private void execute(String sql) throws SQLException {
        try (PreparedStatement statement = this.control.prepareStatement(sql)) {
            statement.execute();
        }
    }

    @Override
    public void close() {
        try {
            this.execute("SET GLOBAL general_log = OFF");
        } catch (SQLException ignored) {
            // Best effort: the window is closed by stop() as well.
        }

        try {
            if (!this.control.isClosed()) {
                this.control.close();
            }
        } catch (SQLException ignored) {
            // Best effort.
        }
    }

    /**
     * What one request did: how many reads it issued, the normalized texts of those reads, and the
     * literal statements the server logged for the account the application runs as.
     */
    record Trace(long selectCount, List<String> selectDigests, List<String> rawStatements,
                 long transactions, long readOnlyTransactions) {

        Trace {
            Objects.requireNonNull(selectDigests, "selectDigests");
            Objects.requireNonNull(rawStatements, "rawStatements");
        }

        /** The literal statements of this window that are reads, for the report. */
        List<String> rawSelects() {
            return this.rawStatements.stream()
                    .filter(statement -> statement.stripLeading().toLowerCase(java.util.Locale.ROOT).startsWith("select"))
                    .toList();
        }
    }
}
