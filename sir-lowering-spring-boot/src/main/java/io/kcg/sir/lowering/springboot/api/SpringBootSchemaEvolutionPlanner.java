package io.kcg.sir.lowering.springboot.api;

import io.kcg.sir.change.edit.SemanticDelta;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan.Column;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan.Table;
import io.kcg.sir.lowering.springboot.model.SchemaContract;
import io.kcg.sir.lowering.springboot.model.SchemaEvolution;
import java.util.*;

/**
 * Translates what changed in the software into how the schema of a running delivery continues.
 *
 * <p>The change layer decides what changed ({@link SemanticDelta}); this class only maps those changes onto tables and
 * columns, matched by semantic identity rather than by name, and decides which of them the target can apply. An entity
 * or field the delta adds becomes a table or column to create. A column kept under the same identity is compared
 * physically and walked through its changes one at a time: first renamed (a field renamed under a declared {@code @id}
 * keeps its values), then widened (a 32-bit number to 64-bit, a bounded text to a larger limit or to unbounded, a
 * required column to an optional one; no existing value changes), then given its new database default (the rows keep
 * their values). Any other difference is refused. An entity or field the delta deletes is dropped, rows and all, but only
 * when the caller says the data loss is accepted ({@code allowDataLoss}); otherwise it is refused. Dropping a key
 * column, or a column that refers to another table, is always refused.
 *
 * <p>A table or column that appears or disappears without the delta adding or deleting it means the two inputs
 * disagree. That, and a chain that does not end at the target contract, are faults of the toolchain (SELFCHECK), never
 * the author's.
 */
public final class SpringBootSchemaEvolutionPlanner {

    /** Either an evolution, or every refusal ("KIND subject: before -> after"). SELFCHECK refusals are internal faults. */
    public record Outcome(Optional<SchemaEvolution> evolution, List<String> refusals) {
        public Outcome { Objects.requireNonNull(evolution); refusals = List.copyOf(refusals); }
    }

    /**
     * @param base           the plan of the running delivery's sources, as this toolchain lowers them; the caller has
     *                       checked that it renders exactly the running delivery's schema contract
     * @param baseMysqlImage the database image the running delivery was published with
     * @param baseVersion    the last schema version of the running delivery
     * @param delta          what changed from the running delivery's model to the target's
     */
    public Outcome plan(DeploymentPlan base, String baseMysqlImage, int baseVersion, SemanticDelta delta, DeploymentPlan target) {
        return plan(base, baseMysqlImage, baseVersion, delta, target, false);
    }

