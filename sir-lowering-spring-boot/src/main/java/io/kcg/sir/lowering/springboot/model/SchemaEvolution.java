package io.kcg.sir.lowering.springboot.model;

import io.kcg.sir.lowering.springboot.model.DeploymentPlan.Column;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan.Table;
import java.util.List;
import java.util.Objects;

/**
 * The schema versions a new delivery appends to the chain V1..V(base) it inherits. Each step is exactly one DDL
 * statement, so an interrupted migration leaves the database at one version or the next, never in between.
 */
public record SchemaEvolution(int baseVersion, List<Step> steps) {
    public SchemaEvolution {
        if (baseVersion < 1) throw new IllegalArgumentException("base version must be at least 1");
        steps = List.copyOf(steps);
        for (int i = 0; i < steps.size(); i++)
            if (steps.get(i).version() != baseVersion + 1 + i) throw new IllegalArgumentException("steps must number V(base+1).. in order");
    }

    public int targetVersion() { return baseVersion + steps.size(); }

    public sealed interface Step permits RenameColumn, CreateTable, AddNullableColumn {
        int version();
        /** The contract rows this version adds. */
        List<String> addedRows();
        /** The contract rows this version takes away; the rows it adds replace them. */
        default List<String> removedRows() { return List.of(); }
        String describe();
    }

    /**
     * A field that kept its declared identity under a new name: the column follows the name and keeps its values. Only
     * the name may differ; the primary key is never renamed, since other tables refer to it.
     */
    public record RenameColumn(int version, String table, Column from, Column to) implements Step {
        public RenameColumn {
            Objects.requireNonNull(table); Objects.requireNonNull(from); Objects.requireNonNull(to);
            if (from.primary() || from.name().equals(to.name()) || !from.symbol().equals(to.symbol())
                    || !SchemaContract.row(table, from).replaceFirst("^[^\\t]*\\t[^\\t]*", "").equals(SchemaContract.row(table, to).replaceFirst("^[^\\t]*\\t[^\\t]*", "")))
                throw new IllegalArgumentException("only the name of a non-key column with the same identity can change");
        }
        public List<String> addedRows() { return List.of(SchemaContract.row(table, to)); }
        public List<String> removedRows() { return List.of(SchemaContract.row(table, from)); }
        public String describe() { return "V" + version + " RENAME_COLUMN " + table + "." + from.name() + " -> " + to.name(); }
    }

    public record CreateTable(int version, Table table) implements Step {
        public CreateTable { Objects.requireNonNull(table); }
        public List<String> addedRows() { return table.columns().stream().map(c -> SchemaContract.row(table.name(), c)).toList(); }
        public String describe() { return "V" + version + " CREATE_TABLE " + table.name(); }
    }

    public record AddNullableColumn(int version, String table, Column column) implements Step {
        public AddNullableColumn {
            Objects.requireNonNull(table); Objects.requireNonNull(column);
            if (!column.nullable() || column.primary() || column.autoIncrement() || column.unique() || column.reference().isPresent())
                throw new IllegalArgumentException("only a plain nullable column can be added to an existing table");
        }
        public List<String> addedRows() { return List.of(SchemaContract.row(table, column)); }
        public String describe() { return "V" + version + " ADD_NULLABLE_COLUMN " + table + "." + column.name(); }
    }
}
