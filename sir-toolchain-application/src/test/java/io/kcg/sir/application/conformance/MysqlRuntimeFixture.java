package io.kcg.sir.application.conformance;

import java.util.Objects;

/**
 * Holds the runtime MySQL credential (distinct from the control credential)
 * and the runtime JDBC URL built from the {@link SchemaName}. The generated
 * application uses this credential and datasource.
 *
 * <p>The runtime credential has no CREATE/DROP authority.
 */
public final class MysqlRuntimeFixture {

    private final String runtimeJdbcUrl;
    private final String runtimeUsername;
    private final String runtimePassword;

    public MysqlRuntimeFixture(String runtimeJdbcUrl, String runtimeUsername,
                               String runtimePassword) {
        this.runtimeJdbcUrl = Objects.requireNonNull(runtimeJdbcUrl, "runtimeJdbcUrl");
        this.runtimeUsername = Objects.requireNonNull(runtimeUsername, "runtimeUsername");
        this.runtimePassword = Objects.requireNonNull(runtimePassword, "runtimePassword");
    }

    public String runtimeJdbcUrl() {
        return runtimeJdbcUrl;
    }

    public String runtimeUsername() {
        return runtimeUsername;
    }

    public String runtimePassword() {
        return runtimePassword;
    }
}