package io.kcg.sir.generator.springboot.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;
import java.util.Optional;

public record GeneratedFile(String relativePath, String content, LoweredNodeId artifactId, Optional<SymbolId> symbolId) {
   public GeneratedFile {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(content, "content");
      Objects.requireNonNull(artifactId, "artifactId");
      Objects.requireNonNull(symbolId, "symbolId");
      if (relativePath.isBlank()) {
         throw new IllegalArgumentException("relativePath must not be blank");
      }

      String validation = GeneratedFile.PathCheck.validate(relativePath);
      if (validation != null) {
         throw new IllegalArgumentException("relativePath invalid: " + validation);
      }

      symbolId = Optional.ofNullable(symbolId.orElse(null));
   }

   static final class PathCheck {
      private PathCheck() {
      }

      static String validate(String path) {
         if (path.startsWith("/")) {
            return "path must not be absolute";
         }

         if (path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0))) {
            return "path must not contain drive letter";
         }

         if (path.indexOf(92) >= 0) {
            return "path must not contain backslash";
         }

         String[] segments = path.split("/", -1);

         for (String segment : segments) {
            if (segment.isEmpty()) {
               return "path must not contain empty segment";
            }

            if ("..".equals(segment)) {
               return "path must not contain parent reference";
            }
         }

         return null;
      }
   }
}
