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
        String sql = "SELECT COUNT(*) FROM `" + schemaName.value() + "`.`" + tableName + "`";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
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