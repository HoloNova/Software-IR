package io.kcg.sir.application.conformance;

import java.util.Objects;

/**
 * Fixed SQL renderer for conformance schema DDL. Accepts only a
 * {@link SchemaName} 鈥?never a {@link String}, JDBC URL, log text, exception
 * text, or caller SQL fragment.
 *
 * <p>The only outputs are the golden SQL forms:
 *
 * <pre>{@code
 * CREATE DATABASE `<exact schema name>`
 * DROP DATABASE `<exact schema name>`
 * }</pre>
 *
 * <p>{@code IF EXISTS} and {@code IF NOT EXISTS} are forbidden. The backtick
 * quoting is fixed and the schema name value is inserted verbatim 鈥?it is
 * already strictly validated by {@link SchemaName#parse} so no SQL injection
 * is possible through this path.
 *
 * <p>All {@code INFORMATION_SCHEMA} queries use a parameterized
 * {@code PreparedStatement} with exact {@code SchemaName.value()} binding;
 * they do not go through this renderer. See {@link #informationSchemaAbsenceSql()}.
 */
public final class SchemaSqlRenderer {

    private static final String CREATE_TEMPLATE = "CREATE DATABASE `%s`";
    private static final String DROP_TEMPLATE = "DROP DATABASE `%s`";

    /**
     * The fixed parameterized absence-query SQL. The single {@code ?}
     * parameter is bound to {@code SchemaName.value()}.
     */
    static final String ABSENCE_QUERY_SQL =
            "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE BINARY SCHEMA_NAME = ?";

    public String createDatabase(SchemaName name) {
        Objects.requireNonNull(name, "name");
        return String.format(CREATE_TEMPLATE, name.value());
    }

    public String dropDatabase(SchemaName name) {
        Objects.requireNonNull(name, "name");
        return String.format(DROP_TEMPLATE, name.value());
    }

    /**
     * @return the fixed parameterized absence-query SQL. The caller must
     *         use a {@code PreparedStatement} and bind the schema name as
     *         the first parameter.
     */
    public String informationSchemaAbsenceSql() {
        return ABSENCE_QUERY_SQL;
    }
}