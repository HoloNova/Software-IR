package io.kcg.sir.semantic.api;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ReferenceSiteBindings {
   private static final ReferenceSiteBindings EMPTY = new ReferenceSiteBindings(List.of());
   private final List<ReferenceSiteBinding> bindings;
   private final Map<AstNodeId, ReferenceSiteBinding> bySiteId;

   private ReferenceSiteBindings(Collection<ReferenceSiteBinding> inputs) {
      Objects.requireNonNull(inputs, "inputs");
      Map<AstNodeId, ReferenceSiteBinding> map = new LinkedHashMap<>();

      for (ReferenceSiteBinding binding : inputs) {
         Objects.requireNonNull(binding, "binding");
         ReferenceSiteBinding previous = map.put(binding.site().id(), binding);
         if (previous != null) {
            throw new IllegalStateException(
               "duplicate ReferenceSite for AstNodeId "
                  + binding.site().id().value()
                  + " (first target: "
                  + previous.targetSymbol()
                  + ", second target: "
                  + binding.targetSymbol()
                  + ")"
            );
         }
      }

      List<ReferenceSiteBinding> sorted = new ArrayList<>(map.values());
      sorted.sort((a, b) -> a.site().id().value().compareTo(b.site().id().value()));
      this.bindings = List.copyOf(sorted);
      this.bySiteId = Collections.unmodifiableMap(new LinkedHashMap<>(map));
   }

   public static ReferenceSiteBindings empty() {
      return EMPTY;
   }

   public static ReferenceSiteBindings of(Collection<ReferenceSiteBinding> bindings) {
      Objects.requireNonNull(bindings, "bindings");
      return bindings.isEmpty() ? empty() : new ReferenceSiteBindings(bindings);
   }

   public List<ReferenceSiteBinding> all() {
      return this.bindings;
   }

   public Optional<ReferenceSiteBinding> bindingFor(AstNodeId siteId) {
      Objects.requireNonNull(siteId, "siteId");
      return Optional.ofNullable(this.bySiteId.get(siteId));
   }

   public Optional<SymbolId> targetFor(AstNodeId siteId) {
      return this.bindingFor(siteId).map(ReferenceSiteBinding::targetSymbol);
   }

   public boolean contains(AstNodeId siteId) {
      Objects.requireNonNull(siteId, "siteId");
      return this.bySiteId.containsKey(siteId);
   }

   public int size() {
      return this.bindings.size();
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else {
         return o instanceof ReferenceSiteBindings that ? this.bindings.equals(that.bindings) : false;
      }
   }

   @Override
   public int hashCode() {
      return this.bindings.hashCode();
   }

   @Override
   public String toString() {
      return "ReferenceSiteBindings" + this.bindings;
   }
}
