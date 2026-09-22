package io.kcg.sir.semantic.symbol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Map.Entry;

public interface SymbolTable {
   List<Symbol> all();

   Optional<Symbol> byName(String var1);

   Optional<Symbol> byId(SymbolId var1);

   List<Symbol> symbolsInScope(SymbolId var1);

   Optional<SymbolId> parentScope(SymbolId var1);

   default Optional<Symbol> lookupInScope(String name, SymbolId scopeId) {
      SymbolId current = scopeId;

      while (current != null) {
         for (Symbol s : this.symbolsInScope(current)) {
            if (s.name().equals(name)) {
               return Optional.of(s);
            }
         }

         current = this.parentScope(current).orElse(null);
      }

      return this.byName(name);
   }

   static SymbolTable of(List<Symbol> symbols, Map<SymbolId, List<Symbol>> scopeSymbols, Map<SymbolId, SymbolId> scopeParents) {
      return new SymbolTable.ImmutableSymbolTable(symbols, scopeSymbols, scopeParents);
   }

   final class ImmutableSymbolTable implements SymbolTable {
      private final List<Symbol> symbols;
      private final Map<String, Symbol> byNameMap;
      private final Map<SymbolId, Symbol> byIdMap;
      private final Map<SymbolId, List<Symbol>> scopeSymbols;
      private final Map<SymbolId, SymbolId> scopeParents;

      private ImmutableSymbolTable(List<Symbol> symbols, Map<SymbolId, List<Symbol>> scopeSymbols, Map<SymbolId, SymbolId> scopeParents) {
         this.symbols = List.copyOf(Objects.requireNonNull(symbols, "symbols"));
         Map<String, Symbol> names = new LinkedHashMap<>();
         Map<SymbolId, Symbol> ids = new LinkedHashMap<>();

         for (Symbol s : this.symbols) {
            Symbol previous = ids.put(s.id(), s);
            if (previous != null) {
               throw new IllegalStateException("duplicate SymbolId in table: " + s.id() + " (first: " + previous.name() + ", second: " + s.name() + ")");
            }

            switch (s.kind()) {
               case PRIMITIVE:
               case ENUM:
               case ENTITY:
               case INPUT:
               case VIEW:
               case ERROR:
               case CAPABILITY:
                  names.put(s.name(), s);
            }
         }

         this.byNameMap = Collections.unmodifiableMap(new LinkedHashMap<>(names));
         this.byIdMap = Collections.unmodifiableMap(new LinkedHashMap<>(ids));
         Map<SymbolId, List<Symbol>> copiedScopes = new LinkedHashMap<>();

         for (Entry<SymbolId, List<Symbol>> entry : Objects.requireNonNull(scopeSymbols, "scopeSymbols").entrySet()) {
            copiedScopes.put(entry.getKey(), List.copyOf(entry.getValue()));
         }

         this.scopeSymbols = Collections.unmodifiableMap(new LinkedHashMap<>(copiedScopes));
         this.scopeParents = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(scopeParents, "scopeParents")));
      }

      @Override
      public List<Symbol> all() {
         return this.symbols;
      }

      @Override
      public Optional<Symbol> byName(String name) {
         return Optional.ofNullable(this.byNameMap.get(name));
      }

      @Override
      public Optional<Symbol> byId(SymbolId id) {
         return Optional.ofNullable(this.byIdMap.get(id));
      }

      @Override
      public List<Symbol> symbolsInScope(SymbolId scopeId) {
         return this.scopeSymbols.getOrDefault(scopeId, List.of());
      }

      @Override
      public Optional<SymbolId> parentScope(SymbolId scopeId) {
         return Optional.ofNullable(this.scopeParents.get(scopeId));
      }
   }
}
