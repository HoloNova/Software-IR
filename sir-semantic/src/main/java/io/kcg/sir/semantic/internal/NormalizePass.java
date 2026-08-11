package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
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
import io.kcg.sir.ast.AstRequirementKind;
import io.kcg.sir.ast.AstRequiresClause;
import io.kcg.sir.ast.AstReturnStep;
import io.kcg.sir.ast.AstSoftware;
import io.kcg.sir.ast.AstStep;
import io.kcg.sir.ast.AstStringLiteral;
import io.kcg.sir.ast.AstUnaryExpression;
import io.kcg.sir.ast.AstUnitLiteral;
import io.kcg.sir.ast.AstUpdateStep;
import io.kcg.sir.ast.AstValidateStep;
import io.kcg.sir.ast.AstWorkflow;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.context.ValidatedContext;
import io.kcg.sir.semantic.model.NormalizedBinding;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedConstraint;
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
import io.kcg.sir.semantic.symbol.SymbolId;
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

   NormalizePass(AstSoftware software, ValidatedContext validated) {
      this.software = software;
      this.softwareName = software.name().text();
      this.validated = validated;
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
         this.validated.referenceSiteBindings()
      );
      return new SemanticAnalysis.Success(model, sortedDiagnostics);
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

      for (AstField f : decl.fields()) {
         String fieldName = f.name().text();
         if (seen.add(fieldName) && !fieldName.equals(decl.identity().name().text())) {
            NormalizedField field = this.normalizeField(f, decl.name().text(), "entity");
            if (field != null) {
               fields.add(field);
            }
         }
      }

      return new NormalizedEntity(id, decl.name().text(), decl.span(), decl.id(), true, identity, List.copyOf(fields));
   }

   private NormalizedIdentity normalizeIdentity(AstIdentity identity, String entityName) {
      SymbolId identityId = SymbolIdFactory.entityIdentity(this.softwareName, entityName);
      SirType type = this.validated.typeRefTypes().get(identity.type().id());
      return new NormalizedIdentity(
         identityId, identity.name().text(), identity.span(), identity.id(), type != null ? type : PrimitiveType.INT64, identity.generation()
      );
   }

   private NormalizedInput normalizeInput(AstInputDecl decl, SymbolId id) {
      List<NormalizedField> fields = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();

      for (AstField f : decl.fields()) {
         String fieldName = f.name().text();
         if (seen.add(fieldName)) {
            NormalizedField field = this.normalizeField(f, decl.name().text(), "input");
            if (field != null) {
               fields.add(field);
            }
         }
      }

      return new NormalizedInput(id, decl.name().text(), decl.span(), decl.id(), List.copyOf(fields));
   }

   private NormalizedError normalizeError(AstErrorDecl decl, SymbolId id) {
      return new NormalizedError(id, decl.name().text(), decl.span(), decl.id());
   }

   private NormalizedField normalizeField(AstField field, String ownerName, String ownerKind) {
      String fieldName = field.name().text();
      SirType type = this.validated.typeRefTypes().get(field.type().id());
      if (type == null) {
         return null;
      }

      SymbolId fieldId = ownerKind.equals("entity")
         ? SymbolIdFactory.entityField(this.softwareName, ownerName, fieldName)
         : SymbolIdFactory.inputField(this.softwareName, ownerName, fieldName);
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
      SirType outputType = this.validated.typeRefTypes().get(decl.output().type().id());
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
      NormalizedWorkflow workflow = this.normalizeWorkflow(decl.workflow(), decl.name().text());
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

   private NormalizedWorkflow normalizeWorkflow(AstWorkflow workflow, String capName) {
      List<NormalizedStep> steps = new ArrayList<>();

      for (AstStep step : workflow.steps()) {
         NormalizedStep normalized = this.normalizeStep(step, capName);
         if (normalized != null) {
            steps.add(normalized);
         }
      }

      SymbolId workflowId = SymbolIdFactory.declaration(this.softwareName, "capability", capName);
      return new NormalizedWorkflow(workflowId, workflow.span(), workflow.id(), List.copyOf(steps));
   }

   private NormalizedStep normalizeStep(AstStep step, String capName) {
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
            yield new NormalizedStep.LoadStep(s.id(), s.span(), entitySym, idExpr != null ? idExpr : this.unitExpr(s.span()), resultVar, errorSym);
         }
         case AstFindStep s -> {
            SymbolId entitySym = this.siteTargetOrUnknown(s.entity().id());
            SymbolId resultVar = this.definitionBindingOrUnknown(s.id());
            SymbolId itemVar = this.validated.findItemBindings().getOrDefault(s.id(), resultVar);
            NormalizedExpression predicate = this.normalizeExpression(s.predicate());
            yield new NormalizedStep.FindStep(s.id(), s.span(), entitySym, predicate != null ? predicate : this.unitExpr(s.span()), resultVar, itemVar);
         }
         case AstCreateStep s -> {
            SymbolId entitySym = this.siteTargetOrUnknown(s.entity().id());
            SymbolId resultVar = this.definitionBindingOrUnknown(s.id());
            List<NormalizedBinding> bindings = this.normalizeBindings(s.bindings(), capName);
            yield new NormalizedStep.CreateStep(s.id(), s.span(), entitySym, resultVar, bindings);
         }
         case AstUpdateStep s -> {
            SymbolId targetVar = this.siteTargetOrUnknown(s.target().id());
            List<NormalizedBinding> bindings = this.normalizeBindings(s.bindings(), capName);
            yield new NormalizedStep.UpdateStep(s.id(), s.span(), targetVar, bindings);
         }
         case AstPersistStep s -> {
            SymbolId targetVar = this.siteTargetOrUnknown(s.target().id());
            yield new NormalizedStep.PersistStep(s.id(), s.span(), targetVar);
         }
         case AstReturnStep s -> {
            NormalizedExpression value = this.normalizeExpression(s.value());
            yield new NormalizedStep.ReturnStep(s.id(), s.span(), value != null ? value : this.unitExpr(s.span()));
         }
         default -> throw new MatchException(null, null);
      };
   }

   private List<NormalizedBinding> normalizeBindings(List<AstBinding> bindings, String capName) {
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
         default -> null;
      });
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
