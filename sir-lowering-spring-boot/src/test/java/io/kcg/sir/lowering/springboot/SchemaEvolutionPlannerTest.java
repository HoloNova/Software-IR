package io.kcg.sir.lowering.springboot;

import io.kcg.sir.api.*;
import io.kcg.sir.change.edit.SemanticDelta;
import io.kcg.sir.ast.AstSourceUnit;
import io.kcg.sir.lowering.springboot.api.*;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan;
import io.kcg.sir.lowering.springboot.model.SchemaEvolution;
import io.kcg.sir.semantic.api.*;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.SoftwareSchema;
import io.kcg.sir.source.SourceId;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What an update may do to an existing physical schema. Both sides are compiled from SIR, what changed comes from the
 * change layer, and the planner only maps it onto tables and columns by semantic identity.
 */
class SchemaEvolutionPlannerTest {
    /** The end of entity Note only (view NoteView also ends with a text field). */
    private static final String NOTE = "      field author: Ref<User>;\n      field text: Text;\n    }";

    @Test void anUnchangedSchemaAppendsNoVersion() {
        var outcome = continueFrom(base(), base());
        assertEquals(List.of(), outcome.evolution().orElseThrow().steps());
        assertEquals(1, outcome.evolution().orElseThrow().targetVersion());
    }

    @Test void newTablesComeAfterTheTablesTheyReferenceAndNewNullableColumnsLast() {
        // Tag refers to Topic, which is declared after it: Topic must still be created first.
        String target = base().replace(NOTE, "      field author: Ref<User>;\n      field text: Text;\n      field title: Optional<Text> where length(0, 80);\n    }\n"
            + "    entity Tag persistent { identity id: Id generated auto; field topic: Ref<Topic>; field parent: Optional<Ref<Tag>>; }\n"
            + "    entity Topic persistent { identity id: Id generated auto; field note: Ref<Note>; }");
        var steps = continueFrom(base(), target).evolution().orElseThrow().steps();
        assertEquals(List.of("V2 CREATE_TABLE topic", "V3 CREATE_TABLE tag", "V4 ADD_NULLABLE_COLUMN note.title"),
            steps.stream().map(SchemaEvolution.Step::describe).toList());
    }

    @Test void aRequiredColumnWithADatabaseDefaultCanBeAddedBecauseExistingRowsGetTheDefault() {
        String target = base().replace(NOTE, "      field author: Ref<User>;\n      field text: Text;\n      field visits: Count default 0;\n"
            + "      field label: Text where length(1, 16) default \"open\";\n    }");
        var steps = continueFrom(base(), target).evolution().orElseThrow().steps();
        assertEquals(List.of("V2 ADD_DEFAULTED_COLUMN note.visits", "V3 ADD_DEFAULTED_COLUMN note.label"),
            steps.stream().map(SchemaEvolution.Step::describe).toList());
        assertTrue(steps.get(1).addedRows().getFirst().endsWith("\t=open"), steps.get(1).addedRows().toString());
    }

    @Test void aChangedDefaultChangesOnlyTheColumnsDefaultAfterAnyRename() {
        String before = base().replace(NOTE, "      field author: Ref<User>;\n      field text: Text;\n      field visits: Count default 0;\n"
            + "      field label: Text where length(1, 16) default \"open\" @id(\"note-label\");\n    }");
        String after = before.replace("field visits: Count default 0;", "field visits: Count default 3;")
            .replace("field label: Text where length(1, 16) default \"open\"", "field tag: Text where length(1, 16) default \"new\"");
        var steps = continueFrom(before, after).evolution().orElseThrow().steps();
        assertEquals(List.of("V2 RENAME_COLUMN note.label -> tag", "V3 CHANGE_COLUMN_DEFAULT note.visits", "V4 CHANGE_COLUMN_DEFAULT note.tag"),
            steps.stream().map(SchemaEvolution.Step::describe).toList());
        // The rename keeps the old default; the default changes after it, under the new name.
        assertTrue(steps.get(0).addedRows().getFirst().endsWith("\t=open"), steps.get(0).addedRows().toString());
        assertTrue(steps.get(1).addedRows().getFirst().endsWith("\t=3"), steps.get(1).addedRows().toString());
        assertTrue(steps.get(2).addedRows().getFirst().endsWith("\t=new"), steps.get(2).addedRows().toString());
        // A default that changes together with anything else is still a changed column.
        var widened = continueFrom(before, before.replace("field visits: Count default 0;", "field visits: Optional<Count> default 1;"));
        assertTrue(widened.refusals().stream().anyMatch(r -> r.startsWith("COLUMN_CHANGED note.visits")), widened.refusals().toString());
    }

    @Test void aDefaultTheDatabaseCannotHoldStillLeavesARequiredColumnRefused() {
        // The moment of creation would give existing rows the migration's time; unbounded text holds no plain default.
        String target = base().replace(NOTE, "      field author: Ref<User>;\n      field text: Text;\n      field openedAt: Moment default now();\n"
            + "      field memo: Text default \"\";\n    }");
        var outcome = continueFrom(base(), target);
        assertEquals(List.of("REQUIRED_COLUMN_ADDED note.opened_at", "REQUIRED_COLUMN_ADDED note.memo"), outcome.refusals());
    }

