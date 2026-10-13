package io.kcg.sir.semantic.schema;

import io.kcg.sir.semantic.schema.SlotSpec.Cardinality;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks that a draft value has the <em>shape</em> the schema allows, and nothing more.
 *
 * <p>Shape means: the node kind exists and is allowed in this slot, every authored slot is present
 * exactly once, scalars parse as their declared type, enum constants exist, references are plain
 * names. Whether the software is <em>meaningful</em> (names resolve, types agree, rules hold) is the
 * formal analysis' job and runs on the written source, so it is never repeated here.
 */
public final class DraftValidator {
   private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
   private static final Set<SlotKind> AUTHORED = Set.of(SlotKind.FACT, SlotKind.REFERENCE);

   private final SoftwareSchema schema;

   public DraftValidator(SoftwareSchema schema) {
      this.schema = schema;
   }

   /** Problems with {@code node} as a value of {@code expected}; empty when it is well formed. */
   public List<String> validateNode(StructNode node, Class<?> expected) {
      List<String> problems = new ArrayList<>();
      this.node(node, expected, node.kind(), problems);
      return problems;
   }

   /** Problems with {@code value} in the slot described by {@code spec}. */
   public List<String> validateSlot(SlotSpec spec, StructValue value) {
      List<String> problems = new ArrayList<>();
      this.slotValue(spec, value, spec.key(), problems);
      return problems;
   }

   /** Problems with {@code value} as <em>one element</em> of the (possibly collection) slot {@code spec}. */
   public List<String> validateElement(SlotSpec spec, StructValue value) {
      List<String> problems = new ArrayList<>();
      this.one(spec, value, spec.key(), problems);
      return problems;
   }

   private void node(StructNode node, Class<?> expected, String where, List<String> problems) {
      Optional<NodeType> found = this.schema.byKind(node.kind());
      if (found.isEmpty()) {
         problems.add(where + ": unknown node kind '" + node.kind() + "'");
         return;
      }

      NodeType type = found.get();
      if (!this.allowed(expected, type.type())) {
         problems.add(where + ": a " + type.kind() + " is not allowed where " + expected.getSimpleName() + " is expected");
         return;
      }

      if (type.type().isEnum()) {
         this.enumConstant(type, node, where, problems);
         return;
      }

      Set<String> known = new HashSet<>();
      for (SlotSpec spec : type.slots()) {
         known.add(spec.key());
      }

      Set<String> seen = new HashSet<>();
      for (StructNode.Slot slot : node.slots()) {
         if (!seen.add(slot.key())) {
            problems.add(where + ": slot '" + slot.key() + "' appears twice");
         } else if (!known.contains(slot.key())) {
            problems.add(where + ": " + type.kind() + " has no slot '" + slot.key() + "'");
         }
      }

      for (SlotSpec spec : type.slots()) {
         Optional<StructNode.Slot> given = node.find(spec.key());
         if (spec.kind() == SlotKind.SOURCE || spec.kind() == SlotKind.DERIVED) {
            if (given.isPresent()) {
               problems.add(where + "." + spec.key() + ": " + spec.kind() + " slots are never written by a command");
            }

            continue;
         }

         if (given.isEmpty()) {
            if (AUTHORED.contains(spec.kind())) {
               problems.add(where + "." + spec.key() + ": missing " + spec.kind() + " slot");
            }

            continue;
         }

         if (given.get().kind() != spec.kind()) {
            problems.add(where + "." + spec.key() + ": slot role is " + given.get().kind() + " but the schema says " + spec.kind());
            continue;
         }

         this.slotValue(spec, given.get().value(), where + "." + spec.key(), problems);
      }
   }

   /** A sealed variant that is an enum (a primitive type) is written as its constant name. */
   private void enumConstant(NodeType type, StructNode node, String where, List<String> problems) {
      Optional<StructNode.Slot> constant = node.find("constant");
      if (node.slots().size() != 1 || constant.isEmpty() || !(constant.get().value() instanceof StructValue.Text text)) {
         problems.add(where + ": " + type.kind() + " is written as one 'constant' value");
         return;
      }

      boolean known = java.util.Arrays.stream(type.type().getEnumConstants()).anyMatch(c -> ((Enum<?>)c).name().equals(text.value()));
      if (!known) {
         problems.add(where + ": '" + text.value() + "' is not a " + type.kind() + " constant");
      }
   }

   private void slotValue(SlotSpec spec, StructValue value, String where, List<String> problems) {
      switch (spec.cardinality()) {
         case ONE -> this.one(spec, value, where, problems);
         case OPTIONAL -> {
            if (!(value instanceof StructValue.None)) {
               this.one(spec, value, where, problems);
            }
         }
         case MANY -> {
            if (value instanceof StructValue.Seq seq) {
               int index = 0;
               for (StructValue element : seq.elements()) {
                  this.one(spec, element, where + "[" + index++ + "]", problems);
               }
            } else {
               problems.add(where + ": expected a sequence");
            }
         }
      }
   }

   private void one(SlotSpec spec, StructValue value, String where, List<String> problems) {
      Class<?> valueType = spec.valueType();
      if (SoftwareSchema.isNode(valueType)) {
         if (value instanceof StructValue.Child child) {
            this.node(child.node(), valueType, where, problems);
         } else {
            problems.add(where + ": expected a " + valueType.getSimpleName() + " node");
         }

         return;
      }

      if (!(value instanceof StructValue.Text text)) {
         problems.add(where + ": expected a single value");
         return;
      }

      this.scalar(spec, valueType, text.value(), where, problems);
   }

   private void scalar(SlotSpec spec, Class<?> type, String text, String where, List<String> problems) {
      try {
         if (spec.kind() == SlotKind.REFERENCE) {
            if (!NAME.matcher(text).matches()) {
               problems.add(where + ": a reference is a plain name, got '" + text + "'");
            }
         } else if (type == boolean.class || type == Boolean.class) {
            if (!text.equals("true") && !text.equals("false")) {
               problems.add(where + ": expected true or false, got '" + text + "'");
            }
         } else if (type == int.class || type == Integer.class) {
            Integer.parseInt(text);
         } else if (type == long.class || type == Long.class) {
            Long.parseLong(text);
         } else if (type == BigInteger.class) {
            new BigInteger(text);
         } else if (type == BigDecimal.class) {
            new BigDecimal(text);
         } else if (type.isEnum()) {
            if (java.util.Arrays.stream(type.getEnumConstants()).noneMatch(c -> ((Enum<?>)c).name().equals(text))) {
               problems.add(where + ": '" + text + "' is not one of " + java.util.Arrays.toString(type.getEnumConstants()));
            }
         } else if (type == SymbolId.class) {
            if (text.isBlank()) {
               problems.add(where + ": an identity must not be blank");
            }
         }
      } catch (NumberFormatException e) {
         problems.add(where + ": '" + text + "' is not a valid " + type.getSimpleName());
      }
   }

   private boolean allowed(Class<?> expected, Class<?> actual) {
      if (expected == actual) {
         return true;
      }

      List<Class<?>> variants = this.schema.unions().get(expected);
      if (variants == null) {
         return false;
      }

      return variants.stream().anyMatch(v -> this.allowed(v, actual));
   }
}
