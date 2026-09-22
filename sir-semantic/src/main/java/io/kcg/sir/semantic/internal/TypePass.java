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
import io.kcg.sir.ast.AstField;
import io.kcg.sir.ast.AstFindOrderKey;
import io.kcg.sir.ast.AstFindPage;
import io.kcg.sir.ast.AstFindStep;
import io.kcg.sir.ast.AstErrorDecl;
import io.kcg.sir.ast.AstGroupedExpression;
import io.kcg.sir.ast.AstInputDecl;
import io.kcg.sir.ast.AstIntegerLiteral;
import io.kcg.sir.ast.AstLoadStep;
import io.kcg.sir.ast.AstMemberExpression;
import io.kcg.sir.ast.AstNameExpression;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstNowExpression;
import io.kcg.sir.ast.AstPresentExpression;
import io.kcg.sir.ast.AstPersistStep;
import io.kcg.sir.ast.AstReturnStep;
import io.kcg.sir.ast.AstSoftware;
import io.kcg.sir.ast.AstStep;
import io.kcg.sir.ast.AstStringLiteral;
import io.kcg.sir.ast.AstUnaryExpression;
import io.kcg.sir.ast.AstUnitLiteral;
import io.kcg.sir.ast.AstUpdateStep;
import io.kcg.sir.ast.AstValidateStep;
import io.kcg.sir.ast.AstViewDecl;
import io.kcg.sir.ast.AstViewField;
import io.kcg.sir.semantic.context.ResolvedContext;
import io.kcg.sir.semantic.context.TypedContext;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.semantic.type.DeclaredType;
import io.kcg.sir.semantic.type.OptionalType;
import io.kcg.sir.semantic.type.PageType;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.RefType;
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
   /** The statuses the Spring Boot target can report for the failure kinds this slice implements. */
   private static final Set<Integer> ALLOWED_ERROR_STATUSES = Set.of(400, 404, 409);

   private final AstSoftware software;
   private final String softwareName;
   private final ResolvedContext resolved;
   private final Map<AstNodeId, SirType> expressionTypes = new LinkedHashMap<>();
   private final List<Diagnostic> diagnostics = new ArrayList<>();
   private final Map<SymbolId, SirType> entityIdentityTypes = new LinkedHashMap<>();
   private final Map<SymbolId, DeclaredType> declaredTypesBySymbolId = new LinkedHashMap<>();
   /** View symbol -> the entity it projects, for the return-projection rule. */
   private final Map<SymbolId, SymbolId> viewSourceEntities = new LinkedHashMap<>();

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

      for (AstDeclaration decl : this.software.declarations()) {
         if (decl instanceof AstViewDecl view) {
            SymbolId viewId = this.resolved.declarationBindings().get(view.id());
            if (viewId != null) {
               this.resolved.referenceSiteBindings().targetFor(view.sourceEntity().id())
                  .ifPresent(source -> this.viewSourceEntities.put(viewId, source));
            }
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
         SymbolId declarationId = this.resolved.declarationBindings().get(decl.id());
         if (declarationId == null) {
            continue;
         }

         switch (decl) {
            case AstEntityDecl entity -> {
               this.typeFieldConstraints(entity.fields(), declarationId);
               this.typeEntityVersionField(entity);
            }
            case AstInputDecl input -> {
               this.typeFieldConstraints(input.fields(), declarationId);
               this.typePatchFields(input);
            }
         case AstViewDecl view -> this.typeViewFields(view);
            case AstErrorDecl error -> this.typeErrorStatus(error);
            case AstCapabilityDecl capability -> this.typeCapabilityWorkflow(capability);
            default -> {
            }
         }
      }

      return TypedContext.from(this.resolved, this.expressionTypes, List.copyOf(this.diagnostics));
   }

   private void typeFieldConstraints(List<AstField> fields, SymbolId scopeId) {
      for (AstField field : fields) {
         field.constraints().forEach(constraint -> constraint.arguments()
            .forEach(argument -> this.typeExpression(argument, scopeId, Set.of())));
      }
   }

   /**
     * A projected field must declare exactly the type of the entity field it reads. Anything else
     * would let the response contract drift away from the column it claims to project.
     */
   private void typeViewFields(AstViewDecl view) {
       for (AstViewField field : view.fields()) {
          SirType declaredType = this.resolved.typeRefTypes().get(field.type().id());
          SymbolId boundFieldId = this.resolved.referenceSiteBindings().targetFor(field.name().id()).orElse(null);
          Symbol boundField = boundFieldId == null ? null : this.resolved.symbols().byId(boundFieldId).orElse(null);
          if (declaredType == null || !(boundField instanceof Symbol.FieldSymbol fieldSymbol)) {
             continue;
          }

          if (fieldSymbol.type() == null || !declaredType.equals(fieldSymbol.type())) {
             this.diagnostics.add(DiagnosticBuilder.error(
            "SIR-TYPE-001",
            "投影字段类型与实体字段不一致: " + view.name().text() + "." + field.name().text() + " 声明为 " + declaredType
                   + " 但实体字段为 " + fieldSymbol.type(),
            field.type().span()));
          }
       }
   }

   /**
   * The version field is the concurrency token, so only an integer can carry it: a
   * version that could be absent, hold a list, or point at another entity would make
   * "compare and increment" undecidable for the target.
   */
   private void typeEntityVersionField(AstEntityDecl entity) {
      for (AstField field : entity.fields()) {
         if (!field.versioned()) {
            continue;
         }

         SirType declaredType = this.resolved.typeRefTypes().get(field.type().id());
         if (declaredType != null && declaredType != PrimitiveType.INT64) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-TYPE-001",
               "version 字段必须是 Int64: " + entity.name().text() + "." + field.name().text() + " 声明为 " + declaredType,
               field.type().span()
            ));
         }
      }
   }

   /**
   * A patch payload field must declare exactly the type of the entity member it applies
   * to; otherwise a request could carry a value the target cannot store.
   */
   private void typePatchFields(AstInputDecl input) {
      if (input.patchSourceEntity().isEmpty()) {
         return;
      }

      for (AstField field : input.fields()) {
         SirType declaredType = this.resolved.typeRefTypes().get(field.type().id());
         SymbolId boundFieldId = this.patchedMemberOf(input, field);
         Symbol boundField = boundFieldId == null ? null : this.resolved.symbols().byId(boundFieldId).orElse(null);
         if (declaredType == null || !(boundField instanceof Symbol.FieldSymbol fieldSymbol)) {
            continue;
         }

         if (fieldSymbol.type() == null || !declaredType.equals(fieldSymbol.type())) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-TYPE-001",
               "patch 字段类型与实体字段不一致: " + input.name().text() + "." + field.name().text() + " 声明为 " + declaredType
                  + " 但实体字段为 " + fieldSymbol.type(),
               field.type().span()
            ));
         }
      }
   }

   /**
   * The entity member a patch payload field applies to, resolved once by the resolver.
   */
   private SymbolId patchedMemberOf(AstInputDecl input, AstField field) {
      SymbolId inputFieldId = SymbolIdFactory.inputField(
         this.softwareName, input.name().text(), field.name().text());
      return this.resolved.patchFieldBindings().get(inputFieldId);
   }

   /**
   * A declared error may only report a status the target can produce for the failure
   * kinds this slice implements. Anything else would be a promise the generated
   * application cannot keep, so it is rejected here rather than rendered.
   */
   private void typeErrorStatus(AstErrorDecl error) {
      error.httpStatus().ifPresent(status -> {
         Integer value = this.statusOrNull(status);
         if (value == null || !ALLOWED_ERROR_STATUSES.contains(value)) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-TYPE-001",
               "error 状态码只允许 400/404/409: " + error.name().text() + " 声明为 " + status,
               error.span()
            ));
         }
      });
   }

   private Integer statusOrNull(java.math.BigInteger status) {
      try {
         return status.intValueExact();
      } catch (ArithmeticException e) {
         return null;
      }
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
       this.typePageOutput(capDecl, outputType);

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

               s.order().ifPresent(order -> order.keys().forEach(this::typeOrderKey));
               s.page().ifPresent(page -> this.typePageClause(page, capScopeId, visibleVars));
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
                  } else if (!TypeRules.isAssignable(valueType, outputType) && !this.isProjectionReturn(valueType, outputType)) {
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

   /**
   * Whether returning this value satisfies a projected output.
   *
   * <p>A write capability normally answers with the projection it declares, but the entity a
   * handler holds is a {@code Ref<Entity>}, not a view. Returning that entity is therefore legal
   * exactly when the declared output is a view of that same entity; the target then renders the
   * projection from the returned entity. Any other mismatch stays a type error.
   */
   private boolean isProjectionReturn(SirType valueType, SirType outputType) {
      if (!(outputType instanceof DeclaredType view) || view.kind() != DeclaredType.DeclaredKind.VIEW) {
         return false;
      }

      if (!(valueType instanceof RefType ref)) {
         return false;
      }

      SymbolId sourceEntity = this.viewSourceEntities.get(view.symbolId());
      return sourceEntity != null && sourceEntity.equals(ref.entityId());
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
         case AstPresentExpression e -> {
            this.typeExpression(e.target(), scopeId, visibleVars);
            yield PrimitiveType.BOOLEAN;
         }
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

   private void typePageOutput(AstCapabilityDecl capDecl, SirType outputType) {
       if (outputType instanceof PageType page && !(page.element() instanceof DeclaredType element && element.kind() == DeclaredType.DeclaredKind.VIEW)) {
          this.diagnostics.add(DiagnosticBuilder.error(
             "SIR-TYPE-001", "Page 的元素类型必须是 view: " + page.element(), capDecl.output().type().span()));
       }
   }

   /**
     * An order key must name a field the database can order by. Nullable comparable columns are
     * allowed because SQL orders NULLs deterministically; everything else is rejected here rather
     * than left for the target to guess.
     */
   private void typeOrderKey(AstFindOrderKey key) {
       SirType fieldType = this.boundFieldType(key.field().id());
       if (fieldType == null) {
          return;
       }

       SirType ordered = fieldType instanceof OptionalType optional ? optional.element() : fieldType;
       if (!TypeRules.isComparable(ordered)) {
          this.diagnostics.add(DiagnosticBuilder.error(
             "SIR-TYPE-001", "排序字段类型不可比较: " + key.field().text() + " (" + fieldType + ")", key.field().span()));
       }
   }

   private void typePageClause(AstFindPage page, SymbolId capScopeId, Set<String> visibleVars) {
       this.typePageSource(page.page(), capScopeId, visibleVars, "page");
       this.typePageSource(page.size(), capScopeId, visibleVars, "size");
   }

   private void typePageSource(AstExpression source, SymbolId capScopeId, Set<String> visibleVars, String role) {
       SirType type = this.typeExpression(source, capScopeId, visibleVars);
       if (type != null && type != PrimitiveType.INT32) {
          this.diagnostics.add(DiagnosticBuilder.error(
             "SIR-TYPE-001", "分页参数 " + role + " 必须是 Int32", source.span()));
       }
   }

   private SirType boundFieldType(AstNodeId referenceSiteId) {
       SymbolId fieldId = this.resolved.referenceSiteBindings().targetFor(referenceSiteId).orElse(null);
       if (fieldId == null) {
          return null;
       }

       Symbol field = this.resolved.symbols().byId(fieldId).orElse(null);
       return field instanceof Symbol.FieldSymbol fieldSymbol ? fieldSymbol.type() : null;
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
               case CONTAINS_LITERAL:
                  SirType matchable = leftType instanceof OptionalType optional ? optional.element() : leftType;
                  if (matchable != PrimitiveType.STRING || rightType != PrimitiveType.STRING) {
                     this.diagnostics.add(DiagnosticBuilder.error(
                  "SIR-TYPE-001", "containsLiteral 的左侧必须是 String 字段，右侧必须是 String 值", expr.span()));
                     return null;
                  }

                  return PrimitiveType.BOOLEAN;
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
