package io.kcg.sir.generator.springboot.internal;

import java.util.Optional;

final class PathValidator {
   private PathValidator() {
   }

   static Optional<String> validate(String path) {
      if (path != null && !path.isBlank()) {
         if (path.startsWith("/")) {
            return Optional.of("path must not be absolute");
         }

         if (path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0))) {
            return Optional.of("path must not contain drive letter");
         }

         if (path.indexOf(92) >= 0) {
            return Optional.of("path must not contain backslash");
         }

         String[] segments = path.split("/", -1);

         for (String segment : segments) {
            if (segment.isEmpty()) {
               return Optional.of("path must not contain empty segment");
            }

            if ("..".equals(segment)) {
               return Optional.of("path must not contain parent reference");
            }
         }

         return Optional.empty();
      } else {
         return Optional.of("path must not be blank");
      }
   }
}
