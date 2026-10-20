package io.kcg.sir.semantic.schema;

import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An editable, immutable copy of a software's authored structure.
 *
 * <p>A draft carries what the author wrote and nothing that analysis computed: no binding table,
 * no inferred types, no diagnostics. References are names, not resolved identities, because a node
 * added by an edit has no identity yet and because names are what the written source will contain.
 * Nodes taken from the baseline keep their identity so an edit can name them precisely; new nodes
 * have none.
 *
 * <p>A draft is not evidence that the software is valid. Only a fresh run of the formal analysis
 * on the source written from it is.
 */
public record SoftwareDraft(StructNode root) {
   public static final String DECLARATIONS = "declarations";

   public SoftwareDraft {
      Objects.requireNonNull(root, "root");
   }

   /** The authored structure of an analyzed model. */
   public static SoftwareDraft of(NormalizedSemanticModel model) {
      return new SoftwareDraft(StructureView.authored(model));
   }

   /** The authored structure with the things {@code renamed} names, by identity, referred to by their new names. */
   public static SoftwareDraft of(NormalizedSemanticModel model, java.util.function.Function<io.kcg.sir.semantic.symbol.SymbolId, Optional<String>> renamed) {
      return new SoftwareDraft(StructureView.authored(model, renamed));
   }

   /** The declaration nodes, in the order they are kept. */
   public List<StructNode> declarations() {
      return ((StructValue.Seq)this.root.slot(DECLARATIONS).value()).elements().stream().map(v -> ((StructValue.Child)v).node()).toList();
   }

   /** The declaration of this kind (for example {@code NormalizedEntity}) and name, if present. */
   public Optional<StructNode> declaration(String kind, String name) {
      return this.declarations().stream().filter(d -> d.kind().equals(kind) && nameOf(d).equals(name)).findFirst();
   }

   public static String nameOf(StructNode node) {
      return node.find("name").map(s -> ((StructValue.Text)s.value()).value()).orElse("");
   }

   /** The identity text of a baseline node; empty for a node an edit added. */
   public static Optional<String> identityOf(StructNode node) {
      return node.slots().stream().filter(s -> s.kind() == SlotKind.IDENTITY).findFirst().map(s -> ((StructValue.Text)s.value()).value());
   }

   public SoftwareDraft withRoot(StructNode changed) {
      return new SoftwareDraft(changed);
   }
}
