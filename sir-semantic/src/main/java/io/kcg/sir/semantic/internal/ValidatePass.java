package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.RelatedLocation;
import io.kcg.sir.ast.AstBinding;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstConstraint;
import io.kcg.sir.ast.AstCreateStep;
import io.kcg.sir.ast.AstDecimalLiteral;
import io.kcg.sir.ast.AstDeclaration;
import io.kcg.sir.ast.AstEntityDecl;
import io.kcg.sir.ast.AstExposureKind;
import io.kcg.sir.ast.AstExpression;
import io.kcg.sir.ast.AstFailsClause;
import io.kcg.sir.ast.AstField;
import io.kcg.sir.ast.AstGroupedExpression;
import io.kcg.sir.ast.AstInputDecl;
import io.kcg.sir.ast.AstIntegerLiteral;
import io.kcg.sir.ast.AstLoadStep;
import io.kcg.sir.ast.AstNameRef;
import io.kcg.sir.ast.AstPersistStep;
import io.kcg.sir.ast.AstRequirementKind;
import io.kcg.sir.ast.AstRequiresClause;
import io.kcg.sir.ast.AstReturnStep;
import io.kcg.sir.ast.AstSoftware;
import io.kcg.sir.ast.AstStep;
import io.kcg.sir.ast.AstUnaryExpression;
import io.kcg.sir.ast.AstUnaryOperator;
import io.kcg.sir.ast.AstUpdateStep;
import io.kcg.sir.ast.AstValidateStep;
import io.kcg.sir.semantic.context.TypedContext;
import io.kcg.sir.semantic.context.ValidatedContext;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.OptionalType;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.RefType;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.semantic.type.TypeRules;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;

final class ValidatePass {
   private final AstSoftware software;
   private final TypedContext typed;
   private final List<Diagnostic> diagnostics = new ArrayList<>();
   private final Map<SymbolId, Map<SymbolId, SirType>> entityFieldTypes = new LinkedHashMap<>();
   private final Map<SymbolId, SymbolId> entityIdentitySymbolIds = new LinkedHashMap<>();
   private final Map<SymbolId, Set<SymbolId>> capabilityFails = new LinkedHashMap<>();
   private final Set<SymbolId> declaredEntities = new LinkedHashSet<>();

   ValidatePass(AstSoftware software, TypedContext typed) {
      this.software = software;
      this.typed = typed;
      this.buildHelperMaps();
   }

   private void buildHelperMaps() {
      for (AstDeclaration decl : this.software.declarations()) {
         if (this.typed.declarationBindings().containsKey(decl.id())) {
            SymbolId declSymId = this.typed.declarationBindings().get(decl.id());
            AstDeclaration var4 = decl;
            switch (var4) {
               case AstEntityDecl e:
                  this.declaredEntities.add(declSymId);
                  this.entityFieldTypes.put(declSymId, new LinkedHashMap<>());

                  for (AstField f : e.fields()) {
                     SirType ft = this.typed.typeRefTypes().get(f.type().id());
                     SymbolId fieldSymId = this.typed.referenceBindings().get(f.id());
                     if (ft != null && fieldSymId != null) {
                        this.entityFieldTypes.get(declSymId).put(fieldSymId, ft);
                     }
                  }

                  SymbolId identitySymId = this.typed.referenceBindings().get(e.identity().id());
                  if (identitySymId != null) {
                     this.entityIdentitySymbolIds.put(declSymId, identitySymId);
                  }
                  break;
               case AstCapabilityDecl var16:
                  AstCapabilityDecl c = (AstCapabilityDecl)var4;
                  Set<SymbolId> fails = new LinkedHashSet<>();

                  for (AstFailsClause fc : c.failures()) {
                     this.typed.referenceSiteBindings().targetFor(fc.error().id()).ifPresent(fails::add);
                  }

                  this.capabilityFails.put(declSymId, fails);
                  continue;
               default:
            }
         }
      }
   }

