package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A new row can collide with a row that is already there (a unique field). {@code persist row else Error} says which
 * declared error that collision is answered with; without it the database's exception reaches the client as a 500.
 */
class UniqueConflictTest {
    private static final SourceId ENTRY = SourceId.of("project.sir");
    private static final String SOURCE = LanguageReferenceExamplesTest.SHELL_HEAD + """
            entity Account persistent {
              identity id: Id generated auto;
              field name: Name unique;
            }
            entity Note persistent {
              identity id: Id generated auto;
              field body: Text;
            }
            view AccountView from Account {
              field id: Id;
              field name: Name;
            }
            view NoteView from Note {
              field id: Id;
              field body: Text;
            }
            input NewAccount {
              field name: Name;
            }
            input NewNote {
              field body: Text;
            }
            error NameTaken http 409;
            error NoteTaken http 409;
            capability Register {
              input NewAccount;
              output AccountView;
              fails NameTaken;
              requires atomic;
              expose command;
              workflow {
                create Account as made {
                  name: input.name;
                }
                persist made else NameTaken;
                return made;
              }
            }
            capability AddNote {
              input NewNote;
              output NoteView;
              requires atomic;
              expose command;
              workflow {
                create Note as made {
                  body: input.body;
                }
                persist made;
                return made;
              }
            }
            """ + LanguageReferenceExamplesTest.SHELL_TAIL;

    @Test void aNewRowThatCollidesIsAnsweredWithTheDeclaredError() {
        var compiled = compile(SOURCE);
        assertTrue(compiled.isPresent());
        String register = service(compiled.get(), "RegisterService"), addNote = service(compiled.get(), "AddNoteService");
        assertTrue(register.contains("catch (org.springframework.dao.DuplicateKeyException $duplicate)"), register);
        assertTrue(register.contains("throw new NameTakenException();"), register);
        // No else, nothing caught: the behaviour of a persist without it is unchanged.
        assertTrue(!addNote.contains("DuplicateKeyException"), addNote);
    }

    @Test void anElseOnAnEntityWithNothingToCollideWithIsRefused() {
        String wrong = SOURCE.replace("input NewNote;\n  output NoteView;\n", "input NewNote;\n  output NoteView;\n  fails NoteTaken;\n")
            .replace("persist made;", "persist made else NoteTaken;");
        assertEquals(List.of("SIR-VALID-004"), errors(wrong));
    }

    private static java.util.Optional<io.kcg.sir.application.internal.ProjectCompilation.Compiled> compile(String text) {
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var compiled = ProjectCompilation.compile(new SourceSnapshot(ENTRY, Map.of(ENTRY, text.getBytes(StandardCharsets.UTF_8))),
            model -> new SpringBootGenerator().generate(model), diagnostics);
        assertTrue(compiled.isPresent(), diagnostics::toString);
        return compiled;
    }

    private static List<String> errors(String text) {
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var compiled = ProjectCompilation.compile(new SourceSnapshot(ENTRY, Map.of(ENTRY, text.getBytes(StandardCharsets.UTF_8))),
            model -> new SpringBootGenerator().generate(model), diagnostics);
        assertTrue(compiled.isEmpty(), "expected a refusal");
        return diagnostics.stream().filter(ExecutionDiagnostic::isError).map(ExecutionDiagnostic::code).distinct().toList();
    }

    private static String service(ProjectCompilation.Compiled compiled, String name) {
        return compiled.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/" + name + ".java")).findFirst().orElseThrow().content();
    }
}
