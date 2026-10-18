package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.ast.AstAnyExpression;
import io.kcg.sir.ast.AstBinaryExpression;
import io.kcg.sir.ast.AstBinding;
import io.kcg.sir.ast.AstBooleanLiteral;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstConstraint;
import io.kcg.sir.ast.AstCreateStep;
import io.kcg.sir.ast.AstDecimalLiteral;
import io.kcg.sir.ast.AstDeclaration;
import io.kcg.sir.ast.AstEntityDecl;
import io.kcg.sir.ast.AstEnumDecl;
import io.kcg.sir.ast.AstEnumMember;
import io.kcg.sir.ast.AstErrorDecl;
import io.kcg.sir.ast.AstExpression;
import io.kcg.sir.ast.AstFailsClause;
import io.kcg.sir.ast.AstField;
import io.kcg.sir.ast.AstFindOrderKey;
import io.kcg.sir.ast.AstFindPage;
import io.kcg.sir.ast.AstFindStep;
import io.kcg.sir.ast.AstGroupedExpression;
import io.kcg.sir.ast.AstIdentity;
import io.kcg.sir.ast.AstInputDecl;
import io.kcg.sir.ast.AstIntegerLiteral;
import io.kcg.sir.ast.AstLoadStep;
import io.kcg.sir.ast.AstMemberExpression;
import io.kcg.sir.ast.AstNameExpression;
import io.kcg.sir.ast.AstNamedTypeRef;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstNowExpression;
import io.kcg.sir.ast.AstPersistStep;
import io.kcg.sir.ast.AstPresentExpression;
import io.kcg.sir.ast.AstRequirementKind;
import io.kcg.sir.ast.AstRequiresClause;
import io.kcg.sir.ast.AstReturnStep;
import io.kcg.sir.ast.AstSoftware;
import io.kcg.sir.ast.AstStep;
import io.kcg.sir.ast.AstStringLiteral;
import io.kcg.sir.ast.AstTimeShiftExpression;
import io.kcg.sir.ast.AstUnaryExpression;
import io.kcg.sir.ast.AstUnitLiteral;
import io.kcg.sir.ast.AstUpdateStep;
import io.kcg.sir.ast.AstValidateStep;
import io.kcg.sir.ast.AstViewDecl;
import io.kcg.sir.ast.AstViewField;
import io.kcg.sir.ast.AstWorkflow;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.context.ValidatedContext;
import io.kcg.sir.semantic.model.NormalizedBinding;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedConstraint;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedCredential;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedEnum;
import io.kcg.sir.semantic.model.NormalizedError;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.model.NormalizedIdentity;
import io.kcg.sir.semantic.model.NormalizedInput;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.model.NormalizedView;
import io.kcg.sir.semantic.model.NormalizedViewField;
import io.kcg.sir.semantic.model.NormalizedWorkflow;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.DeclaredType;
import io.kcg.sir.semantic.type.ListType;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.source.SourceSpan;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class NormalizePass {
   private static final SymbolId UNKNOWN_SYMBOL_ID = new SymbolId("sir://unknown");
   private final AstSoftware software;
   private final String softwareName;
   private final ValidatedContext validated;
   private final List<io.kcg.sir.ast.AstFeatureUseDecl> uses;
   private final java.util.Map<AstNodeId, FeatureExpander.Expansion> expanded;

   private final io.kcg.sir.semantic.feature.SemanticTypes semanticTypes;
   private final List<io.kcg.sir.ast.AstSemanticTypeDecl> typeDeclarations;
   private final List<io.kcg.sir.ast.AstFeatureDefinitionDecl> featureDefinitions;
   private final List<io.kcg.sir.ast.AstEntityExtensionDecl> extensions;
   private final Set<AstNodeId> addedFields;

   NormalizePass(AstSoftware software, ValidatedContext validated) {
      this(software, validated, List.of(), java.util.Map.of(), io.kcg.sir.semantic.feature.SemanticTypes.builtin(), List.of(), List.of(), List.of(), Set.of());
   }

   NormalizePass(AstSoftware software, ValidatedContext validated, List<io.kcg.sir.ast.AstFeatureUseDecl> uses,
         java.util.Map<AstNodeId, FeatureExpander.Expansion> expanded, io.kcg.sir.semantic.feature.SemanticTypes semanticTypes,
         List<io.kcg.sir.ast.AstSemanticTypeDecl> typeDeclarations, List<io.kcg.sir.ast.AstFeatureDefinitionDecl> featureDefinitions,
         List<io.kcg.sir.ast.AstEntityExtensionDecl> extensions, Set<AstNodeId> addedFields) {
      this.featureDefinitions = List.copyOf(featureDefinitions);
      this.extensions = List.copyOf(extensions);
      this.addedFields = Set.copyOf(addedFields);
      this.semanticTypes = semanticTypes;
      this.typeDeclarations = List.copyOf(typeDeclarations);
      this.software = software;
      this.softwareName = software.name().text();
      this.validated = validated;
      this.uses = List.copyOf(uses);
      this.expanded = java.util.Map.copyOf(expanded);
   }

   SemanticAnalysis run() {
      List<Diagnostic> sortedDiagnostics = this.sortDiagnostics(this.validated.diagnostics());
      List<NormalizedDeclaration> declarations = new ArrayList<>();

      for (AstDeclaration decl : this.software.declarations()) {
         if (this.validated.declarationBindings().containsKey(decl.id())) {
            NormalizedDeclaration normalized = this.normalizeDeclaration(decl);
            if (normalized != null) {
               declarations.add(normalized);
            }
         }
      }

      // The use declarations themselves, after every declaration they expanded into, in name order like the others.
      java.util.Map<SymbolId, io.kcg.sir.semantic.api.FeatureOrigin> expansions = new java.util.LinkedHashMap<>();
      this.separateExtensions(declarations, expansions);
      for (io.kcg.sir.ast.AstFeatureUseDecl use : this.uses.stream().sorted(java.util.Comparator.comparing(u -> u.name().text())).toList()) {
         SymbolId feature = SymbolIdFactory.declaration(this.softwareName, "feature", use.name().text());
         declarations.add(new io.kcg.sir.semantic.model.NormalizedFeatureUse(feature, use.name().text(), use.span(), use.id(),
            use.arguments().stream().map(a -> new io.kcg.sir.semantic.model.NormalizedFeatureArgument(a.parameter().text(), a.value().text())).toList()));
      }
      for (io.kcg.sir.ast.AstFeatureDefinitionDecl definition : this.featureDefinitions.stream().sorted(java.util.Comparator.comparing(d -> d.name().text())).toList()) {
         declarations.add(new io.kcg.sir.semantic.model.NormalizedFeatureDefinition(
            SymbolIdFactory.declaration(this.softwareName, "feature-definition", definition.name().text()), definition.name().text(), definition.span(), definition.id(),
            definition.parameters().stream().map(p -> new io.kcg.sir.semantic.model.NormalizedFeatureParameter(p.name().text(), p.kind().text(),
               p.emptyDefault() ? java.util.Optional.of("") : p.defaultValue().map(io.kcg.sir.ast.AstFeatureArgument.Value::text))).toList(),
            definition.template()));
      }
      for (io.kcg.sir.ast.AstSemanticTypeDecl type : this.typeDeclarations.stream().sorted(java.util.Comparator.comparing(t -> t.name().text())).toList()) {
         declarations.add(new io.kcg.sir.semantic.model.NormalizedSemanticTypeDeclaration(
            SymbolIdFactory.declaration(this.softwareName, "type", type.name().text()), type.name().text(), type.span(), type.id(),
            type.base().text(), type.constraints().stream().map(this::normalizeConstraint).toList()));
      }
      for (AstDeclaration decl : this.software.declarations()) {
         FeatureExpander.Expansion expansion = this.expanded.get(decl.id());
         SymbolId id = this.validated.declarationBindings().get(decl.id());
         if (expansion != null && id != null) {
            expansions.put(id, new io.kcg.sir.semantic.api.FeatureOrigin(
               SymbolIdFactory.declaration(this.softwareName, "feature", expansion.use().name().text()), expansion.role()));
         }
      }

      if (sortedDiagnostics.stream().anyMatch(Diagnostic::isError)) {
         return new SemanticAnalysis.Failure(sortedDiagnostics);
      }

      NormalizedSemanticModel model = new NormalizedSemanticModel(
         this.softwareName,
         this.software.metadata(),
         this.software.target(),
         this.validated.symbols(),
         this.validated.referenceBindings(),
         this.validated.findItemBindings(),
         this.validated.expressionTypes(),
         List.copyOf(declarations),
         sortedDiagnostics,
         this.validated.referenceSiteBindings(),
         expansions
      );
      return new SemanticAnalysis.Success(model, sortedDiagnostics);
   }

   /**
    * Moves every field an extend declaration added out of its entity and into a declaration of its own, where the author
    * wrote it. Resolution and the checks saw the entity whole; the model shows each field in the declaration it is
    * written in, and {@code implementable()} puts them back together.
    */
   private void separateExtensions(List<NormalizedDeclaration> declarations, java.util.Map<SymbolId, io.kcg.sir.semantic.api.FeatureOrigin> expansions) {
      if (this.extensions.isEmpty()) {
         return;
      }
      java.util.Map<AstNodeId, NormalizedField> added = new java.util.HashMap<>();
      java.util.Map<String, SymbolId> entityIds = new java.util.HashMap<>();
      for (int i = 0; i < declarations.size(); i++) {
         if (declarations.get(i) instanceof NormalizedEntity entity) {
            entityIds.put(entity.name(), entity.id());
            List<NormalizedField> own = new ArrayList<>();
            for (NormalizedField field : entity.fields()) {
               if (this.addedFields.contains(field.sourceNodeId())) added.put(field.sourceNodeId(), field);
               else own.add(field);
            }
            declarations.set(i, new NormalizedEntity(entity.id(), entity.name(), entity.span(), entity.sourceNodeId(), entity.persistent(),
               entity.identity(), own, entity.versionField(), entity.credential(), entity.scope()));
         }
      }
      for (io.kcg.sir.ast.AstEntityExtensionDecl extension : this.extensions.stream()
            .sorted(java.util.Comparator.comparing((io.kcg.sir.ast.AstEntityExtensionDecl e) -> e.entity().text()).thenComparing(e -> e.span().source().value())).toList()) {
         SymbolId entity = entityIds.get(extension.entity().text());
         if (entity == null) {
            continue;
         }
         SymbolId id = SymbolIdFactory.declaration(this.softwareName, "extend", extension.entity().text() + "@" + extension.span().source().value());
         declarations.add(new io.kcg.sir.semantic.model.NormalizedEntityExtension(id, extension.entity().text(), extension.span(), extension.id(), entity,
            extension.fields().stream().map(f -> added.get(f.id())).filter(java.util.Objects::nonNull).toList()));
         FeatureExpander.Expansion expansion = this.expanded.get(extension.id());
         if (expansion != null) {
            expansions.put(id, new io.kcg.sir.semantic.api.FeatureOrigin(
               SymbolIdFactory.declaration(this.softwareName, "feature", expansion.use().name().text()), expansion.role()));
         }
      }
   }

   private List<Diagnostic> sortDiagnostics(List<Diagnostic> diagnostics) {
      List<Diagnostic> sorted = new ArrayList<>(diagnostics);
      sorted.sort((a, b) -> {
         int cmp = Integer.compare(a.primarySpan().start().codePointOffset(), b.primarySpan().start().codePointOffset());
         if (cmp != 0) {
            return cmp;
         }

         cmp = a.code().value().compareTo(b.code().value());
         return cmp != 0 ? cmp : a.message().compareTo(b.message());
      });
      return List.copyOf(new LinkedHashSet<>(sorted));
   }

   private NormalizedDeclaration normalizeDeclaration(AstDeclaration decl) {
      SymbolId id = this.validated.declarationBindings().get(decl.id());

      return switch (decl) {
         case AstEnumDecl e -> this.normalizeEnum(e, id);
         case AstEntityDecl e -> this.normalizeEntity(e, id);
         case AstInputDecl e -> this.normalizeInput(e, id);
         case AstViewDecl e -> this.normalizeView(e, id);
         case AstErrorDecl e -> this.normalizeError(e, id);
         case AstCapabilityDecl e -> this.normalizeCapability(e, id);
         default -> throw new MatchException(null, null);
      };
   }

   private NormalizedEnum normalizeEnum(AstEnumDecl decl, SymbolId id) {
      List<NormalizedEnum.NormalizedEnumMember> members = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();

      for (AstEnumMember m : decl.members()) {
         String memberName = m.name().text();
         if (seen.add(memberName)) {
            SymbolId memberId = SymbolIdFactory.enumMember(this.softwareName, decl.name().text(), memberName);
            members.add(new NormalizedEnum.NormalizedEnumMember(memberId, memberName, m.span(), m.id()));
         }
      }

      return new NormalizedEnum(id, decl.name().text(), decl.span(), decl.id(), List.copyOf(members));
   }

   private NormalizedEntity normalizeEntity(AstEntityDecl decl, SymbolId id) {
      NormalizedIdentity identity = this.normalizeIdentity(decl.identity(), decl.name().text());
      List<NormalizedField> fields = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();
      SymbolId versionFieldId = null;

      for (AstField f : decl.fields()) {
         String fieldName = f.name().text();
         if (seen.add(fieldName) && !fieldName.equals(decl.identity().name().text())) {
            NormalizedField field = this.normalizeField(f);
            if (field != null) {
               if (f.versioned()) {
                  field = field.withVersioned(true);
                  versionFieldId = field.id();
               }

               if (f.unique()) {
                  field = field.withUnique(true);
               }

               if (f.secret()) {
                  field = field.withSecret(true);
               }

               NormalizedExpression defaultValue = f.defaultValue().map(this::normalizeExpression).orElse(null);
               if (defaultValue != null) {
                  field = field.withDefault(defaultValue);
               }

               fields.add(field);
            }
         }
      }

      return new NormalizedEntity(
         id,
         decl.name().text(),
         decl.span(),
         decl.id(),
         true,
         identity,
         List.copyOf(fields),
         Optional.ofNullable(versionFieldId),
         decl.credential().map(clause -> new NormalizedCredential(
            clause.id(), clause.span(), this.siteTargetOrUnknown(clause.subject().id()), this.siteTargetOrUnknown(clause.expires().id()))),
         decl.scope().map(this::normalizeExpression)
      );
   }

   private NormalizedIdentity normalizeIdentity(AstIdentity identity, String entityName) {
      SymbolId identityId = SymbolIdFactory.entityIdentity(this.softwareName, entityName);
      SirType type = this.semantic(identity.type(), this.validated.typeRefTypes().get(identity.type().id()));
      return new NormalizedIdentity(
         identityId, identity.name().text(), identity.span(), identity.id(), type != null ? type : PrimitiveType.INT64, identity.generation()
      );
   }

   private NormalizedInput normalizeInput(AstInputDecl decl, SymbolId id) {
      List<NormalizedField> fields = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();
      boolean patch = decl.patchSourceEntity().isPresent();

      for (AstField f : decl.fields()) {
         String fieldName = f.name().text();
         if (seen.add(fieldName)) {
            NormalizedField field = this.normalizeField(f);
            if (field != null) {
               if (patch) {
                  field = field.withPatchSourceField(this.patchSourceFieldOrUnknown(decl.name().text(), fieldName));
               }

               fields.add(field);
            }
         }
      }

      SymbolId patchSourceEntity = patch ? this.siteTargetOrUnknown(decl.patchSourceEntity().orElseThrow().id()) : null;
      return new NormalizedInput(
         id,
         decl.name().text(),
         decl.span(),
         decl.id(),
         patch ? NormalizedInput.Kind.PATCH : NormalizedInput.Kind.PLAIN,
         Optional.ofNullable(patchSourceEntity),
         List.copyOf(fields)
      );
   }

   private NormalizedView normalizeView(AstViewDecl decl, SymbolId id) {
      List<NormalizedViewField> fields = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();

      for (AstViewField field : decl.fields()) {
         String fieldName = field.name().text();
         if (!seen.add(fieldName)) {
         continue;
         }

         SirType type = this.semantic(field.type(), this.validated.typeRefTypes().get(field.type().id()));
         if (type == null) {
         continue;
         }

         fields.add(new NormalizedViewField(
         SymbolIdFactory.viewField(this.softwareName, decl.name().text(), fieldName),
         fieldName,
         field.span(),
         field.id(),
         type,
         this.siteTargetOrUnknown(field.name().id()),
         this.relationOf(type),
         field.type() instanceof AstNamedTypeRef named ? Optional.of(named.name().id()) : Optional.empty()));
      }

      return new NormalizedView(
         id,
         decl.name().text(),
         decl.span(),
         decl.id(),
         this.siteTargetOrUnknown(decl.sourceEntity().id()),
         List.copyOf(fields));
   }

   private NormalizedError normalizeError(AstErrorDecl decl, SymbolId id) {
      int httpStatus = decl.httpStatus().map(java.math.BigInteger::intValue).orElse(NormalizedError.DEFAULT_HTTP_STATUS);
      return new NormalizedError(id, decl.name().text(), decl.span(), decl.id(), httpStatus);
   }

   /**
    * The declared type as the author named it: where the declaration says a semantic type, the model keeps that type
    * (the resolved type is only the kind of value behind it).
    */
   private SirType semantic(io.kcg.sir.ast.AstTypeRef ref, SirType resolved) {
      if (resolved == null) {
         return null;
      }

      return switch (ref) {
         case io.kcg.sir.ast.AstNamedTypeRef named when this.semanticTypes.named(named.name().text()).isPresent() ->
            this.semanticTypes.type(named.name().text());
         case io.kcg.sir.ast.AstOptionalTypeRef optional when resolved instanceof io.kcg.sir.semantic.type.OptionalType o ->
            new io.kcg.sir.semantic.type.OptionalType(this.semantic(optional.elementType(), o.element()));
         case io.kcg.sir.ast.AstListTypeRef list when resolved instanceof io.kcg.sir.semantic.type.ListType o ->
            new io.kcg.sir.semantic.type.ListType(this.semantic(list.elementType(), o.element()));
         case io.kcg.sir.ast.AstPageTypeRef page when resolved instanceof io.kcg.sir.semantic.type.PageType o ->
            new io.kcg.sir.semantic.type.PageType(this.semantic(page.elementType(), o.element()));
         default -> resolved;
      };
   }

   private NormalizedField normalizeField(AstField field) {
      String fieldName = field.name().text();
      SirType type = this.semantic(field.type(), this.validated.typeRefTypes().get(field.type().id()));
      if (type == null) {
         return null;
      }

      SymbolId fieldId = this.memberIdentity(field);
      List<NormalizedConstraint> constraints = new ArrayList<>();
      Set<String> seenConstraints = new LinkedHashSet<>();

      for (AstConstraint c : field.constraints()) {
         String cname = c.name().text();
         if (seenConstraints.add(cname)) {
            constraints.add(this.normalizeConstraint(c));
         }
      }

      Optional<AstNodeId> directNamedTypeReferenceSiteId = this.directNamedTypeSiteId(field);
      return new NormalizedField(fieldId, fieldName, field.span(), field.id(), type, List.copyOf(constraints), directNamedTypeReferenceSiteId);
   }

   /**
    * The identity the resolver assigned to a member.
    *
    * <p>Members never derive their own identity here: the resolver is the single place that knows
    * whether a member carries an explicit {@code @id}, so reading its binding is what keeps a
    * declared member's identity stable when its name changes.
    */
   private SymbolId memberIdentity(AstField field) {
      SymbolId bound = this.validated.referenceBindings().get(field.id());
      if (bound == null) {
         throw new IllegalStateException("member has no resolved identity: " + field.id().value());
      }

      return bound;
   }

   private Optional<AstNodeId> directNamedTypeSiteId(AstField field) {
      return field.type() instanceof AstNamedTypeRef named ? Optional.of(named.name().id()) : Optional.empty();
   }

   private NormalizedConstraint normalizeConstraint(AstConstraint constraint) {
      List<NormalizedExpression> args = new ArrayList<>();

      for (AstExpression arg : constraint.arguments()) {
         NormalizedExpression expr = this.normalizeExpression(arg);
         if (expr != null) {
            args.add(expr);
         }
      }

      return new NormalizedConstraint(constraint.id(), constraint.span(), constraint.name().text(), List.copyOf(args));
   }

   private NormalizedCapability normalizeCapability(AstCapabilityDecl decl, SymbolId id) {
      Optional<SymbolId> actorSymbol = decl.actor().map(c -> this.definitionBindingOrUnknown(c.id()));
      Optional<SymbolId> inputSymbol = decl.input().map(c -> this.definitionBindingOrUnknown(c.id()));
      SirType outputType = this.semantic(decl.output().type(), this.validated.typeRefTypes().get(decl.output().type().id()));
      List<SymbolId> fails = new ArrayList<>();
      Set<SymbolId> failsSeen = new LinkedHashSet<>();

      for (AstFailsClause f : decl.failures()) {
         SymbolId errorId = this.siteTargetOrUnknown(f.error().id());
         if (failsSeen.add(errorId)) {
            fails.add(errorId);
         }
      }

      fails.sort((a, b) -> a.value().compareTo(b.value()));
      List<AstRequirementKind> requires = new ArrayList<>();
      Set<AstRequirementKind> reqSeen = new LinkedHashSet<>();

      for (AstRequiresClause r : decl.requirements()) {
         if (reqSeen.add(r.requirement())) {
            requires.add(r.requirement());
         }
      }

      requires.sort((a, b) -> Integer.compare(a.ordinal(), b.ordinal()));
      NormalizedWorkflow workflow = this.normalizeWorkflow(decl.workflow(), id);
      return new NormalizedCapability(
         id,
         decl.name().text(),
         decl.span(),
         decl.id(),
         actorSymbol,
         inputSymbol,
         outputType != null ? outputType : PrimitiveType.UNIT,
         List.copyOf(fails),
         List.copyOf(requires),
         decl.exposure().exposure(),
         workflow
      );
   }

   private NormalizedWorkflow normalizeWorkflow(AstWorkflow workflow, SymbolId capabilityScope) {
      List<NormalizedStep> steps = new ArrayList<>();

      for (AstStep step : workflow.steps()) {
         NormalizedStep normalized = this.normalizeStep(step, capabilityScope);
         if (normalized != null) {
            steps.add(normalized);
         }
      }

      // The workflow shares the capability's own identity, so renaming the capability leaves it alone.
      return new NormalizedWorkflow(capabilityScope, workflow.span(), workflow.id(), List.copyOf(steps));
   }

   private NormalizedStep normalizeStep(AstStep step, SymbolId capabilityScope) {
      return switch (step) {
         case AstValidateStep s -> {
            SymbolId errorSym = this.siteTargetOrUnknown(s.error().id());
            NormalizedExpression condition = this.normalizeExpression(s.condition());
            yield new NormalizedStep.ValidateStep(s.id(), s.span(), condition != null ? condition : this.unitExpr(s.span()), errorSym);
         }
         case AstLoadStep s -> {
            SymbolId entitySym = this.siteTargetOrUnknown(s.entity().id());
            SymbolId resultVar = this.definitionBindingOrUnknown(s.id());
            SymbolId errorSym = this.siteTargetOrUnknown(s.error().id());
            NormalizedExpression idExpr = this.normalizeExpression(s.idExpression());
            Optional<SymbolId> keyField = s.keyField().map(key -> this.siteTargetOrUnknown(key.id()));
            yield new NormalizedStep.LoadStep(s.id(), s.span(), entitySym, idExpr != null ? idExpr : this.unitExpr(s.span()), keyField, resultVar, errorSym);
         }
         case AstFindStep s -> {
            SymbolId entitySym = this.siteTargetOrUnknown(s.entity().id());
            SymbolId resultVar = this.definitionBindingOrUnknown(s.id());
            SymbolId itemVar = this.validated.findItemBindings().getOrDefault(s.id(), resultVar);
            NormalizedExpression predicate = this.normalizeExpression(s.predicate());
         List<NormalizedStep.OrderKey> orderKeys = s.order()
               .map(order -> order.keys().stream().map(this::normalizeOrderKey).toList())
               .orElseGet(List::of);
         Optional<NormalizedStep.PageSpec> page = s.page().map(this::normalizePageSpec);
         yield new NormalizedStep.FindStep(
               s.id(),
               s.span(),
               entitySym,
               predicate != null ? predicate : this.unitExpr(s.span()),
               orderKeys,
               page,
               resultVar,
               itemVar);
         }
         case AstCreateStep s -> {
            SymbolId entitySym = this.siteTargetOrUnknown(s.entity().id());
            SymbolId resultVar = this.definitionBindingOrUnknown(s.id());
            List<NormalizedBinding> bindings = this.normalizeBindings(s.bindings());
            yield new NormalizedStep.CreateStep(s.id(), s.span(), entitySym, resultVar, bindings);
         }
         case AstUpdateStep s -> {
            SymbolId targetVar = this.siteTargetOrUnknown(s.target().id());
            List<NormalizedBinding> bindings = this.normalizeBindings(s.bindings());
            Optional<NormalizedExpression> guard = s.guard().map(this::normalizeExpression);
            Optional<SymbolId> guardError = s.guardError().flatMap(ref -> this.validated.referenceSiteBindings().targetFor(ref.id()));
            yield new NormalizedStep.UpdateStep(s.id(), s.span(), targetVar, guard, guardError, bindings);
         }
         case AstPersistStep s -> {
            SymbolId targetVar = this.siteTargetOrUnknown(s.target().id());
            Optional<SymbolId> failure = s.failure().flatMap(ref -> this.validated.referenceSiteBindings().targetFor(ref.id()));
            yield new NormalizedStep.PersistStep(s.id(), s.span(), targetVar, failure);
         }
         case io.kcg.sir.ast.AstDeleteStep s -> new NormalizedStep.DeleteStep(s.id(), s.span(), this.siteTargetOrUnknown(s.target().id()));
         case AstReturnStep s -> {
            NormalizedExpression value = this.normalizeExpression(s.value());
            yield new NormalizedStep.ReturnStep(s.id(), s.span(), value != null ? value : this.unitExpr(s.span()));
         }
         default -> throw new MatchException(null, null);
      };
   }

   private NormalizedStep.OrderKey normalizeOrderKey(AstFindOrderKey key) {
      return new NormalizedStep.OrderKey(key.id(), key.span(), this.siteTargetOrUnknown(key.field().id()), key.descending());
   }

   private NormalizedStep.PageSpec normalizePageSpec(AstFindPage page) {
      return new NormalizedStep.PageSpec(
         page.id(),
         page.span(),
         this.siteTargetOrUnknown(this.memberSiteId(page.page())),
         this.siteTargetOrUnknown(this.memberSiteId(page.size())),
         this.siteTargetOrUnknown(page.error().id()));
   }

   /**
   * The pagination sources are member accesses such as {@code input.page}; their bound reference
   * site is the member name, not the whole expression.
   */
   private AstNodeId memberSiteId(AstExpression expression) {
      AstExpression ungrouped = this.ungroup(expression);
      return ungrouped instanceof AstMemberExpression member ? member.member().id() : ungrouped.id();
   }

   private List<NormalizedBinding> normalizeBindings(List<AstBinding> bindings) {
      List<NormalizedBinding> result = new ArrayList<>();
      Set<SymbolId> seen = new LinkedHashSet<>();

      for (AstBinding b : bindings) {
         SymbolId fieldSym = this.siteTargetOrUnknown(b.fieldName().id());
         if (!isUnknown(fieldSym) && seen.add(fieldSym)) {
            NormalizedExpression value = this.normalizeExpression(b.value());
            if (value != null) {
               result.add(new NormalizedBinding(b.fieldName().text(), fieldSym, value));
            }
         }
      }

      return List.copyOf(result);
   }

   private NormalizedExpression normalizeExpression(AstExpression expr) {
      AstExpression ungrouped = this.ungroup(expr);

      return (NormalizedExpression)(switch (ungrouped) {
         case AstBooleanLiteral e -> new NormalizedExpression.BooleanLiteral(e.id(), e.span(), PrimitiveType.BOOLEAN, e.value());
         case AstIntegerLiteral e -> new NormalizedExpression.IntegerLiteral(e.id(), e.span(), this.typeOf(e.id(), PrimitiveType.INT32), e.value());
         case AstDecimalLiteral e -> new NormalizedExpression.DecimalLiteral(e.id(), e.span(), this.typeOf(e.id(), PrimitiveType.DECIMAL), e.value());
         case AstStringLiteral e -> new NormalizedExpression.StringLiteral(e.id(), e.span(), PrimitiveType.STRING, e.value());
         case AstUnitLiteral e -> new NormalizedExpression.UnitLiteral(e.id(), e.span(), PrimitiveType.UNIT);
         case AstNowExpression e -> new NormalizedExpression.NowExpression(e.id(), e.span(), PrimitiveType.DATE_TIME);
         case AstNameExpression e -> {
            SymbolId resolved = this.siteTargetOrUnknown(e.name().id());
            SirType type = this.typeOf(e.id(), null);
            yield !isUnknown(resolved) && type != null ? new NormalizedExpression.NameExpression(e.id(), e.span(), type, e.name().text(), resolved) : null;
         }
         case AstMemberExpression e -> {
            NormalizedExpression receiver = this.normalizeExpression(e.receiver());
            SymbolId resolvedMember = this.siteTargetOrUnknown(e.member().id());
            SirType type = this.typeOf(e.id(), null);
            yield receiver != null && !isUnknown(resolvedMember) && type != null
               ? new NormalizedExpression.MemberExpression(e.id(), e.span(), type, receiver, e.member().text(), resolvedMember)
               : null;
         }
         case AstUnaryExpression e -> {
            NormalizedExpression operand = this.normalizeExpression(e.operand());
            SirType type = this.typeOf(e.id(), null);
            yield operand != null && type != null ? new NormalizedExpression.UnaryExpression(e.id(), e.span(), type, e.operator(), operand) : null;
         }
         case AstBinaryExpression e -> {
            NormalizedExpression left = this.normalizeExpression(e.left());
            NormalizedExpression right = this.normalizeExpression(e.right());
            SirType type = this.typeOf(e.id(), null);
            yield left != null && right != null && type != null
               ? new NormalizedExpression.BinaryExpression(e.id(), e.span(), type, left, e.operator(), right)
               : null;
         }
         case AstGroupedExpression e -> this.normalizeExpression(e.inner());
         case AstTimeShiftExpression e -> {
            NormalizedExpression shifted = this.normalizeExpression(e.base());
            SirType type = this.typeOf(e.id(), null);
            yield shifted != null && type != null
               ? new NormalizedExpression.TimeShiftExpression(e.id(), e.span(), type, shifted, e.direction(), e.amount(), e.unit())
               : null;
         }
          case AstPresentExpression e -> {
             NormalizedExpression target = this.normalizeExpression(e.target());
             SirType type = this.typeOf(e.id(), PrimitiveType.BOOLEAN);
             SymbolId field = this.siteTargetOrUnknown(e.id());
             yield target != null && type != null && !isUnknown(field)
                ? new NormalizedExpression.PresentExpression(e.id(), e.span(), type, target, field)
                : null;
          }
          case AstAnyExpression e -> {
             NormalizedExpression conditions = this.normalizeExpression(e.conditions());
             SymbolId entity = this.siteTargetOrUnknown(e.entity().id());
             SymbolId connectionField = ExistenceConnection.fieldOf(
                e.conditions(), this.validated.referenceSiteBindings(), this.validated.findItemBindings());
             SirType type = this.typeOf(e.id(), PrimitiveType.BOOLEAN);
             yield conditions != null && !isUnknown(entity) && connectionField != null && type != null
                ? new NormalizedExpression.ExistsExpression(e.id(), e.span(), type, entity, connectionField, conditions)
                : null;
          }
         default -> null;
      });
   }

   /**
   * The relation a nested projection reads, or an empty value for a column projection.
   *
   * <p>The reference field itself is already the field's source; what this records is how many
   * related rows it yields and which view projects them, so a target renders the relation without
   * inspecting the declared type again.
   */
   private Optional<NormalizedViewField.Relation> relationOf(SirType type) {
      SirType element = type instanceof ListType list ? list.element() : type;
      if (!(element instanceof DeclaredType declared) || declared.kind() != DeclaredType.DeclaredKind.VIEW) {
         return Optional.empty();
      }

      return Optional.of(new NormalizedViewField.Relation(
         type instanceof ListType
            ? NormalizedViewField.Cardinality.TO_MANY
            : NormalizedViewField.Cardinality.TO_ONE,
         declared.symbolId()));
   }

   private AstExpression ungroup(AstExpression expr) {
      while (expr instanceof AstGroupedExpression) {
         AstGroupedExpression ge = (AstGroupedExpression)expr;
         expr = ge.inner();
      }

      return expr;
   }

   private SirType typeOf(AstNodeId id, SirType fallback) {
      SirType type = this.validated.expressionTypes().get(id);
      return type != null ? type : fallback;
   }

   private NormalizedExpression unitExpr(SourceSpan span) {
      return new NormalizedExpression.UnitLiteral(new AstNodeId("synthetic"), span, PrimitiveType.UNIT);
   }

   /**
   * The entity member a patch payload field applies to, or the unknown symbol when resolution
   * could not bind it (in which case a diagnostic already exists).
   */
   private SymbolId patchSourceFieldOrUnknown(String inputName, String fieldName) {
      return this.validated.patchFieldBindings().getOrDefault(
         SymbolIdFactory.inputField(this.softwareName, inputName, fieldName), UNKNOWN_SYMBOL_ID);
   }

   private SymbolId siteTargetOrUnknown(AstNodeId sourceNodeId) {
      return this.validated.referenceSiteBindings().targetFor(sourceNodeId).orElse(UNKNOWN_SYMBOL_ID);
   }

   private SymbolId definitionBindingOrUnknown(AstNodeId sourceNodeId) {
      return this.validated.referenceBindings().getOrDefault(sourceNodeId, UNKNOWN_SYMBOL_ID);
   }

   private static boolean isUnknown(SymbolId id) {
      return UNKNOWN_SYMBOL_ID.equals(id);
   }
}
