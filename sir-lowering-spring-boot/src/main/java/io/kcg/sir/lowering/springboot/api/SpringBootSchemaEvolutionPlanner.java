package io.kcg.sir.lowering.springboot.api;

import io.kcg.sir.lowering.springboot.model.DeploymentPlan;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan.Column;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan.Table;
import io.kcg.sir.lowering.springboot.model.SchemaContract;
import io.kcg.sir.lowering.springboot.model.SchemaEvolution;
import java.util.*;

/**
 * Decides how a new delivery continues the schema of the delivery it updates, from that delivery's frozen contract
 * (never by recompiling its sources). The match is by exact physical name: physical names come from current names
 * only, so a rename is a removal and is refused rather than guessed. Round 1 supports an unchanged schema, new tables
 * and new plain nullable columns; everything else is listed as a refusal and nothing is planned.
 */
public final class SpringBootSchemaEvolutionPlanner {

    /** Either an evolution, or every refusal ("KIND subject: before -> after"). SELFCHECK refusals are internal faults. */
    public record Outcome(Optional<SchemaEvolution> evolution, List<String> refusals) {
        public Outcome { Objects.requireNonNull(evolution); refusals = List.copyOf(refusals); }
    }

    public Outcome plan(List<String> baseContract, int baseVersion, String baseMysqlImage, DeploymentPlan target) {
        Map<String, Map<String, String>> base = new LinkedHashMap<>();
        for (String line : baseContract) {
            if (line.isEmpty()) continue;
            String[] row = line.split("\t", -1);
            if (row.length != 10) throw new IllegalArgumentException("invalid base schema contract row");
            if (base.computeIfAbsent(row[0], t -> new LinkedHashMap<>()).put(row[1], line) != null)
                throw new IllegalArgumentException("duplicate base schema contract row: " + row[0] + "." + row[1]);
        }
        List<String> refusals = new ArrayList<>();
        if (!target.images().mysql().equals(baseMysqlImage))
            refusals.add("ENGINE_CHANGED mysql: " + baseMysqlImage + " -> " + target.images().mysql());
        Map<String, Table> tables = new LinkedHashMap<>();
        target.tables().forEach(t -> tables.put(t.name(), t));
        for (var entry : base.entrySet()) {
            Table now = tables.get(entry.getKey());
            if (now == null) { refusals.add("TABLE_REMOVED " + entry.getKey()); continue; }
            for (var column : entry.getValue().entrySet()) {
                Optional<Column> current = now.columns().stream().filter(c -> c.name().equals(column.getKey())).findFirst();
                if (current.isEmpty()) refusals.add("COLUMN_REMOVED " + now.name() + "." + column.getKey());
                else {
                    String after = SchemaContract.row(now.name(), current.get());
                    if (!after.equals(column.getValue())) refusals.add("COLUMN_CHANGED " + now.name() + "." + column.getKey() + ": "
                        + attributes(column.getValue()) + " -> " + attributes(after));
                }
            }
        }
        Set<String> baseTablesLower = new HashSet<>();
        base.keySet().forEach(t -> baseTablesLower.add(t.toLowerCase(Locale.ROOT)));
        List<SchemaEvolution.Step> adds = new ArrayList<>();
        List<Table> created = new ArrayList<>();
        for (Table table : target.tables()) {
            Map<String, String> old = base.get(table.name());
            if (old == null) {
                if (baseTablesLower.contains(table.name().toLowerCase(Locale.ROOT))) refusals.add("NAME_CASE_CONFLICT " + table.name());
                else created.add(table);
                continue;
            }
            Set<String> oldLower = new HashSet<>();
            old.keySet().forEach(c -> oldLower.add(c.toLowerCase(Locale.ROOT)));
            for (Column column : table.columns()) {
                if (old.containsKey(column.name())) continue;
                String subject = table.name() + "." + column.name();
                if (oldLower.contains(column.name().toLowerCase(Locale.ROOT))) refusals.add("NAME_CASE_CONFLICT " + subject);
                else if (!column.nullable()) refusals.add("REQUIRED_COLUMN_ADDED " + subject);
                else if (column.unique()) refusals.add("UNIQUE_COLUMN_ADDED " + subject);
                else if (column.reference().isPresent()) refusals.add("REFERENCE_COLUMN_ADDED " + subject);
                else adds.add(new SchemaEvolution.AddNullableColumn(0, table.name(), column));
            }
        }
        List<Table> ordered = dependencyOrder(created, refusals);
        if (!refusals.isEmpty()) return new Outcome(Optional.empty(), refusals);
        List<SchemaEvolution.Step> steps = new ArrayList<>();
        int version = baseVersion;
        for (Table table : ordered) steps.add(new SchemaEvolution.CreateTable(++version, table));
        for (var add : adds) {
            var column = (SchemaEvolution.AddNullableColumn) add;
            steps.add(new SchemaEvolution.AddNullableColumn(++version, column.table(), column.column()));
        }
        var evolution = new SchemaEvolution(baseVersion, steps);
        // The chain must end exactly at the target contract; anything else is a fault of this planner, not of the input.
        Set<String> reached = new HashSet<>();
        base.values().forEach(rows -> reached.addAll(rows.values()));
        steps.forEach(step -> reached.addAll(step.addedRows()));
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

    private static String attributes(String row) {
        String[] f = row.split("\t", -1);
        return f[2] + "(" + f[3] + ") nullable=" + f[4] + " primary=" + f[5] + " auto=" + f[6] + " unique=" + f[7]
            + (f[8].isEmpty() ? "" : " ref=" + f[8] + "." + f[9]);
    }
}