    /** @param allowDataLoss whether the tables and columns the delta deletes may be dropped, with the data in them */
    public Outcome plan(DeploymentPlan base, String baseMysqlImage, int baseVersion, SemanticDelta delta, DeploymentPlan target,
            boolean allowDataLoss) {
        Set<String> added = new HashSet<>(), deleted = new HashSet<>();
        for (SemanticDelta.Change change : delta.changes()) {
            change.subject().ifPresent(subject -> (change.kind() == SemanticDelta.Kind.ADD ? added : deleted).add(subject));
        }
        List<String> refusals = new ArrayList<>();
        if (!target.images().mysql().equals(baseMysqlImage))
            refusals.add("ENGINE_CHANGED mysql: " + baseMysqlImage + " -> " + target.images().mysql());
        Map<String, Table> targetTables = new LinkedHashMap<>();
        target.tables().forEach(t -> targetTables.put(t.symbol(), t));
        Set<String> baseTableSymbols = new HashSet<>(), baseTableNamesLower = new HashSet<>();
        List<SchemaEvolution.RenameColumn> renames = new ArrayList<>();
        List<SchemaEvolution.WidenColumn> widenings = new ArrayList<>();
        List<SchemaEvolution.ChangeColumnDefault> defaults = new ArrayList<>();
        List<SchemaEvolution.DropColumn> droppedColumns = new ArrayList<>();
        List<Table> droppedTables = new ArrayList<>();
        for (Table old : base.tables()) {
            baseTableSymbols.add(old.symbol());
            baseTableNamesLower.add(old.name().toLowerCase(Locale.ROOT));
            Table now = targetTables.get(old.symbol());
            if (now == null) {
                if (!deleted.contains(old.symbol()))
                    refusals.add("SELFCHECK table " + old.name() + " is gone but the change layer deleted no entity for it");
                else if (!allowDataLoss) refusals.add("TABLE_REMOVED " + old.name());
                else droppedTables.add(old);
                continue;
            }
            // A unique key is checked against the rows already there, so one cannot be added to, taken from or changed on a
            // table that exists; it can only come with a new table.
            if (!old.uniqueKeys().equals(now.uniqueKeys())) refusals.add("UNIQUE_KEY_CHANGED " + old.name());
            Map<String, Column> nowColumns = new LinkedHashMap<>();
            now.columns().forEach(c -> nowColumns.put(c.symbol(), c));
            for (Column column : old.columns()) {
                Column current = nowColumns.get(column.symbol());
                if (current == null) {
                    String subject = old.name() + "." + column.name();
                    if (!deleted.contains(column.symbol()))
                        refusals.add("SELFCHECK column " + subject + " is gone but the change layer deleted no field for it");
                    else if (column.primary()) refusals.add("PRIMARY_COLUMN_REMOVED " + subject);
                    else if (column.reference().isPresent()) refusals.add("REFERENCE_COLUMN_REMOVED " + subject);
                    else if (!allowDataLoss) refusals.add("COLUMN_REMOVED " + subject);
                    else droppedColumns.add(new SchemaEvolution.DropColumn(0, old.name(), column));
                    continue;
                }
                String before = SchemaContract.row(old.name(), column), after = SchemaContract.row(now.name(), current);
                if (before.equals(after)) continue;
                // The column's states, one change each: renamed, widened, then given its final default. Each version is
                // written against the column as the versions before it left it.
                Column renamed = rename(column, current.name());
                Column widened = withDefault(current, column.defaultValue());
                boolean widen = !renamed.equals(widened);
                if (widen && !SchemaEvolution.WidenColumn.widens(renamed, widened)) {
                    refusals.add("COLUMN_CHANGED " + old.name() + "." + column.name() + ": " + attributes(before) + " -> " + attributes(after));
                    continue;
                }
                if (!column.name().equals(current.name())) {
                    String subject = old.name() + "." + column.name() + " -> " + current.name();
                    boolean taken = old.columns().stream().anyMatch(c -> !c.symbol().equals(column.symbol())
                        && c.name().equalsIgnoreCase(current.name()));
                    if (column.primary()) { refusals.add("PRIMARY_COLUMN_RENAMED " + subject); continue; }
                    if (taken) { refusals.add("NAME_CASE_CONFLICT " + subject); continue; }
                    renames.add(new SchemaEvolution.RenameColumn(0, old.name(), column, renamed));
                }
                if (widen) widenings.add(new SchemaEvolution.WidenColumn(0, now.name(), renamed, widened));
                if (!column.defaultValue().equals(current.defaultValue()))
                    defaults.add(new SchemaEvolution.ChangeColumnDefault(0, now.name(), widened, current));
            }
        }
        List<SchemaEvolution.Step> columnsToAdd = new ArrayList<>();
        List<Table> created = new ArrayList<>();
        for (Table table : target.tables()) {
            if (!baseTableSymbols.contains(table.symbol())) {
                if (!added.contains(table.symbol())) refusals.add("SELFCHECK table " + table.name() + " is new but the change layer added no entity for it");
                else if (baseTableNamesLower.contains(table.name().toLowerCase(Locale.ROOT))) refusals.add("NAME_CASE_CONFLICT " + table.name());
                else created.add(table);
                continue;
            }
            Table old = base.tables().stream().filter(t -> t.symbol().equals(table.symbol())).findFirst().orElseThrow();
            Set<String> oldSymbols = new HashSet<>(), oldNamesLower = new HashSet<>();
            old.columns().forEach(c -> { oldSymbols.add(c.symbol()); oldNamesLower.add(c.name().toLowerCase(Locale.ROOT)); });
            for (Column column : table.columns()) {
                if (oldSymbols.contains(column.symbol())) continue;
                String subject = table.name() + "." + column.name();
                if (!added.contains(column.symbol())) refusals.add("SELFCHECK column " + subject + " is new but the change layer added no field for it");
                else if (oldNamesLower.contains(column.name().toLowerCase(Locale.ROOT))) refusals.add("NAME_CASE_CONFLICT " + subject);
                else if (!column.nullable() && column.defaultValue().isEmpty()) refusals.add("REQUIRED_COLUMN_ADDED " + subject);
                else if (column.unique()) refusals.add("UNIQUE_COLUMN_ADDED " + subject);
                else if (column.reference().isPresent()) refusals.add("REFERENCE_COLUMN_ADDED " + subject);
                else if (column.nullable()) columnsToAdd.add(new SchemaEvolution.AddNullableColumn(0, table.name(), column));
                else columnsToAdd.add(new SchemaEvolution.AddDefaultedColumn(0, table.name(), column));
            }
        }
        List<Table> ordered = dependencyOrder(created, refusals);
        List<Table> dropOrder = dropOrder(droppedTables, target, refusals);
        if (!refusals.isEmpty()) return new Outcome(Optional.empty(), refusals);
        List<SchemaEvolution.Step> steps = new ArrayList<>();
        int version = baseVersion;
        // Renames first, so every later version is written against the final column names.
        for (var rename : renames) steps.add(new SchemaEvolution.RenameColumn(++version, rename.table(), rename.from(), rename.to()));
        for (var widening : widenings) steps.add(new SchemaEvolution.WidenColumn(++version, widening.table(), widening.from(), widening.to()));
        for (var change : defaults) steps.add(new SchemaEvolution.ChangeColumnDefault(++version, change.table(), change.from(), change.to()));
        for (Table table : ordered) steps.add(new SchemaEvolution.CreateTable(++version, table));
        for (var column : columnsToAdd) steps.add(switch (column) {
            case SchemaEvolution.AddNullableColumn c -> new SchemaEvolution.AddNullableColumn(++version, c.table(), c.column());
            case SchemaEvolution.AddDefaultedColumn c -> new SchemaEvolution.AddDefaultedColumn(++version, c.table(), c.column());
            default -> throw new IllegalStateException("not a column addition: " + column);
        });
        // Drops come last, so nothing above reads a column that is about to go, and tables go before the tables they refer to.
        for (var drop : droppedColumns) steps.add(new SchemaEvolution.DropColumn(++version, drop.table(), drop.column()));
        for (Table table : dropOrder) steps.add(new SchemaEvolution.DropTable(++version, table));
        var evolution = new SchemaEvolution(baseVersion, steps);
        // The chain must end exactly at the target contract; anything else is a fault of this planner, not of the input.
        Set<String> reached = new HashSet<>(SchemaContract.render(base.tables()).lines().toList());
        for (var step : steps) { step.removedRows().forEach(reached::remove); reached.addAll(step.addedRows()); }
        Set<String> expected = new HashSet<>(SchemaContract.render(target.tables()).lines().toList());
        if (!reached.equals(expected)) return new Outcome(Optional.empty(), List.of("SELFCHECK the planned chain does not end at the target contract"));
        return new Outcome(Optional.of(evolution), List.of());
    }

