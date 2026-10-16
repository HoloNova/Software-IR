package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.schema.Reference;
import io.kcg.sir.semantic.schema.Source;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/**
 * An entity whose rows are bearer credentials: the identity is the token, {@code subjectField} holds who
 * the token belongs to and {@code expiryField} the moment after which it is no longer valid.
 */
public record NormalizedCredential(
   @Source AstNodeId sourceNodeId, @Source SourceSpan span, @Reference SymbolId subjectField, @Reference SymbolId expiryField
) {
   public NormalizedCredential {
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(subjectField, "subjectField");
      Objects.requireNonNull(expiryField, "expiryField");
   }
}
