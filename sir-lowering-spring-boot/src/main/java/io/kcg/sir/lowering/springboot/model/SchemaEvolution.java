package io.kcg.sir.lowering.springboot.model;

import io.kcg.sir.lowering.springboot.model.DeploymentPlan.Column;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan.Storage;
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

    public sealed interface Step permits RenameColumn, WidenColumn, ChangeColumnDefault, CreateTable, AddNullableColumn, AddDefaultedColumn,
            DropColumn, DropTable {
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

    /**
     * A column that can hold everything it held before and more, so no existing value changes: a 32-bit number becomes a
     * 64-bit one, a bounded text gets a larger limit or none, a required column becomes optional. Name, key, uniqueness,
     * reference and default stay as they were. The database may copy the table to do it.
     */
    public record WidenColumn(int version, String table, Column from, Column to) implements Step {
        public WidenColumn {
            Objects.requireNonNull(table); Objects.requireNonNull(from); Objects.requireNonNull(to);
            if (!widens(from, to)) throw new IllegalArgumentException("not a widening of the column: " + from.name());
        }
        public List<String> addedRows() { return List.of(SchemaContract.row(table, to)); }
        public List<String> removedRows() { return List.of(SchemaContract.row(table, from)); }
        public String describe() { return "V" + version + " WIDEN_COLUMN " + table + "." + to.name(); }

        /**
         * Whether {@code to} is {@code from} widened in at least one way and changed in no other. Storage widens from
         * INT to BIGINT, a VARCHAR to a longer VARCHAR, and a VARCHAR without a default or a unique key to LONGTEXT;
         * nullability only from required to optional. A column that is a key, counts up by itself or refers to another
         * table is never widened, since other tables depend on its exact type.
         */
        public static boolean widens(Column from, Column to) {
            if (from.primary() || from.autoIncrement() || to.primary() || to.autoIncrement()
                    || from.reference().isPresent() && (from.storage() != to.storage() || from.length() != to.length())
                    || !from.symbol().equals(to.symbol()) || !from.name().equals(to.name())
                    || from.unique() != to.unique() || !from.reference().equals(to.reference())
                    || !from.defaultValue().equals(to.defaultValue())) return false;
            boolean sameType = from.storage() == to.storage() && from.decimalShape().equals(to.decimalShape())
                && (from.storage() != Storage.VARCHAR || from.length() == to.length());
            boolean longer = from.storage() == Storage.VARCHAR && to.storage() == Storage.VARCHAR && to.length() > from.length();
            boolean wider = from.storage() == Storage.INT && to.storage() == Storage.BIGINT
                || from.storage() == Storage.VARCHAR && to.storage() == Storage.LONGTEXT && !from.unique() && from.defaultValue().isEmpty();
            boolean typeOk = sameType || longer || wider;
            boolean optional = !from.nullable() && to.nullable();
            return typeOk && (!sameType || optional) && (from.nullable() == to.nullable() || optional);
        }
    }

    /**
     * A column whose database default changed and nothing else: only what the database fills in for a row written
     * without the column changes, and the rows already there keep their values. An empty {@code to} default drops it.
     */
    public record ChangeColumnDefault(int version, String table, Column from, Column to) implements Step {
        public ChangeColumnDefault {
            Objects.requireNonNull(table); Objects.requireNonNull(from); Objects.requireNonNull(to);
            if (from.primary() || !from.symbol().equals(to.symbol()) || from.defaultValue().equals(to.defaultValue())
                    || !withoutDefault(SchemaContract.row(table, from)).equals(withoutDefault(SchemaContract.row(table, to))))
                throw new IllegalArgumentException("only the default of a non-key column with the same identity and name can change");
        }
        public List<String> addedRows() { return List.of(SchemaContract.row(table, to)); }
        public List<String> removedRows() { return List.of(SchemaContract.row(table, from)); }
        public String describe() { return "V" + version + " CHANGE_COLUMN_DEFAULT " + table + "." + to.name(); }

        /** A contract row without its last field, the default. */
        public static String withoutDefault(String row) { return row.substring(0, row.lastIndexOf('\t')); }
    }

    /** The contract rows of a whole table: its columns, then its unique keys. */
    static List<String> tableRows(Table table) {
        List<String> rows = new java.util.ArrayList<>(table.columns().stream().map(c -> SchemaContract.row(table.name(), c)).toList());
        table.uniqueKeys().forEach(key -> rows.add(SchemaContract.keyRow(table.name(), key)));
        return rows;
    }

    public record CreateTable(int version, Table table) implements Step {
        public CreateTable { Objects.requireNonNull(table); }
        public List<String> addedRows() { return tableRows(table); }
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

    /** A required column with a database default: the rows already in the table get the default, so none is left empty. */
    public record AddDefaultedColumn(int version, String table, Column column) implements Step {
        public AddDefaultedColumn {
            Objects.requireNonNull(table); Objects.requireNonNull(column);
            if (column.nullable() || column.defaultValue().isEmpty() || column.primary() || column.autoIncrement() || column.unique()
                    || column.reference().isPresent())
                throw new IllegalArgumentException("only a plain required column with a default can be added this way");
        }
        public List<String> addedRows() { return List.of(SchemaContract.row(table, column)); }
        public String describe() { return "V" + version + " ADD_DEFAULTED_COLUMN " + table + "." + column.name(); }
    }

    /**
     * A column that is gone, and every value in it with it. Only a column no other table refers to and that is not a key:
     * dropping a column that holds a foreign key would first need that constraint dropped by its name.
     */
    public record DropColumn(int version, String table, Column column) implements Step {
        public DropColumn {
            Objects.requireNonNull(table); Objects.requireNonNull(column);
            if (column.primary() || column.reference().isPresent())
                throw new IllegalArgumentException("a key or reference column cannot be dropped: " + column.name());
        }
        public List<String> addedRows() { return List.of(); }
        public List<String> removedRows() { return List.of(SchemaContract.row(table, column)); }
        public String describe() { return "V" + version + " DROP_COLUMN " + table + "." + column.name(); }
    }

    /** A table that is gone, with all its rows. No table that stays may refer to it; the caller drops referring tables first. */
    public record DropTable(int version, Table table) implements Step {
        public DropTable { Objects.requireNonNull(table); }
        public List<String> addedRows() { return List.of(); }
        public List<String> removedRows() { return tableRows(table); }
        public String describe() { return "V" + version + " DROP_TABLE " + table.name(); }
    }
}
