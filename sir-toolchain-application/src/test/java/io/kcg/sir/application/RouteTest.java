package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.change.edit.Command;
import io.kcg.sir.change.edit.CommandBatch;
import io.kcg.sir.change.edit.EditResult;
import io.kcg.sir.change.edit.Selector;
import io.kcg.sir.change.edit.SourceEditor;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.api.SpringBootDeploymentPlanner;
import io.kcg.sir.semantic.schema.StructValue;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Where capabilities are reached: the method and path a REST-style Crud gets, the sample paths the packaged contract
 * test requests, and a path changed by an edit and written back.
 */
class RouteTest {
    private static final SourceId ENTRY = SourceId.of("project.sir");

    @Test void aRestStyleCrudIsReachedAtItsPathsAndTheContractTestRequestsSamplesOfThem() throws IOException {
        var compiled = compile(resource("/valid/crud-feature.sir"));
        var plan = new SpringBootDeploymentPlanner().plan(compiled.semanticModel(), compiled.loweredModel());
        Map<String, String> samples = new TreeMap<>();
        plan.api().endpoints().forEach(e -> samples.put(e.method() + " " + e.route(), e.sample()));
        assertEquals("/api/notes", samples.get("POST /api/notes"));
        assertEquals("/api/notes", samples.get("GET /api/notes"));
        assertEquals("/api/notes/1", samples.get("GET /api/notes/{id}"));
        assertEquals("/api/notes/1", samples.get("PATCH /api/notes/{id}"), "a versioned entity is changed by a patch");
        assertEquals("/api/notes/1", samples.get("DELETE /api/notes/{id}"));
        String uuid = "/api/items/00000000-0000-4000-8000-000000000001";
        assertEquals(uuid, samples.get("PUT /api/items/{id}"));
        assertEquals(uuid, samples.get("GET /api/items/{id}"));
        assertEquals(uuid, samples.get("DELETE /api/items/{id}"));
        // A Crud without a path is reached at its capabilities' names.
        assertEquals("/api/get-user", samples.get("GET /api/get-user"));
    }

    @Test void anEditOfThePathIsWrittenBack() throws IOException {
        String text = LanguageReferenceExamplesTest.SHELL_HEAD + """
                entity Note persistent {
                  identity id: Id generated auto;
                  field body: Text;
                }
                view NoteView from Note {
                  field id: Id;
                  field body: Text;
                }
                input NoteRef {
                  field id: Id;
                }
                error NoteNotFound http 404;
                capability ShowNote {
                  input NoteRef;
                  output NoteView;
                  fails NoteNotFound;
                  requires readonly;
                  expose query at "/notes/{id}";
                  workflow {
                    load Note by input.id as note else NoteNotFound;
                    return note;
                  }
                }
                """ + LanguageReferenceExamplesTest.SHELL_TAIL;
        var snapshot = new SourceSnapshot(ENTRY, Map.of(ENTRY, text.getBytes(StandardCharsets.UTF_8)));
        var result = new SourceEditor().edit(snapshot, new CommandBatch(CommandBatch.PROTOCOL, "Reference", SourceEditor.sha256(snapshot),
            List.of(new Command.Update(Selector.root().named("declarations", "ShowNote").slot("path"), new StructValue.Text("/memos/{id}")))));
        assertTrue(result instanceof EditResult.Edited, result::toString);
        String written = new String(((EditResult.Edited) result).sources().bytes(ENTRY), StandardCharsets.UTF_8);
        assertTrue(written.contains("expose query at \"/memos/{id}\";"), written);
    }

    private static ProjectCompilation.Compiled compile(String text) {
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        var compiled = ProjectCompilation.compile(new SourceSnapshot(ENTRY, Map.of(ENTRY, text.getBytes(StandardCharsets.UTF_8))),
            model -> new SpringBootGenerator().generate(model), diagnostics);
        assertTrue(compiled.isPresent(), diagnostics::toString);
        return compiled.get();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = RouteTest.class.getResourceAsStream(name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
