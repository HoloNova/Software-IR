package io.kcg.sir.semantic.api;

import io.kcg.sir.semantic.schema.Derived;
import io.kcg.sir.semantic.schema.Fact;
import io.kcg.sir.semantic.schema.Keyed;
import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.ast.AstMetadata;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstTarget;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolTable;
import io.kcg.sir.semantic.type.SirType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record NormalizedSemanticModel(
   @Fact String softwareName,
   @Fact AstMetadata metadata,
   @Fact AstTarget target,
   @Derived SymbolTable symbols,
   @Derived Map<AstNodeId, SymbolId> referenceBindings,
   @Derived Map<AstNodeId, SymbolId> findItemBindings,
   @Derived Map<AstNodeId, SirType> expressionTypes,
   @Fact @Keyed List<NormalizedDeclaration> declarations,
   @Derived List<Diagnostic> diagnostics,
   @Derived ReferenceSiteBindings referenceSiteBindings,
   @Derived Map<SymbolId, FeatureOrigin> expansions
) {
   public NormalizedSemanticModel {
      Objects.requireNonNull(softwareName, "softwareName");
      Objects.requireNonNull(metadata, "metadata");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(symbols, "symbols");
      referenceBindings = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(referenceBindings, "referenceBindings")));
      findItemBindings = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(findItemBindings, "findItemBindings")));
      expressionTypes = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(expressionTypes, "expressionTypes")));
      declarations = List.copyOf(Objects.requireNonNull(declarations, "declarations"));
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      Objects.requireNonNull(referenceSiteBindings, "referenceSiteBindings");
      expansions = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(expansions, "expansions")));
   }

   public NormalizedSemanticModel(
      String softwareName,
      AstMetadata metadata,
      AstTarget target,
      SymbolTable symbols,
      Map<AstNodeId, SymbolId> referenceBindings,
      Map<AstNodeId, SymbolId> findItemBindings,
      Map<AstNodeId, SirType> expressionTypes,
      List<NormalizedDeclaration> declarations,
      List<Diagnostic> diagnostics,
      ReferenceSiteBindings referenceSiteBindings
   ) {
      this(softwareName, metadata, target, symbols, referenceBindings, findItemBindings, expressionTypes, declarations, diagnostics,
         referenceSiteBindings, Map.of());
   }

   /** The {@code use} declaration a declaration was expanded from, or empty for a declaration the author wrote. */
   public java.util.Optional<FeatureOrigin> expandedFrom(SymbolId declaration) {
      return java.util.Optional.ofNullable(this.expansions.get(declaration));
   }

   /**
    * The same model without the declarations that only say how the software is written: {@code use} declarations
    * (a target implements the declarations a feature expanded into, which are already in the model), {@code feature}
    * definitions (what a use expands by) and {@code type} declarations (the fields that use a type carry its rules and
    * its kind of value). Every entity's {@code scope} is applied to the workflows here, so what a target implements
    * already filters out rows outside it. The fields {@code extend} declarations add are put back into their entities,
    * after the entity's own, and the extend declarations go.
    */
   public NormalizedSemanticModel implementable() {
      return new NormalizedSemanticModel(this.softwareName, this.metadata, this.target, this.symbols, this.referenceBindings,
         this.findItemBindings, this.expressionTypes,
         EntityScopes.apply(this.symbols, withExtensions(this.declarations).stream().filter(d -> !(d instanceof io.kcg.sir.semantic.model.NormalizedFeatureUse)
            && !(d instanceof io.kcg.sir.semantic.model.NormalizedSemanticTypeDeclaration)
            && !(d instanceof io.kcg.sir.semantic.model.NormalizedFeatureDefinition)).toList()),
         this.diagnostics, this.referenceSiteBindings, this.expansions);
   }

   private static List<NormalizedDeclaration> withExtensions(List<NormalizedDeclaration> declarations) {
      Map<SymbolId, List<io.kcg.sir.semantic.model.NormalizedField>> added = new LinkedHashMap<>();
      for (NormalizedDeclaration d : declarations) {
         if (d instanceof io.kcg.sir.semantic.model.NormalizedEntityExtension extension) {
            added.computeIfAbsent(extension.entity(), e -> new java.util.ArrayList<>()).addAll(extension.fields());
         }
      }
      return declarations.stream().<NormalizedDeclaration>mapMulti((d, out) -> {
         if (d instanceof io.kcg.sir.semantic.model.NormalizedEntityExtension) {
            return;
         }
         if (d instanceof io.kcg.sir.semantic.model.NormalizedEntity e && added.containsKey(e.id())) {
            List<io.kcg.sir.semantic.model.NormalizedField> fields = new java.util.ArrayList<>(e.fields());
            fields.addAll(added.get(e.id()));
            out.accept(new io.kcg.sir.semantic.model.NormalizedEntity(e.id(), e.name(), e.span(), e.sourceNodeId(), e.persistent(), e.identity(),
               fields, e.versionField(), e.credential(), e.scope()));
         } else {
            out.accept(d);
         }
      }).toList();
   }

   /** Whether the author wrote this declaration as source text (a {@code use} declaration counts; what it expands into does not). */
   public boolean isWritten(NormalizedDeclaration declaration) {
      return !this.expansions.containsKey(declaration.id());
   }

   public NormalizedSemanticModel(
      String softwareName,
      AstMetadata metadata,
      AstTarget target,
      SymbolTable symbols,
      Map<AstNodeId, SymbolId> referenceBindings,
      Map<AstNodeId, SymbolId> findItemBindings,
      Map<AstNodeId, SirType> expressionTypes,
      List<NormalizedDeclaration> declarations,
      List<Diagnostic> diagnostics
   ) {
      this(
         softwareName,
         metadata,
         target,
         symbols,
         referenceBindings,
         findItemBindings,
         expressionTypes,
         declarations,
         diagnostics,
         ReferenceSiteBindings.empty()
      );
   }
}
