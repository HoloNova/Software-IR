package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record NormalizedEnum(SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId, List<NormalizedEnum.NormalizedEnumMember> members)
   implements NormalizedDeclaration {
   public NormalizedEnum {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      members = List.copyOf(Objects.requireNonNull(members, "members"));
   }

   public record NormalizedEnumMember(SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId) {
      public NormalizedEnumMember {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(name, "name");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      }
   }
}
