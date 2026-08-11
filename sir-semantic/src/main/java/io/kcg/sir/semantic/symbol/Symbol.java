package io.kcg.sir.semantic.symbol;

import io.kcg.sir.ast.AstDeclaration;
import io.kcg.sir.ast.AstErrorDecl;
import io.kcg.sir.ast.AstName;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.source.SourceSpan;

public sealed interface Symbol permits Symbol.TypeSymbol, Symbol.ErrorSymbol, Symbol.VariableSymbol, Symbol.EnumMemberSymbol, Symbol.FieldSymbol {
   SymbolId id();

   String name();

   SourceSpan declarationSpan();

   SymbolKind kind();

   record EnumMemberSymbol(SymbolId id, String name, SourceSpan declarationSpan, SymbolId enumId) implements Symbol {
      @Override
      public SymbolKind kind() {
         return SymbolKind.ENUM_MEMBER;
      }
   }

   record ErrorSymbol(SymbolId id, String name, SourceSpan declarationSpan, AstErrorDecl ast) implements Symbol {
      @Override
      public SymbolKind kind() {
         return SymbolKind.ERROR;
      }
   }

   record FieldSymbol(SymbolId id, String name, SourceSpan declarationSpan, SirType type, SymbolId ownerId) implements Symbol {
      @Override
      public SymbolKind kind() {
         return SymbolKind.FIELD;
      }
   }

   record TypeSymbol(SymbolId id, String name, SourceSpan declarationSpan, SymbolKind kind, AstDeclaration ast) implements Symbol {
   }

   record VariableSymbol(SymbolId id, String name, SourceSpan declarationSpan, SirType type, AstName declarationName) implements Symbol {
      @Override
      public SymbolKind kind() {
         return SymbolKind.VARIABLE;
      }
   }
}
