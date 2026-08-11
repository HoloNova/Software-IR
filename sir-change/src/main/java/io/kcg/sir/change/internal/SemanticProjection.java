package io.kcg.sir.change.internal;

import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.ast.AstBuildTool;
import io.kcg.sir.ast.AstDatabase;
import io.kcg.sir.ast.AstExposureKind;
import io.kcg.sir.ast.AstFramework;
import io.kcg.sir.ast.AstGenerationStrategy;
import io.kcg.sir.ast.AstInterfaceKind;
import io.kcg.sir.ast.AstLanguage;
import io.kcg.sir.ast.AstMetadata;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstPersistence;
import io.kcg.sir.ast.AstRequirementKind;
import io.kcg.sir.ast.AstTarget;
import io.kcg.sir.ast.AstUnaryOperator;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.ReferenceRole;
import io.kcg.sir.semantic.api.ReferenceSite;
import io.kcg.sir.semantic.api.ReferenceSiteBinding;
import io.kcg.sir.semantic.api.ReferenceSiteBindings;
import io.kcg.sir.semantic.model.NormalizedBinding;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedEnum;
import io.kcg.sir.semantic.model.NormalizedError;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.model.NormalizedIdentity;
import io.kcg.sir.semantic.model.NormalizedInput;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.model.NormalizedWorkflow;
import io.kcg.sir.semantic.model.NormalizedExpression.BinaryExpression;
import io.kcg.sir.semantic.model.NormalizedExpression.BooleanLiteral;
import io.kcg.sir.semantic.model.NormalizedExpression.DecimalLiteral;
import io.kcg.sir.semantic.model.NormalizedExpression.IntegerLiteral;
import io.kcg.sir.semantic.model.NormalizedExpression.MemberExpression;
import io.kcg.sir.semantic.model.NormalizedExpression.NameExpression;
import io.kcg.sir.semantic.model.NormalizedExpression.NowExpression;
import io.kcg.sir.semantic.model.NormalizedExpression.StringLiteral;
import io.kcg.sir.semantic.model.NormalizedExpression.UnaryExpression;
import io.kcg.sir.semantic.model.NormalizedExpression.UnitLiteral;
import io.kcg.sir.semantic.model.NormalizedStep.CreateStep;
import io.kcg.sir.semantic.model.NormalizedStep.FindStep;
import io.kcg.sir.semantic.model.NormalizedStep.LoadStep;
import io.kcg.sir.semantic.model.NormalizedStep.PersistStep;
import io.kcg.sir.semantic.model.NormalizedStep.ReturnStep;
import io.kcg.sir.semantic.model.NormalizedStep.UpdateStep;
import io.kcg.sir.semantic.model.NormalizedStep.ValidateStep;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.semantic.symbol.Symbol.TypeSymbol;
import io.kcg.sir.semantic.type.DeclaredType;
import io.kcg.sir.semantic.type.ListType;
import io.kcg.sir.semantic.type.OptionalType;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.RefType;
import io.kcg.sir.semantic.type.SirType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class SemanticProjection {
   private SemanticProjection() {
   }

   static SemanticProjection.DeclarationProjection ofFullDeclaration(NormalizedDeclaration decl) {
      if (decl instanceof NormalizedEnum e) {
         return new SemanticProjection.DeclarationProjection.EnumP(
            e.id().value(),
            e.name(),
            e.members().stream().map(m -> new SemanticProjection.DeclarationProjection.EnumMemberP(m.id().value(), m.name())).toList()
         );
      } else if (decl instanceof NormalizedEntity en) {
         return new SemanticProjection.DeclarationProjection.EntityP(
            en.id().value(), en.name(), en.persistent(), identityP(en.identity()), en.fields().stream().map(SemanticProjection::fieldP).toList()
         );
      } else if (decl instanceof NormalizedInput i) {
         return new SemanticProjection.DeclarationProjection.InputP(i.id().value(), i.name(), i.fields().stream().map(SemanticProjection::fieldP).toList());
      } else if (decl instanceof NormalizedError er) {
         return new SemanticProjection.DeclarationProjection.ErrorP(er.id().value(), er.name());
      } else if (decl instanceof NormalizedCapability c) {
         return new SemanticProjection.DeclarationProjection.CapabilityP(
            c.id().value(),
            c.name(),
            c.actorSymbol().map(SymbolId::value),
            c.inputSymbol().map(SymbolId::value),
            typeP(c.outputType()),
            c.fails().stream().map(SymbolId::value).toList(),
            List.copyOf(c.requires()),
            c.exposure(),
            workflowP(c.workflow())
         );
      } else {
         throw new IllegalStateException("unsupported NormalizedDeclaration variant: " + decl.getClass());
      }
   }

   static SemanticProjection.CapabilityContractP ofCapabilityContract(NormalizedCapability cap) {
      return new SemanticProjection.CapabilityContractP(
         cap.id().value(),
         cap.name(),
         cap.actorSymbol().map(SymbolId::value),
         cap.inputSymbol().map(SymbolId::value),
         typeP(cap.outputType()),
         cap.fails().stream().map(SymbolId::value).toList(),
         List.copyOf(cap.requires()),
         cap.exposure(),
         cap.sourceNodeId().value(),
         cap.workflow().sourceNodeId().value()
      );
   }

   static SemanticProjection.CapabilityExceptExposureP ofCapabilityExceptExposure(NormalizedCapability cap) {
      return new SemanticProjection.CapabilityExceptExposureP(
         cap.id().value(),
         cap.name(),
         cap.actorSymbol().map(SymbolId::value),
         cap.inputSymbol().map(SymbolId::value),
         typeP(cap.outputType()),
         cap.fails().stream().map(SymbolId::value).toList(),
         List.copyOf(cap.requires()),
         cap.sourceNodeId().value(),
         cap.workflow().sourceNodeId().value()
      );
   }

   static SemanticProjection.WorkflowP ofWorkflowOnly(NormalizedWorkflow workflow) {
      return workflowP(workflow);
   }

   static SemanticProjection.MetadataP ofMetadata(AstMetadata metadata) {
      return new SemanticProjection.MetadataP(metadata.displayName(), metadata.namespace());
   }

   static SemanticProjection.TargetP ofTarget(AstTarget target) {
      return new SemanticProjection.TargetP(
         target.language().value(),
         target.languageVersion(),
         target.framework().value(),
         target.persistence().value(),
         target.database().value(),
         target.build().value(),
         target.interfaceKind().value()
      );
   }

   static SemanticProjection.FieldContractP ofFieldContract(NormalizedField field) {
      return new SemanticProjection.FieldContractP(field.id().value(), field.name(), field.sourceNodeId().value(), typeP(field.type()));
   }

   static SemanticProjection.ConstraintsOnlyP ofConstraintsOnly(NormalizedField field) {
      return new SemanticProjection.ConstraintsOnlyP(
         field.constraints()
            .stream()
            .map(c -> new SemanticProjection.ConstraintP(c.name(), c.arguments().stream().map(SemanticProjection::expressionP).toList()))
            .toList()
      );
   }

   static SemanticProjection.InputContractP ofInputContract(NormalizedInput input) {
      return new SemanticProjection.InputContractP(
         input.id().value(), input.name(), input.sourceNodeId().value(), input.fields().stream().map(SemanticProjection::ofFieldContract).toList()
      );
   }

   static SemanticProjection.FieldP ofNonTargetField(NormalizedField field) {
      return fieldP(field);
   }

   static SemanticProjection.TypedReferenceSiteProjection ofTypedReferenceSites(ReferenceSiteBindings bindings) {
      Objects.requireNonNull(bindings, "bindings");
      List<SemanticProjection.TypedReferenceSiteP> projected = new ArrayList<>();

      for (ReferenceSiteBinding b : bindings.all()) {
         ReferenceSite site = b.site();
         projected.add(new SemanticProjection.TypedReferenceSiteP(site.id().value(), site.role(), b.targetSymbol().value()));
      }

      projected.sort((a, bx) -> a.siteNodeId().compareTo(bx.siteNodeId()));
      return new SemanticProjection.TypedReferenceSiteProjection(List.copyOf(projected));
   }

   static SemanticProjection.FieldTypeSiteP ofFieldTypeSite(NormalizedField field, NormalizedSemanticModel model) {
      Objects.requireNonNull(field, "field");
      Objects.requireNonNull(model, "model");
      Optional<AstNodeId> siteId = field.directNamedTypeReferenceSiteId();
      if (siteId.isEmpty()) {
         return new SemanticProjection.FieldTypeSiteP(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
      }

      Optional<ReferenceSiteBinding> binding = model.referenceSiteBindings().bindingFor(siteId.get());
      if (binding.isEmpty()) {
         return new SemanticProjection.FieldTypeSiteP(siteId, Optional.empty(), Optional.empty(), Optional.empty());
      }

      ReferenceSite site = binding.get().site();
      SymbolId target = binding.get().targetSymbol();
      Optional<ReferenceRole> role = Optional.of(site.role());
      Optional<Symbol> symbol = model.symbols().byId(target);
      Optional<String> primitiveName = symbol.filter(s -> s instanceof TypeSymbol && s.kind() == SymbolKind.PRIMITIVE).map(Symbol::name);
      return new SemanticProjection.FieldTypeSiteP(siteId, Optional.of(target), role, primitiveName);
   }

   static SemanticProjection.TypedReferenceSiteProjectionV5 ofTypedReferenceSitesExcluding(ReferenceSiteBindings bindings, AstNodeId excludedSiteId) {
      Objects.requireNonNull(bindings, "bindings");
      Objects.requireNonNull(excludedSiteId, "excludedSiteId");
      List<SemanticProjection.TypedReferenceSitePV5> projected = new ArrayList<>();

      for (ReferenceSiteBinding b : bindings.all()) {
         ReferenceSite site = b.site();
         if (!site.id().equals(excludedSiteId)) {
            projected.add(new SemanticProjection.TypedReferenceSitePV5(site.id(), site.role(), b.targetSymbol()));
         }
      }

      projected.sort((a, bx) -> a.siteNodeId().value().compareTo(bx.siteNodeId().value()));
      return new SemanticProjection.TypedReferenceSiteProjectionV5(List.copyOf(projected));
   }

   private static SemanticProjection.IdentityP identityP(NormalizedIdentity identity) {
      return new SemanticProjection.IdentityP(identity.id().value(), identity.name(), typeP(identity.type()), identity.generation());
   }

   private static SemanticProjection.FieldP fieldP(NormalizedField f) {
      return new SemanticProjection.FieldP(
         f.id().value(),
         f.name(),
         typeP(f.type()),
         f.constraints()
            .stream()
            .map(c -> new SemanticProjection.ConstraintP(c.name(), c.arguments().stream().map(SemanticProjection::expressionP).toList()))
            .toList()
      );
   }

   private static SemanticProjection.WorkflowP workflowP(NormalizedWorkflow workflow) {
      return new SemanticProjection.WorkflowP(workflow.steps().stream().map(SemanticProjection::stepP).toList());
   }

   private static SemanticProjection.StepP stepP(NormalizedStep step) {
      if (step instanceof ValidateStep v) {
         return new SemanticProjection.StepP.ValidateP(v.errorSymbol().value(), expressionP(v.condition()));
      } else if (step instanceof LoadStep l) {
         return new SemanticProjection.StepP.LoadP(l.entitySymbol().value(), l.resultVariable().value(), l.errorSymbol().value(), expressionP(l.idExpression()));
      } else if (step instanceof FindStep f) {
         return new SemanticProjection.StepP.FindP(f.entitySymbol().value(), f.resultVariable().value(), f.itemVariable().value(), expressionP(f.predicate()));
      } else if (step instanceof CreateStep c) {
         return new SemanticProjection.StepP.CreateP(
            c.entitySymbol().value(), c.resultVariable().value(), c.bindings().stream().map(SemanticProjection::bindingP).toList()
         );
      } else if (step instanceof UpdateStep u) {
         return new SemanticProjection.StepP.UpdateP(u.targetVariable().value(), u.bindings().stream().map(SemanticProjection::bindingP).toList());
      } else if (step instanceof PersistStep p) {
         return new SemanticProjection.StepP.PersistP(p.targetVariable().value());
      } else if (step instanceof ReturnStep r) {
         return new SemanticProjection.StepP.ReturnP(expressionP(r.value()));
      } else {
         throw new IllegalStateException("unsupported NormalizedStep variant: " + step.getClass());
      }
   }

   private static SemanticProjection.BindingP bindingP(NormalizedBinding b) {
      return new SemanticProjection.BindingP(b.fieldName(), b.fieldSymbol().value(), expressionP(b.value()));
   }

   private static SemanticProjection.ExpressionP expressionP(NormalizedExpression expr) {
      if (expr instanceof BooleanLiteral b) {
         return new SemanticProjection.ExpressionP.BooleanLiteralP(b.value());
      } else if (expr instanceof IntegerLiteral i) {
         return new SemanticProjection.ExpressionP.IntegerLiteralP(i.value());
      } else if (expr instanceof DecimalLiteral d) {
         return new SemanticProjection.ExpressionP.DecimalLiteralP(d.value());
      } else if (expr instanceof StringLiteral s) {
         return new SemanticProjection.ExpressionP.StringLiteralP(s.value());
      } else if (expr instanceof UnitLiteral) {
         return new SemanticProjection.ExpressionP.UnitLiteralP();
      } else if (expr instanceof NowExpression) {
         return new SemanticProjection.ExpressionP.NowExpressionP();
      } else if (expr instanceof NameExpression n) {
         return new SemanticProjection.ExpressionP.NameExpressionP(n.name(), n.resolvedSymbol().value());
      } else if (expr instanceof MemberExpression m) {
         return new SemanticProjection.ExpressionP.MemberExpressionP(m.member(), m.resolvedMember().value(), expressionP(m.receiver()));
      } else if (expr instanceof UnaryExpression u) {
         return new SemanticProjection.ExpressionP.UnaryExpressionP(u.operator(), expressionP(u.operand()));
      } else if (expr instanceof BinaryExpression b) {
         return new SemanticProjection.ExpressionP.BinaryExpressionP(b.operator(), expressionP(b.left()), expressionP(b.right()));
      } else {
         throw new IllegalStateException("unsupported NormalizedExpression variant: " + expr.getClass());
      }
   }

   static SemanticProjection.ExpressionP expressionPForTest(NormalizedExpression expr) {
      return expressionP(expr);
   }

   private static SemanticProjection.TypeP typeP(SirType type) {
      if (type instanceof PrimitiveType p) {
         return new SemanticProjection.TypeP.PrimitiveP(p.name());
      } else if (type instanceof DeclaredType d) {
         return new SemanticProjection.TypeP.DeclaredP(d.kind().name(), d.symbolId().value());
      } else if (type instanceof RefType r) {
         return new SemanticProjection.TypeP.RefP(r.entityId().value());
      } else if (type instanceof OptionalType o) {
         return new SemanticProjection.TypeP.OptionalP(typeP(o.element()));
      } else if (type instanceof ListType l) {
         return new SemanticProjection.TypeP.ListP(typeP(l.element()));
      } else {
         throw new IllegalStateException("unsupported SirType variant: " + type.getClass());
      }
   }

   record BindingP(String fieldName, String fieldSymbol, SemanticProjection.ExpressionP value) {
      public BindingP {
         Objects.requireNonNull(fieldName, "fieldName");
         Objects.requireNonNull(fieldSymbol, "fieldSymbol");
         Objects.requireNonNull(value, "value");
      }
   }

   record CapabilityContractP(
      String symbolId,
      String name,
      Optional<String> actorSymbol,
      Optional<String> inputSymbol,
      SemanticProjection.TypeP outputType,
      List<String> fails,
      List<AstRequirementKind> requires,
      AstExposureKind exposure,
      String declarationNodeId,
      String workflowNodeId
   ) {
      public CapabilityContractP {
         Objects.requireNonNull(symbolId, "symbolId");
         Objects.requireNonNull(name, "name");
         actorSymbol = Objects.requireNonNull(actorSymbol, "actorSymbol");
         inputSymbol = Objects.requireNonNull(inputSymbol, "inputSymbol");
         Objects.requireNonNull(outputType, "outputType");
         fails = List.copyOf(Objects.requireNonNull(fails, "fails"));
         requires = List.copyOf(Objects.requireNonNull(requires, "requires"));
         Objects.requireNonNull(exposure, "exposure");
         Objects.requireNonNull(declarationNodeId, "declarationNodeId");
         Objects.requireNonNull(workflowNodeId, "workflowNodeId");
      }
   }

   record CapabilityExceptExposureP(
      String symbolId,
      String name,
      Optional<String> actorSymbol,
      Optional<String> inputSymbol,
      SemanticProjection.TypeP outputType,
      List<String> fails,
      List<AstRequirementKind> requires,
      String declarationNodeId,
      String workflowNodeId
   ) {
      public CapabilityExceptExposureP {
         Objects.requireNonNull(symbolId, "symbolId");
         Objects.requireNonNull(name, "name");
         actorSymbol = Objects.requireNonNull(actorSymbol, "actorSymbol");
         inputSymbol = Objects.requireNonNull(inputSymbol, "inputSymbol");
         Objects.requireNonNull(outputType, "outputType");
         fails = List.copyOf(Objects.requireNonNull(fails, "fails"));
         requires = List.copyOf(Objects.requireNonNull(requires, "requires"));
         Objects.requireNonNull(declarationNodeId, "declarationNodeId");
         Objects.requireNonNull(workflowNodeId, "workflowNodeId");
      }
   }

   record ConstraintP(String name, List<SemanticProjection.ExpressionP> arguments) {
      public ConstraintP {
         Objects.requireNonNull(name, "name");
         arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
      }
   }

   record ConstraintsOnlyP(List<SemanticProjection.ConstraintP> constraints) {
      public ConstraintsOnlyP {
         constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints"));
      }
   }

   sealed interface DeclarationProjection
      permits SemanticProjection.DeclarationProjection.EnumP,
      SemanticProjection.DeclarationProjection.EntityP,
      SemanticProjection.DeclarationProjection.InputP,
      SemanticProjection.DeclarationProjection.ErrorP,
      SemanticProjection.DeclarationProjection.CapabilityP {
      record CapabilityP(
         String symbolId,
         String name,
         Optional<String> actorSymbol,
         Optional<String> inputSymbol,
         SemanticProjection.TypeP outputType,
         List<String> fails,
         List<AstRequirementKind> requires,
         AstExposureKind exposure,
         SemanticProjection.WorkflowP workflow
      ) implements SemanticProjection.DeclarationProjection {
         public CapabilityP {
            Objects.requireNonNull(symbolId, "symbolId");
            Objects.requireNonNull(name, "name");
            actorSymbol = Objects.requireNonNull(actorSymbol, "actorSymbol");
            inputSymbol = Objects.requireNonNull(inputSymbol, "inputSymbol");
            Objects.requireNonNull(outputType, "outputType");
            fails = List.copyOf(Objects.requireNonNull(fails, "fails"));
            requires = List.copyOf(Objects.requireNonNull(requires, "requires"));
            Objects.requireNonNull(exposure, "exposure");
            Objects.requireNonNull(workflow, "workflow");
         }
      }

      record EntityP(String symbolId, String name, boolean persistent, SemanticProjection.IdentityP identity, List<SemanticProjection.FieldP> fields)
         implements SemanticProjection.DeclarationProjection {
         public EntityP {
            Objects.requireNonNull(symbolId, "symbolId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(identity, "identity");
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
         }
      }

      record EnumMemberP(String symbolId, String name) {
         public EnumMemberP {
            Objects.requireNonNull(symbolId, "symbolId");
            Objects.requireNonNull(name, "name");
         }
      }

      record EnumP(String symbolId, String name, List<SemanticProjection.DeclarationProjection.EnumMemberP> members)
         implements SemanticProjection.DeclarationProjection {
         public EnumP {
            Objects.requireNonNull(symbolId, "symbolId");
            Objects.requireNonNull(name, "name");
            members = List.copyOf(Objects.requireNonNull(members, "members"));
         }
      }

      record ErrorP(String symbolId, String name) implements SemanticProjection.DeclarationProjection {
         public ErrorP {
            Objects.requireNonNull(symbolId, "symbolId");
            Objects.requireNonNull(name, "name");
         }
      }

      record InputP(String symbolId, String name, List<SemanticProjection.FieldP> fields) implements SemanticProjection.DeclarationProjection {
         public InputP {
            Objects.requireNonNull(symbolId, "symbolId");
            Objects.requireNonNull(name, "name");
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
         }
      }
   }

   sealed interface ExpressionP
      permits SemanticProjection.ExpressionP.BooleanLiteralP,
      SemanticProjection.ExpressionP.IntegerLiteralP,
      SemanticProjection.ExpressionP.DecimalLiteralP,
      SemanticProjection.ExpressionP.StringLiteralP,
      SemanticProjection.ExpressionP.UnitLiteralP,
      SemanticProjection.ExpressionP.NowExpressionP,
      SemanticProjection.ExpressionP.NameExpressionP,
      SemanticProjection.ExpressionP.MemberExpressionP,
      SemanticProjection.ExpressionP.UnaryExpressionP,
      SemanticProjection.ExpressionP.BinaryExpressionP {
      record BinaryExpressionP(AstBinaryOperator operator, SemanticProjection.ExpressionP left, SemanticProjection.ExpressionP right)
         implements SemanticProjection.ExpressionP {
         public BinaryExpressionP {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
         }
      }

      record BooleanLiteralP(boolean value) implements SemanticProjection.ExpressionP {
      }

      record DecimalLiteralP(BigDecimal value) implements SemanticProjection.ExpressionP {
         public DecimalLiteralP {
            Objects.requireNonNull(value, "value");
         }
      }

      record IntegerLiteralP(BigInteger value) implements SemanticProjection.ExpressionP {
         public IntegerLiteralP {
            Objects.requireNonNull(value, "value");
         }
      }

      record MemberExpressionP(String member, String resolvedMember, SemanticProjection.ExpressionP receiver) implements SemanticProjection.ExpressionP {
         public MemberExpressionP {
            Objects.requireNonNull(member, "member");
            Objects.requireNonNull(resolvedMember, "resolvedMember");
            Objects.requireNonNull(receiver, "receiver");
         }
      }

      record NameExpressionP(String name, String resolvedSymbol) implements SemanticProjection.ExpressionP {
         public NameExpressionP {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(resolvedSymbol, "resolvedSymbol");
         }
      }

      record NowExpressionP() implements SemanticProjection.ExpressionP {
      }

      record StringLiteralP(String value) implements SemanticProjection.ExpressionP {
         public StringLiteralP {
            Objects.requireNonNull(value, "value");
         }
      }

      record UnaryExpressionP(AstUnaryOperator operator, SemanticProjection.ExpressionP operand) implements SemanticProjection.ExpressionP {
         public UnaryExpressionP {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
         }
      }

      record UnitLiteralP() implements SemanticProjection.ExpressionP {
      }
   }

   record FieldContractP(String symbolId, String name, String sourceNodeId, SemanticProjection.TypeP type) {
      public FieldContractP {
         Objects.requireNonNull(symbolId, "symbolId");
         Objects.requireNonNull(name, "name");
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(type, "type");
      }
   }

   record FieldP(String symbolId, String name, SemanticProjection.TypeP type, List<SemanticProjection.ConstraintP> constraints) {
      public FieldP {
         Objects.requireNonNull(symbolId, "symbolId");
         Objects.requireNonNull(name, "name");
         Objects.requireNonNull(type, "type");
         constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints"));
      }
   }

   record FieldTypeSiteP(Optional<AstNodeId> siteId, Optional<SymbolId> bindingTarget, Optional<ReferenceRole> role, Optional<String> primitiveName) {
      public FieldTypeSiteP {
         Objects.requireNonNull(siteId, "siteId");
         Objects.requireNonNull(bindingTarget, "bindingTarget");
         Objects.requireNonNull(role, "role");
         Objects.requireNonNull(primitiveName, "primitiveName");
      }
   }

   record IdentityP(String symbolId, String name, SemanticProjection.TypeP type, AstGenerationStrategy generation) {
      public IdentityP {
         Objects.requireNonNull(symbolId, "symbolId");
         Objects.requireNonNull(name, "name");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(generation, "generation");
      }
   }

   record InputContractP(String symbolId, String name, String sourceNodeId, List<SemanticProjection.FieldContractP> fields) {
      public InputContractP {
         Objects.requireNonNull(symbolId, "symbolId");
         Objects.requireNonNull(name, "name");
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
      }
   }

   record MetadataP(String displayName, String namespace) {
      public MetadataP {
         Objects.requireNonNull(displayName, "displayName");
         Objects.requireNonNull(namespace, "namespace");
      }
   }

   sealed interface StepP
      permits SemanticProjection.StepP.ValidateP,
      SemanticProjection.StepP.LoadP,
      SemanticProjection.StepP.FindP,
      SemanticProjection.StepP.CreateP,
      SemanticProjection.StepP.UpdateP,
      SemanticProjection.StepP.PersistP,
      SemanticProjection.StepP.ReturnP {
      record CreateP(String entitySymbol, String resultVariable, List<SemanticProjection.BindingP> bindings) implements SemanticProjection.StepP {
         public CreateP {
            Objects.requireNonNull(entitySymbol, "entitySymbol");
            Objects.requireNonNull(resultVariable, "resultVariable");
            bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
         }
      }

      record FindP(String entitySymbol, String resultVariable, String itemVariable, SemanticProjection.ExpressionP predicate)
         implements SemanticProjection.StepP {
         public FindP {
            Objects.requireNonNull(entitySymbol, "entitySymbol");
            Objects.requireNonNull(resultVariable, "resultVariable");
            Objects.requireNonNull(itemVariable, "itemVariable");
            Objects.requireNonNull(predicate, "predicate");
         }
      }

      record LoadP(String entitySymbol, String resultVariable, String errorSymbol, SemanticProjection.ExpressionP idExpression)
         implements SemanticProjection.StepP {
         public LoadP {
            Objects.requireNonNull(entitySymbol, "entitySymbol");
            Objects.requireNonNull(resultVariable, "resultVariable");
            Objects.requireNonNull(errorSymbol, "errorSymbol");
            Objects.requireNonNull(idExpression, "idExpression");
         }
      }

      record PersistP(String targetVariable) implements SemanticProjection.StepP {
         public PersistP {
            Objects.requireNonNull(targetVariable, "targetVariable");
         }
      }

      record ReturnP(SemanticProjection.ExpressionP value) implements SemanticProjection.StepP {
         public ReturnP {
            Objects.requireNonNull(value, "value");
         }
      }

      record UpdateP(String targetVariable, List<SemanticProjection.BindingP> bindings) implements SemanticProjection.StepP {
         public UpdateP {
            Objects.requireNonNull(targetVariable, "targetVariable");
            bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
         }
      }

      record ValidateP(String errorSymbol, SemanticProjection.ExpressionP condition) implements SemanticProjection.StepP {
         public ValidateP {
            Objects.requireNonNull(errorSymbol, "errorSymbol");
            Objects.requireNonNull(condition, "condition");
         }
      }
   }

   record TargetP(
      AstLanguage language,
      BigInteger languageVersion,
      AstFramework framework,
      AstPersistence persistence,
      AstDatabase database,
      AstBuildTool build,
      AstInterfaceKind interfaceKind
   ) {
      public TargetP {
         Objects.requireNonNull(language, "language");
         Objects.requireNonNull(languageVersion, "languageVersion");
         Objects.requireNonNull(framework, "framework");
         Objects.requireNonNull(persistence, "persistence");
         Objects.requireNonNull(database, "database");
         Objects.requireNonNull(build, "build");
         Objects.requireNonNull(interfaceKind, "interfaceKind");
      }
   }

   sealed interface TypeP
      permits SemanticProjection.TypeP.PrimitiveP,
      SemanticProjection.TypeP.DeclaredP,
      SemanticProjection.TypeP.RefP,
      SemanticProjection.TypeP.OptionalP,
      SemanticProjection.TypeP.ListP {
      record DeclaredP(String kind, String symbolId) implements SemanticProjection.TypeP {
         public DeclaredP {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(symbolId, "symbolId");
         }
      }

      record ListP(SemanticProjection.TypeP element) implements SemanticProjection.TypeP {
         public ListP {
            Objects.requireNonNull(element, "element");
         }
      }

      record OptionalP(SemanticProjection.TypeP element) implements SemanticProjection.TypeP {
         public OptionalP {
            Objects.requireNonNull(element, "element");
         }
      }

      record PrimitiveP(String name) implements SemanticProjection.TypeP {
         public PrimitiveP {
            Objects.requireNonNull(name, "name");
         }
      }

      record RefP(String entityId) implements SemanticProjection.TypeP {
         public RefP {
            Objects.requireNonNull(entityId, "entityId");
         }
      }
   }

   record TypedReferenceSiteP(String siteNodeId, ReferenceRole role, String targetSymbol) {
      public TypedReferenceSiteP {
         Objects.requireNonNull(siteNodeId, "siteNodeId");
         Objects.requireNonNull(role, "role");
         Objects.requireNonNull(targetSymbol, "targetSymbol");
      }
   }

   record TypedReferenceSitePV5(AstNodeId siteNodeId, ReferenceRole role, SymbolId targetSymbol) {
      public TypedReferenceSitePV5 {
         Objects.requireNonNull(siteNodeId, "siteNodeId");
         Objects.requireNonNull(role, "role");
         Objects.requireNonNull(targetSymbol, "targetSymbol");
      }
   }

   record TypedReferenceSiteProjection(List<SemanticProjection.TypedReferenceSiteP> sites) {
      public TypedReferenceSiteProjection {
         sites = List.copyOf(Objects.requireNonNull(sites, "sites"));
      }
   }

   record TypedReferenceSiteProjectionV5(List<SemanticProjection.TypedReferenceSitePV5> sites) {
      public TypedReferenceSiteProjectionV5 {
         sites = List.copyOf(Objects.requireNonNull(sites, "sites"));
      }
   }

   record WorkflowP(List<SemanticProjection.StepP> steps) {
      public WorkflowP {
         steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
      }
   }
}
