package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedCapability;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@code expose command put at "/notes/{id}";}: where a capability is reached, and what may be written there. */
class RouteSemanticsTest {
    private static final String BASE = """
            entity Note persistent {
              identity id: Id generated auto;
              field body: Name;
              field pinned: Flag;
              field version: Version;
            }
            view NoteView from Note {
              field id: Id;
              field body: Name;
            }
            input NoteRef {
              field id: Id;
              field slug: Optional<Text>;
              field pinned: Flag;
            }
            input PatchNote patch of Note {
              field id: Id;
              field body: Text;
            }
            error NoteNotFound http 404;
            error Stale http 409;
            capability GetNote {
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
            capability DeleteNote {
              input NoteRef;
              output Unit;
              fails NoteNotFound;
              requires atomic;
              expose command delete at "/notes/{id}";
              workflow {
                load Note by input.id as note else NoteNotFound;
                delete note;
                return unit;
              }
            }
            capability EditNote {
              input PatchNote;
              output NoteView;
              fails NoteNotFound;
              fails Stale;
              requires atomic;
              expose command at "/notes/{id}";
              workflow {
                load Note by input.id as note else NoteNotFound;
                update note {
                  body: input.body;
                }
                persist note else Stale;
                return note;
              }
            }
            """;

    @Test
    void theModelKeepsTheMethodAndThePathAsWritten() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir(BASE));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        var capabilities = result.model().orElseThrow().declarations().stream().filter(NormalizedCapability.class::isInstance)
            .map(NormalizedCapability.class::cast).toList();
        var delete = capabilities.stream().filter(c -> c.name().equals("DeleteNote")).findFirst().orElseThrow();
        assertEquals(Optional.of("delete"), delete.method());
        assertEquals(Optional.of("/notes/{id}"), delete.path());
        var get = capabilities.stream().filter(c -> c.name().equals("GetNote")).findFirst().orElseThrow();
        assertEquals(Optional.empty(), get.method());
    }

    @Test
    void whatMayNotBeWrittenIsRefusedAtTheExpose() {
        Map<String, String> refused = Map.of(
            "a query cannot choose its method", BASE.replace("expose query at", "expose query put at"),
            "a patch payload is always PATCH", BASE.replace("expose command at \"/notes/{id}\"", "expose command put at \"/notes/{id}\""),
            "a path segment is lower-case", BASE.replace("expose query at \"/notes/{id}\"", "expose query at \"/Notes/{id}\""),
            "a path names a field of the input", BASE.replace("expose query at \"/notes/{id}\"", "expose query at \"/notes/{noteId}\""),
            "a path field is required", BASE.replace("expose query at \"/notes/{id}\"", "expose query at \"/notes/{slug}\""),
            "a path field is a number, a text or a uuid", BASE.replace("expose query at \"/notes/{id}\"", "expose query at \"/notes/{pinned}\""),
            "a patch payload puts only its identity in the path", BASE.replace("expose command at \"/notes/{id}\"", "expose command at \"/notes/{body}\""),
            "two capabilities cannot share a method and a path shape", BASE.replace("expose command delete at \"/notes/{id}\"", "expose command delete at \"/notes/{noteId}\"")
                .replace("input NoteRef {\n              field id: Id;", "input NoteRef {\n              field id: Id;\n              field noteId: Id;")
                .replace("expose query at \"/notes/{id}\"", "expose command delete at \"/notes/{id}\"").replace("requires readonly;\n              expose command delete", "requires atomic;\n              expose command delete"),
            "the same path twice", BASE.replace("expose query at \"/notes/{id}\"", "expose query at \"/notes/{id}/{id}\""));
        refused.forEach((why, source) -> {
            SemanticAnalysis result = TestSources.analyze(TestSources.sir(source));
            assertTrue(TestSources.hasError(result, "SIR-VALID-013"), why + " -> " + result.diagnostics());
        });
    }

    @Test
    void putDeleteAndAtStayLegalNames() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir("entity at persistent {\n  identity id: Id generated auto;\n  field put: Text;\n  field delete: Text;\n}\n"));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }
}
