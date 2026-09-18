package io.kcg.sir.application.conformance;

import java.util.Objects;

/**
 * Control-connection configuration for the conformance harness.
 *
 * <p>Carries the control JDBC URL plus its credentials. Per ADR-017 the URL
 * itself never contains credentials, and this record is the only place the
 * control password is held before the session is opened; it is never rendered
 * into evidence.
 *
 * @param jdbcUrl  the control JDBC URL (no credentials inside the URL)
 * @param username the control user name
 * @param password the control password
 */
record MysqlControlConfiguration(String jdbcUrl, String username, String password) {

    MysqlControlConfiguration {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
    }
}
