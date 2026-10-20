package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.change.edit.Command;
import io.kcg.sir.change.edit.CommandBatch;
import io.kcg.sir.change.edit.EditResult;
import io.kcg.sir.change.edit.Selector;
import io.kcg.sir.change.edit.SourceEditor;
import io.kcg.sir.semantic.schema.StructValue;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A rename gives something a new name and every place that refers to it too: references, types, expressions, the view
 * and patch fields named after an entity field, and the arguments of a use. Whatever is only spelled the same is left.
 */
class RenameTest {
    private static final SourceId ENTRY = SourceId.of("project.sir");
    private static final String SOURCE = LanguageReferenceExamplesTest.SHELL_HEAD + """
            enum Level {
              LOW,
              HIGH
            }
            entity User persistent {
              identity id: Id generated auto;
              field name: Name;
            }
            entity Note persistent {
              identity id: Id generated auto;
              field author: Ref<User>;
              field body: Text;
              field level: Level default Level.LOW;
              field version: Version;
            }
            extend Note {
              field tag: Optional<Text>;
            }
            entity Post persistent {
              identity id: Id generated auto;
              field writer: Ref<User>;
              field text: Text;
            }
            view NoteView from Note {
              field id: Id;
              field body: Text;
              field level: Level;
            }
            input NoteRef {
              field id: Id;
            }
            input EditNote patch of Note {
              field id: Id;
              field body: Text;
            }
            input Search {
              field body: Text;
              field page: PageNo;
              field size: PageSize;
            }
            error BadPage;
            error NoteNotFound http 404;
            error Stale http 409;
            capability ShowNote {
              input NoteRef;
              output NoteView;
              fails NoteNotFound;
              requires readonly;
              expose query;
              workflow {
                load Note by input.id as note else NoteNotFound;
                return note;
              }
            }
            capability EditNoteBody {
              input EditNote;
              output NoteView;
              fails NoteNotFound;
              fails Stale;
              requires atomic;
              expose command;
              workflow {
                validate input.body.present else NoteNotFound;
                load Note by input.id as note else NoteNotFound;
                update note {
                  body: input.body;
                }
                persist note else Stale;
                return note;
              }
            }
            capability FindNotes {
              input Search;
              output Page<NoteView>;
              fails BadPage;
              requires readonly;
              expose query;
              workflow {
                find Note where item.body containsLiteral input.body and item.level == Level.HIGH order by body ascending Page input.page, input.size else BadPage as rows;
                return rows;
              }
            }
            capability PostNote {
              actor Ref<User>;
              input Search;
              output NoteView;
              requires authenticated;
              requires atomic;
              expose command;
              workflow {
                create Note as note {
                  author: actor;
                  body: input.body;
                }
                persist note;
                return note;
              }
            }
            use Crud {
              entity: Post;
              owner: writer;
            }
            """ + LanguageReferenceExamplesTest.SHELL_TAIL;

    @Test void renamingAnEntityRenamesEveryReferenceToIt() {
        String text = renamed(new Command.Rename(Selector.root().named("declarations", "Note"), "Memo"));
        assertFalse(Pattern.compile("\\bNote\\b").matcher(text).find(), text);
        for (String written : List.of("entity Memo persistent", "author: Ref<User>", "extend Memo {", "view NoteView from Memo",
                "input EditNote patch of Memo", "load Memo by input.id", "find Memo where", "create Memo as note")) {
            assertTrue(contains(text, written), written + "\n" + text);
        }
    }

    @Test void renamingAnEntityFieldRenamesItsUsesAndTheFieldsNamedAfterIt() {
        String text = renamed(new Command.Rename(Selector.root().named("declarations", "Note").named("fields", "body"), "content"));
        // The entity's field, the view and patch fields named after it, the expressions and bindings that read or write it.
        for (String written : List.of("field content: Text; field level", "validate input.content.present", "content: input.content;",
                "item.content containsLiteral input.body", "order by content ascending", "content: input.body;")) {
            assertTrue(contains(text, written), written + "\n" + text);
        }
        // The search input's own field is only spelled the same: it stays.
        assertTrue(contains(text, "input Search { field body: Text;"), text);
    }

    @Test void renamingAnEnumMemberAnInputAndAnErrorRenamesTheirUses() {
        String text = renamed(
            new Command.Rename(Selector.root().named("declarations", "Level").named("members", "LOW"), "MINOR"),
            new Command.Rename(Selector.root().named("declarations", "NoteRef"), "NoteKey"),
            new Command.Rename(Selector.root().named("declarations", "NoteNotFound"), "Missing"));
        for (String written : List.of("default Level.MINOR", "Level.HIGH", "input NoteKey {", "input NoteKey;", "fails Missing;", "else Missing;")) {
            assertTrue(text.contains(written), written + "\n" + text);
        }
        assertFalse(text.contains("NoteNotFound"), text);
    }

    @Test void aUseFollowsTheEntityAndTheFieldItNames() {
        String text = renamed(
            new Command.Rename(Selector.root().named("declarations", "Post"), "Article"),
            new Command.Rename(Selector.root().named("declarations", "Post").named("fields", "writer"), "byline"));
        assertTrue(contains(text, "use Crud { entity: Article; owner: byline; }"), text);
    }

    @Test void theOtherCommandsOfTheBatchSeeTheNewNames() {
        String text = renamed(
            new Command.Update(Selector.root().named("declarations", "Missing").slot("httpStatus"), new StructValue.Text("409")),
            new Command.Rename(Selector.root().named("declarations", "NoteNotFound"), "Missing"));
        assertTrue(text.contains("error Missing http 409;"), text);
    }

    @Test void whatCannotBeRenamedIsRefused() {
        for (Command rename : List.of(
                new Command.Rename(Selector.root().named("declarations", "PostView"), "Shown"),
                new Command.Rename(Selector.root().named("declarations", "Note"), "1st"),
                new Command.Rename(Selector.root().named("declarations", "Nothing"), "Something"))) {
            var result = edit(List.of(rename));
            assertTrue(result instanceof EditResult.Refused, result::toString);
        }
        var expanded = (EditResult.Refused) edit(List.of(new Command.Rename(Selector.root().named("declarations", "PostView"), "Shown")));
        assertTrue(expanded.message().contains("expanded from a use"), expanded.message());
    }

    /** Whether the text contains the words, however the writer lays them out. */
    private static boolean contains(String text, String words) {
        return text.replaceAll("\\s+", " ").contains(words);
    }

    private static String renamed(Command... commands) {
        var result = edit(List.of(commands));
        assertTrue(result instanceof EditResult.Edited, result::toString);
        return new String(((EditResult.Edited) result).sources().bytes(ENTRY), StandardCharsets.UTF_8);
    }

    private static EditResult edit(List<Command> commands) {
        var snapshot = new SourceSnapshot(ENTRY, Map.of(ENTRY, SOURCE.getBytes(StandardCharsets.UTF_8)));
        return new SourceEditor().edit(snapshot, new CommandBatch(CommandBatch.PROTOCOL, "Reference", SourceEditor.sha256(snapshot), commands));
    }
}
