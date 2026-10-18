package io.kcg.sir.lowering.springboot.model;

import io.kcg.sir.lowering.springboot.model.DeploymentPlan.Column;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan.ForeignKey;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan.Storage;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan.Table;
import java.util.List;

/**
 * The physical schema contract a delivery's migration tool verifies the live database against: one tab-separated row
 * per column (table, column, MySQL data type, length, nullable, primary, auto increment, unique, referenced table and
 * column, and the database default: empty for none, else "=" and the value URL-encoded, so any text fits one field).
 * The same rows are what an update compares a new plan with, so both sides are rendered here only.
 */
public final class SchemaContract {
    private SchemaContract() {}

    public static String render(List<Table> tables) {
        StringBuilder contract = new StringBuilder();
        for (Table table : tables) for (Column column : table.columns()) contract.append(row(table.name(), column)).append('\n');
        return contract.toString();
    }

    public static String row(String table, Column column) {
        var ref = column.reference();
        return String.join("\t", table, column.name(), dataType(column.storage()),
            Integer.toString(column.storage() == Storage.UUID ? 36 : column.length()),
            Boolean.toString(column.nullable()), Boolean.toString(column.primary()), Boolean.toString(column.autoIncrement()),
            Boolean.toString(column.unique()), ref.map(ForeignKey::table).orElse(""), ref.map(ForeignKey::column).orElse(""),
            column.defaultValue().map(v -> "=" + java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8)).orElse(""));
    }

    private static String dataType(Storage storage) {
        return switch (storage) {
            case INT -> "int"; case BIGINT -> "bigint"; case BOOLEAN -> "tinyint";
            case VARCHAR -> "varchar"; case LONGTEXT -> "longtext"; case UUID -> "char";
            case DATE -> "date"; case DATETIME -> "datetime";
        };
    }
}
