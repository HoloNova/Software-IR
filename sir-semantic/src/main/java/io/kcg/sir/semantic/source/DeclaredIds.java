package io.kcg.sir.semantic.source;

import io.kcg.sir.semantic.schema.SlotKind;
import io.kcg.sir.semantic.schema.StructNode;
import io.kcg.sir.semantic.schema.StructValue;
import io.kcg.sir.semantic.symbol.DeclarationIdentity;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Reads back the persistent id a declaration was written with.
 *
 * <p>An {@code @id("...")} annotation does not survive as text in the model; it survives as the
 * declaration's identity, {@code sir://<software>/declared/<kind>/<encoded id>}. Writing the
 * declaration again must write the same annotation, otherwise a rewritten capability would silently
 * become a different declaration. Identities derived from a name have no annotation to restore.
 */
final class DeclaredIds {
   private DeclaredIds() {
   }

   static Optional<String> of(StructNode node) {
      return node.slots()
         .stream()
         .filter(s -> s.kind() == SlotKind.IDENTITY)
         .map(s -> ((StructValue.Text)s.value()).value())
         .findFirst()
         .flatMap(DeclaredIds::declaredIdOf);
   }

   private static Optional<String> declaredIdOf(String identity) {
      String[] segments = identity.replaceFirst("^sir://", "").split("/", -1);
      if (segments.length != 4 || !segments[1].equals(DeclarationIdentity.NAMESPACE)) {
         return Optional.empty();
      }

      return Optional.of(decode(segments[3]));
   }

   private static String decode(String encoded) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      for (int i = 0; i < encoded.length(); i++) {
         char c = encoded.charAt(i);
         if (c == '%' && i + 2 < encoded.length()) {
            bytes.write(Integer.parseInt(encoded.substring(i + 1, i + 3), 16));
            i += 2;
         } else {
            bytes.write(c);
         }
      }

      return bytes.toString(StandardCharsets.UTF_8);
   }
}