    @Test void everyUnsupportedChangeIsListedAndNothingIsPlanned() {
        String archive = "\n    entity Archive persistent { identity id: Id generated auto; field text: Text; }";
        String before = base().replace(NOTE, NOTE + archive);
        String after = base()
            .replace("field name: Text where length(1, 64) unique;", "field name: Text where length(1, 128) unique;")
            .replace(NOTE, "      field author: Ref<User>;\n      field text: Text;\n      field editor: Optional<Ref<User>>;\n    }" + archive.replace("field text", "field body"));
        var outcome = continueFrom(before, after);
        assertTrue(outcome.evolution().isEmpty());
        var kinds = outcome.refusals().stream().map(r -> r.split(" ")[0] + " " + r.split(" ")[1].replace(":", "")).toList();
        // Without @id a field's identity comes from its name, so a rename really is a deletion plus an addition.
        assertEquals(java.util.Set.of("COLUMN_CHANGED user.name", "COLUMN_REMOVED archive.text", "REFERENCE_COLUMN_ADDED note.editor",
            "REQUIRED_COLUMN_ADDED archive.body"), java.util.Set.copyOf(kinds), outcome.refusals().toString());
        assertEquals(4, kinds.size());
    }

    @Test void aFieldThatKeepsItsDeclaredIdentityRenamesItsColumnFirst() {
        String before = base().replace(NOTE, NOTE + "\n    entity Archive persistent { identity id: Id generated auto; field text: Text @id(\"archive-text\"); }");
        String after = before.replace("field text: Text @id(", "field body: Text @id(")
            .replace(NOTE, "      field author: Ref<User>;\n      field text: Text;\n      field title: Optional<Text>;\n    }");
        var steps = continueFrom(before, after).evolution().orElseThrow().steps();
        assertEquals(List.of("V2 RENAME_COLUMN archive.text -> body", "V3 ADD_NULLABLE_COLUMN note.title"),
            steps.stream().map(SchemaEvolution.Step::describe).toList());
    }

    @Test void aRenamedKeyOrARenameThatAlsoChangesTheColumnIsRefused() {
        String before = base().replace(NOTE, NOTE + "\n    entity Archive persistent { identity id: Id generated auto; field text: Text @id(\"archive-text\"); }");
        // The identity keeps its identity under any name, but other tables refer to the key column by name.
        String key = before.replace("entity Archive persistent { identity id:", "entity Archive persistent { identity archiveId:");
        assertEquals(List.of("PRIMARY_COLUMN_RENAMED archive.id -> archive_id"), continueFrom(before, key).refusals());
        String retyped = before.replace("field text: Text @id(", "field body: Serial @id(");
        assertTrue(continueFrom(before, retyped).refusals().stream().anyMatch(r -> r.startsWith("COLUMN_CHANGED archive.text")),
            continueFrom(before, retyped).refusals().toString());
    }

    @Test void removedTablesNewTableCyclesAndAnotherDatabaseEngineAreRefused() {
        String withArchive = base().replace(NOTE, NOTE + "\n    entity Archive persistent { identity id: Id generated auto; field text: Text; }");
        assertEquals(List.of("TABLE_REMOVED archive"), continueFrom(withArchive, base()).refusals());
        String cycle = base().replace(NOTE, NOTE + "\n    entity Left persistent { identity id: Id generated auto; field right: Ref<Right>; }\n"
            + "    entity Right persistent { identity id: Id generated auto; field left: Ref<Left>; }");
        var cycleRefusals = continueFrom(base(), cycle).refusals();
        assertTrue(cycleRefusals.stream().anyMatch(r -> r.startsWith("NEW_TABLE_CYCLE")), cycleRefusals.toString());
        var compiled = compile(base());
        var engine = new SpringBootSchemaEvolutionPlanner().plan(compiled.plan(), "mysql:8.0.0", 1, new SemanticDelta(List.of()), compiled.plan());
        assertEquals(List.of("ENGINE_CHANGED mysql: mysql:8.0.0 -> mysql:8.4.5"), engine.refusals());
    }

    @Test void aSchemaDifferenceTheChangeLayerDidNotReportIsAToolchainFault() {
        String target = base().replace(NOTE, NOTE + "\n    entity Archive persistent { identity id: Id generated auto; field text: Text; }");
        var outcome = new SpringBootSchemaEvolutionPlanner().plan(compile(base()).plan(), "mysql:8.4.5", 1, new SemanticDelta(List.of()), compile(target).plan());
        assertEquals(List.of("SELFCHECK table archive is new but the change layer added no entity for it"), outcome.refusals());
    }

    private record Compiled(NormalizedSemanticModel model, DeploymentPlan plan) {}

    private static SpringBootSchemaEvolutionPlanner.Outcome continueFrom(String base, String target) {
        Compiled before = compile(base), after = compile(target);
        var delta = SemanticDelta.between(SoftwareDraft.of(before.model()), SoftwareDraft.of(after.model()), SoftwareSchema.normalizedModel());
        return new SpringBootSchemaEvolutionPlanner().plan(before.plan(), "mysql:8.4.5", 1, delta, after.plan());
    }

    private static String base() {
        return LoweringTestSupport.source("valid/bearer-credential.sir").replace("field name: Text unique;", "field name: Text where length(1, 64) unique;");
    }

    private static Compiled compile(String source) {
        var parsed = SirParser.create().parseProject(new SirSource(SourceId.of("project.sir"), source));
        assertTrue(parsed.isSuccess(), () -> parsed.diagnostics().toString());
        var semantic = new SirSemanticAnalyzer().analyzeProject(new ProjectSemanticInput((AstSourceUnit.Root) parsed.unit().orElseThrow(), List.of()));
        assertTrue(semantic.isSuccess(), () -> semantic.diagnostics().toString());
        var model = semantic.model().orElseThrow();
        var lowered = new SpringBootTargetLowering().lower(model);
        assertTrue(lowered.isSuccess(), () -> lowered.diagnostics().toString());
        return new Compiled(model, new SpringBootDeploymentPlanner().plan(model, lowered.model().orElseThrow()));
    }
}
