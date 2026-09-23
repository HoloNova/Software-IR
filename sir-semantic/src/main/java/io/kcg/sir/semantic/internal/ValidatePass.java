package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.RelatedLocation;
import io.kcg.sir.ast.AstAnyExpression;
import io.kcg.sir.ast.AstBinding;
import io.kcg.sir.ast.AstBinaryExpression;
import io.kcg.sir.ast.AstBinaryOperator;
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
import io.kcg.sir.ast.AstFindOrderKey;
import io.kcg.sir.ast.AstFindPage;
import io.kcg.sir.ast.AstFindStep;
import io.kcg.sir.ast.AstGroupedExpression;
import io.kcg.sir.ast.AstInputDecl;
import io.kcg.sir.ast.AstIntegerLiteral;
import io.kcg.sir.ast.AstLoadStep;
import io.kcg.sir.ast.AstMemberExpression;
import io.kcg.sir.ast.AstNameExpression;
import io.kcg.sir.ast.AstNameRef;
import io.kcg.sir.ast.AstPersistStep;
import io.kcg.sir.ast.AstPresentExpression;
import io.kcg.sir.ast.AstRequirementKind;
import io.kcg.sir.ast.AstRequiresClause;
import io.kcg.sir.ast.AstReturnStep;
import io.kcg.sir.ast.AstSoftware;
import io.kcg.sir.ast.AstStep;
import io.kcg.sir.ast.AstTypeRef;
import io.kcg.sir.ast.AstUnaryExpression;
import io.kcg.sir.ast.AstUnaryOperator;
import io.kcg.sir.ast.AstUpdateStep;
import io.kcg.sir.ast.AstValidateStep;
import io.kcg.sir.ast.AstViewDecl;
import io.kcg.sir.ast.AstViewField;
import io.kcg.sir.semantic.context.TypedContext;
import io.kcg.sir.semantic.context.ValidatedContext;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.DeclaredType;
import io.kcg.sir.semantic.type.ListType;
import io.kcg.sir.semantic.type.OptionalType;
import io.kcg.sir.semantic.type.PageType;
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
   /** Levels of nested projection allowed below the root view; see {@link #validateProjectionDepth}. */
   private static final int PROJECTION_DEPTH_LIMIT = 2;

   private final AstSoftware software;
   private final TypedContext typed;
   private final String softwareName;
   private final List<Diagnostic> diagnostics = new ArrayList<>();
   private final Map<SymbolId, Map<SymbolId, SirType>> entityFieldTypes = new LinkedHashMap<>();
   private final Map<SymbolId, SymbolId> entityIdentitySymbolIds = new LinkedHashMap<>();
   private final Map<SymbolId, Set<SymbolId>> capabilityFails = new LinkedHashMap<>();
   private final Set<SymbolId> declaredEntities = new LinkedHashSet<>();
   private final Map<SymbolId, AstViewDecl> viewDecls = new LinkedHashMap<>();
   private final Map<SymbolId, SymbolId> viewSourceEntities = new LinkedHashMap<>();
   /** Entity symbol -> its version field, for entities that declare one. */
   private final Map<SymbolId, SymbolId> entityVersionFields = new LinkedHashMap<>();
   /** Patch payload symbol -> the entity it changes. */
   private final Map<SymbolId, SymbolId> patchInputSourceEntities = new LinkedHashMap<>();
   /** AST input declarations by symbol, for the payload-shape rules. */
   private final Map<SymbolId, AstInputDecl> inputDeclarations = new LinkedHashMap<>();

   ValidatePass(AstSoftware software, TypedContext typed) {
      this.software = software;
      this.softwareName = software.name().text();
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

                     if (f.versioned() && fieldSymId != null) {
                        this.entityVersionFields.put(declSymId, fieldSymId);
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
               case AstViewDecl var17:
                  AstViewDecl viewDecl = (AstViewDecl)var4;
                  this.viewDecls.put(declSymId, viewDecl);
                  this.typed.referenceSiteBindings().targetFor(viewDecl.sourceEntity().id())
                     .ifPresent(source -> this.viewSourceEntities.put(declSymId, source));
                  continue;
               case AstInputDecl var18:
                  AstInputDecl inputDecl = (AstInputDecl)var4;
                  if (inputDecl.patchSourceEntity().isEmpty()) {
                     continue;
                  }

                  SymbolId patchSource = this.typed.referenceSiteBindings()
                     .targetFor(inputDecl.patchSourceEntity().orElseThrow().id())
                     .orElse(null);
                  if (patchSource == null) {
                     continue;
                  }

                  this.patchInputSourceEntities.put(declSymId, patchSource);
                  continue;
               default:
            }
         }
      }
   }

   ValidatedContext run() {
      // Payload shapes are consulted while validating capabilities, so the input declarations must be
      // indexed before that pass rather than alongside the declaration checks below.
      for (AstDeclaration decl : this.software.declarations()) {
         if (decl instanceof AstInputDecl inputDecl && this.typed.declarationBindings().containsKey(inputDecl.id())) {
            this.inputDeclarations.put(this.typed.declarationBindings().get(inputDecl.id()), inputDecl);
         }
      }

      for (AstDeclaration decl : this.software.declarations()) {
         if (decl instanceof AstCapabilityDecl capDecl && this.typed.declarationBindings().containsKey(capDecl.id())) {
            this.validateCapability(capDecl);
         }
      }

      for (AstDeclaration decl : this.software.declarations()) {
         if (this.typed.declarationBindings().containsKey(decl.id())) {
            if (decl instanceof AstEntityDecl entityDecl) {
               this.validateEntityConstraints(entityDecl);
               this.validateEntityVersionField(entityDecl);
            } else if (decl instanceof AstInputDecl inputDecl) {
               this.validateInputConstraints(inputDecl);
               this.validatePatchPayload(inputDecl);
         } else if (decl instanceof AstViewDecl viewDecl) {
               this.validateViewFields(viewDecl);
               this.validateProjectionDepth(viewDecl);
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
      this.validatePageUsage(capDecl, isQuery);
      this.validateWriteWorkflow(capDecl, failsSet);
      this.validateExistencePlacement(capDecl);
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
               s.failure().ifPresent(failure -> {
                  Optional<SymbolId> failureSymId = this.typed.referenceSiteBindings().targetFor(failure.id());
                  if (failureSymId.isPresent() && failsSet != null && !failsSet.contains(failureSymId.get())) {
                     this.diagnostics.add(DiagnosticBuilder.error(
                        "SIR-FLOW-003", "persist 引用未声明的 Error: " + failure.text(), failure.span()));
                  }
               });
               continue;
         case AstFindStep s:
               this.validateFindStep(s, capDecl, failsSet);
               break;
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

   /**
     * {@code Page<T>} is a response contract for read-only queries; the element rule itself (the
     * element must be a declared projection) belongs to {@code TypePass} and is reported as
     * {@code SIR-TYPE-001}. This method only owns the exposure rule, so that a paged envelope can
     * never leak into a write command.
     */
   private void validatePageUsage(AstCapabilityDecl capDecl, boolean isQuery) {
       SirType outputType = this.typed.typeRefTypes().get(capDecl.output().type().id());
       if (outputType instanceof PageType && !isQuery) {
          this.diagnostics.add(DiagnosticBuilder.error(
             "SIR-VALID-001", "Page 输出只允许用于 expose query 的能力", capDecl.exposure().span()));
       }
   }

   private void validateFindStep(AstFindStep step, AstCapabilityDecl capDecl, Set<SymbolId> failsSet) {
      step.page().ifPresent(page -> this.validatePageClause(step, capDecl, failsSet, page));
      step.order().ifPresent(order -> this.validateOrderKeys(order.keys()));
      this.validateStringMatchShape(step.predicate());
   }

   private void validatePageClause(AstFindStep step, AstCapabilityDecl capDecl, Set<SymbolId> failsSet, AstFindPage page) {
      SirType outputType = this.typed.typeRefTypes().get(capDecl.output().type().id());
      if (!(outputType instanceof PageType pageType)) {
         this.diagnostics.add(DiagnosticBuilder.error(
         "SIR-VALID-001", "带分页的 find 需要 Page<view> 输出", capDecl.output().type().span()));
      } else if (pageType.element() instanceof DeclaredType viewType && viewType.kind() == DeclaredType.DeclaredKind.VIEW) {
         SymbolId viewSource = this.viewSourceEntities.get(viewType.symbolId());
         SymbolId rootEntity = this.typed.referenceSiteBindings().targetFor(step.entity().id()).orElse(null);
         if (viewSource != null && rootEntity != null && !viewSource.equals(rootEntity)) {
         AstViewDecl viewDecl = this.viewDecls.get(viewType.symbolId());
         this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-001",
               "分页 find 的根实体必须是投影 " + viewType.name() + " 的来源实体",
               viewDecl == null ? step.span() : viewDecl.span()));
         }
      }

      SymbolId pageField = this.memberTarget(page.page());
      SymbolId sizeField = this.memberTarget(page.size());
      if (pageField != null && pageField.equals(sizeField)) {
         this.diagnostics.add(DiagnosticBuilder.error(
         "SIR-VALID-001", "page 与 size 必须使用两个不同的字段", page.size().span()));
      }

      SymbolId errorSymbol = this.typed.referenceSiteBindings().targetFor(page.error().id()).orElse(null);
      if (errorSymbol != null && failsSet != null && !failsSet.contains(errorSymbol)) {
         this.diagnostics.add(DiagnosticBuilder.error(
         "SIR-FLOW-003", "分页 find 引用未声明的 Error: " + page.error().text(), page.error().span()));
      }
   }

   private void validateOrderKeys(List<AstFindOrderKey> keys) {
      Set<SymbolId> seen = new LinkedHashSet<>();
      for (AstFindOrderKey key : keys) {
         SymbolId fieldSymbol = this.typed.referenceSiteBindings().targetFor(key.field().id()).orElse(null);
         if (fieldSymbol != null && !seen.add(fieldSymbol)) {
         this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-001", "排序字段重复: " + key.field().text(), key.field().span()));
         }
      }
   }

   /**
   * {@code containsLiteral} lowers to a single {@code LIKE} comparison, so its left side must be an
   * entity field of the find item and its right side must be a value. Anything else has no
   * parameterized single-statement form and is rejected before lowering.
   */
   private void validateStringMatchShape(AstExpression expression) {
      AstExpression ungrouped = this.ungroup(expression);
      if (ungrouped instanceof AstBinaryExpression binary) {
         if (binary.operator() == AstBinaryOperator.CONTAINS_LITERAL) {
         if (!(this.ungroup(binary.left()) instanceof AstMemberExpression leftMember) || !this.isItemName(leftMember.receiver())) {
               this.diagnostics.add(DiagnosticBuilder.error(
                  "SIR-VALID-001", "containsLiteral 的左侧必须是 item.<字段>", binary.span()));
         }

         if (this.ungroup(binary.right()) instanceof AstMemberExpression rightMember && this.isItemName(rightMember.receiver())) {
               this.diagnostics.add(DiagnosticBuilder.error(
                  "SIR-VALID-001", "containsLiteral 的右侧不能是实体字段", binary.span()));
         }
         }

         this.validateStringMatchShape(binary.left());
         this.validateStringMatchShape(binary.right());
      } else if (ungrouped instanceof AstUnaryExpression unary) {
         this.validateStringMatchShape(unary.operand());
      }
   }

   private boolean isItemName(AstExpression expression) {
      return this.ungroup(expression) instanceof AstNameExpression name && "item".equals(name.name().text());
   }

   private SymbolId memberTarget(AstExpression expression) {
      return this.ungroup(expression) instanceof AstMemberExpression member
         ? this.typed.referenceSiteBindings().targetFor(member.member().id()).orElse(null)
         : null;
   }

   private void validateViewFields(AstViewDecl viewDecl) {
      for (AstViewField field : viewDecl.fields()) {
         if (field.versioned()) {
         this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-002", "version 标记只允许用于实体字段: " + viewDecl.name().text() + "." + field.name().text(), field.name().span()));
         }

         for (AstConstraint constraint : field.constraints()) {
         this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-001", "响应投影字段不能声明约束: " + field.name().text(), constraint.span()));
         }
      }
   }

   /**
   * The version marker's own rules: one per entity, integer, author-unconstrained.
   *
   * <p>A second version field would leave the concurrency token ambiguous, and a
   * constraint on it would have to hold for a value the client does not author, so
   * both are rejected where they are declared.
   */
   private void validateEntityVersionField(AstEntityDecl entityDecl) {
      SymbolId entitySymId = this.typed.declarationBindings().get(entityDecl.id());
      AstField firstMarked = null;

      for (AstField field : entityDecl.fields()) {
         if (!field.versioned()) {
            continue;
         }

         if (firstMarked != null) {
            RelatedLocation related = new RelatedLocation("首次声明在此处", firstMarked.span());
            this.diagnostics.add(DiagnosticBuilder.errorWithRelated(
               "SIR-VALID-002", "一个实体最多只能有一个 version 字段: " + entityDecl.name().text(), field.span(), related));
         } else {
            firstMarked = field;
         }

         for (AstConstraint constraint : field.constraints()) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-002", "version 字段不能声明约束: " + field.name().text(), constraint.span()));
         }
      }

   }

   /**
   * Patch payload rules: which entity may be changed, which of its members the payload
   * may name, and what a payload may not promise.
   *
   * <p>Everything here is a statement about the payload as a contract. Whether a
   * capability actually respects that contract is checked by {@link #validateWriteWorkflow}.
   */
   private void validatePatchPayload(AstInputDecl inputDecl) {
      if (inputDecl.patchSourceEntity().isEmpty()) {
         for (AstField field : inputDecl.fields()) {
            if (field.versioned()) {
               this.diagnostics.add(DiagnosticBuilder.error(
                  "SIR-VALID-002", "version 标记只允许用于实体字段: " + inputDecl.name().text() + "." + field.name().text(), field.name().span()));
            }
         }

         return;
      }

      SymbolId inputSymId = this.typed.declarationBindings().get(inputDecl.id());
      SymbolId entitySymId = this.patchInputSourceEntities.get(inputSymId);
      String entityName = inputDecl.patchSourceEntity().orElseThrow().text();
      SymbolId identitySymId = entitySymId == null ? null : this.entityIdentitySymbolIds.get(entitySymId);
      SymbolId versionSymId = entitySymId == null ? null : this.entityVersionFields.get(entitySymId);
      boolean identityDeclared = false;
      int changeFieldCount = 0;

      for (AstField field : inputDecl.fields()) {
         if (field.versioned()) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-002", "version 标记只允许用于实体字段: " + inputDecl.name().text() + "." + field.name().text(), field.name().span()));
         }

         for (AstConstraint constraint : field.constraints()) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-003", "patch 字段不能声明约束（约束的权威来源是实体字段）: " + field.name().text(), constraint.span()));
         }

         SymbolId inputFieldSymId = SymbolIdFactory.inputField(this.softwareName, inputDecl.name().text(), field.name().text());
         SymbolId memberSymId = this.typed.patchFieldBindings().get(inputFieldSymId);
         if (memberSymId == null) {
            continue;
         }

         if (versionSymId != null && memberSymId.equals(versionSymId)) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-003", "patch 载荷不能声明 version 字段: " + field.name().text(), field.name().span()));
            continue;
         }

         if (identitySymId != null && memberSymId.equals(identitySymId)) {
            if (identityDeclared) {
               this.diagnostics.add(DiagnosticBuilder.error(
                  "SIR-VALID-003", "patch 载荷只能声明一次 identity 字段: " + field.name().text(), field.name().span()));
            }

            identityDeclared = true;
         } else {
            changeFieldCount++;
         }
      }

      if (!identityDeclared) {
         this.diagnostics.add(DiagnosticBuilder.error(
            "SIR-VALID-003", "patch 载荷必须声明 " + entityName + " 的 identity 字段: " + inputDecl.name().text(), inputDecl.span()));
      }

      if (changeFieldCount == 0) {
         this.diagnostics.add(DiagnosticBuilder.error(
            "SIR-VALID-003", "patch 载荷必须至少声明一个可变字段: " + inputDecl.name().text(), inputDecl.span()));
      }

      if (entitySymId != null && this.entityVersionFields.get(entitySymId) == null) {
         this.diagnostics.add(DiagnosticBuilder.error(
            "SIR-VALID-003", "patch 载荷的实体必须声明 version 字段: " + entityName, inputDecl.span()));
      }
   }

   /**
   * How a capability may use versioned entities and patch payloads.
   *
   * <p>A version can only be honoured if the request carries the expected value and the
   * update applies to the versioned entity itself, so every combination that would
   * silently skip the check is rejected here.
   */
   private void validateWriteWorkflow(AstCapabilityDecl capDecl, Set<SymbolId> failsSet) {
      SymbolId candidateInputSymId = this.capabilityInputSymbolId(capDecl);
      SymbolId patchInputSymId = candidateInputSymId != null && this.patchInputSourceEntities.containsKey(candidateInputSymId)
         ? candidateInputSymId
         : null;
      SymbolId patchInputEntity = patchInputSymId == null ? null : this.patchInputSourceEntities.get(patchInputSymId);
      AstStep lastPersist = null;
      AstStep lastUpdate = null;

      for (AstStep step : capDecl.workflow().steps()) {
         if (step instanceof AstPersistStep persist) {
            lastPersist = persist;
         } else if (step instanceof AstUpdateStep update) {
            lastUpdate = update;
         }
      }

      if (lastUpdate instanceof AstUpdateStep updateStep) {
         SymbolId updateEntity = this.updateTargetEntitySymId(updateStep);
         if (updateEntity != null && this.entityVersionFields.get(updateEntity) != null && !updateEntity.equals(patchInputEntity)) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-004",
               "versioned 实体的 update 必须使用该实体的 patch 载荷作为 input",
               capDecl.input().isPresent() ? capDecl.input().orElseThrow().span() : capDecl.span()));
         }
      }

      if (lastPersist instanceof AstPersistStep persist) {
         SymbolId targetEntity = this.persistTargetEntitySymId(persist);
         SymbolId targetVariable = this.typed.referenceSiteBindings().targetFor(persist.target().id()).orElse(null);
         // The version check only guards a conditional update: an insert always writes its row,
         // so a create-only workflow has no zero-rows outcome to report.
         boolean conditionalUpdate = targetEntity != null
            && this.entityVersionFields.get(targetEntity) != null
            && lastUpdate instanceof AstUpdateStep updateStep
            && targetVariable != null
            && targetVariable.equals(this.typed.referenceSiteBindings().targetFor(updateStep.target().id()).orElse(null));
         if (conditionalUpdate && persist.failure().isEmpty()) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-004", "versioned 实体的条件更新 persist 必须声明 else 错误", persist.span()));
         }

         if (!conditionalUpdate && persist.failure().isPresent()) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-004", "persist ... else 只适用于 versioned 实体的条件更新（插入行数恒为 1）", persist.failure().orElseThrow().span()));
         }
      }

      if (patchInputSymId != null) {
         this.validatePatchCapabilityUsage(capDecl, patchInputSymId, patchInputEntity, failsSet);
      }
   }

   private void validatePatchCapabilityUsage(
      AstCapabilityDecl capDecl, SymbolId patchInputSymId, SymbolId patchInputEntity, Set<SymbolId> failsSet
   ) {
      boolean readonly = capDecl.requirements().stream().anyMatch(r -> r.requirement() == AstRequirementKind.READONLY);
      if (readonly || capDecl.exposure().exposure() != AstExposureKind.COMMAND) {
         this.diagnostics.add(DiagnosticBuilder.error(
            "SIR-VALID-003", "patch 载荷只允许用于 expose command 的写能力", capDecl.span()));
      }

      boolean changesEntity = false;
      for (AstStep step : capDecl.workflow().steps()) {
         if (step instanceof AstUpdateStep update) {
            SymbolId updateEntity = this.updateTargetEntitySymId(update);
            if (updateEntity != null && updateEntity.equals(patchInputEntity)) {
               changesEntity = true;
            }
         } else if (step instanceof AstPersistStep persist) {
            SymbolId persistEntity = this.persistTargetEntitySymId(persist);
            if (persistEntity != null && persistEntity.equals(patchInputEntity)) {
               changesEntity = true;
            }
         }
      }

      if (!changesEntity) {
         this.diagnostics.add(DiagnosticBuilder.error(
            "SIR-VALID-003", "patch 载荷对应的能力必须包含该实体的写步骤", capDecl.span()));
      }

      SymbolId identityMemberSymId = patchInputEntity == null ? null : this.entityIdentitySymbolIds.get(patchInputEntity);

      // Every change the payload can carry must reach the entity through an authored binding: a
      // payload field nobody binds would otherwise be accepted and silently dropped.
      Set<SymbolId> boundMembers = new LinkedHashSet<>();
      for (AstStep step : capDecl.workflow().steps()) {
         if (step instanceof AstUpdateStep update && patchInputEntity != null
            && patchInputEntity.equals(this.updateTargetEntitySymId(update))) {
            for (AstBinding binding : update.bindings()) {
               this.typed.referenceSiteBindings().targetFor(binding.fieldName().id()).ifPresent(boundMembers::add);
            }
         }
      }

      AstInputDecl patchInputDecl = this.inputDeclarations.get(patchInputSymId);
      if (patchInputDecl != null) {
         for (AstField field : patchInputDecl.fields()) {
            SymbolId payloadFieldSymId = SymbolIdFactory.inputField(
               this.softwareName, patchInputDecl.name().text(), field.name().text());
            SymbolId member = this.typed.patchFieldBindings().get(payloadFieldSymId);
            if (member == null || member.equals(identityMemberSymId) || boundMembers.contains(member)) {
               continue;
            }

            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-004", "patch 载荷字段必须在 update 步骤中被绑定: " + field.name().text(), field.name().span()));
         }
      }

      for (AstStep step : capDecl.workflow().steps()) {
         this.forEachPresentExpression(step, present -> {
            // The presence test binds the payload field; the rule is about the entity member it
            // applies to, because the identity is carried outside the change set.
            SymbolId presentField = this.typed.referenceSiteBindings().targetFor(present.id()).orElse(null);
            SymbolId presentMember = presentField == null ? null : this.typed.patchFieldBindings().get(presentField);
            if (presentMember == null || identityMemberSymId == null) {
               return;
            }

            if (presentMember.equals(identityMemberSymId)) {
               this.diagnostics.add(DiagnosticBuilder.error(
                  "SIR-VALID-004", "present 不能用于 patch 载荷的 identity 字段（identity 不在 changes 内）", present.span()));
            }
         });
      }
   }

   private SymbolId capabilityInputSymbolId(AstCapabilityDecl capDecl) {
      if (capDecl.input().isEmpty()) {
         return null;
      }

      SirType inputType = this.typed.typeRefTypes().get(capDecl.input().orElseThrow().type().id());
      if (inputType instanceof DeclaredType declared && declared.kind() == DeclaredType.DeclaredKind.INPUT) {
         return declared.symbolId();
      }

      return null;
   }

   private SymbolId updateTargetEntitySymId(AstUpdateStep step) {
      SymbolId targetVarId = this.typed.referenceSiteBindings().targetFor(step.target().id()).orElse(null);
      SymbolId identitySiteTarget = null;
      if (targetVarId != null) {
         Symbol targetSym = this.typed.symbols().byId(targetVarId).orElse(null);
         if (targetSym instanceof Symbol.VariableSymbol vs && vs.type() instanceof RefType rt) {
            identitySiteTarget = rt.entityId();
         }
      }

      return identitySiteTarget;
   }

   private SymbolId persistTargetEntitySymId(AstPersistStep step) {
      SymbolId targetVarId = this.typed.referenceSiteBindings().targetFor(step.target().id()).orElse(null);
      if (targetVarId == null) {
         return null;
      }

      Symbol targetSym = this.typed.symbols().byId(targetVarId).orElse(null);
      return targetSym instanceof Symbol.VariableSymbol vs && vs.type() instanceof RefType rt ? rt.entityId() : null;
   }

   /**
   * Walks a step's expressions looking for presence tests. Presence is a leaf question, so
   * only the expression shapes that can contain one are traversed.
   */
   private void forEachPresentExpression(AstStep step, java.util.function.Consumer<AstPresentExpression> action) {
      switch (step) {
         case AstValidateStep s -> walkPresent(s.condition(), action);
         case AstLoadStep s -> walkPresent(s.idExpression(), action);
         case AstFindStep s -> walkPresent(s.predicate(), action);
         case AstCreateStep s -> s.bindings().forEach(binding -> walkPresent(binding.value(), action));
         case AstUpdateStep s -> s.bindings().forEach(binding -> walkPresent(binding.value(), action));
         case AstReturnStep s -> walkPresent(s.value(), action);
         default -> {
         }
      }
   }

   private void walkPresent(AstExpression expression, java.util.function.Consumer<AstPresentExpression> action) {
      if (expression == null) {
         return;
      }

      switch (expression) {
         case AstPresentExpression e -> action.accept(e);
         case AstGroupedExpression e -> this.walkPresent(e.inner(), action);
         case AstUnaryExpression e -> this.walkPresent(e.operand(), action);
         case AstBinaryExpression e -> {
            this.walkPresent(e.left(), action);
            this.walkPresent(e.right(), action);
         }
         default -> {
         }
      }
   }

   /**
   * The existence predicate's placement rules: it belongs to a {@code find} predicate and it does
   * not contain another one.
   *
   * <p>Where a predicate may appear is a flow question, not a typing one: the same expression type
   * is legal in a {@code find} and meaningless in a {@code validate} or a binding, so the rule is
   * stated once per workflow instead of being repeated in every step's own validation.
   */
   private void validateExistencePlacement(AstCapabilityDecl capDecl) {
      for (AstStep step : capDecl.workflow().steps()) {
         if (step instanceof AstFindStep find) {
            this.validateExistenceUsage(find.predicate(), ExistenceUsage.ALLOWED);
            find.page().ifPresent(page -> {
               this.validateExistenceUsage(page.page(), ExistenceUsage.REJECTED);
               this.validateExistenceUsage(page.size(), ExistenceUsage.REJECTED);
            });
         } else if (step instanceof AstValidateStep validateStep) {
            this.validateExistenceUsage(validateStep.condition(), ExistenceUsage.REJECTED);
         } else if (step instanceof AstLoadStep loadStep) {
            this.validateExistenceUsage(loadStep.idExpression(), ExistenceUsage.REJECTED);
         } else if (step instanceof AstCreateStep createStep) {
            createStep.bindings().forEach(binding -> this.validateExistenceUsage(binding.value(), ExistenceUsage.REJECTED));
         } else if (step instanceof AstUpdateStep updateStep) {
            updateStep.bindings().forEach(binding -> this.validateExistenceUsage(binding.value(), ExistenceUsage.REJECTED));
         } else if (step instanceof AstReturnStep returnStep) {
            this.validateExistenceUsage(returnStep.value(), ExistenceUsage.REJECTED);
         }
      }
   }

   private void validateExistenceUsage(AstExpression expression, ExistenceUsage usage) {
      if (expression == null) {
         return;
      }

      switch (expression) {
         case AstAnyExpression any -> {
            if (usage == ExistenceUsage.REJECTED) {
               this.diagnostics.add(DiagnosticBuilder.error(
                  "SIR-FLOW-005", "any 只能出现在 find 的 where 谓词中", any.span()));
            } else if (usage == ExistenceUsage.NESTED) {
               this.diagnostics.add(DiagnosticBuilder.error(
                  "SIR-FLOW-006", "any 的条件内不能再出现 any: " + any.entity().text(), any.span()));
            }

            this.validateExistenceUsage(any.conditions(), ExistenceUsage.NESTED);
         }
         case AstGroupedExpression grouped -> this.validateExistenceUsage(grouped.inner(), usage);
         case AstUnaryExpression unary -> this.validateExistenceUsage(unary.operand(), usage);
         case AstBinaryExpression binary -> {
            this.validateExistenceUsage(binary.left(), usage);
            this.validateExistenceUsage(binary.right(), usage);
         }
         case AstMemberExpression member -> this.validateExistenceUsage(member.receiver(), usage);
         default -> {
         }
      }
   }

   /** Where an existence predicate was found while walking one workflow's expressions. */
   private enum ExistenceUsage {
      /** Inside a {@code find} predicate: the predicate's normal home. */
      ALLOWED,
      /** Inside the conditions of another existence predicate. */
      NESTED,
      /** Anywhere else in the workflow. */
      REJECTED
   }

   /**
   * Rejects a projection whose relation chain nests deeper than the allowed levels.
   *
   * <p>The limit is what keeps a response from walking the whole model. A chain that reaches one
   * level too far is rejected where it is declared; the target never trims it silently at run time,
   * and a cycle between views is rejected for the same reason.
   */
   private void validateProjectionDepth(AstViewDecl viewDecl) {
      for (AstViewField field : viewDecl.fields()) {
         SymbolId targetView = this.projectedViewOf(field.type());
         if (targetView == null) {
            continue;
         }

         if (1 + this.relationChainDepth(targetView, new LinkedHashSet<>()) > PROJECTION_DEPTH_LIMIT) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-005",
               "投影嵌套深度最多 " + PROJECTION_DEPTH_LIMIT + " 层: " + viewDecl.name().text() + "." + field.name().text(),
               field.name().span()));
         }
      }
   }

   private int relationChainDepth(SymbolId viewSymbol, Set<SymbolId> visiting) {
      if (!visiting.add(viewSymbol)) {
         return PROJECTION_DEPTH_LIMIT + 1;
      }

      int depth = 0;
      for (SymbolId target : this.projectedViewsOf(viewSymbol)) {
         depth = Math.max(depth, 1 + this.relationChainDepth(target, visiting));
         if (depth > PROJECTION_DEPTH_LIMIT) {
            break;
         }
      }

      visiting.remove(viewSymbol);
      return depth;
   }

   private List<SymbolId> projectedViewsOf(SymbolId viewSymbol) {
      AstViewDecl declaration = this.viewDecls.get(viewSymbol);
      if (declaration == null) {
         return List.of();
      }

      List<SymbolId> targets = new ArrayList<>();
      for (AstViewField field : declaration.fields()) {
         SymbolId target = this.projectedViewOf(field.type());
         if (target != null) {
            targets.add(target);
         }
      }

      return targets;
   }

   /** The view a projected field nests, or {@code null} for a column projection. */
   private SymbolId projectedViewOf(AstTypeRef typeRef) {
      SirType type = this.typed.typeRefTypes().get(typeRef.id());
      if (type instanceof ListType list) {
         type = list.element();
      }

      return type instanceof DeclaredType declared && declared.kind() == DeclaredType.DeclaredKind.VIEW
         ? declared.symbolId()
         : null;
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
            SymbolId versionSymId = this.entityVersionFields.get(entityId);
            for (Entry<SymbolId, SirType> entry : fieldTypes.entrySet()) {
               SymbolId fieldSymId = entry.getKey();
               SirType fieldType = entry.getValue();
               if (versionSymId != null && fieldSymId.equals(versionSymId)) {
                  // The framework initializes the version: binding it is wrong, and leaving it
                  // unbound is the required shape, so it is exempt from the required-field rule.
                  if (boundFieldIds.contains(fieldSymId)) {
                     this.diagnostics.add(DiagnosticBuilder.error(
                        "SIR-VALID-004", "create 不能绑定 version 字段（由框架初始化）: " + this.displayName(fieldSymId, "version"), step.span()));
                  }
               } else if (!(fieldType instanceof OptionalType) && !boundFieldIds.contains(fieldSymId)) {
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
                        } else if (this.entityVersionFields.get(entityId) != null
                           && fieldSymId.equals(this.entityVersionFields.get(entityId))) {
                           this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-004", "update 不能绑定 version 字段（由框架递增）: " + displayName, binding.span()));
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
      if (inputDecl.patchSourceEntity().isPresent()) {
         return;
      }

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
