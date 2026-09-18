package io.kcg.sir.application.conformance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Read-only MySQL observer for assertions. Uses a distinct connection from
 * the control session. Has no CREATE/DROP authority.
 */
public final class MysqlObserver implements AutoCloseable {

    /** MySQL's maximum identifier length. */
    static final int MAX_IDENTIFIER_LENGTH = 64;

    private final Connection connection;

    private MysqlObserver(Connection connection) {
        this.connection = connection;
    }

    public static MysqlObserver open(String jdbcUrl, String username, String password)
            throws SQLException {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        props.setProperty("autoReconnect", "false");
        Connection conn = DriverManager.getConnection(jdbcUrl, props);
        conn.setAutoCommit(true);
        conn.setReadOnly(true);
        return new MysqlObserver(conn);
    }

    /**
     * Query rows from a table in the given schema. Returns rows as
     * space-joined column values in deterministic order.
     */
    public List<String> queryRows(SchemaName schemaName, String tableName,
                                  String orderByColumn) throws SQLException {
        Objects.requireNonNull(schemaName, "schemaName");
        validateTableIdentifier(tableName);
        validateTableIdentifier(orderByColumn);
        List<String> rows = new ArrayList<>();
        String sql = "SELECT * FROM `" + schemaName.value() + "`.`" + tableName + "`"
                + " ORDER BY `" + orderByColumn + "`";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        sb.append("|");
                    }
                    sb.append(rs.getString(i));
                }
                rows.add(sb.toString());
            }
        }
        return rows;
    }

    /**
     * Count rows in a table.
     */
    public int countRows(SchemaName schemaName, String tableName) throws SQLException {
        Objects.requireNonNull(schemaName, "schemaName");
        validateTableIdentifier(tableName);
        String sql = "SELECT COUNT(*) FROM `" + schemaName.value() + "`.`" + tableName + "`";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    /**
     * Validate an unquoted MySQL identifier that will be interpolated into a
     * backtick-quoted position in SQL text.
     *
     * <p>Schema names never take this path: they are strictly validated by
     * {@link SchemaName#parse} and rendered by {@link SchemaSqlRenderer}. Table and
     * column names have no such typed carrier, so any identifier that is
     * concatenated into SQL must pass through this method first. It rejects every
     * character that could terminate or escape the backtick-quoted position
     * (backtick, single quote, semicolon, comment sequences) as well as whitespace,
     * hyphens, and digit-first names, and it enforces MySQL's 64-character limit.
     *
     * @param identifier the identifier to validate (must not be {@code null})
     * @return the same identifier when valid
     * @throws NullPointerException     if {@code identifier} is {@code null}
     * @throws IllegalArgumentException if the identifier is empty, longer than 64
     *                                  characters, starts with a digit, or contains a
     *                                  character outside {@code [A-Za-z0-9_]}
     */
    public static String validateTableIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        int length = identifier.length();
        if (length == 0) {
            throw new IllegalArgumentException("identifier must not be empty");
        }
        if (length > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(
                    "identifier must not exceed " + MAX_IDENTIFIER_LENGTH + " characters: " + length);
        }
        char first = identifier.charAt(0);
        if (!isIdentifierStart(first)) {
            throw new IllegalArgumentException(
                    "identifier must start with a letter or underscore: " + identifier);
        }
        for (int i = 1; i < length; i++) {
            if (!isIdentifierPart(identifier.charAt(i))) {
                throw new IllegalArgumentException(
                        "identifier contains a character outside [A-Za-z0-9_]: " + identifier);
            }
        }
        return identifier;
    }

    private static boolean isIdentifierStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || (c >= '0' && c <= '9');
    }

    @Override
    public void close() {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // Best effort.
        }
    }
}