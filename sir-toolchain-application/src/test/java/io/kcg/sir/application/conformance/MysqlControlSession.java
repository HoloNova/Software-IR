package io.kcg.sir.application.conformance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Properties;

/**
 * Dedicated MySQL control connection for schema ownership. Owns one
 * non-pooled, no-auto-reconnect connection that remains open from lock
 * acquisition through successful DROP and lock release.
 *
 * <p>The control connection is not shared with the generated application
 * and is not given to the generation engineering. It performs:
 * <ul>
 *   <li>server identity query ({@code SELECT @@server_uuid, VERSION(), CONNECTION_ID()});</li>
 *   <li>advisory lock acquisition and recheck;</li>
 *   <li>schema absence query (parameterized);</li>
 *   <li>CREATE/DROP DATABASE (via {@link SchemaSqlRenderer});</li>
 *   <li>owner-marker table and run-token;</li>
 *   <li>fixture DDL (executed via {@link Connection#setCatalog}—never raw {@code USE <schema>} SQL);</li>
 *   <li>schema inventory;</li>
 *   <li>RELEASE_LOCK.</li>
 * </ul>
 *
 * <p>Per ADR-017 sections 7.2 and 10.4, the control session rechecks lock and connection
 * identity at every boundary: schema metadata/DDL, generation/Apply, Spring
 * start, inventory, cleanup, DROP, post-DROP absence proof.
 *
 * <p>Implicit reconnect, connection-ID change, or loss of the control
 * connection is never repaired by opening a new connection.
 */
public final class MysqlControlSession implements AutoCloseable {

    private final Connection connection;
    private final String controlJdbcUrl;
    private final String controlUsername;
    private final String originalConnectionId;
    private final String observedServerUuid;
    private final String observedServerVersion;
    private AdvisoryLockKey lockKey;
    private long capturedConnectionId;

    private MysqlControlSession(Connection connection, String controlJdbcUrl,
                               String controlUsername, String originalConnectionId,
                               String observedServerUuid, String observedServerVersion) {
        this.connection = connection;
        this.controlJdbcUrl = controlJdbcUrl;
        this.controlUsername = controlUsername;
        this.originalConnectionId = originalConnectionId;
        this.observedServerUuid = observedServerUuid;
        this.observedServerVersion = observedServerVersion;
    }

    /**
     * Open a dedicated control connection with auto-reconnect disabled.
     *
     * @param controlJdbcUrl the control JDBC URL (no credentials in the URL)
     * @param username       the control username
     * @param password       the control password
     * @return the open control session with server identity recorded
     */
    public static MysqlControlSession open(String controlJdbcUrl, String username,
                                           String password) throws SQLException {
        Objects.requireNonNull(controlJdbcUrl, "controlJdbcUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        // Disable auto-reconnect.
        props.setProperty("autoReconnect", "false");
        Connection conn = DriverManager.getConnection(controlJdbcUrl, props);
        conn.setAutoCommit(true);

        // Query server identity.
        String serverUuid;
        String serverVersion;
        String connectionId;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT @@server_uuid, VERSION(), CONNECTION_ID()")) {
            if (!rs.next()) {
                conn.close();
                throw new SQLException("server identity query returned no rows");
            }
            serverUuid = rs.getString(1);
            serverVersion = rs.getString(2);
            connectionId = rs.getString(3);
        }

        return new MysqlControlSession(conn, controlJdbcUrl, username,
                connectionId, serverUuid, serverVersion);
    }

    /**
     * @return the observed server UUID from the initial connection.
     */
    public String observedServerUuid() {
        return observedServerUuid;
    }

    /**
     * @return the observed server version from the initial connection.
     */
    public String observedServerVersion() {
        return observedServerVersion;
    }

    /**
     * @return the original connection ID captured at open time.
     */
    public String originalConnectionId() {
        return originalConnectionId;
    }

    /**
     * Verify that the expected server UUID matches the observed UUID.
     *
     * @return true if they match exactly
     */
    public boolean serverUuidMatches(String expectedUuid) {
        return observedServerUuid != null && observedServerUuid.equals(expectedUuid);
    }

    /**
     * Acquire the advisory lock for the given schema name. Only
     * {@code GET_LOCK == 1} and {@code IS_USED_LOCK == capturedConnectionId}
     * authorize progress.
     *
     * @param expectedServerUuid the expected server UUID (must match observed)
     * @param schemaName the exact owned schema name
     * */

    // TODO(conformance): implement advisory-lock acquisition and the remaining
    // fail-closed control-session operations before enabling this package.
}
