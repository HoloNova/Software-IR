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
public final class MysqlControlSession implements ControlSession {

    /**
     * In-schema owner-marker table. It lives inside the owned schema, so
     * {@code DROP DATABASE} removes it together with the fixture tables, and it can
     * never collide with a generated table (generated names never start with
     * {@code kcg_}).
     */
    static final String OWNER_MARKER_TABLE = "kcg_conformance_owner";

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
     * @param timeoutSeconds the GET_LOCK timeout in seconds
     * @return the raw GET_LOCK result (1 acquired, 0 timeout/error)
     */
    @Override
    public int acquireAdvisoryLock(String expectedServerUuid, SchemaName schemaName,
                                   int timeoutSeconds) throws SQLException {
        Objects.requireNonNull(expectedServerUuid, "expectedServerUuid");
        Objects.requireNonNull(schemaName, "schemaName");
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("timeoutSeconds must not be negative");
        }
        if (!serverUuidMatches(expectedServerUuid)) {
            throw new SQLException("CONTROL_OWNERSHIP_LOST: server-uuid-mismatch");
        }
        AdvisoryLockKey key = AdvisoryLockKey.derive(expectedServerUuid, schemaName);
        try (PreparedStatement ps = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            ps.setString(1, key.lockName());
            ps.setInt(2, timeoutSeconds);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                int result = rs.getInt(1);
                if (rs.wasNull()) {
                    // GET_LOCK returns NULL on error; never treat it as acquired.
                    return 0;
                }
                if (result == 1) {
                    this.lockKey = key;
                    this.capturedConnectionId = Long.parseLong(currentConnectionId());
                }
                return result;
            }
        }
    }

    /**
     * Recheck all four ownership conditions at a run boundary. This never repairs
     * the connection, never re-acquires the lock, and never opens a new connection.
     *
     * @param expectedServerUuid the expected server UUID
     * @throws SQLException if any ownership condition no longer holds
     */
    @Override
    public void recheckLockAndIdentity(String expectedServerUuid) throws SQLException {
        Objects.requireNonNull(expectedServerUuid, "expectedServerUuid");
        boolean original = isConnectionOriginal();
        boolean idUnchanged = original && connectionIdUnchanged();
        boolean uuidMatches = serverUuidMatches(expectedServerUuid);
        boolean lockHeld = idUnchanged && lockHeldByThisConnection();
        if (!(original && idUnchanged && uuidMatches && lockHeld)) {
            throw new SQLException("CONTROL_OWNERSHIP_LOST: connectionOriginal=" + original
                    + " connectionIdUnchanged=" + idUnchanged
                    + " serverUuidMatches=" + uuidMatches
                    + " lockHeldByThisConnection=" + lockHeld);
        }
    }

    /**
     * @param schemaName the exact schema name
     * @return true if the schema exists
     */
    @Override
    public boolean schemaExists(SchemaName schemaName) throws SQLException {
        Objects.requireNonNull(schemaName, "schemaName");
        SchemaSqlRenderer renderer = new SchemaSqlRenderer();
        try (PreparedStatement ps = connection.prepareStatement(
                renderer.informationSchemaAbsenceSql())) {
            ps.setString(1, schemaName.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** @param schemaName the exact schema name */
    @Override
    public void createDatabase(SchemaName schemaName) throws SQLException {
        Objects.requireNonNull(schemaName, "schemaName");
        executeDdl(new SchemaSqlRenderer().createDatabase(schemaName));
    }

    /** @param schemaName the exact schema name */
    @Override
    public void dropDatabase(SchemaName schemaName) throws SQLException {
        Objects.requireNonNull(schemaName, "schemaName");
        executeDdl(new SchemaSqlRenderer().dropDatabase(schemaName));
    }

    /**
     * Create the in-schema owner marker and record the run token.
     *
     * <p>The marker table is referenced with an explicitly schema-qualified name rather
     * than by switching the connection's catalog: {@link Connection#setCatalog} is a
     * driver-side hint whose {@code USE} is issued at the driver's discretion, and against
     * MySQL 8.4 the observed behavior is a statement that runs with no database selected
     * ({@code ERROR 1046}) even though {@code getCatalog} reports the value. The schema
     * name is a validated {@link SchemaName} and every identifier is backquoted, so
     * qualification carries no injection surface.
     *
     * @param schemaName the exact schema name
     * @param runToken   the run-specific ownership token
     */
    @Override
    public void createOwnerMarker(SchemaName schemaName, String runToken) throws SQLException {
        Objects.requireNonNull(schemaName, "schemaName");
        Objects.requireNonNull(runToken, "runToken");
        String markerTable = qualifiedOwnerMarkerTable(schemaName);
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE " + markerTable
                    + " (run_token VARCHAR(64) NOT NULL)");
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + markerTable + " (run_token) VALUES (?)")) {
                ps.setString(1, runToken);
                ps.executeUpdate();
            }
        }
    }

    /**
     * The owner marker table qualified with its schema.
     *
     * @param schemaName the validated schema name
     * @return the backquoted {@code `schema`.`table`} name
     */
    private static String qualifiedOwnerMarkerTable(SchemaName schemaName) {
        return "`" + schemaName.value() + "`.`" + OWNER_MARKER_TABLE + "`";
    }

    /**
     * @param schemaName the exact schema name
     * @param runToken   the run token to verify
     * @return true if the marker exists and carries exactly this run token
     */
    @Override
    public boolean verifyOwnerMarker(SchemaName schemaName, String runToken) throws SQLException {
        Objects.requireNonNull(schemaName, "schemaName");
        Objects.requireNonNull(runToken, "runToken");
        // See createOwnerMarker for why the table is qualified instead of switching catalog.
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT run_token FROM " + qualifiedOwnerMarkerTable(schemaName))) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && runToken.equals(rs.getString(1));
            }
        } catch (SQLException e) {
            // A missing marker table means ownership is not proven.
            return false;
        }
    }

    /**
     * Release the advisory lock held by this connection. Releasing a lock that this
     * connection does not hold returns NULL and is reported as a no-op, never as
     * success.
     */
    @Override
    public void releaseLock() throws SQLException {
        if (lockKey == null) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, lockKey.lockName());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && !rs.wasNull() && rs.getInt(1) == 1) {
                    lockKey = null;
                    capturedConnectionId = 0L;
                }
            }
        }
    }

    /** @return true iff the control connection is still open */
    @Override
    public boolean isConnectionOriginal() throws SQLException {
        return !connection.isClosed();
    }

    /** @return true iff CONNECTION_ID() still equals the value captured at open */
    @Override
    public boolean connectionIdUnchanged() throws SQLException {
        return originalConnectionId != null && originalConnectionId.equals(currentConnectionId());
    }

    /** @return true iff the advisory lock is held by this very connection */
    @Override
    public boolean lockHeldByThisConnection() throws SQLException {
        if (lockKey == null) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT IS_USED_LOCK(?)")) {
            ps.setString(1, lockKey.lockName());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                long holder = rs.getLong(1);
                if (rs.wasNull()) {
                    return false;
                }
                return holder == capturedConnectionId;
            }
        }
    }

    /** @return the raw control connection, for parameterized metadata reads only */
    @Override
    public Connection rawConnection() {
        return connection;
    }

    /** Release the lock (best effort) and close the control connection. */
    @Override
    public void close() {
        try {
            releaseLock();
        } catch (SQLException ignored) {
            // Cleanup is best effort; the connection is closed below regardless.
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing further can be done on a connection that will not close.
        }
    }

    private void executeDdl(String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    private String currentConnectionId() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT CONNECTION_ID()")) {
            if (!rs.next()) {
                throw new SQLException("CONNECTION_ID query returned no rows");
            }
            return rs.getString(1);
        }
    }
}
