package io.kcg.sir.application.conformance;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Package-private view of the dedicated MySQL control connection used by the
 * conformance harness. {@link MysqlControlSession} is the real implementation;
 * test fakes implement this interface so the orchestration can be driven past
 * {@code MYSQL_CONTROL_CONNECT} without a real server.
 *
 * <p>The method set is exactly what the harness calls. It is derived from the
 * call sites, not invented: server identity, advisory lock, schema lifecycle,
 * owner marker, ownership checks, and raw-connection access for the
 * parameterized {@code INFORMATION_SCHEMA} reads that
 * {@link MysqlSchemaInventory} and {@link MysqlObserver} perform.
 *
 * <p>Every operation that talks to the server declares {@link SQLException}.
 * The orchestrator treats any such failure according to whether the
 * side-effect boundary has been crossed.
 */
interface ControlSession extends AutoCloseable {

    /** @return the server UUID observed when the control connection was opened */
    String observedServerUuid();

    /** @return the server version observed when the control connection was opened */
    String observedServerVersion();

    /** @return the {@code CONNECTION_ID()} captured when the connection was opened */
    String originalConnectionId();

    /**
     * @param expectedUuid the expected server UUID
     * @return true if the observed server UUID matches exactly
     */
    boolean serverUuidMatches(String expectedUuid);

    /**
     * Acquire the server-scoped advisory lock for the given schema.
     *
     * @param expectedServerUuid the expected server UUID (must match observed)
     * @param schemaName         the exact owned schema name
     * @param timeoutSeconds     the {@code GET_LOCK} timeout
     * @return the raw {@code GET_LOCK} result: 1 acquired, 0 timeout, null as 0
     * @throws SQLException if the query fails or the server UUID does not match
     */
    int acquireAdvisoryLock(String expectedServerUuid, SchemaName schemaName, int timeoutSeconds)
            throws SQLException;

    /**
     * Recheck lock and connection identity at a run boundary.
     *
     * @param expectedServerUuid the expected server UUID
     * @throws SQLException if any ownership condition no longer holds
     */
    void recheckLockAndIdentity(String expectedServerUuid) throws SQLException;

    /**
     * @param schemaName the exact schema name
     * @return true if the schema exists
     * @throws SQLException if the parameterized absence query fails
     */
    boolean schemaExists(SchemaName schemaName) throws SQLException;

    /**
     * @param schemaName the exact schema name
     * @throws SQLException if {@code CREATE DATABASE} fails
     */
    void createDatabase(SchemaName schemaName) throws SQLException;

    /**
     * @param schemaName the exact schema name
     * @throws SQLException if {@code DROP DATABASE} fails
     */
    void dropDatabase(SchemaName schemaName) throws SQLException;

    /**
     * Create the in-schema owner marker carrying the run token.
     *
     * @param schemaName the exact schema name
     * @param runToken   the run-specific token proving ownership
     * @throws SQLException if the marker cannot be created
     */
    void createOwnerMarker(SchemaName schemaName, String runToken) throws SQLException;

    /**
     * @param schemaName the exact schema name
     * @param runToken   the run-specific token to verify
     * @return true if the marker exists and carries exactly this run token
     * @throws SQLException if the marker cannot be read
     */
    boolean verifyOwnerMarker(SchemaName schemaName, String runToken) throws SQLException;

    /**
     * Release the advisory lock held by this connection.
     *
     * @throws SQLException if the release query fails
     */
    void releaseLock() throws SQLException;

    /**
     * @return true iff the control connection is still the original open connection
     * @throws SQLException if the connection state cannot be read
     */
    boolean isConnectionOriginal() throws SQLException;

    /**
     * @return true iff {@code CONNECTION_ID()} still equals the value captured at open
     * @throws SQLException if the connection ID cannot be read
     */
    boolean connectionIdUnchanged() throws SQLException;

    /**
     * @return true iff the advisory lock is held by this very connection
     * @throws SQLException if the lock owner cannot be read
     */
    boolean lockHeldByThisConnection() throws SQLException;

    /**
     * @return the raw JDBC connection, for parameterized metadata reads only
     */
    Connection rawConnection();

    /** Release the lock (best effort) and close the control connection. */
    @Override
    void close();
}
