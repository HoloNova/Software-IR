package io.kcg.sir.generator.springboot.internal;

/** JDBC 4.2 proleptic UTC fields, explicitly enabled by the target calendar plan. */
final class UtcInstantRenderer {
    private UtcInstantRenderer() {}
    static String render() {
        return """
                /** DATETIME(6) is a UTC wall-clock carrier, never the JVM's hybrid legacy calendar. */
                @org.springframework.stereotype.Component
                @org.apache.ibatis.type.MappedTypes(java.time.Instant.class)
                @org.apache.ibatis.type.MappedJdbcTypes(value = org.apache.ibatis.type.JdbcType.TIMESTAMP, includeNullJdbcType = true)
                public static final class UtcInstantHandler extends org.apache.ibatis.type.BaseTypeHandler<java.time.Instant> {
                    @Override public void setNonNullParameter(java.sql.PreparedStatement statement, int index,
                            java.time.Instant value, org.apache.ibatis.type.JdbcType jdbcType) throws java.sql.SQLException {
                        statement.setObject(index, java.time.LocalDateTime.ofInstant(value, java.time.ZoneOffset.UTC), java.sql.Types.TIMESTAMP);
                    }
                    @Override public java.time.Instant getNullableResult(java.sql.ResultSet rows, String column) throws java.sql.SQLException {
                        return instant(rows.getObject(column, java.time.LocalDateTime.class));
                    }
                    @Override public java.time.Instant getNullableResult(java.sql.ResultSet rows, int column) throws java.sql.SQLException {
                        return instant(rows.getObject(column, java.time.LocalDateTime.class));
                    }
                    @Override public java.time.Instant getNullableResult(java.sql.CallableStatement statement, int column) throws java.sql.SQLException {
                        return instant(statement.getObject(column, java.time.LocalDateTime.class));
                    }
                    private static java.time.Instant instant(java.time.LocalDateTime value) {
                        return value == null ? null : value.toInstant(java.time.ZoneOffset.UTC);
                    }
                }

            """;
    }
}
