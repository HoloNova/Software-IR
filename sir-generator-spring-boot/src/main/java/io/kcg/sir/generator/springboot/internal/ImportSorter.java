package io.kcg.sir.generator.springboot.internal;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

final class ImportSorter {
   private final LinkedHashSet<String> imports = new LinkedHashSet<>();

   void add(String fullyQualifiedType) {
      Objects.requireNonNull(fullyQualifiedType, "fullyQualifiedType");
      if (!fullyQualifiedType.isBlank()) {
         if (fullyQualifiedType.indexOf(46) >= 0) {
            this.imports.add(fullyQualifiedType);
         }
      }
   }

   boolean isEmpty() {
      return this.imports.isEmpty();
   }

   String render() {
      if (this.imports.isEmpty()) {
         return "";
      }

      Set<String> sorted = new TreeSet<>((a, b) -> {
         int comparison = a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT));
         return comparison != 0 ? comparison : a.compareTo(b);
      });
      sorted.addAll(this.imports);
      StringBuilder out = new StringBuilder();
      boolean first = true;

      for (String entry : sorted) {
         if (!first) {
            out.append('\n');
         }

         out.append("import ").append(entry).append(';');
         first = false;
      }

      return out.toString();
   }
}
