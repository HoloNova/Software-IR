package io.kcg.sir.application.conformance;

import java.sql.SQLException;

/**
 * Package-private factory for opening a {@link ControlSession}. The real
 * implementation delegates to {@link MysqlControlSession#open}. Tests
 * inject a fake factory that returns a {@link FakeControlSession}
 * without a real MySQL connection.
 *
 * <p>This seam allows default {@code *Test} classes to drive the full
 * orchestration path past {@code MYSQL_CONTROL_CONNECT} without
 * external MySQL.
 */
@FunctionalInterface
interface ControlSessionFactory {

    /**
     * Open a control session from the given configuration.
     *
     * @param config the control connection configuration
     * @return the open control session
     * @throws SQLException if the connection cannot be opened
     */
    ControlSession open(MysqlControlConfiguration config) throws SQLException;
}
