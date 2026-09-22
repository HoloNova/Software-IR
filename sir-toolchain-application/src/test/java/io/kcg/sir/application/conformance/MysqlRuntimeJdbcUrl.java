package io.kcg.sir.application.conformance;

import java.util.Objects;

/**
 * The rendered runtime datasource URL for the generated application (P0-C2).
 *
 * <p>The URL is derived from exactly two inputs: the MySQL endpoint parsed out of
 * the control JDBC URL, and a validated {@link SchemaName}. There is deliberately
 * no constructor that accepts a caller-supplied runtime URL and no fallback to
 * reusing the control URL as the runtime datasource, because both would let a
 * caller redirect the application at a database the run does not own.
 *
 * <p>{@link #rendered()} never contains credentials. They travel in the separate
 * child-environment keys, so the URL can be written into evidence.
 *
 * <p>The accepted control-URL shape is deliberately narrow — {@code
 * jdbc:mysql://<host>[:<port>]} with no credentials, query string, fragment, or
 * path beyond the optional empty path. Anything else is rejected rather than
 * reinterpreted, because a lenient parser here would silently pick an endpoint
 * the harness did not validate.
 */
public final class MysqlRuntimeJdbcUrl {

    private static final String PREFIX = "jdbc:mysql://";
    private static final int DEFAULT_PORT = 3306;

    private final String host;
    private final int port;
    private final String schemaValue;

    private MysqlRuntimeJdbcUrl(String host, int port, String schemaValue) {
        this.host = host;
        this.port = port;
        this.schemaValue = schemaValue;
    }

    /**
     * Parse the MySQL endpoint from a control JDBC URL and bind it to a validated
     * schema name.
     *
     * @param controlJdbcUrl the control JDBC URL (may carry no credentials itself,
     *                       and must not)
     * @param schemaName     the validated schema name of the owned schema
     * @return the typed runtime URL
     * @throws IllegalArgumentException if the endpoint cannot be parsed strictly
     */
    public static MysqlRuntimeJdbcUrl fromControlEndpoint(String controlJdbcUrl, SchemaName schemaName) {
        Objects.requireNonNull(schemaName, "schemaName");
        if (controlJdbcUrl == null) {
            throw new IllegalArgumentException("control JDBC URL must not be null");
        }
        String url = controlJdbcUrl.trim();
        if (!url.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            throw new IllegalArgumentException("control JDBC URL must start with " + PREFIX);
        }
        String remainder = url.substring(PREFIX.length());
        if (remainder.isEmpty()) {
            throw new IllegalArgumentException("control JDBC URL has no endpoint");
        }
        if (remainder.indexOf('?') >= 0 || remainder.indexOf('#') >= 0) {
            throw new IllegalArgumentException(
                    "control JDBC URL must not carry query parameters or a fragment");
        }
        // The endpoint is everything before the first separator; the path must be
        // empty because the runtime schema comes from SchemaName, never from the URL.
        String endpoint = remainder;
        int slash = remainder.indexOf('/');
        if (slash >= 0) {
            endpoint = remainder.substring(0, slash);
            if (!remainder.substring(slash + 1).isEmpty()) {
                throw new IllegalArgumentException(
                        "control JDBC URL must not select a schema; the runtime schema comes "
                                + "from the validated SchemaName");
            }
        }
        if (endpoint.isEmpty()) {
            throw new IllegalArgumentException("control JDBC URL has no host");
        }
        if (endpoint.indexOf('@') >= 0) {
            throw new IllegalArgumentException(
                    "control JDBC URL must not embed credentials");
        }

        String hostPart;
        String portPart = null;
        if (endpoint.startsWith("[")) {
            int close = endpoint.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("unterminated IPv6 host literal");
            }
            hostPart = endpoint.substring(0, close + 1);
            String tail = endpoint.substring(close + 1);
            if (!tail.isEmpty()) {
                if (!tail.startsWith(":")) {
                    throw new IllegalArgumentException("unexpected text after IPv6 host literal");
                }
                portPart = tail.substring(1);
            }
        } else {
            int colon = endpoint.indexOf(':');
            if (colon >= 0) {
                hostPart = endpoint.substring(0, colon);
                portPart = endpoint.substring(colon + 1);
                if (portPart.indexOf(':') >= 0) {
                    throw new IllegalArgumentException(
                            "IPv6 host literals must be enclosed in brackets");
                }
            } else {
                hostPart = endpoint;
            }
        }

        if (hostPart.isEmpty() || hostPart.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("control JDBC URL has an invalid host");
        }
        int port = DEFAULT_PORT;
        if (portPart != null) {
            if (portPart.isEmpty()) {
                throw new IllegalArgumentException("control JDBC URL has an empty port");
            }
            try {
                port = Integer.parseInt(portPart, 10);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("control JDBC URL has a non-numeric port");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("control JDBC URL port out of range: " + port);
            }
        }

        return new MysqlRuntimeJdbcUrl(hostPart, port, schemaName.value());
    }

    /** @return the parsed host (IPv6 literals keep their brackets) */
    public String host() {
        return host;
    }

    /** @return the parsed port, defaulting to 3306 when the control URL omitted it */
    public int port() {
        return port;
    }

    /**
     * Render the credential-free runtime datasource URL.
     *
     * @return {@code jdbc:mysql://host:port/<schema>}
     */
    public String rendered() {
        return PREFIX + host + ":" + port + "/" + schemaValue;
    }

    @Override
    public String toString() {
        return rendered();
    }
}
