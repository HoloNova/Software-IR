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

    public sealed interface Step permits CreateTable, AddNullableColumn {
        int version();
        /** The contract rows this version adds. */
        List<String> addedRows();
        String describe();
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
