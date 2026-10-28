package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workflow variable keeps its SIR name in the generated method, so every local the generator adds next to it
 * (a catch parameter, a query wrapper, paging counters, a projection lambda, batch-read rows) is written with a
 * leading {@code $}, which a SIR identifier cannot contain. Here the author's variables take exactly the names those
 * locals used to have, and the generated project must still compile.
 */
class GeneratedLocalNamesTest {
    private static final String SOURCE = """
            sir 0.3
            software LocalNames {
              metadata { displayName "Local Names"; namespace "example.names"; }
              target { language java 21; framework spring_boot; persistence mybatis_plus;
                database mysql; build maven; interface rest; }
              sources { } imports { }
              declarations {
                entity Tag persistent { identity id: Id generated auto; field name: Code unique; }
                entity Note persistent { identity id: Id generated auto; field tag: Ref<Tag>; field title: Text; }
                view TagView from Tag { field id: Id; field name: Code; }
                view NoteView from Note { field id: Id; field title: Text; field tag: TagView; }
                input NewTag { field name: Code; }
                input NoteRef { field id: Id; }
                input Paging { field page: PageNo; field size: PageSize; }
                error Taken http 409;
                error Missing http 404;
                error BadPage;
                capability AddTag {
                  input NewTag; output TagView; fails Taken; requires atomic; expose command;
                  workflow {
                    exists Tag where item.name == input.name as duplicate;
                    validate not duplicate else Taken;
                    create Tag as made { name: input.name; }
                    persist made else Taken;
                    return made;
                  }
                }
                capability ListNotes {
                  input Paging; output Page<NoteView>; fails BadPage; requires readonly; expose query;
                  workflow {
                    count Tag where true as wrapper;
                    count Note where true as row;
                    count Tag where true as related0;
                    count Note where true as notesTotal;
                    find Note where true order by id ascending Page input.page, input.size else BadPage as notes;
                    return notes;
                  }
                }
                capability ShowNote {
                  input NoteRef; output NoteView; fails Missing; requires readonly; expose query;
                  workflow {
                    count Tag where true as returnedRows;
                    load Note by input.id as found else Missing;
                    return found;
                  }
                }
              }
            }
            """;

    @Test void authorVariablesNamedLikeGeneratorLocalsStillCompile(@TempDir Path directory) throws Exception {
        try (var loader = GeneratedRuntimeSupport.compile(SOURCE, directory)) {
            assertNotNull(loader.loadClass("example.names.application.AddTagService"));
            assertNotNull(loader.loadClass("example.names.application.ListNotesService"));
            assertNotNull(loader.loadClass("example.names.application.ShowNoteService"));
        }
    }
}
