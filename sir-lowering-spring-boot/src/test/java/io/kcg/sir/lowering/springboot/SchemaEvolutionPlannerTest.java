package io.kcg.sir.lowering.springboot;

import io.kcg.sir.api.*;
import io.kcg.sir.ast.AstSourceUnit;
import io.kcg.sir.lowering.springboot.api.*;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan;
import io.kcg.sir.lowering.springboot.model.SchemaContract;
import io.kcg.sir.lowering.springboot.model.SchemaEvolution;
import io.kcg.sir.semantic.api.*;
import io.kcg.sir.source.SourceId;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** What an update may do to an existing physical schema, decided from the base delivery's frozen contract. */
class SchemaEvolutionPlannerTest {
    /** The end of entity Note only (view NoteView also ends with a text field). */
    private static final String NOTE = "      field author: Ref<User>;\n      field text: String;\n    }";

    @Test void anUnchangedSchemaAppendsNoVersion() {
        var outcome = continueFrom(base(), plan(base()));
        assertEquals(List.of(), outcome.evolution().orElseThrow().steps());
        assertEquals(1, outcome.evolution().orElseThrow().targetVersion());
    }

    @Test void newTablesComeAfterTheTablesTheyReferenceAndNewNullableColumnsLast() {
        // Tag refers to Topic, which is declared after it: Topic must still be created first.
        String target = base().replace(NOTE, "      field author: Ref<User>;\n      field text: String;\n      field title: Optional<String> where length(0, 80);\n    }\n"
            + "    entity Tag persistent { identity id: Int64 generated auto; field topic: Ref<Topic>; field parent: Optional<Ref<Tag>>; }\n"
            + "    entity Topic persistent { identity id: Int64 generated auto; field note: Ref<Note>; }");
        var steps = continueFrom(base(), plan(target)).evolution().orElseThrow().steps();
        assertEquals(List.of("V2 CREATE_TABLE topic", "V3 CREATE_TABLE tag", "V4 ADD_NULLABLE_COLUMN note.title"),
            steps.stream().map(SchemaEvolution.Step::describe).toList());
    }

    @Test void everyUnsupportedChangeIsListedAndNothingIsPlanned() {
        String archive = "\n    entity Archive persistent { identity id: Int64 generated auto; field text: String; }";
        String before = base().replace(NOTE, NOTE + archive);
        String after = base()
            .replace("field name: String where length(1, 64) unique;", "field name: String where length(1, 128) unique;")
            .replace(NOTE, "      field author: Ref<User>;\n      field text: String;\n      field editor: Optional<Ref<User>>;\n    }" + archive.replace("field text", "field body"));
        var outcome = new SpringBootSchemaEvolutionPlanner().plan(contract(before), 1, "mysql:8.4.5", plan(after));
        assertTrue(outcome.evolution().isEmpty());
        var kinds = outcome.refusals().stream().map(r -> r.split(" ")[0] + " " + r.split(" ")[1].replace(":", "")).toList();
        // A rename is a removal plus an addition, never guessed from the name. (SIR has no optional unique field.)
        assertEquals(java.util.Set.of("COLUMN_CHANGED user.name", "COLUMN_REMOVED archive.text", "REFERENCE_COLUMN_ADDED note.editor",
            "REQUIRED_COLUMN_ADDED archive.body"), java.util.Set.copyOf(kinds), outcome.refusals().toString());
        assertEquals(4, kinds.size());
    }

    @Test void removedTablesNewTableCyclesAndAnotherDatabaseEngineAreRefused() {
        String withArchive = base().replace(NOTE, NOTE + "\n    entity Archive persistent { identity id: Int64 generated auto; field text: String; }");
        var removed = new SpringBootSchemaEvolutionPlanner().plan(contract(withArchive), 1, "mysql:8.4.5", plan(base()));
        assertEquals(List.of("TABLE_REMOVED archive"), removed.refusals());
        String cycle = base().replace(NOTE, NOTE + "\n    entity Left persistent { identity id: Int64 generated auto; field right: Ref<Right>; }\n"
            + "    entity Right persistent { identity id: Int64 generated auto; field left: Ref<Left>; }");
        assertTrue(refusals(cycle).stream().anyMatch(r -> r.startsWith("NEW_TABLE_CYCLE")), refusals(cycle).toString());
        var engine = new SpringBootSchemaEvolutionPlanner().plan(contract(base()), 1, "mysql:8.0.0", plan(base()));
        assertEquals(List.of("ENGINE_CHANGED mysql: mysql:8.0.0 -> mysql:8.4.5"), engine.refusals());
    }

    private static List<String> refusals(String target) { return continueFrom(base(), plan(target)).refusals(); }

    private static SpringBootSchemaEvolutionPlanner.Outcome continueFrom(String base, DeploymentPlan target) {
        return new SpringBootSchemaEvolutionPlanner().plan(contract(base), 1, "mysql:8.4.5", target);
    }

    private static List<String> contract(String source) { return SchemaContract.render(plan(source).tables()).lines().toList(); }

    private static String base() {
        return LoweringTestSupport.source("valid/bearer-credential.sir").replace("field name: String unique;", "field name: String where length(1, 64) unique;");
    }

    private static DeploymentPlan plan(String source) {
        var parsed = SirParser.create().parseProject(new SirSource(SourceId.of("project.sir"), source));
        assertTrue(parsed.isSuccess(), () -> parsed.diagnostics().toString());
        var semantic = new SirSemanticAnalyzer().analyzeProject(new ProjectSemanticInput((AstSourceUnit.Root) parsed.unit().orElseThrow(), List.of()));
        assertTrue(semantic.isSuccess(), () -> semantic.diagnostics().toString());
        var model = semantic.model().orElseThrow();
        var lowered = new SpringBootTargetLowering().lower(model);
        assertTrue(lowered.isSuccess(), () -> lowered.diagnostics().toString());
        return new SpringBootDeploymentPlanner().plan(model, lowered.model().orElseThrow());
    }
}
