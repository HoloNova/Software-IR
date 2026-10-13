package io.kcg.sir.semantic.schema;

import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * A read-only, generic reading of a normalized model through {@link SoftwareSchema}.
 *
 * <p>The view contains only what the schema classifies as a fact, an identity or a reference, so two
 * models that differ only in positions or in recomputed analysis produce equal views. Source
 * positions can be added on request but are always kept in their own slots. {@code @Derived}
 * components never appear.
 *
 * <p>Collections marked {@link Keyed} are ordered by their elements' identity; every other
 * collection keeps its order, because there the order is part of the software.
 */
public final class StructureView {
   private static final Set<SlotKind> FACTS = EnumSet.of(SlotKind.FACT, SlotKind.IDENTITY, SlotKind.REFERENCE);
   private static final Set<SlotKind> WITH_SOURCE = EnumSet.of(SlotKind.FACT, SlotKind.IDENTITY, SlotKind.REFERENCE, SlotKind.SOURCE);

   private final SoftwareSchema schema;
   private final Set<SlotKind> include;
   private final BiFunction<SlotSpec, SymbolId, String> referenceText;

   private StructureView(SoftwareSchema schema, Set<SlotKind> include, BiFunction<SlotSpec, SymbolId, String> referenceText) {
      this.schema = schema;
      this.include = include;
      this.referenceText = referenceText;
   }

   /** Facts, identities and references of a normalized model. */
   public static StructNode facts(Object model) {
      return new StructureView(SoftwareSchema.normalizedModel(), FACTS, (spec, id) -> id.value()).node(model);
   }

   /** The same view plus the source slots, for comparing where things are written. */
   public static StructNode withSource(Object model) {
      return new StructureView(SoftwareSchema.normalizedModel(), WITH_SOURCE, (spec, id) -> id.value()).node(model);
   }

   /** A view of any model rooted in a schema built by {@link SoftwareSchema#of}; used by schema tests. */
   public static StructNode of(SoftwareSchema schema, Object model, Set<SlotKind> include) {
      Set<SlotKind> kinds = EnumSet.of(SlotKind.IDENTITY);
      kinds.addAll(include);
      kinds.remove(SlotKind.DERIVED);
      return new StructureView(schema, kinds, (spec, id) -> id.value()).node(model);
   }

   /**
    * The same facts, identities and references, but with every reference written as the
    * <em>name</em> the author wrote instead of the resolved identity.
    *
    * <p>This is the form a structural edit works on: a new node cannot know an identity before
    * Resolve has run, and a name is what the source text will contain when it is written back.
    */
   static StructNode authored(NormalizedSemanticModel model) {
      BiFunction<SlotSpec, SymbolId, String> nameOf = (spec, id) -> {
         Symbol symbol = model.symbols()
            .byId(id)
            .orElseThrow(() -> new IllegalStateException("a reference points at " + id.value() + ", which the symbol table does not know"));
         return spec.viaVariableType() ? writtenTypeName(symbol) : symbol.name();
      };
      return new StructureView(SoftwareSchema.normalizedModel(), FACTS, nameOf).node(model);
   }

   /** The type name the author wrote for a parameter variable such as {@code input} or {@code actor}. */
   private static String writtenTypeName(Symbol symbol) {
      if (symbol instanceof Symbol.VariableSymbol variable) {
         if (variable.type() instanceof io.kcg.sir.semantic.type.DeclaredType declared) {
            return declared.name();
         }

         if (variable.type() instanceof io.kcg.sir.semantic.type.RefType ref) {
            return ref.entityName();
         }
      }

      throw new IllegalStateException("the parameter " + symbol.name() + " has no written type name");
   }

   private StructNode node(Object value) {
      Class<?> type = value instanceof Enum<?> e ? e.getDeclaringClass() : value.getClass();
      NodeType nodeType = this.schema.nodes().get(type);
      if (nodeType == null) {
         throw new IllegalStateException("the model holds a " + type.getName() + ", which the software schema does not describe");
      }

      if (type.isEnum()) {
         return new StructNode(nodeType.kind(), List.of(new StructNode.Slot("constant", SlotKind.FACT, new StructValue.Text(((Enum<?>)value).name()))));
      }

      List<StructNode.Slot> slots = new ArrayList<>();
      RecordComponent[] components = type.getRecordComponents();
      for (int i = 0; i < components.length; i++) {
         SlotSpec spec = nodeType.slots().get(i);
         if (!this.include.contains(spec.kind())) {
            continue;
         }

         slots.add(new StructNode.Slot(spec.key(), spec.kind(), this.slotValue(spec, read(components[i], value))));
      }

      return new StructNode(nodeType.kind(), slots);
   }

   private StructValue slotValue(SlotSpec spec, Object raw) {
      return switch (spec.cardinality()) {
         case ONE -> this.single(spec, raw);
         case OPTIONAL -> ((Optional<?>)raw).<StructValue>map(v -> this.single(spec, v)).orElseGet(StructValue.None::new);
         case MANY -> this.sequence(spec, (List<?>)raw);
      };
   }

   private StructValue sequence(SlotSpec spec, List<?> raw) {
      List<StructValue> elements = new ArrayList<>();
      for (Object element : raw) {
         elements.add(this.single(spec, element));
      }

      if (spec.keyed()) {
         elements.sort(Comparator.comparing(StructureView::identityOf));
      }

      return new StructValue.Seq(elements);
   }

   private static String identityOf(StructValue element) {
      if (element instanceof StructValue.Text text) {
         return text.value();
      }

      if (element instanceof StructValue.Child child) {
         for (StructNode.Slot slot : child.node().slots()) {
            if (slot.kind() == SlotKind.IDENTITY && slot.value() instanceof StructValue.Text text) {
               return text.value();
            }
         }
      }

      throw new IllegalStateException("keyed element has no identity text");
   }

   private StructValue single(SlotSpec spec, Object value) {
      if (spec.kind() == SlotKind.SOURCE) {
         return new StructValue.Text(String.valueOf(value));
      }

      Class<?> type = value instanceof Enum<?> e ? e.getDeclaringClass() : value.getClass();
      if (this.schema.nodes().containsKey(type)) {
         return new StructValue.Child(this.node(value));
      }

      if (spec.kind() == SlotKind.REFERENCE && value instanceof SymbolId id) {
         return new StructValue.Text(this.referenceText.apply(spec, id));
      }

      return new StructValue.Text(scalar(value));
   }

   private static String scalar(Object value) {
      if (value instanceof SymbolId id) {
         return id.value();
      }

      if (value instanceof Enum<?> constant) {
         return constant.name();
      }

      if (value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
         return value.toString();
      }

      throw new IllegalStateException("the software schema has no text form for a " + value.getClass().getName());
   }

   private static Object read(RecordComponent component, Object record) {
      try {
         return component.getAccessor().invoke(record);
      } catch (IllegalAccessException | InvocationTargetException e) {
         throw new IllegalStateException("cannot read " + record.getClass().getName() + "." + component.getName(), e);
      }
   }
}
