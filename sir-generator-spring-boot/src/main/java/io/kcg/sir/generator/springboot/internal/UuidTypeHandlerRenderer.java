package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.UuidTypeHandler;
import java.util.Optional;

/**
 * Renders the MyBatis handler that stores a {@code java.util.UUID} as its canonical text.
 *
 * <p>The handler is a Spring bean, which the MyBatis starter collects and registers by the type it is
 * mapped to; without it MyBatis would treat a {@code UUID} as a bean with properties and fail on the first
 * statement that binds one.
 */
final class UuidTypeHandlerRenderer {

    private UuidTypeHandlerRenderer() {
    }

    static GeneratedFile render(UuidTypeHandler handler) {
        ImportSorter imports = new ImportSorter();
        imports.add("java.sql.CallableStatement");
        imports.add("java.sql.PreparedStatement");
        imports.add("java.sql.ResultSet");
        imports.add("java.sql.SQLException");
        imports.add("java.util.UUID");
        imports.add("org.apache.ibatis.type.BaseTypeHandler");
        imports.add("org.apache.ibatis.type.JdbcType");
        imports.add("org.apache.ibatis.type.MappedTypes");
        imports.add("org.springframework.stereotype.Component");
        String content = GenerationContext.assembleSource(handler.packageName(), imports, renderBody(handler));
        return new GeneratedFile(handler.path(), content, handler.id(), Optional.empty());
    }

    private static String renderBody(UuidTypeHandler handler) {
        String name = handler.simpleName();
        StringBuilder out = new StringBuilder();
        out.append("@Component\n");
        out.append("@MappedTypes(UUID.class)\n");
        out.append("public class ").append(name).append(" extends BaseTypeHandler<UUID> {\n\n");
        out.append("    @Override\n");
        out.append("    public void setNonNullParameter(PreparedStatement statement, int index, UUID parameter, JdbcType jdbcType)\n");
        out.append("            throws SQLException {\n");
        out.append("        statement.setString(index, parameter.toString());\n");
        out.append("    }\n\n");
        out.append("    @Override\n");
        out.append("    public UUID getNullableResult(ResultSet resultSet, String columnName) throws SQLException {\n");
        out.append("        return parse(resultSet.getString(columnName));\n");
        out.append("    }\n\n");
        out.append("    @Override\n");
        out.append("    public UUID getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {\n");
        out.append("        return parse(resultSet.getString(columnIndex));\n");
        out.append("    }\n\n");
        out.append("    @Override\n");
        out.append("    public UUID getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {\n");
        out.append("        return parse(statement.getString(columnIndex));\n");
        out.append("    }\n\n");
        out.append("    private static UUID parse(String text) {\n");
        out.append("        return text == null ? null : UUID.fromString(text);\n");
        out.append("    }\n");
        out.append("}\n");
        return out.toString();
    }
}
