package io.kcg.sir.semantic.schema;

import io.kcg.sir.ast.AstMetadata;
import io.kcg.sir.ast.AstTarget;
import io.kcg.sir.ast.AstTargetValue;
import java.util.Map;

/**
 * Classification of the few parser records that sit inside the normalized model.
 *
 * <p>Semantic cannot annotate classes owned by the parser module, and the parser must not know
 * what a "fact" is. The table lives here and goes through the same completeness check as an
 * annotation: a component of these records that is not listed makes the schema fail to build.
 */
final class ExternalSlots {
   private static final Map<Class<?>, Map<String, SlotKind>> TABLE = Map.of(
      AstMetadata.class,
      Map.of(
         "id", SlotKind.SOURCE,
         "span", SlotKind.SOURCE,
         "displayName", SlotKind.FACT,
         "displayNameSpan", SlotKind.SOURCE,
         "namespace", SlotKind.FACT,
         "namespaceSpan", SlotKind.SOURCE
      ),
      AstTarget.class,
      Map.of(
         "id", SlotKind.SOURCE,
         "span", SlotKind.SOURCE,
         "language", SlotKind.FACT,
         "languageVersion", SlotKind.FACT,
         "languageVersionSpan", SlotKind.SOURCE,
         "framework", SlotKind.FACT,
         "persistence", SlotKind.FACT,
         "database", SlotKind.FACT,
         "build", SlotKind.FACT,
         "interfaceKind", SlotKind.FACT
      ),
      AstTargetValue.class,
      Map.of("value", SlotKind.FACT, "span", SlotKind.SOURCE)
   );

   private ExternalSlots() {
   }

   static boolean covers(Class<?> type) {
      return TABLE.containsKey(type);
   }

   static SlotKind kindOf(Class<?> type, String component) {
      Map<String, SlotKind> slots = TABLE.get(type);
      return slots == null ? null : slots.get(component);
   }
}
