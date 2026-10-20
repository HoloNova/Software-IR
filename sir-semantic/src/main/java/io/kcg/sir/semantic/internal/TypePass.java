package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.ast.AstAnyExpression;
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
import io.kcg.sir.ast.AstNameRef;
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
import io.kcg.sir.ast.AstTimeShiftExpression;
import io.kcg.sir.ast.AstTimeUnit;
import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.ast.AstUnaryExpression;
import io.kcg.sir.ast.AstUnaryOperator;
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
import io.kcg.sir.semantic.type.ListType;
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
   private static final Set<Integer> ALLOWED_ERROR_STATUSES = Set.of(400, 401, 403, 404, 409);

   private final AstSoftware software;
   private final String softwareName;
   private final ResolvedContext resolved;
   private final Map<AstNodeId, SirType> expressionTypes = new LinkedHashMap<>();
   private final List<Diagnostic> diagnostics = new ArrayList<>();
   /** Every integer literal typed, to check its range once its width is settled. */
   private final List<AstIntegerLiteral> integerLiterals = new ArrayList<>();
   private final Map<SymbolId, SirType> entityIdentityTypes = new LinkedHashMap<>();
   /** Entity field symbol -> its declared type, for the value a keyed load compares the field with. */
   private final Map<SymbolId, SirType> entityFieldTypes = new LinkedHashMap<>();
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

            for (AstField field : entity.fields()) {
               SymbolId fieldId = this.resolved.referenceBindings().get(field.id());
               SirType fieldType = this.resolved.typeRefTypes().get(field.type().id());
               if (fieldId != null && fieldType != null) {
                  this.entityFieldTypes.put(fieldId, fieldType);
               }
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
               this.typeEntityUniqueField(entity);
               this.typeEntitySecretField(entity);
               this.typeEntityDefaults(entity, declarationId);
               entity.scope().ifPresent(scope -> {
                  SirType scopeType = this.typeExpression(scope, declarationId, Set.of());
                  if (scopeType != null && !TypeRules.isBoolean(scopeType)) {
                     this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "scope 条件必须是 Boolean: " + entity.name().text(), scope.span()));
                  }
               });
               this.typeEntityCredential(entity);
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

      this.checkIntegerLiteralRanges();
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

         DeclaredType nestedView = this.nestedViewType(declaredType);
         if (nestedView != null) {
            SymbolId relationTarget = fieldSymbol.type() instanceof RefType ref ? ref.entityId() : null;
            SymbolId expected = declaredType instanceof ListType
               ? this.viewSourceEntities.get(this.resolved.declarationBindings().get(view.id()))
               : this.viewSourceEntities.get(nestedView.symbolId());
            if (relationTarget == null || !relationTarget.equals(expected)) {
               this.diagnostics.add(DiagnosticBuilder.error(
                  "SIR-TYPE-001",
                  "关联投影的关系字段必须指向投影的来源实体: " + view.name().text() + "." + field.name().text(),
                  field.span()));
            }

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
   * The view a projected field nests, or {@code null} for a column projection.
   *
   * <p>A relation projection is not the entity's own value, so the equality rule that keeps a
   * projected column honest does not apply to it; what must hold instead is that the reference it
   * reads points at the entity the nested view projects.
   */
   private DeclaredType nestedViewType(SirType declaredType) {
      SirType candidate = declaredType instanceof ListType list ? list.element() : declaredType;
      if (candidate instanceof DeclaredType declared && declared.kind() == DeclaredType.DeclaredKind.VIEW) {
         return declared;
      }

      return null;
   }

   /**
   * The existence predicate's rules: the conditions must be a Boolean expression, and they must
   * connect the related entity back to the queried root.
   *
   * <p>The connection is not an optimisation detail. Without it the predicate would ask about the
   * whole table instead of the current row, and the answer would still type-check, so the missing
   * connection is rejected here rather than lowered into a query that silently means something
   * else.
   */
   private SirType typeAnyExpression(AstAnyExpression expr, SymbolId scopeId, Set<String> visibleVars) {
      SirType conditionType = this.typeExpression(expr.conditions(), scopeId, visibleVars);
      if (conditionType != null && !TypeRules.isBoolean(conditionType)) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "any 的条件必须是 Boolean", expr.conditions().span()));
      }

      SymbolId sourceEntity = this.resolved.referenceSiteBindings().targetFor(expr.entity().id()).orElse(null);
      if (sourceEntity != null) {
         SymbolId connectionField = ExistenceConnection.fieldOf(
            expr.conditions(), this.resolved.referenceSiteBindings(), this.resolved.findItemBindings());
         if (connectionField == null || !this.referencesItemEntity(connectionField, expr)) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-TYPE-004", "any 的条件必须包含与根实体的连接比较: <Ref 字段> == item", expr.span()));
         }
      }

      return PrimitiveType.BOOLEAN;
   }

   /**
   * Whether the connection field really reads the entity the {@code find} item stands for.
   *
   * <p>The comparison must relate the two entities the predicate joins; a reference field that
   * happens to be compared with the item but points somewhere else would produce a subquery that
   * joins on the wrong key.
   */
   private boolean referencesItemEntity(SymbolId connectionField, AstAnyExpression expr) {
      Symbol field = this.resolved.symbols().byId(connectionField).orElse(null);
      if (!(field instanceof Symbol.FieldSymbol fieldSymbol) || !(fieldSymbol.type() instanceof RefType fieldRef)) {
         return false;
      }

      return this.resolved.findItemBindings().values().stream()
         .map(this.resolved.symbols()::byId)
         .flatMap(java.util.Optional::stream)
         .filter(Symbol.VariableSymbol.class::isInstance)
         .map(Symbol.VariableSymbol.class::cast)
         .filter(variable -> variable.type() instanceof RefType)
         .map(variable -> ((RefType) variable.type()).entityId())
         .anyMatch(fieldRef.entityId()::equals);
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
   * What a credential entity's rows must be able to carry: a token nobody can guess (a uuid key), the
   * subject it belongs to (a reference to another entity's row) and the moment it expires. The two named
   * members must be fields, not the key, and only their types are checked here; that every
   * subject has one credential entity and how the capabilities use it is the validator's business.
   */
   private void typeEntityCredential(AstEntityDecl entity) {
      entity.credential().ifPresent(credential -> {
         SirType identityType = this.resolved.typeRefTypes().get(entity.identity().type().id());
         if (identityType != null && identityType != PrimitiveType.UUID) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-TYPE-001", "凭据实体的主键必须是 Uuid（令牌不可猜测）: " + entity.name().text(), entity.identity().type().span()));
         }

         this.typeCredentialMember(entity, credential.subject(), "subject", "引用其他实体的字段（Ref<实体>）", type -> type instanceof RefType);
         this.typeCredentialMember(entity, credential.expires(), "expires", "DateTime 字段", type -> type == PrimitiveType.DATE_TIME);
      });
   }

   private void typeCredentialMember(
      AstEntityDecl entity, AstNameRef member, String role, String expectation, java.util.function.Predicate<SirType> fits
   ) {
      if (member.text().equals(entity.identity().name().text())) {
         this.diagnostics.add(DiagnosticBuilder.error(
            "SIR-TYPE-001", role + " 必须是" + expectation + "，不能是主键: " + member.text(), member.span()));
         return;
      }

      for (AstField field : entity.fields()) {
         if (field.name().text().equals(member.text())) {
            SirType declared = this.resolved.typeRefTypes().get(field.type().id());
            if (declared != null && !fits.test(declared)) {
               this.diagnostics.add(DiagnosticBuilder.error(
                  "SIR-TYPE-001", role + " 必须是" + expectation + ": " + member.text() + " 声明为 " + declared, member.span()));
            }
         }
      }
   }

   /**
   * A secret is kept as a digest of a text the author never sees again, so only text can be one.
   * Whether its other declarations make sense (unique, constraints) is the validator's business.
   */
   private void typeEntityDefaults(AstEntityDecl entity, SymbolId scopeId) {
      for (AstField field : entity.fields()) {
         if (field.defaultValue().isEmpty()) {
            continue;
         }

         SirType valueType = this.typeExpression(field.defaultValue().get(), scopeId, Set.of());
         SirType declaredType = this.resolved.typeRefTypes().get(field.type().id());
         if (valueType != null && declaredType != null && !TypeRules.isAssignable(valueType, declaredType)) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-TYPE-001", "default 类型不匹配: " + entity.name().text() + "." + field.name().text(), field.defaultValue().get().span()));
         }
      }
   }

   private void typeEntitySecretField(AstEntityDecl entity) {
      for (AstField field : entity.fields()) {
         if (!field.secret()) {
            continue;
         }

         SirType declaredType = this.resolved.typeRefTypes().get(field.type().id());
         if (declaredType != null && declaredType != PrimitiveType.STRING) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-TYPE-001",
               "secret 字段必须是 String: " + entity.name().text() + "." + field.name().text() + " 声明为 " + declaredType,
               field.type().span()));
         }
      }
   }

   /**
   * A unique field is looked up by equality, so its type must have one value per row and an equality
   * that means the same thing in the language, in Java and in SQL: text, the two integers and the
   * uuid. An absent value is no value to look up (and SQL lets many rows hold none), a decimal's scale
   * and a date-time's precision make equal values unequal, and the rest are not keys.
   */
   private void typeEntityUniqueField(AstEntityDecl entity) {
      for (AstField field : entity.fields()) {
         if (!field.unique()) {
            continue;
         }

         SirType declaredType = this.resolved.typeRefTypes().get(field.type().id());
         boolean keyType = declaredType == PrimitiveType.STRING || declaredType == PrimitiveType.INT32
            || declaredType == PrimitiveType.INT64 || declaredType == PrimitiveType.UUID;
         if (declaredType != null && !keyType) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-TYPE-001",
               "unique 字段必须是 String、Int32、Int64 或 Uuid: " + entity.name().text() + "." + field.name().text() + " 声明为 " + declaredType,
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
               "error 状态码只允许 400/401/403/404/409: " + error.name().text() + " 声明为 " + status,
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
      // The resolver already decided this capability's identity; typing only re-derives the scopes it
      // typed expressions under, so a declared id must be read from the binding and not from the name.
      SymbolId capScopeId = this.resolved.declarationBindings().get(capDecl.id());
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
               if (s.keyField().isPresent()) {
                  // The key field is bound by Resolve; one that did not bind was already reported there.
                  SymbolId keyId = this.resolved.referenceSiteBindings().targetFor(s.keyField().orElseThrow().id()).orElse(null);
                  SirType keyType = keyId == null ? null : this.entityFieldTypes.get(keyId);
                  if (idType != null && keyType != null && !TypeRules.isAssignable(idType, keyType)) {
                     this.diagnostics.add(DiagnosticBuilder.error(
                        "SIR-TYPE-001", "load 的键表达式类型与字段 " + s.keyField().orElseThrow().text() + " 不兼容", s.idExpression().span()));
                  }
               } else {
                  SirType identityType = entityId == null ? null : this.entityIdentityTypes.get(entityId);
                  // A reference to this very entity stores its identity, so it names the row as well as the identity does.
                  boolean referenceToIt = idType instanceof RefType reference && reference.entityId().equals(entityId);
                  if (idType != null && identityType != null && !referenceToIt && !TypeRules.isAssignable(idType, identityType)) {
                     this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "load 的 id 表达式类型与 Entity identity 不兼容", s.idExpression().span()));
                  }
               }

               visibleVars.add(s.result().text());
               break;
            case AstFindStep s:
               SymbolId stepScopeId = SymbolIdFactory.capabilityStepScope(capScopeId, s.id().value());
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
            case io.kcg.sir.ast.AstScalarQueryStep s:
               Set<String> queryVars = new LinkedHashSet<>(visibleVars);
               queryVars.add("item");
               SirType queryType = this.typeExpression(s.predicate(), SymbolIdFactory.capabilityStepScope(capScopeId, s.id().value()), queryVars);
               if (queryType != null && !TypeRules.isBoolean(queryType)) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "查询谓词必须是 Boolean", s.predicate().span()));
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
               s.guard().ifPresent(guard -> {
                  SirType guardType = this.typeExpression(guard, capScopeId, visibleVars);
                  if (guardType != null && !TypeRules.isBoolean(guardType)) {
                     this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "update 的守卫条件必须是 Boolean", guard.span()));
                  }
               });
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
            case io.kcg.sir.ast.AstDeleteStep s:
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
         case AstIntegerLiteral e -> {
            this.integerLiterals.add(e);
            yield PrimitiveType.INT32;
         }
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
          case AstAnyExpression e -> this.typeAnyExpression(e, scopeId, visibleVars);
         case AstUnaryExpression e -> this.typeUnaryExpression(e, scopeId, visibleVars);
         case AstBinaryExpression e -> this.typeBinaryExpression(e, scopeId, visibleVars);
         case AstTimeShiftExpression e -> this.typeTimeShift(e, scopeId, visibleVars);
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
      }

      Symbol found = this.resolved.symbols().byId(symbolIdOpt.get()).orElse(null);
      if (found instanceof Symbol.VariableSymbol vs) {
         return vs.type();
      }

      if (found instanceof Symbol.TypeSymbol ts && ts.kind() == SymbolKind.ENUM) {
         // An enum is a type of its own even where no type reference names it, so a member of an enum no field
         // declares is still told apart from every other enum's.
         return this.declaredTypesBySymbolId.computeIfAbsent(ts.id(), id -> new DeclaredType(DeclaredType.DeclaredKind.ENUM, ts.name(), id));
      }

      // A bare name can also be a field of the entity an existence predicate refers to; that is the
      // one place in the language where a field is named as an expression rather than reached
      // through a receiver.
      return found instanceof Symbol.FieldSymbol field ? field.type() : null;
   }

   private boolean isComparison(AstBinaryOperator operator) {
      return switch (operator) {
         case EQ, NE, GT, GE, LT, LE -> true;
         default -> false;
      };
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
     * than left for the target to guess. A uuid has no meaningful order, but it has a stable one, and a page of rows
     * whose only unique key is a uuid needs a stable order; so a uuid may be an order key though it cannot be compared.
     */
   private void typeOrderKey(AstFindOrderKey key) {
       SirType fieldType = this.boundFieldType(key.field().id());
       if (fieldType == null) {
          return;
       }

       SirType ordered = fieldType instanceof OptionalType optional ? optional.element() : fieldType;
       if (!TypeRules.isComparable(ordered) && ordered != PrimitiveType.UUID) {
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
          case IS_NONE:
          case IS_PRESENT:
             if (!(operandType instanceof OptionalType)) {
                this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "is none/is present 只用于可选值", expr.span()));
                return null;
             }
             return PrimitiveType.BOOLEAN;
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

   /** The largest amount a time shift may state: what a 32-bit signed integer holds. */
   private static final java.math.BigInteger MAX_SHIFT_AMOUNT = java.math.BigInteger.valueOf(Integer.MAX_VALUE);

   /**
   * A time shift keeps the type of what it moves. A date can only move by whole days, because a date
   * has no time of day to move by hours; a date-time moves by any unit.
   */
   private SirType typeTimeShift(AstTimeShiftExpression expr, SymbolId scopeId, Set<String> visibleVars) {
      SirType baseType = this.typeExpression(expr.base(), scopeId, visibleVars);
      if (baseType == null) {
         return null;
      }

      if (expr.amount().compareTo(MAX_SHIFT_AMOUNT) > 0) {
         this.diagnostics.add(DiagnosticBuilder.error(
            "SIR-TYPE-001", "时间平移的数值不能大于 " + MAX_SHIFT_AMOUNT, expr.span()));
         return null;
      }

      if (baseType == PrimitiveType.DATE_TIME) {
         return baseType;
      }

      if (baseType == PrimitiveType.DATE) {
         if (expr.unit() != AstTimeUnit.DAYS) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "Date 只能按天平移", expr.span()));
            return null;
         }

         return baseType;
      }

      this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "只有 Date 和 DateTime 可以平移: " + baseType, expr.span()));
      return null;
   }


   private SirType typeBinaryExpression(AstBinaryExpression expr, SymbolId scopeId, Set<String> visibleVars) {
      SirType leftType = this.typeExpression(expr.left(), scopeId, visibleVars);
      SirType rightType = this.typeExpression(expr.right(), scopeId, visibleVars);
       // Comparisons operate on compatible values; absence is carried through to the target's explicit plan.
       // Arithmetic deliberately does not unwrap optional values.
       if (this.isComparison(expr.operator())) {
          if (leftType instanceof OptionalType optional) leftType = optional.element();
          if (rightType instanceof OptionalType optional) rightType = optional.element();
       }
       if (leftType != null && rightType != null && this.mixesInt32AndInt64(expr.operator())) {
         if (leftType == PrimitiveType.INT32 && rightType == PrimitiveType.INT64 && this.widenIntegerLiteral(expr.left())) {
            leftType = PrimitiveType.INT64;
         } else if (leftType == PrimitiveType.INT64 && rightType == PrimitiveType.INT32 && this.widenIntegerLiteral(expr.right())) {
            rightType = PrimitiveType.INT64;
         }
      }


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
            case MATCHES:
               if (leftType != PrimitiveType.STRING || rightType != PrimitiveType.STRING) {
                  this.diagnostics.add(DiagnosticBuilder.error(
                     "SIR-TYPE-001", "matches 的两侧都必须是 String", expr.span()));
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
            case ADD:
            case SUB:
               if (!leftType.equals(rightType) || !TypeRules.isNumeric(leftType)) {
                  this.diagnostics.add(DiagnosticBuilder.error(
                     "SIR-TYPE-001", "加减的两侧必须是同一种数值类型（Int32、Int64 或 Decimal）", expr.span()));
                  return null;
               }

               return leftType;
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
   /**
   * The generated code writes a literal as written, so one that does not fit its type would only fail
   * when the generated project is compiled. The width is settled by now (an Int32 unless the other
   * operand made it an Int64), so the check is made once, here, against that. A minus sign is written
   * in front of the literal, so the most negative value of a type cannot be written as a literal.
   */
   private void checkIntegerLiteralRanges() {
      for (AstIntegerLiteral literal : this.integerLiterals) {
         boolean wide = this.expressionTypes.get(literal.id()) == PrimitiveType.INT64;
         int bits = wide ? 63 : 31;
         java.math.BigInteger largest = java.math.BigInteger.ONE.shiftLeft(bits).subtract(java.math.BigInteger.ONE);
         if (literal.value().compareTo(largest) > 0) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-TYPE-001",
               "整数字面量超出 " + (wide ? "Int64" : "Int32") + " 的范围: " + literal.value()
                  + (wide ? "" : "（与 Int64 的值比较或运算时，字面量按 Int64 理解）"),
               literal.span()));
         }
      }
   }

   private boolean mixesInt32AndInt64(AstBinaryOperator operator) {
      return switch (operator) {
         case EQ, NE, GT, GE, LT, LE, ADD, SUB -> true;
         default -> false;
      };
   }

   /**
   * An integer literal has no width of its own: it is an Int32 unless the other operand is an Int64, in
   * which case it is read as an Int64 (so {@code item.qty > 0} and {@code input.amount - 1} need no
   * conversion). Only a literal does this, optionally negated or in parentheses; an Int32 field or input
   * is still not an Int64. Returns whether the operand was such a literal and has been retyped.
   */
   private boolean widenIntegerLiteral(AstExpression operand) {
      AstExpression inner = operand;
      List<AstExpression> chain = new ArrayList<>();
      while (true) {
         chain.add(inner);
         if (inner instanceof AstGroupedExpression grouped) {
            inner = grouped.inner();
         } else if (inner instanceof AstUnaryExpression unary && unary.operator() == AstUnaryOperator.NEGATE) {
            inner = unary.operand();
         } else {
            break;
         }
      }

      if (!(inner instanceof AstIntegerLiteral)) {
         return false;
      }

      chain.forEach(node -> this.expressionTypes.put(node.id(), PrimitiveType.INT64));
      return true;
   }
}