    /** New tables after the ones they reference (self references are inline); a reference cycle among them is refused. */
    private static List<Table> dependencyOrder(List<Table> created, List<String> refusals) {
        Set<String> pending = new LinkedHashSet<>();
        created.forEach(t -> pending.add(t.name()));
        List<Table> ordered = new ArrayList<>();
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (Table table : created) {
                if (!pending.contains(table.name())) continue;
                boolean ready = table.columns().stream().flatMap(c -> c.reference().stream())
                    .noneMatch(ref -> !ref.table().equals(table.name()) && pending.contains(ref.table()));
                if (ready) { ordered.add(table); pending.remove(table.name()); progress = true; }
            }
        }
        if (!pending.isEmpty()) refusals.add("NEW_TABLE_CYCLE " + String.join(", ", pending));
        return ordered;
    }

    /**
     * The tables to drop, those that refer to another dropped table first. A table that stays and still refers to a
     * dropped one cannot happen unless its reference column is also gone, which is refused on its own; it is checked
     * all the same, as is a reference cycle among the dropped tables.
     */
    private static List<Table> dropOrder(List<Table> dropped, DeploymentPlan target, List<String> refusals) {
        Set<String> names = new LinkedHashSet<>();
        dropped.forEach(t -> names.add(t.name()));
        for (Table kept : target.tables()) for (Column column : kept.columns())
            if (column.reference().isPresent() && names.contains(column.reference().get().table()))
                refusals.add("TABLE_REFERENCED " + column.reference().get().table() + " by " + kept.name() + "." + column.name());
        Set<String> pending = new LinkedHashSet<>(names);
        List<Table> ordered = new ArrayList<>();
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (Table table : dropped) {
                if (!pending.contains(table.name())) continue;
                // Nothing still waiting to be dropped may refer to this table (a table referring to itself is dropped with it).
                boolean referred = dropped.stream().filter(other -> pending.contains(other.name()) && !other.name().equals(table.name()))
                    .anyMatch(other -> other.columns().stream().anyMatch(c -> c.reference().map(r -> r.table().equals(table.name())).orElse(false)));
                if (!referred) { ordered.add(table); pending.remove(table.name()); progress = true; }
            }
        }
        if (!pending.isEmpty()) refusals.add("DROPPED_TABLE_CYCLE " + String.join(", ", pending));
        return ordered;
    }

    private static Column rename(Column column, String name) {
        return new Column(column.symbol(), name, column.storage(), column.length(), column.nullable(), column.primary(),
            column.autoIncrement(), column.unique(), column.reference(), column.defaultValue(), column.decimalShape());
    }

    private static Column withDefault(Column column, Optional<String> defaultValue) {
        return new Column(column.symbol(), column.name(), column.storage(), column.length(), column.nullable(), column.primary(),
            column.autoIncrement(), column.unique(), column.reference(), defaultValue, column.decimalShape());
    }

    /** Everything a contract row says about a column except where it is (table and column name). */
    private static String attributes(String row) {
        String[] f = row.split("\t", -1);
        return f[2] + "(" + f[3] + ") nullable=" + f[4] + " primary=" + f[5] + " auto=" + f[6] + " unique=" + f[7]
            + (f[8].isEmpty() ? "" : " ref=" + f[8] + "." + f[9]) + (f[10].isEmpty() ? "" : " default" + f[10])
            + (f.length == 13 ? " precision=" + f[11] + " scale=" + f[12] : "");
    }
}