   ValidatedContext run() {
      for (AstDeclaration decl : this.software.declarations()) {
         if (decl instanceof AstCapabilityDecl capDecl && this.typed.declarationBindings().containsKey(capDecl.id())) {
            this.validateCapability(capDecl);
         }
      }

      for (AstDeclaration decl : this.software.declarations()) {
         if (this.typed.declarationBindings().containsKey(decl.id())) {
            if (decl instanceof AstEntityDecl entityDecl) {
               this.validateEntityConstraints(entityDecl);
            } else if (decl instanceof AstInputDecl inputDecl) {
               this.validateInputConstraints(inputDecl);
            }
         }
      }

      return ValidatedContext.from(this.typed, List.copyOf(this.diagnostics));
   }

   private void validateCapability(AstCapabilityDecl capDecl) {
      SymbolId capSymId = this.typed.declarationBindings().get(capDecl.id());
      Set<SymbolId> failsSet = this.capabilityFails.get(capSymId);
      boolean isReadonly = capDecl.requirements().stream().anyMatch(r -> r.requirement() == AstRequirementKind.READONLY);
      boolean isAtomic = capDecl.requirements().stream().anyMatch(r -> r.requirement() == AstRequirementKind.ATOMIC);
      boolean isQuery = capDecl.exposure().exposure() == AstExposureKind.QUERY;
      if (isQuery && !isReadonly) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-001", "query 必须声明 readonly", capDecl.exposure().span()));
      }

