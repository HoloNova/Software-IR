package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/**
 * An {@code error} declaration with the HTTP status it reports.
 *
 * <p>The status is resolved here, once: an error without an explicit status keeps
 * the default {@link #DEFAULT_HTTP_STATUS}, and the target profile is not allowed
 * to invent a different one later.
 */
public record NormalizedError(SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId, int httpStatus) implements NormalizedDeclaration {
   public static final int DEFAULT_HTTP_STATUS = 400;

   public NormalizedError {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
   }

   public NormalizedError(SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId) {
      this(id, name, span, sourceNodeId, DEFAULT_HTTP_STATUS);
   }
}
