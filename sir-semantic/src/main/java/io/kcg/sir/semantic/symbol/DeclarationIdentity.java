package io.kcg.sir.semantic.symbol;

import java.util.Objects;

/**
 * The explicit persistent-identity namespace of {@link SymbolId} values.
 *
 * <p>A declaration that writes {@code @id("...")} gets an identity of the form
 * {@code sir://<software>/declared/<kind>/<id>}. The {@code declared} segment is what separates it
 * from every name-derived identity ({@code sir://<software>/<kind>/<name>}): the name-derived form
 * puts a kind keyword in the second segment, and no kind keyword is {@code declared}, so an old
 * name-derived identity can never be read as a new explicit one. Declarations without {@code @id}
 * keep the old form untouched, which is why existing graphs and bundles stay byte-identical.
 */
public final class DeclarationIdentity {
   /** Second segment of every explicit declaration identity. */
   public static final String NAMESPACE = "declared";
   /** Kind segment used by explicit capability identities. */
   public static final String CAPABILITY_KIND = "capability";
   /** Kind segment used by explicit entity-field identities. */
   public static final String ENTITY_FIELD_KIND = "entity-field";

   private static final String SCHEME = "sir://";

   private DeclarationIdentity() {
   }

   /**
    * Whether this identity was declared in the source rather than derived from a name.
    *
    * <p>Only an explicit identity can prove that two differently named declarations are the same
    * declaration; a name-derived identity cannot, which is why change planning refuses to treat one
    * as the other.
    */
   public static boolean isDeclared(SymbolId id) {
      Objects.requireNonNull(id, "id");
      String value = id.value();
      if (!value.startsWith(SCHEME)) {
         return false;
      }

      int kindStart = value.indexOf('/', SCHEME.length());
      if (kindStart < 0) {
         return false;
      }

      int kindEnd = value.indexOf('/', kindStart + 1);
      return kindEnd >= 0 && value.substring(kindStart + 1, kindEnd).equals(NAMESPACE);
   }
}