      if (isAtomic && isReadonly) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-001", "atomic 与 readonly 互斥", capDecl.span()));
      }

      int writeStepCount = 0;

      for (AstStep step : capDecl.workflow().steps()) {
         if (this.isWriteStep(step)) {
            writeStepCount++;
         }
      }

      if (writeStepCount >= 2 && !isAtomic) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-001", "多个写步骤需要 requires atomic", capDecl.span()));
      }

      this.validateReturnPlacement(capDecl);
      int returnCount = 0;
      boolean returnSeen = false;

      for (AstStep step : capDecl.workflow().steps()) {
         if (returnSeen) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-002", "Return 后不能有其他步骤", step.span()));
         }

         AstStep var12 = step;
         switch (var12) {
            case AstReturnStep s:
               returnCount++;
               returnSeen = true;
               break;
            case AstValidateStep s:
               Optional<SymbolId> errorSymIdx = this.typed.referenceSiteBindings().targetFor(s.error().id());
               if (errorSymIdx.isPresent() && failsSet != null && !failsSet.contains(errorSymIdx.get())) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-003", "validate 引用未声明的 Error: " + s.error().text(), s.error().span()));
               }
               break;
            case AstLoadStep s:
               Optional<SymbolId> errorSymId = this.typed.referenceSiteBindings().targetFor(s.error().id());
               if (errorSymId.isPresent() && failsSet != null && !failsSet.contains(errorSymId.get())) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-003", "load 引用未声明的 Error: " + s.error().text(), s.error().span()));
               }
               break;
            case AstCreateStep s:
               if (isReadonly || isQuery) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-004", "query/readonly 不允许 create 步骤", s.span()));
               }

               this.validateCreateStep(s);
               break;
            case AstUpdateStep s:
               if (isReadonly || isQuery) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-004", "query/readonly 不允许 update 步骤", s.span()));
               }

               this.validateUpdateStep(s);
               break;
            case AstPersistStep s:
               if (isReadonly || isQuery) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-004", "query/readonly 不允许 persist 步骤", s.span()));
               }

               this.validatePersistStep(s);
               continue;
            default:
         }
      }

      if (returnCount == 0) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-002", "Workflow 必须包含 Return 步骤", capDecl.workflow().span()));
      } else if (returnCount > 1) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-002", "Workflow 只能包含一个 Return 步骤", capDecl.workflow().span()));
      }

      this.validateFailsOrder(capDecl);
      this.validateRequiresOrder(capDecl);
   }

   private void validateReturnPlacement(AstCapabilityDecl capDecl) {
      List<AstStep> steps = capDecl.workflow().steps();
      if (steps.isEmpty()) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-002", "Workflow 最后一步必须是 Return", capDecl.workflow().span()));
      } else {
         if (!(steps.getLast() instanceof AstReturnStep)) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-002", "Workflow 最后一步必须是 Return", capDecl.workflow().span()));
         }
      }
   }

   private boolean isWriteStep(AstStep step) {
      return step instanceof AstCreateStep || step instanceof AstUpdateStep || step instanceof AstPersistStep;
   }

   private void validateCreateStep(AstCreateStep step) {
      SymbolId entityId = this.typed.referenceSiteBindings().targetFor(step.entity().id()).orElse(null);
      if (entityId != null && this.declaredEntities.contains(entityId)) {
         SymbolId identitySymId = this.entityIdentitySymbolIds.get(entityId);
         Set<SymbolId> boundFieldIds = new LinkedHashSet<>();

         for (AstBinding binding : step.bindings()) {
            SymbolId fieldSymId = this.typed.referenceSiteBindings().targetFor(binding.fieldName().id()).orElse(null);
            if (fieldSymId != null) {
               String displayName = this.displayName(fieldSymId, binding.fieldName().text());
               if (boundFieldIds.contains(fieldSymId)) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-001", "create 中字段重复绑定: " + displayName, binding.span()));
               } else {
                  boundFieldIds.add(fieldSymId);
                  if (identitySymId != null && fieldSymId.equals(identitySymId)) {
                     this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-001", "不能绑定 generated identity: " + displayName, binding.span()));
                  }
               }
            }
         }

         Map<SymbolId, SirType> fieldTypes = this.entityFieldTypes.get(entityId);
         if (fieldTypes != null) {
            for (Entry<SymbolId, SirType> entry : fieldTypes.entrySet()) {
               SymbolId fieldSymId = entry.getKey();
               SirType fieldType = entry.getValue();
               if (!(fieldType instanceof OptionalType) && !boundFieldIds.contains(fieldSymId)) {
                  String displayName = this.displayName(fieldSymId, "unknown");
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-001", "create 未绑定必填字段: " + displayName, step.span()));
               }
            }
         }
      }
   }

   private void validateUpdateStep(AstUpdateStep step) {
      SymbolId targetVarId = this.typed.referenceSiteBindings().targetFor(step.target().id()).orElse(null);
      if (targetVarId != null) {
         Symbol targetSym = this.typed.symbols().byId(targetVarId).orElse(null);
         if (targetSym instanceof Symbol.VariableSymbol vs && vs.type() != null) {
            if (!(vs.type() instanceof RefType)) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "update 目标必须是 Ref<Entity>", step.target().span()));
            } else {
               RefType rt = (RefType)vs.type();
               SymbolId entityId = rt.entityId();
               SymbolId identitySymId = this.entityIdentitySymbolIds.get(entityId);
               Set<SymbolId> boundFieldIds = new LinkedHashSet<>();

               for (AstBinding binding : step.bindings()) {
                  SymbolId fieldSymId = this.typed.referenceSiteBindings().targetFor(binding.fieldName().id()).orElse(null);
                  if (fieldSymId != null) {
                     String displayName = this.displayName(fieldSymId, binding.fieldName().text());
                     if (boundFieldIds.contains(fieldSymId)) {
                        this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-001", "update 中字段重复绑定: " + displayName, binding.span()));
                     } else {
                        boundFieldIds.add(fieldSymId);
                        if (identitySymId != null && fieldSymId.equals(identitySymId)) {
                           this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-001", "不能修改 identity: " + displayName, binding.span()));
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void validatePersistStep(AstPersistStep step) {
      SymbolId targetVarId = this.typed.referenceSiteBindings().targetFor(step.target().id()).orElse(null);
      if (targetVarId != null) {
         Symbol targetSym = this.typed.symbols().byId(targetVarId).orElse(null);
         if (targetSym instanceof Symbol.VariableSymbol vs && vs.type() != null) {
            if (!(vs.type() instanceof RefType)) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "persist 目标必须是 Ref<Entity>", step.target().span()));
            }
         }
      }
   }

   private void validateFailsOrder(AstCapabilityDecl capDecl) {
      List<String> names = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();

      for (AstFailsClause fails : capDecl.failures()) {
         String name = fails.error().text();
         if (seen.contains(name)) {
            AstNameRef first = this.findFirstFails(capDecl, name);
            RelatedLocation related = new RelatedLocation("首次声明在此处", first.span());
            this.diagnostics.add(DiagnosticBuilder.errorWithRelated("SIR-SYMBOL-001", "重复 fails: " + name, fails.error().span(), related));
         } else {
            seen.add(name);
            names.add(name);
         }
      }

      for (int i = 1; i < names.size(); i++) {
         if (names.get(i).compareTo(names.get(i - 1)) < 0) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-001", "fails 必须按 Error 名称升序排列", capDecl.failures().get(i).span()));
            break;
         }
      }
   }

   private AstNameRef findFirstFails(AstCapabilityDecl capDecl, String name) {
      for (AstFailsClause fails : capDecl.failures()) {
         if (fails.error().text().equals(name)) {
            return fails.error();
         }
      }

      throw new IllegalStateException("fails not found: " + name);
   }

   private void validateRequiresOrder(AstCapabilityDecl capDecl) {
      List<AstRequirementKind> kinds = new ArrayList<>();
      Set<AstRequirementKind> seen = new LinkedHashSet<>();

      for (AstRequiresClause req : capDecl.requirements()) {
         AstRequirementKind kind = req.requirement();
         if (seen.contains(kind)) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-001", "重复 requires: " + kind.name().toLowerCase(Locale.ROOT), req.span()));
         } else {
            seen.add(kind);
            kinds.add(kind);
         }
      }

      for (int i = 1; i < kinds.size(); i++) {
         if (kinds.get(i).ordinal() < kinds.get(i - 1).ordinal()) {
            this.diagnostics
               .add(DiagnosticBuilder.error("SIR-VALID-001", "requires 必须按 authenticated, atomic, readonly 顺序", capDecl.requirements().get(i).span()));
            break;
         }
      }
   }

   private void validateEntityConstraints(AstEntityDecl entityDecl) {
      for (AstField field : entityDecl.fields()) {
         SirType fieldType = this.typed.typeRefTypes().get(field.type().id());
         if (fieldType != null) {
            this.validateConstraints(field, fieldType);
         }
      }
   }

   private void validateInputConstraints(AstInputDecl inputDecl) {
      for (AstField field : inputDecl.fields()) {
         SirType fieldType = this.typed.typeRefTypes().get(field.type().id());
         if (fieldType != null) {
            this.validateConstraints(field, fieldType);
         }
      }
   }

   private void validateConstraints(AstField field, SirType fieldType) {
      SirType constraintType = fieldType;
      if (constraintType instanceof OptionalType ot) {
         constraintType = ot.element();
      }

      Set<String> seenConstraints = new LinkedHashSet<>();
      BigDecimal minValue = null;
      BigDecimal maxValue = null;

      for (AstConstraint constraint : field.constraints()) {
         String name = constraint.name().text();
         if (!this.isValidConstraintName(name)) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "未知约束名: " + name, constraint.name().span()));
         } else if (seenConstraints.contains(name)) {
            AstNameRef first = this.findFirstConstraint(field, name);
            RelatedLocation related = new RelatedLocation("首次声明在此处", first.span());
            this.diagnostics.add(DiagnosticBuilder.errorWithRelated("SIR-SYMBOL-001", "重复约束: " + name, constraint.name().span(), related));
         } else {
            seenConstraints.add(name);
            int expectedArgs = this.constraintArgCount(name);
            if (constraint.arguments().size() != expectedArgs) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "约束参数数量错误: " + name + " 需要 " + expectedArgs + " 个参数", constraint.span()));
            } else {
               if ((name.equals("notBlank") || name.equals("email") || name.equals("length")) && constraintType != PrimitiveType.STRING) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "约束 " + name + " 只适用于 String", constraint.span()));
               }

               if (name.equals("min") || name.equals("max")) {
                  if (!TypeRules.isNumeric(constraintType)) {
                     this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "约束 " + name + " 只适用于数字类型", constraint.span()));
                  }

                  if (!constraint.arguments().isEmpty()) {
                     BigDecimal val = this.evalNumeric(constraint.arguments().get(0));
                     if (val == null) {
                        this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "min/max 参数必须是数字常量", constraint.span()));
                     }
                  }
               }

               if (name.equals("length") && constraint.arguments().size() == 2) {
                  BigInteger minArg = this.evalInteger(constraint.arguments().get(0));
                  BigInteger maxArg = this.evalInteger(constraint.arguments().get(1));
                  if (minArg == null || maxArg == null) {
                     this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "length 参数必须是整数常量", constraint.span()));
                  } else if (minArg.signum() < 0 || maxArg.signum() < 0) {
                     this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "length 参数必须为非负", constraint.span()));
                  } else if (minArg.compareTo(maxArg) > 0) {
                     this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "length 的 min 不能大于 max", constraint.span()));
                  }
               }

               if (name.equals("min") && !constraint.arguments().isEmpty()) {
                  minValue = this.evalNumeric(constraint.arguments().get(0));
               }

               if (name.equals("max") && !constraint.arguments().isEmpty()) {
                  maxValue = this.evalNumeric(constraint.arguments().get(0));
               }
            }
         }
      }

      if (minValue != null && maxValue != null && minValue.compareTo(maxValue) > 0) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-001", "min/max 冲突: min(" + minValue + ") > max(" + maxValue + ")", field.span()));
      }
   }

   private boolean isValidConstraintName(String name) {
      return name.equals("notBlank") || name.equals("email") || name.equals("min") || name.equals("max") || name.equals("length");
   }

   private int constraintArgCount(String name) {
      return switch (name) {
         case "notBlank", "email" -> 0;
         case "min", "max" -> 1;
         case "length" -> 2;
         default -> 0;
      };
   }

   private BigDecimal evalNumeric(AstExpression expr) {
      expr = this.ungroup(expr);
      if (expr instanceof AstUnaryExpression unary && unary.operator() == AstUnaryOperator.NEGATE) {
         BigDecimal operand = this.evalNumeric(unary.operand());
         return operand == null ? null : operand.negate();
      } else if (expr instanceof AstIntegerLiteral il) {
         return new BigDecimal(il.value());
      } else {
         return expr instanceof AstDecimalLiteral dl ? dl.value() : null;
      }
   }

   private BigInteger evalInteger(AstExpression expr) {
      expr = this.ungroup(expr);
      if (expr instanceof AstUnaryExpression unary && unary.operator() == AstUnaryOperator.NEGATE) {
         BigInteger operand = this.evalInteger(unary.operand());
         return operand == null ? null : operand.negate();
      } else {
         return expr instanceof AstIntegerLiteral integer ? integer.value() : null;
      }
   }

   private AstExpression ungroup(AstExpression expr) {
      while (expr instanceof AstGroupedExpression) {
         AstGroupedExpression ge = (AstGroupedExpression)expr;
         expr = ge.inner();
      }

      return expr;
   }

   private AstNameRef findFirstConstraint(AstField field, String name) {
      for (AstConstraint c : field.constraints()) {
         if (c.name().text().equals(name)) {
            return c.name();
         }
      }

      throw new IllegalStateException("constraint not found: " + name);
   }

   private String displayName(SymbolId fieldSymId, String fallback) {
      return this.typed.symbols().byId(fieldSymId).map(Symbol::name).orElse(fallback);
   }
}
