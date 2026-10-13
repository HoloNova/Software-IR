package io.kcg.sir.change.edit;

import static io.kcg.sir.change.edit.DraftNodes.child;
import static io.kcg.sir.change.edit.DraftNodes.node;
import static io.kcg.sir.change.edit.DraftNodes.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.schema.Fact;
import io.kcg.sir.semantic.schema.Identity;
import io.kcg.sir.semantic.schema.Keyed;
import io.kcg.sir.semantic.schema.SlotKind;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.SoftwareSchema;
import io.kcg.sir.semantic.schema.StructNode;
import io.kcg.sir.semantic.schema.StructValue;
import io.kcg.sir.semantic.schema.StructureView;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The promise behind "the change layer never needs a feature added": a structure the editing code
 * has never seen is edited, compared and checked with the same three commands, because only the
 * schema knows what it is made of.
 *
 * <p>The structure below exists only in this test. Nothing in {@code io.kcg.sir.change.edit} mentions
 * a shelf or a book.
 */
class NewStructureProbeTest {
    record Book(@Identity SymbolId id, @Fact String name, @Fact int pages) {
    }

    record Shelf(@Fact String label, @Fact @Keyed List<Book> books, @Fact Optional<Book> featured, @Fact List<String> tags) {
    }

    private static final SoftwareSchema SCHEMA = SoftwareSchema.of(Shelf.class);
    private static final DraftEdit EDIT = new DraftEdit(SCHEMA);

    private static SoftwareDraft shelf() {
        Shelf shelf = new Shelf(
            "Reading",
            List.of(new Book(new SymbolId("b/1"), "Dune", 412), new Book(new SymbolId("b/2"), "Emma", 474)),
            Optional.empty(),
            List.of("novel")
        );
        return new SoftwareDraft(StructureView.of(SCHEMA, shelf, Set.of(SlotKind.FACT, SlotKind.IDENTITY)));
    }

    private static StructNode book(String name, int pages) {
        return node("Book", "name", SlotKind.FACT, text(name), "pages", SlotKind.FACT, text(String.valueOf(pages)));
    }

    @Test
    void add_update_delete_work_on_a_structure_the_editing_code_has_never_seen() {
        SoftwareDraft base = shelf();
        Selector books = Selector.root().slot("books");

        SoftwareDraft edited = assertInstanceOf(DraftEdit.Applied.class, EDIT.apply(base, List.of(
            new Command.Add(books, child(book("Ulysses", 730))),
            new Command.Update(Selector.root().named("books", "Dune").slot("pages"), text("500")),
            new Command.Delete(Selector.root().named("books", "Emma")),
            new Command.Add(Selector.root().slot("featured"), child(book("Ulysses", 730))),
            new Command.Add(Selector.root().slot("tags"), text("classic"))
        ))).draft();

        String rendered = edited.root().render();
        assertTrue(rendered.contains("name [FACT] = Ulysses"), rendered);
        assertTrue(rendered.contains("pages [FACT] = 500"), rendered);
        assertTrue(!rendered.contains("Emma"), rendered);
        assertTrue(rendered.contains("= classic"), rendered);
    }

    @Test
    void the_same_shape_rules_apply_without_anyone_writing_them_for_this_structure() {
        SoftwareDraft base = shelf();

        DraftEdit.Rejected badNumber = assertInstanceOf(DraftEdit.Rejected.class, EDIT.apply(base, List.of(
            new Command.Update(Selector.root().named("books", "Dune").slot("pages"), text("many"))
        )));
        DraftEdit.Rejected duplicate = assertInstanceOf(DraftEdit.Rejected.class, EDIT.apply(base, List.of(
            new Command.Add(Selector.root().slot("books"), child(book("Dune", 1)))
        )));
        DraftEdit.Rejected wrongKind = assertInstanceOf(DraftEdit.Rejected.class, EDIT.apply(base, List.of(
            new Command.Add(Selector.root().slot("books"), child(node("Shelf")))
        )));

        assertTrue(badNumber.reason().contains("not a valid"), badNumber.reason());
        assertTrue(duplicate.reason().contains("already has an element named 'Dune'"), duplicate.reason());
        assertTrue(wrongKind.reason().contains("not allowed where Book"), wrongKind.reason());
    }

    @Test
    void the_semantic_delta_describes_the_new_structure_too() {
        SoftwareDraft base = shelf();
        SoftwareDraft edited = assertInstanceOf(DraftEdit.Applied.class, EDIT.apply(base, List.of(
            new Command.Update(Selector.root().named("books", "Dune").slot("pages"), text("500")),
            new Command.Delete(Selector.root().named("books", "Emma")),
            new Command.Add(Selector.root().slot("tags"), text("classic"))
        ))).draft();

        SemanticDelta delta = SemanticDelta.between(base, edited, SCHEMA);

        assertEquals(3, delta.changes().size(), delta.toString());
        assertTrue(delta.changes().stream().anyMatch(c -> c.kind() == SemanticDelta.Kind.UPDATE && c.after().equals("500")));
        assertTrue(delta.changes().stream().anyMatch(c -> c.kind() == SemanticDelta.Kind.DELETE && c.before().contains("Emma")));
        assertTrue(delta.changes().stream().anyMatch(c -> c.kind() == SemanticDelta.Kind.ADD && c.after().equals("classic")));
    }

    @Test
    void a_field_added_to_the_structure_without_a_classification_stops_the_build_instead_of_being_ignored() {
        record Unclassified(@Fact String label, int forgotten) {
        }

        SoftwareSchema.SchemaViolation violation = org.junit.jupiter.api.Assertions.assertThrows(SoftwareSchema.SchemaViolation.class, () -> SoftwareSchema.of(Unclassified.class));

        assertTrue(violation.getMessage().contains("forgotten"), violation.getMessage());
    }
}
