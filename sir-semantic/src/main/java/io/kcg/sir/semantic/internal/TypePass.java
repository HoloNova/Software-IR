package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.ast.AstBinaryExpression;
import io.kcg.sir.ast.AstBinding;
import io.kcg.sir.ast.AstBooleanLiteral;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstCreateStep;
import io.kcg.sir.ast.AstDecimalLiteral;
import io.kcg.sir.ast.AstDeclaration;
import io.kcg.sir.ast.AstEntityDecl;
import io.kcg.sir.ast.AstExpression;
import io.kcg.sir.ast.AstFindStep;
import io.kcg.sir.ast.AstGroupedExpression;
import io.kcg.sir.ast.AstIntegerLiteral;
import io.kcg.sir.ast.AstLoadStep;
import io.kcg.sir.ast.AstMemberExpression;
import io.kcg.sir.ast.AstNameExpression;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstNowExpression;
import io.kcg.sir.ast.AstPersistStep;
import io.kcg.sir.ast.AstReturnStep;
import io.kcg.sir.ast.AstSoftware;
import io.kcg.sir.ast.AstStep;
import io.kcg.sir.ast.AstStringLiteral;
import io.kcg.sir.ast.AstUnaryExpression;
import io.kcg.sir.ast.AstUnitLiteral;
import io.kcg.sir.ast.AstUpdateStep;
import io.kcg.sir.ast.AstValidateStep;
import io.kcg.sir.semantic.context.ResolvedContext;
import io.kcg.sir.semantic.context.TypedContext;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.semantic.type.DeclaredType;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.semantic.type.TypeRules;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class TypePass {
   private final AstSoftware software;
   private final String softwareName;
   private final ResolvedContext resolved;
   private final Map<AstNodeId, SirType> expressionTypes = new LinkedHashMap<>();
   private final List<Diagnostic> diagnostics = new ArrayList<>();
   private final Map<SymbolId, SirType> entityIdentityTypes = new LinkedHashMap<>();
   private final Map<SymbolId, DeclaredType> declaredTypesBySymbolId = new LinkedHashMap<>();

   TypePass(AstSoftware software, ResolvedContext resolved) {
      this.software = software;
      this.softwareName = software.name().text();
      this.resolved = resolved;
      this.buildTypeMaps();
   }

   private void buildTypeMaps() {
      for (AstEntityDecl entity : this.entityDeclarations()) {
         SymbolId entityId = this.resolved.declarationBindings().get(entity.id());
         if (entityId != null) {
            SirType idType = this.resolved.typeRefTypes().get(entity.identity().type().id());
            if (idType != null) {
               this.entityIdentityTypes.put(entityId, idType);
            }
         }
      }

      for (SirType type : this.resolved.typeRefTypes().values()) {
         if (type instanceof DeclaredType dt) {
            this.declaredTypesBySymbolId.put(dt.symbolId(), dt);
         }
      }
   }

   private List<AstEntityDecl> entityDeclarations() {
      List<AstEntityDecl> list = new ArrayList<>();

      for (AstDeclaration decl : this.software.declarations()) {
         if (decl instanceof AstEntityDecl e && this.resolved.declarationBindings().containsKey(e.id())) {
            list.add(e);
         }
      }

      return list;
   }

   TypedContext run() {
      for (AstDeclaration decl : this.software.declarations()) {
         if (decl instanceof AstCapabilityDecl capDecl && this.resolved.declarationBindings().containsKey(capDecl.id())) {
            this.typeCapabilityWorkflow(capDecl);
         }
      }

      return TypedContext.from(this.resolved, this.expressionTypes, List.copyOf(this.diagnostics));
   }

   private void typeCapabilityWorkflow(AstCapabilityDecl capDecl) {
      SymbolId capScopeId = SymbolIdFactory.declaration(this.softwareName, "capability", capDecl.name().text());
      Set<String> visibleVars = new LinkedHashSet<>();
      if (capDecl.actor().isPresent()) {
         visibleVars.add("actor");
      }

      if (capDecl.input().isPresent()) {
         visibleVars.add("input");
      }

      SirType outputType = this.resolved.typeRefTypes().get(capDecl.output().type().id());

      for (AstStep step : capDecl.workflow().steps()) {
         AstStep var7 = step;
         switch (var7) {
            case AstValidateStep s:
               SirType condType = this.typeExpression(s.condition(), capScopeId, visibleVars);
               if (condType != null && !TypeRules.isBoolean(condType)) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "validate 条件必须是 Boolean", s.condition().span()));
               }
               break;
            case AstLoadStep s:
               SirType idType = this.typeExpression(s.idExpression(), capScopeId, visibleVars);
               SymbolId entityId = this.resolved.referenceSiteBindings().targetFor(s.entity().id()).orElse(null);
               SirType identityType = entityId == null ? null : this.entityIdentityTypes.get(entityId);
               if (idType != null && identityType != null && !TypeRules.isAssignable(idType, identityType)) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "load 的 id 表达式类型与 Entity identity 不兼容", s.idExpression().span()));
               }

               visibleVars.add(s.result().text());
               break;
            case AstFindStep s:
               SymbolId stepScopeId = SymbolIdFactory.stepScope(this.softwareName, capDecl.name().text(), s.id().value());
               Set<String> predVars = new LinkedHashSet<>(visibleVars);
               predVars.add("item");
               SirType predType = this.typeExpression(s.predicate(), stepScopeId, predVars);
               if (predType != null && !TypeRules.isBoolean(predType)) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "find 谓词必须是 Boolean", s.predicate().span()));
               }

               visibleVars.add(s.result().text());
               break;
            case AstCreateStep s:
               Set<SymbolId> boundFieldIds = new LinkedHashSet<>();

               for (AstBinding binding : s.bindings()) {
                  SirType valueType = this.typeExpression(binding.value(), capScopeId, visibleVars);
                  SymbolId fieldSymId = this.resolved.referenceSiteBindings().targetFor(binding.fieldName().id()).orElse(null);
                  if (fieldSymId != null && !boundFieldIds.contains(fieldSymId)) {
                     boundFieldIds.add(fieldSymId);
                     SirType fieldType = this.boundFieldType(binding);
                     if (fieldType != null && valueType != null && !TypeRules.isAssignable(valueType, fieldType)) {
                        this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "create 绑定类型不匹配: " + binding.fieldName().text(), binding.span()));
                     }
                  }
               }

               visibleVars.add(s.result().text());
               break;
            case AstUpdateStep s:
               Set<SymbolId> updateBoundFieldIds = new LinkedHashSet<>();

               for (AstBinding binding : s.bindings()) {
                  SirType valueType = this.typeExpression(binding.value(), capScopeId, visibleVars);
                  SymbolId fieldSymId = this.resolved.referenceSiteBindings().targetFor(binding.fieldName().id()).orElse(null);
                  if (fieldSymId != null && !updateBoundFieldIds.contains(fieldSymId)) {
                     updateBoundFieldIds.add(fieldSymId);
                     SirType fieldType = this.boundFieldType(binding);
                     if (fieldType != null && valueType != null && !TypeRules.isAssignable(valueType, fieldType)) {
                        this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "update 绑定类型不匹配: " + binding.fieldName().text(), binding.span()));
                     }
                  }
               }
               continue;
            case AstPersistStep s:
               break;
            case AstReturnStep var37:
               AstReturnStep s = (AstReturnStep)var7;
               SirType valueType = this.typeExpression(s.value(), capScopeId, visibleVars);
               if (outputType != null && valueType != null) {
                  if (outputType instanceof PrimitiveType pt && pt == PrimitiveType.UNIT) {
                     if (!(this.ungroup(s.value()) instanceof AstUnitLiteral)) {
                        this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "Unit output 必须返回 unit", s.value().span()));
                     }
                  } else if (this.ungroup(s.value()) instanceof AstUnitLiteral) {
                     this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "非 Unit output 不能返回 unit", s.value().span()));
                  } else if (!TypeRules.isAssignable(valueType, outputType)) {
                     this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "返回类型不匹配", s.value().span()));
                  }
               }
               break;
            default:
         }
      }
   }

   private AstExpression ungroup(AstExpression expr) {
      while (expr instanceof AstGroupedExpression) {
         AstGroupedExpression ge = (AstGroupedExpression)expr;
         expr = ge.inner();
      }

      return expr;
   }

   private SirType typeExpression(AstExpression expr, SymbolId scopeId, Set<String> visibleVars) {
      if (expr == null) {
         return null;
      }

      SirType result = switch (expr) {
         case AstBooleanLiteral e -> PrimitiveType.BOOLEAN;
         case AstIntegerLiteral e -> PrimitiveType.INT32;
         case AstDecimalLiteral e -> PrimitiveType.DECIMAL;
         case AstStringLiteral e -> PrimitiveType.STRING;
         case AstUnitLiteral e -> PrimitiveType.UNIT;
         case AstNowExpression e -> PrimitiveType.DATE_TIME;
         case AstNameExpression e -> this.typeNameExpression(e, scopeId, visibleVars);
         case AstMemberExpression e -> this.typeMemberExpression(e, scopeId, visibleVars);
         case AstGroupedExpression e -> this.typeExpression(e.inner(), scopeId, visibleVars);
         case AstUnaryExpression e -> this.typeUnaryExpression(e, scopeId, visibleVars);
         case AstBinaryExpression e -> this.typeBinaryExpression(e, scopeId, visibleVars);
         default -> throw new IllegalStateException("unexpected expression: " + expr);
      };
      if (result != null) {
         this.expressionTypes.put(expr.id(), result);
      }

      return result;
   }

   private SirType typeNameExpression(AstNameExpression expr, SymbolId scopeId, Set<String> visibleVars) {
      Optional<SymbolId> symbolIdOpt = this.resolved.referenceSiteBindings().targetFor(expr.name().id());
      if (symbolIdOpt.isEmpty()) {
         return null;
      } else {
         Symbol found = this.resolved.symbols().byId(symbolIdOpt.get()).orElse(null);
         if (found instanceof Symbol.VariableSymbol vs) {
            return vs.type();
         } else {
            return found instanceof Symbol.TypeSymbol ts && ts.kind() == SymbolKind.ENUM ? this.declaredTypesBySymbolId.get(ts.id()) : null;
         }
      }
   }

   private SirType typeMemberExpression(AstMemberExpression expr, SymbolId scopeId, Set<String> visibleVars) {
      SirType receiverType = this.typeExpression(expr.receiver(), scopeId, visibleVars);
      if (receiverType == null) {
         return null;
      } else {
         Optional<SymbolId> memberIdOpt = this.resolved.referenceSiteBindings().targetFor(expr.member().id());
         if (memberIdOpt.isEmpty()) {
            return null;
         } else {
            Symbol member = this.resolved.symbols().byId(memberIdOpt.get()).orElse(null);
            if (member instanceof Symbol.EnumMemberSymbol) {
               return receiverType;
            } else {
               return member instanceof Symbol.FieldSymbol field ? field.type() : null;
            }
         }
      }
   }

   private SirType boundFieldType(AstBinding binding) {
      Optional<SymbolId> fieldIdOpt = this.resolved.referenceSiteBindings().targetFor(binding.fieldName().id());
      if (fieldIdOpt.isEmpty()) {
         return null;
      }

      Symbol field = this.resolved.symbols().byId(fieldIdOpt.get()).orElse(null);
      return field instanceof Symbol.FieldSymbol fs ? fs.type() : null;
   }

   private SirType typeUnaryExpression(AstUnaryExpression expr, SymbolId scopeId, Set<String> visibleVars) {
      SirType operandType = this.typeExpression(expr.operand(), scopeId, visibleVars);
      if (operandType == null) {
         return null;
      }

      switch (expr.operator()) {
         case NOT:
            if (!TypeRules.isBoolean(operandType)) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "not 操作数必须是 Boolean", expr.operand().span()));
               return null;
            }

            return PrimitiveType.BOOLEAN;
         case NEGATE:
            if (!TypeRules.isNumeric(operandType)) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "负号操作数必须是数字", expr.operand().span()));
               return null;
            }

            return operandType;
         default:
            throw new IllegalStateException("unexpected operator: " + expr.operator());
      }
   }

   private SirType typeBinaryExpression(AstBinaryExpression expr, SymbolId scopeId, Set<String> visibleVars) {
      SirType leftType = this.typeExpression(expr.left(), scopeId, visibleVars);
      SirType rightType = this.typeExpression(expr.right(), scopeId, visibleVars);
      if (leftType != null && rightType != null) {
         switch (expr.operator()) {
            case EQ:
            case NE:
               if (!leftType.equals(rightType)) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "比较操作数类型不一致", expr.span()));
                  return null;
               }

               return PrimitiveType.BOOLEAN;
            case GT:
            case GE:
            case LT:
            case LE:
               if (!leftType.equals(rightType)) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "比较操作数类型不一致", expr.span()));
                  return null;
               } else {
                  if (!TypeRules.isComparable(leftType)) {
                     this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "类型不可比较: " + leftType, expr.span()));
                     return null;
                  }

                  return PrimitiveType.BOOLEAN;
               }
            case AND:
            case OR:
               if (TypeRules.isBoolean(leftType) && TypeRules.isBoolean(rightType)) {
                  return PrimitiveType.BOOLEAN;
               }

               this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "逻辑操作数必须是 Boolean", expr.span()));
               return null;
            default:
               throw new IllegalStateException("unexpected operator: " + expr.operator());
         }
      } else {
         return null;
      }
   }
}
