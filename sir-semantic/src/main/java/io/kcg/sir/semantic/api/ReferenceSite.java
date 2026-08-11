package io.kcg.sir.semantic.api;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record ReferenceSite(AstNodeId id, SourceSpan span, String text, ReferenceRole role) {
   public ReferenceSite {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(role, "role");
      if (text.isBlank()) {
         throw new IllegalArgumentException("text must not be blank");
      }
   }
}
