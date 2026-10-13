package io.kcg.sir.semantic.schema;

import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.schema.SlotSpec.Cardinality;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single, typed description of what the software model is made of.
 *
 * <p>The strongly typed model records stay the source of truth. This class reads the explicit
 * classification written on each record component ({@link Fact}, {@link Identity},
 * {@link Reference}, {@link Source}, {@link Derived}, {@link Keyed}) and checks it is complete, so a
 * consumer such as change comparison never keeps its own list of "fields that matter".
 *
 * <p>Nothing is guessed: a component without exactly one classification, a type the schema does
 * not know how to describe, or a sealed variant that cannot be reached makes {@link #of} throw with
 * every problem listed. Annotation reflection only <em>reads</em> what the model authors wrote.
 */
public final class SoftwareSchema {
   private static final Set<Class<?>> SCALARS = Set.of(
      String.class, boolean.class, Boolean.class, int.class, Integer.class, long.class, Long.class, BigInteger.class, BigDecimal.class, SymbolId.class
   );

   private static volatile SoftwareSchema normalized;

   private final NodeType root;
   private final Map<Class<?>, NodeType> nodes;
   private final Map<Class<?>, List<Class<?>>> unions;

   private SoftwareSchema(NodeType root, Map<Class<?>, NodeType> nodes, Map<Class<?>, List<Class<?>>> unions) {
      this.root = root;
      this.nodes = Collections.unmodifiableMap(nodes);
      this.unions = Collections.unmodifiableMap(unions);
   }

   /** The schema of {@link NormalizedSemanticModel}; built once and checked on first use. */
   public static SoftwareSchema normalizedModel() {
      SoftwareSchema current = normalized;
      if (current == null) {
         synchronized (SoftwareSchema.class) {
            current = normalized;
            if (current == null) {
               current = of(NormalizedSemanticModel.class);
               normalized = current;
            }
         }
      }

      return current;
   }

   /** Builds and checks the schema reachable from {@code rootType}; throws with all problems. */
   public static SoftwareSchema of(Class<?> rootType) {
      Builder builder = new Builder();
      builder.visit(rootType);
      builder.verifyKeys();
      if (!builder.problems.isEmpty()) {
         throw new SchemaViolation(List.copyOf(builder.problems));
      }

      return new SoftwareSchema(builder.nodes.get(rootType), builder.nodes, builder.unions);
   }

   public NodeType root() {
      return this.root;
   }

   /** Every record kind in the schema, in discovery order. */
   public Map<Class<?>, NodeType> nodes() {
      return this.nodes;
   }

   /** Every sealed interface and the variants it permits; an enum variant is a leaf. */
   public Map<Class<?>, List<Class<?>>> unions() {
      return this.unions;
   }

   /** The node kind with this simple name, if the schema has one. */
   public java.util.Optional<NodeType> byKind(String kind) {
      return this.nodes.values().stream().filter(n -> n.kind().equals(kind)).findFirst();
   }

   public NodeType node(Class<?> type) {
      NodeType found = this.nodes.get(type);
      if (found == null) {
         throw new IllegalArgumentException("not a schema node: " + type.getName());
      }

      return found;
   }

   /** Raised when the model classification is incomplete or ambiguous. */
   public static final class SchemaViolation extends IllegalStateException {
      private final List<String> problems;

      SchemaViolation(List<String> problems) {
         super("software schema is incomplete:\n - " + String.join("\n - ", problems));
         this.problems = problems;
      }

      public List<String> problems() {
         return this.problems;
      }
   }

   private static final class Builder {
      final Map<Class<?>, NodeType> nodes = new LinkedHashMap<>();
      final Map<Class<?>, List<Class<?>>> unions = new LinkedHashMap<>();
      final List<String> problems = new ArrayList<>();
      private final Set<Class<?>> visited = new LinkedHashSet<>();
      private final Map<String, Class<?>> kinds = new LinkedHashMap<>();
      private final Deque<Class<?>> pending = new ArrayDeque<>();

      void visit(Class<?> start) {
         this.pending.add(start);
         while (!this.pending.isEmpty()) {
            Class<?> type = this.pending.poll();
            if (!this.visited.add(type)) {
               continue;
            }

            if (type.isRecord()) {
               this.record(type);
            } else if (type.isSealed() && type.isInterface()) {
               List<Class<?>> permitted = List.of(type.getPermittedSubclasses());
               this.unions.put(type, permitted);
               permitted.forEach(this.pending::add);
            } else if (type.isEnum()) {
               this.enumLeaf(type);
            } else {
               this.problems.add(type.getName() + " is neither a record, a sealed interface nor an enum");
            }
         }
      }

      private void enumLeaf(Class<?> type) {
         // An enum is a plain scalar except when it is a variant of a sealed interface (PrimitiveType);
         // then it must still be a node so that the union stays closed.
         String kind = type.getSimpleName();
         this.claimKind(kind, type);
         this.nodes.put(type, new NodeType(type, kind, List.of(new SlotSpec("constant", SlotKind.FACT, false, Cardinality.ONE, String.class))));
      }

      private void claimKind(String kind, Class<?> type) {
         Class<?> previous = this.kinds.put(kind, type);
         if (previous != null && previous != type) {
            this.problems.add("kind '" + kind + "' is used by both " + previous.getName() + " and " + type.getName());
         }
      }

      private void record(Class<?> type) {
         String kind = type.getSimpleName();
         this.claimKind(kind, type);
         List<SlotSpec> slots = new ArrayList<>();
         for (RecordComponent component : type.getRecordComponents()) {
            String where = type.getName() + "." + component.getName();
            SlotKind slotKind = this.classify(type, component, where);
            if (slotKind == null) {
               continue;
            }

            boolean keyed = component.isAnnotationPresent(Keyed.class);
            Shape shape = shape(component.getGenericType());
            if (keyed && (shape.cardinality != Cardinality.MANY || !isNode(shape.valueType))) {
               this.problems.add(where + " is @Keyed but is not a collection of nodes");
            }

            boolean viaType = component.isAnnotationPresent(Reference.class) && component.getAnnotation(Reference.class).viaVariableType();
            slots.add(new SlotSpec(component.getName(), slotKind, keyed, shape.cardinality, shape.valueType, viaType));
            if (slotKind == SlotKind.DERIVED || slotKind == SlotKind.SOURCE) {
               continue;
            }

            this.checkValue(slotKind, shape.valueType, where);
         }

         this.nodes.put(type, new NodeType(type, kind, slots));
      }

      private SlotKind classify(Class<?> type, RecordComponent component, String where) {
         List<SlotKind> found = new ArrayList<>();
         if (component.isAnnotationPresent(Fact.class)) {
            found.add(SlotKind.FACT);
         }
         if (component.isAnnotationPresent(Identity.class)) {
            found.add(SlotKind.IDENTITY);
         }
         if (component.isAnnotationPresent(Reference.class)) {
            found.add(SlotKind.REFERENCE);
         }
         if (component.isAnnotationPresent(Source.class)) {
            found.add(SlotKind.SOURCE);
         }
         if (component.isAnnotationPresent(Derived.class)) {
            found.add(SlotKind.DERIVED);
         }

         SlotKind external = ExternalSlots.kindOf(type, component.getName());
         if (external != null) {
            found.add(external);
         }

         if (found.isEmpty()) {
            this.problems.add(where + " has no classification (@Fact, @Identity, @Reference, @Source or @Derived)");
            return null;
         }

         if (found.size() > 1) {
            this.problems.add(where + " has more than one classification: " + found);
            return null;
         }

         return found.get(0);
      }

      private void checkValue(SlotKind kind, Class<?> valueType, String where) {
         if ((kind == SlotKind.IDENTITY || kind == SlotKind.REFERENCE) && valueType != SymbolId.class) {
            this.problems.add(where + " is " + kind + " so it must hold a SymbolId, not " + valueType.getName());
            return;
         }

         if (isScalar(valueType)) {
            return;
         }

         if (isNode(valueType)) {
            this.pending.add(valueType);
            return;
         }

         this.problems.add(
            where + " has type " + valueType.getName() + ", which the schema cannot describe; make it a record, a sealed interface, an enum or a scalar, or mark it @Derived"
         );
      }

      void verifyKeys() {
         // A keyed collection is matched by element identity, so every possible element needs one.
         for (NodeType node : this.nodes.values()) {
            for (SlotSpec slot : node.slots()) {
               if (!slot.keyed()) {
                  continue;
               }

               for (Class<?> element : this.concreteTypes(slot.valueType())) {
                  NodeType elementNode = this.nodes.get(element);
                  boolean hasIdentity = elementNode != null && elementNode.slots().stream().anyMatch(s -> s.kind() == SlotKind.IDENTITY);
                  if (!hasIdentity) {
                     this.problems.add(node.type().getName() + "." + slot.key() + " is @Keyed but " + element.getName() + " has no @Identity slot");
                  }
               }
            }
         }
      }

      private List<Class<?>> concreteTypes(Class<?> type) {
         if (!type.isSealed() || !type.isInterface()) {
            return List.of(type);
         }

         List<Class<?>> result = new ArrayList<>();
         for (Class<?> permitted : type.getPermittedSubclasses()) {
            result.addAll(this.concreteTypes(permitted));
         }

         return result;
      }
   }

   private record Shape(Cardinality cardinality, Class<?> valueType) {
   }

   private static Shape shape(Type type) {
      if (type instanceof ParameterizedType parameterized) {
         Class<?> raw = (Class<?>)parameterized.getRawType();
         if (raw == Optional.class) {
            return new Shape(Cardinality.OPTIONAL, erase(parameterized.getActualTypeArguments()[0]));
         }

         if (raw == List.class) {
            return new Shape(Cardinality.MANY, erase(parameterized.getActualTypeArguments()[0]));
         }
      }

      return new Shape(Cardinality.ONE, erase(type));
   }

   private static Class<?> erase(Type type) {
      if (type instanceof Class<?> c) {
         return c;
      }

      if (type instanceof ParameterizedType p) {
         return (Class<?>)p.getRawType();
      }

      if (type instanceof TypeVariable<?>) {
         return Object.class;
      }

      return Object.class;
   }

   /** Whether a value of this type is rendered as one piece of text rather than as a node. */
   static boolean isScalar(Class<?> type) {
      return SCALARS.contains(type) || type == Object.class || (type.isEnum() && !isUnionVariant(type));
   }

   static boolean isNode(Class<?> type) {
      if (SCALARS.contains(type)) {
         return false;
      }

      return type.isRecord() || (type.isSealed() && type.isInterface()) || (type.isEnum() && isUnionVariant(type));
   }

   private static boolean isUnionVariant(Class<?> type) {
      for (Class<?> parent : type.getInterfaces()) {
         if (parent.isSealed()) {
            return true;
         }
      }

      return false;
   }
}
