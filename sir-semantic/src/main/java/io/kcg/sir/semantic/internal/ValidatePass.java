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
import io.kcg.sir.ast.AstStringLiteral;
import io.kcg.sir.source.SourceSpan;
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
import io.kcg.sir.ast.AstTimeShiftExpression;
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
   /** Entity field symbols the author declared unique: the only fields a load may look a row up by. */
   private final Set<SymbolId> uniqueFields = new LinkedHashSet<>();
   /** Entity field symbols the author declared secret: stored as a digest, readable only through matches. */
   private final Set<SymbolId> secretFields = new LinkedHashSet<>();

   /** Entity field symbols that declare a default: a create step that does not bind one is still complete. */
   private final Set<SymbolId> defaultedFields = new LinkedHashSet<>();
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

                     if (f.unique() && fieldSymId != null) {
                        this.uniqueFields.add(fieldSymId);
                     }

                     if (f.secret() && fieldSymId != null) {
                        this.secretFields.add(fieldSymId);
                     }

                     if (f.defaultValue().isPresent() && fieldSymId != null) {
                        this.defaultedFields.add(fieldSymId);
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

      this.validateExposures();

      for (AstDeclaration decl : this.software.declarations()) {
         if (this.typed.declarationBindings().containsKey(decl.id())) {
            if (decl instanceof AstEntityDecl entityDecl) {
               this.validateEntityConstraints(entityDecl);
               this.validateEntityVersionField(entityDecl);
               this.validateEntityUniqueField(entityDecl);
               this.validateEntitySecretField(entityDecl);
               this.validateEntityDefaultField(entityDecl);
               this.validateEntityScope(entityDecl);
            } else if (decl instanceof AstInputDecl inputDecl) {
               this.validateInputConstraints(inputDecl);
               this.validatePatchPayload(inputDecl);
         } else if (decl instanceof AstViewDecl viewDecl) {
               this.validateViewFields(viewDecl);
               this.validateProjectionDepth(viewDecl);
            }
         }
      }

      this.validateCredentials();
      return ValidatedContext.from(this.typed, List.copyOf(this.diagnostics));
   }

   private static final String ROUTE_CODE = "SIR-VALID-013";
   private static final java.util.regex.Pattern ROUTE_SEGMENT = java.util.regex.Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*|\\{[A-Za-z][A-Za-z0-9]*}");

   /**
   * Where each capability is reached: an HTTP method and a path under {@code /api}.
   *
   * <p>{@code put} and {@code delete} change a command's method; a patch payload is always sent with PATCH. A written
   * path is lower-case words and {@code {field}} segments, each naming a required field of the input with a plain value
   * (a number, a text or a uuid); with a patch payload only its identity may be in the path, because the
   * other fields are changes. Two capabilities may not share a method and a path shape ({@code /notes/{id}} and
   * {@code /notes/{noteId}} are one shape), counting the paths capabilities get from their names.
   */
   private void validateExposures() {
      Map<String, AstCapabilityDecl> shapes = new LinkedHashMap<>();
      for (AstDeclaration decl : this.software.declarations()) {
         if (!(decl instanceof AstCapabilityDecl capDecl) || !this.typed.declarationBindings().containsKey(decl.id())) {
            continue;
         }

         var expose = capDecl.exposure();
         boolean query = expose.exposure() == AstExposureKind.QUERY;
         SymbolId inputSymId = this.capabilityInputSymbolId(capDecl);
         boolean patch = inputSymId != null && this.patchInputSourceEntities.containsKey(inputSymId);
         if (expose.method().isPresent() && query) {
            this.diagnostics.add(DiagnosticBuilder.error(ROUTE_CODE, "put/delete 只能用于 expose command", expose.method().get().span()));
         } else if (expose.method().isPresent() && patch) {
            this.diagnostics.add(DiagnosticBuilder.error(ROUTE_CODE, "patch 载荷固定用 PATCH，不能写 put/delete", expose.method().get().span()));
         }

         String method = query ? "GET" : expose.method().map(m -> m.text().toUpperCase(java.util.Locale.ROOT)).orElse(patch ? "PATCH" : "POST");
         String path = expose.path().map(AstStringLiteral::value).orElse("/" + kebab(capDecl.name().text()));
         if (expose.path().isPresent() && !this.validRoute(capDecl, path, inputSymId, patch)) {
            continue;
         }

         String shape = method + " " + path.replaceAll("\\{[^}]*}", "{}");
         AstCapabilityDecl first = shapes.putIfAbsent(shape, capDecl);
         if (first != null) {
            this.diagnostics.add(DiagnosticBuilder.errorWithRelated(ROUTE_CODE, "两个能力的方法和路径相同: " + method + " /api" + path,
               expose.span(), new RelatedLocation("另一个能力在此处", first.exposure().span())));
         }
      }
   }

   private boolean validRoute(AstCapabilityDecl capDecl, String path, SymbolId inputSymId, boolean patch) {
      SourceSpan span = capDecl.exposure().path().orElseThrow().span();
      if (!path.startsWith("/") || path.length() < 2 || path.length() > 200) {
         this.diagnostics.add(DiagnosticBuilder.error(ROUTE_CODE, "路径以 / 开头，不能只有 /，最长 200 个字符: " + path, span));
         return false;
      }

      AstInputDecl input = inputSymId == null ? null : this.inputDeclarations.get(inputSymId);
      Set<String> named = new LinkedHashSet<>();
      boolean valid = true;
      for (String segment : path.substring(1).split("/", -1)) {
         if (!ROUTE_SEGMENT.matcher(segment).matches()) {
            this.diagnostics.add(DiagnosticBuilder.error(ROUTE_CODE, "路径段只能是小写字母、数字和短横线，或 {输入字段}: " + segment, span));
            valid = false;
            continue;
         }

         if (!segment.startsWith("{")) {
            continue;
         }

         String name = segment.substring(1, segment.length() - 1);
         if (!named.add(name)) {
            this.diagnostics.add(DiagnosticBuilder.error(ROUTE_CODE, "路径里 {" + name + "} 出现了两次", span));
            valid = false;
            continue;
         }

         AstField field = input == null ? null : input.fields().stream().filter(f -> f.name().text().equals(name)).findFirst().orElse(null);
         if (field == null) {
            this.diagnostics.add(DiagnosticBuilder.error(ROUTE_CODE, "路径里的 {" + name + "} 不是这个能力输入的字段", span));
            valid = false;
            continue;
         }

         SirType type = this.typed.typeRefTypes().get(field.type().id());
         SirType kind = type == null ? null : io.kcg.sir.semantic.feature.SemanticTypes.kind(type);
         boolean plain = kind == PrimitiveType.INT32 || kind == PrimitiveType.INT64 || kind == PrimitiveType.STRING || kind == PrimitiveType.UUID;
         if (!plain) {
            this.diagnostics.add(DiagnosticBuilder.error(ROUTE_CODE,
               "路径里的 {" + name + "} 必须是必填的数字、文本或 uuid 字段", span));
            valid = false;
            continue;
         }

         if (patch) {
            SymbolId member = this.typed.patchFieldBindings().get(SymbolIdFactory.inputField(this.softwareName, input.name().text(), name));
            SymbolId identity = this.entityIdentitySymbolIds.get(this.patchInputSourceEntities.get(inputSymId));
            if (member == null || !member.equals(identity)) {
               this.diagnostics.add(DiagnosticBuilder.error(ROUTE_CODE, "patch 载荷只能把主键放进路径: " + name, span));
               valid = false;
            }
         }
      }

      return valid;
   }

   /** A capability's name as the path it gets when none is written: PublishGoods is publish-goods. */
   private static String kebab(String value) {
      StringBuilder result = new StringBuilder();
      for (int offset = 0; offset < value.length(); ) {
         int point = value.codePointAt(offset);
         if (Character.isUpperCase(point) && result.length() > 0) {
            result.append('-');
         }

         result.appendCodePoint(Character.toLowerCase(point));
         offset += Character.charCount(point);
      }

      return result.toString();
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

      this.validateDeletes(capDecl);
      this.validateReturnPlacement(capDecl);
      this.validatePageUsage(capDecl, isQuery);
      this.validateWriteWorkflow(capDecl, failsSet);
      this.validateExistencePlacement(capDecl);
      this.validateSecretUsage(capDecl);
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

               s.keyField().ifPresent(key -> this.validateKeyedLoad(s, key, capDecl));
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
               if (s.guard().isPresent()) {
                  this.validateUpdateGuard(s, capDecl, failsSet);
               }
               break;
            case io.kcg.sir.ast.AstDeleteStep s:
               if (isReadonly || isQuery) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-004", "query/readonly 不允许 delete 步骤", s.span()));
               }

               continue;
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
      this.rejectArithmeticTheQueryCannotCarry(step.predicate(), false);
   }

   /**
   * A find predicate becomes one parameterized query. A value computed from the request or the clock is
   * evaluated in Java and bound as a parameter, so {@code item.expiresAt > now() + 5 minutes} is fine; arithmetic
   * on a column ({@code item.a + 1 > x}), or on any name inside an {@code any(...)} condition where bare names
   * are columns, would have to be translated into the query, which this language does not do. It is refused
   * here instead of being generated into code that cannot work.
   */
   private void rejectArithmeticTheQueryCannotCarry(AstExpression expression, boolean insideAny) {
      switch (expression) {
         case null -> {
         }
         case AstGroupedExpression e -> this.rejectArithmeticTheQueryCannotCarry(e.inner(), insideAny);
         case AstUnaryExpression e -> this.rejectArithmeticTheQueryCannotCarry(e.operand(), insideAny);
         case AstAnyExpression e -> this.rejectArithmeticTheQueryCannotCarry(e.conditions(), true);
         case AstTimeShiftExpression e -> {
            if (insideAny || this.mentionsItem(e)) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-001", ARITHMETIC_ON_COLUMN, e.span()));
            } else {
               this.rejectArithmeticTheQueryCannotCarry(e.base(), insideAny);
            }
         }
         case AstBinaryExpression e -> {
            boolean arithmetic = e.operator() == AstBinaryOperator.ADD || e.operator() == AstBinaryOperator.SUB;
            if (arithmetic && (insideAny || this.mentionsItem(e))) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-001", ARITHMETIC_ON_COLUMN, e.span()));
            } else {
               this.rejectArithmeticTheQueryCannotCarry(e.left(), insideAny);
               this.rejectArithmeticTheQueryCannotCarry(e.right(), insideAny);
            }
         }
         default -> {
         }
      }
   }

   private static final String ARITHMETIC_ON_COLUMN =
      "find 谓词里对列做加减或时间平移无法翻译成查询；请把运算放到不依赖 item 的一侧";

   private boolean mentionsItem(AstExpression expression) {
      return switch (expression) {
         case AstNameExpression name -> "item".equals(name.name().text());
         case AstMemberExpression member -> this.mentionsItem(member.receiver());
         case AstGroupedExpression grouped -> this.mentionsItem(grouped.inner());
         case AstUnaryExpression unary -> this.mentionsItem(unary.operand());
         case AstPresentExpression present -> this.mentionsItem(present.target());
         case AstTimeShiftExpression shift -> this.mentionsItem(shift.base());
         case AstBinaryExpression binary -> this.mentionsItem(binary.left()) || this.mentionsItem(binary.right());
         default -> false;
      };
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

         if (isComparison(binary.operator())) {
            this.validateComparisonShape(binary);
         }

         this.validateStringMatchShape(binary.left());
         this.validateStringMatchShape(binary.right());
      } else if (ungrouped instanceof AstUnaryExpression unary) {
         this.validateStringMatchShape(unary.operand());
      }
   }

   /**
   * A comparison in a find predicate becomes one column condition with a bound value, so the column
   * must be on the left and the other side must be a value that does not read the row. The renderer
   * takes the left side as the column; without this rule {@code input.n < item.qty} would only fail
   * while generating code, and {@code item.a == item.b} would have no single-statement form at all.
   * A column with arithmetic on it is already refused with its own message, so it is not repeated here.
   */
   private void validateComparisonShape(AstBinaryExpression comparison) {
      boolean columnOnLeft = this.ungroup(comparison.left()) instanceof AstMemberExpression member && this.isItemName(member.receiver());
      boolean leftIsColumnArithmetic = this.mentionsItem(comparison.left())
         && (this.ungroup(comparison.left()) instanceof AstTimeShiftExpression || this.ungroup(comparison.left()) instanceof AstBinaryExpression);
      if (leftIsColumnArithmetic && !this.mentionsItem(comparison.right())) {
         return;
      }

      if (!columnOnLeft || this.mentionsItem(comparison.right())) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-001", COMPARISON_SHAPE, comparison.span()));
      }
   }

   private static final String COMPARISON_SHAPE =
      "find 谓词里的比较，左侧必须是 item.<字段>，右侧必须是不读取 item 的值；例如把 0 < item.qty 写成 item.qty > 0";

   private static boolean isComparison(AstBinaryOperator operator) {
      return switch (operator) {
         case EQ, NE, LT, LE, GT, GE -> true;
         default -> false;
      };
   }

   private boolean isItemName(AstExpression expression) {
      return this.ungroup(expression) instanceof AstNameExpression name && "item".equals(name.name().text());
   }

   private SymbolId memberTarget(AstExpression expression) {
      return this.ungroup(expression) instanceof AstMemberExpression member
         ? this.typed.referenceSiteBindings().targetFor(member.member().id()).orElse(null)
         : null;
   }

   /**
   * The unique marker's own rule: it cannot sit on the version field.
   *
   * <p>The version changes with every write and is not a value the author chooses, so it identifies
   * nothing a caller could look a row up by. Where a unique field may be declared, and of what type,
   * is the grammar's and the type check's business.
   */
   private void validateEntityUniqueField(AstEntityDecl entityDecl) {
      for (AstField field : entityDecl.fields()) {
         if (field.unique() && field.versioned()) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-002", "version 字段不能同时声明 unique: " + entityDecl.name().text() + "." + field.name().text(), field.name().span()));
         }
      }
   }

   private static final String SECRET_CODE = "SIR-VALID-008";
   private static final String DEFAULT_CODE = "SIR-VALID-010";
   private static final String SCOPE_CODE = "SIR-VALID-011";
   private static final String CREDENTIAL_CODE = "SIR-VALID-009";

   /**
   * How credential entities and authenticated capabilities fit together.
   *
   * <p>A subject can be known by one kind of token only, or the same request could be authenticated two
   * ways. And the generated service has one authenticator for the whole application, so it either knows
   * the credentials of every authenticated capability's actor or of none: a mixture would leave some
   * routes to a hand-written adapter and some to the generated one, which is only discovered when the
   * application fails to start. Both are refused here, where they are written.
   */
   private void validateCredentials() {
      Map<SymbolId, AstEntityDecl> credentialBySubject = new LinkedHashMap<>();
      for (AstDeclaration decl : this.software.declarations()) {
         if (!(decl instanceof AstEntityDecl entity) || entity.credential().isEmpty()
            || !this.typed.declarationBindings().containsKey(entity.id())) {
            continue;
         }

         SymbolId subjectField = this.typed.referenceSiteBindings().targetFor(entity.credential().get().subject().id()).orElse(null);
         SymbolId subjectEntity = null;
         for (Map<SymbolId, SirType> fields : this.entityFieldTypes.values()) {
            if (fields.get(subjectField) instanceof RefType reference) {
               subjectEntity = reference.entityId();
            }
         }

         if (subjectEntity != null && credentialBySubject.putIfAbsent(subjectEntity, entity) != null) {
            this.diagnostics.add(DiagnosticBuilder.error(
               CREDENTIAL_CODE, "同一个主体只能有一种凭据实体: " + credentialBySubject.get(subjectEntity).name().text() + " 与 " + entity.name().text(),
               entity.credential().get().span()));
         }
      }

      List<AstCapabilityDecl> backed = new ArrayList<>();
      List<AstCapabilityDecl> unbacked = new ArrayList<>();
      for (AstDeclaration decl : this.software.declarations()) {
         if (!(decl instanceof AstCapabilityDecl capability) || capability.actor().isEmpty()
            || capability.requirements().stream().noneMatch(requirement -> requirement.requirement() == AstRequirementKind.AUTHENTICATED)) {
            continue;
         }

         SirType actor = this.typed.typeRefTypes().get(capability.actor().get().type().id());
         if (actor instanceof RefType reference && credentialBySubject.containsKey(reference.entityId())) {
            backed.add(capability);
         } else {
            unbacked.add(capability);
         }
      }

      if (!backed.isEmpty() && !unbacked.isEmpty()) {
         for (AstCapabilityDecl capability : unbacked) {
            this.diagnostics.add(DiagnosticBuilder.error(
               CREDENTIAL_CODE,
               "认证方式不能混用：有的能力的 actor 由凭据实体认证，这个能力的 actor 没有凭据实体: " + capability.name().text(),
               capability.actor().get().span()));
         }
      }
   }

   /**
   * What a secret field may be declared together with.
   *
   * <p>It holds a digest, not the author's value, so a constraint on the value would be checked
   * against something it never describes; a unique secret could be looked up, which is a lookup by
   * digest that no caller can form; and the version field is the concurrency token, not a credential.
   */
   /**
   * A scope is added to every find, existence test and load of the entity, so it may say only what all of them can
   * check about one row: no existence test of its own, no presence test of a request field, and a secret only the
   * way a find may read one.
   */
   private void validateEntityScope(AstEntityDecl entityDecl) {
      entityDecl.scope().ifPresent(scope -> {
         this.checkSecretReads(scope, true);
         this.walkScope(scope, entityDecl.name().text());
      });
   }

   private void walkScope(AstExpression expression, String entity) {
      switch (expression) {
         case AstAnyExpression e -> this.diagnostics.add(DiagnosticBuilder.error(SCOPE_CODE, "scope 中不能使用 any(): " + entity, e.span()));
         case AstPresentExpression e -> this.diagnostics.add(DiagnosticBuilder.error(SCOPE_CODE, "scope 中不能使用 .present: " + entity, e.span()));
         case AstGroupedExpression e -> this.walkScope(e.inner(), entity);
         case AstUnaryExpression e -> this.walkScope(e.operand(), entity);
         case AstTimeShiftExpression e -> this.walkScope(e.base(), entity);
         case AstBinaryExpression e -> {
            this.walkScope(e.left(), entity);
            this.walkScope(e.right(), entity);
         }
         default -> {
         }
      }
   }

   private void validateEntityDefaultField(AstEntityDecl entityDecl) {
      for (AstField field : entityDecl.fields()) {
         if (field.defaultValue().isEmpty()) {
            continue;
         }

         String name = entityDecl.name().text() + "." + field.name().text();
         // A default is the same value in every row it fills; a field that must differ per row, hide its value or
         // be set by the framework cannot have one.
         if (field.unique() || field.secret() || field.versioned()) {
            this.diagnostics.add(DiagnosticBuilder.error(DEFAULT_CODE, "unique、secret、version 字段不能声明 default: " + name, field.defaultValue().get().span()));
         }
      }
   }

   private void validateEntitySecretField(AstEntityDecl entityDecl) {
      for (AstField field : entityDecl.fields()) {
         if (!field.secret()) {
            continue;
         }

         String name = entityDecl.name().text() + "." + field.name().text();
         if (field.unique()) {
            this.diagnostics.add(DiagnosticBuilder.error(SECRET_CODE, "secret 字段不能同时声明 unique: " + name, field.name().span()));
         }

         if (field.versioned()) {
            this.diagnostics.add(DiagnosticBuilder.error(SECRET_CODE, "secret 字段不能同时声明 version: " + name, field.name().span()));
         }

         if (!field.constraints().isEmpty()) {
            this.diagnostics.add(DiagnosticBuilder.error(SECRET_CODE, "secret 字段不能声明约束: " + name, field.constraints().getFirst().span()));
         }
      }
   }

   /**
   * How a secret is read: only as the left side of {@code matches}, and {@code matches} reads nothing else.
   *
   * <p>The stored value is a digest. Returning it, comparing it, or putting it in a query would either
   * expose something that helps an attacker or compare something no caller can form, so the one
   * permitted read asks whether a candidate text produces it. Writing a secret (a binding to the
   * field) is not a read and needs no rule here: the generated code digests the value on the way in.
   * A {@code find} cannot carry {@code matches}, because the digest comparison happens in the service,
   * after the row is loaded.
   */
   private void validateSecretUsage(AstCapabilityDecl capDecl) {
      for (AstStep step : capDecl.workflow().steps()) {
         switch (step) {
            case AstValidateStep s -> this.checkSecretReads(s.condition(), false);
            case AstLoadStep s -> this.checkSecretReads(s.idExpression(), false);
            case AstFindStep s -> {
               this.checkSecretReads(s.predicate(), true);
               s.page().ifPresent(page -> {
                  this.checkSecretReads(page.page(), true);
                  this.checkSecretReads(page.size(), true);
               });
            }
            case AstCreateStep s -> s.bindings().forEach(binding -> this.checkSecretReads(binding.value(), false));
            case AstUpdateStep s -> {
               s.guard().ifPresent(guard -> this.checkSecretReads(guard, false));
               s.bindings().forEach(binding -> this.checkSecretReads(binding.value(), false));
            }
            case AstReturnStep s -> this.checkSecretReads(s.value(), false);
            default -> {
            }
         }
      }
   }

   private void checkSecretReads(AstExpression expression, boolean inFind) {
      switch (expression) {
         case null -> {
         }
         case AstGroupedExpression e -> this.checkSecretReads(e.inner(), inFind);
         case AstUnaryExpression e -> this.checkSecretReads(e.operand(), inFind);
         case AstPresentExpression e -> this.checkSecretReads(e.target(), inFind);
         case AstTimeShiftExpression e -> this.checkSecretReads(e.base(), inFind);
         case AstAnyExpression e -> this.checkSecretReads(e.conditions(), true);
         case AstNameExpression e -> {
            SymbolId target = this.typed.referenceSiteBindings().targetFor(e.name().id()).orElse(null);
            if (target != null && this.secretFields.contains(target)) {
               this.diagnostics.add(DiagnosticBuilder.error(SECRET_CODE, SECRET_READ, e.span()));
            }
         }
         case AstMemberExpression e -> {
            if (this.secretFields.contains(this.memberTarget(e))) {
               this.diagnostics.add(DiagnosticBuilder.error(SECRET_CODE, SECRET_READ, e.span()));
            } else {
               this.checkSecretReads(e.receiver(), inFind);
            }
         }
         case AstBinaryExpression e when e.operator() == AstBinaryOperator.MATCHES -> {
            if (inFind) {
               this.diagnostics.add(DiagnosticBuilder.error(SECRET_CODE, "matches 不能用在 find 的谓词里：摘要的比对在加载出行之后、由服务完成", e.span()));
            } else if (this.ungroup(e.left()) instanceof AstMemberExpression stored && this.secretFields.contains(this.memberTarget(stored))) {
               this.checkSecretReads(stored.receiver(), inFind);
            } else {
               this.diagnostics.add(DiagnosticBuilder.error(SECRET_CODE, "matches 的左侧必须是 secret 字段", e.left().span()));
            }

            this.checkSecretReads(e.right(), inFind);
         }
         case AstBinaryExpression e -> {
            this.checkSecretReads(e.left(), inFind);
            this.checkSecretReads(e.right(), inFind);
         }
         default -> {
         }
      }
   }

   private static final String SECRET_READ = "secret 字段只能作为 matches 的左侧读取，不能返回、比较或参与其他运算";

   /**
   * What a load may look a row up by.
   *
   * <p>The field must be one the author declared unique, so a missing row and a found row are the
   * only outcomes. The identity is rejected on purpose: loading by it is the form without a field
   * name, and accepting both would give one thing two spellings.
   *
   * <p>A versioned entity that is loaded and then updated is locked by its identity (the conditional
   * update compares the version it read under that lock), so a load by another field cannot take that
   * path. It is refused rather than quietly generated without the lock.
   */
   private void validateKeyedLoad(AstLoadStep load, AstNameRef key, AstCapabilityDecl capDecl) {
      SymbolId keySymId = this.typed.referenceSiteBindings().targetFor(key.id()).orElse(null);
      if (keySymId == null) {
         return;
      }

      if (!this.uniqueFields.contains(keySymId)) {
         boolean isIdentity = this.entityIdentitySymbolIds.containsValue(keySymId);
         this.diagnostics.add(DiagnosticBuilder.error(
            "SIR-VALID-006",
            isIdentity
               ? "按主键加载不需要字段名，请写 load " + load.entity().text() + " by 表达式: " + key.text()
               : "load 只能按声明了 unique 的字段查找: " + load.entity().text() + "." + key.text(),
            key.span()));
         return;
      }

      SymbolId entitySymId = this.typed.referenceSiteBindings().targetFor(load.entity().id()).orElse(null);
      SymbolId resultVar = this.typed.referenceBindings().get(load.id());
      if (entitySymId == null || resultVar == null || this.entityVersionFields.get(entitySymId) == null) {
         return;
      }

      for (AstStep step : capDecl.workflow().steps()) {
         if (step instanceof AstUpdateStep update
            && resultVar.equals(this.typed.referenceSiteBindings().targetFor(update.target().id()).orElse(null))) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-006",
               "带 version 的实体先按字段加载再更新时无法加行锁，请改为按主键加载: " + load.entity().text(),
               load.span()));
            return;
         }
      }
   }

   private void validateViewFields(AstViewDecl viewDecl) {
      for (AstViewField field : viewDecl.fields()) {
         SymbolId projected = this.typed.referenceSiteBindings().targetFor(field.name().id()).orElse(null);
         if (projected != null && this.secretFields.contains(projected)) {
            this.diagnostics.add(DiagnosticBuilder.error(
               SECRET_CODE, "secret 字段不能出现在响应投影里: " + viewDecl.name().text() + "." + field.name().text(), field.name().span()));
         }

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

   /**
   * What a guarded update may be.
   *
   * <p>The guard becomes part of the one statement that writes the row, so it can only say what a
   * statement can check on the stored row: a column compared with a value computed before the write.
   * The write sets only the columns the update binds, so a binding that read another column of the
   * same row would be computing from a copy that another request may already have changed, and the
   * guard would not cover it; that is refused.
   *
   * <p>The row must have been loaded (a row created in this workflow has no stored state to test) and
   * must not be versioned (a versioned row is already protected by comparing its version, and mixing
   * the two protections is left for a later decision).
   */
   private void validateUpdateGuard(AstUpdateStep step, AstCapabilityDecl capDecl, Set<SymbolId> failsSet) {
      AstExpression guard = step.guard().orElseThrow();
      SymbolId errorSymbol = this.typed.referenceSiteBindings().targetFor(step.guardError().orElseThrow().id()).orElse(null);
      if (errorSymbol != null && failsSet != null && !failsSet.contains(errorSymbol)) {
         this.diagnostics.add(DiagnosticBuilder.error(
            "SIR-FLOW-003", "update 的守卫引用未声明的 Error: " + step.guardError().orElseThrow().text(), step.guardError().orElseThrow().span()));
      }

      SymbolId target = this.typed.referenceSiteBindings().targetFor(step.target().id()).orElse(null);
      if (target == null) {
         return;
      }

      boolean loaded = capDecl.workflow().steps().stream()
         .anyMatch(other -> other instanceof AstLoadStep load && target.equals(this.typed.referenceBindings().get(load.id())));
      if (!loaded) {
         this.diagnostics.add(DiagnosticBuilder.error(
            "SIR-VALID-007", "update 的守卫只能用于 load 出来的行: " + step.target().text(), step.target().span()));
      }

      SymbolId entity = this.updateTargetEntitySymId(step);
      if (entity != null && this.entityVersionFields.get(entity) != null) {
         this.diagnostics.add(DiagnosticBuilder.error(
            "SIR-VALID-007", "带 version 的实体不能使用 update 的守卫（版本已经保护了这一行）", step.target().span()));
      }

      this.validateGuardShape(guard, target);
      for (AstBinding binding : step.bindings()) {
         if (this.mentionsVariable(binding.value(), target)) {
            this.diagnostics.add(DiagnosticBuilder.error(
               "SIR-VALID-007", "带守卫的 update 的绑定值不能读取目标自身的字段: " + binding.fieldName().text(), binding.value().span()));
         }
      }
   }

   private static final String GUARD_SHAPE =
      "update 的守卫必须是“目标.字段 与值的比较”（==、!=、<、<=、>、>=），可以用 and、or、not 组合；值不能读取目标自身的字段";

   private void validateGuardShape(AstExpression expression, SymbolId target) {
      switch (this.ungroup(expression)) {
         case AstBinaryExpression binary when binary.operator() == AstBinaryOperator.AND || binary.operator() == AstBinaryOperator.OR -> {
            this.validateGuardShape(binary.left(), target);
            this.validateGuardShape(binary.right(), target);
         }
         case AstBinaryExpression binary when binary.operator() == AstBinaryOperator.EQ || binary.operator() == AstBinaryOperator.NE
            || binary.operator() == AstBinaryOperator.GT || binary.operator() == AstBinaryOperator.GE
            || binary.operator() == AstBinaryOperator.LT || binary.operator() == AstBinaryOperator.LE -> {
            boolean columnOnTheLeft = this.ungroup(binary.left()) instanceof AstMemberExpression member
               && this.ungroup(member.receiver()) instanceof AstNameExpression name
               && target.equals(this.typed.referenceSiteBindings().targetFor(name.name().id()).orElse(null));
            if (!columnOnTheLeft || this.mentionsVariable(binary.right(), target)) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-007", GUARD_SHAPE, binary.span()));
            }
         }
         case AstUnaryExpression unary when unary.operator() == AstUnaryOperator.NOT -> this.validateGuardShape(unary.operand(), target);
         default -> this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-007", GUARD_SHAPE, expression.span()));
      }
   }

   /** Whether the expression reads the variable, directly or through a member of it. */
   private boolean mentionsVariable(AstExpression expression, SymbolId variable) {
      return switch (expression) {
         case AstNameExpression name -> variable.equals(this.typed.referenceSiteBindings().targetFor(name.name().id()).orElse(null));
         case AstMemberExpression member -> this.mentionsVariable(member.receiver(), variable);
         case AstGroupedExpression grouped -> this.mentionsVariable(grouped.inner(), variable);
         case AstUnaryExpression unary -> this.mentionsVariable(unary.operand(), variable);
         case AstPresentExpression present -> this.mentionsVariable(present.target(), variable);
         case AstTimeShiftExpression shift -> this.mentionsVariable(shift.base(), variable);
         case AstBinaryExpression binary -> this.mentionsVariable(binary.left(), variable) || this.mentionsVariable(binary.right(), variable);
         default -> false;
      };
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
         case AstUpdateStep s -> {
            s.guard().ifPresent(guard -> walkPresent(guard, action));
            s.bindings().forEach(binding -> walkPresent(binding.value(), action));
         }
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
         case AstTimeShiftExpression e -> this.walkPresent(e.base(), action);
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
            updateStep.guard().ifPresent(guard -> this.validateExistenceUsage(guard, ExistenceUsage.REJECTED));
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
         case AstTimeShiftExpression shift -> this.validateExistenceUsage(shift.base(), usage);
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
      return step instanceof AstCreateStep || step instanceof AstUpdateStep || step instanceof AstPersistStep
         || step instanceof io.kcg.sir.ast.AstDeleteStep;
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
               } else if (!(fieldType instanceof OptionalType) && !this.defaultedFields.contains(fieldSymId) && !boundFieldIds.contains(fieldSymId)) {
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

   /**
    * A delete removes a row that this workflow read: the variable must be the result of an earlier load, and once the
    * row is deleted nothing may change or delete it again (a returned value is still the row as it was read).
    */
   private void validateDeletes(AstCapabilityDecl capDecl) {
      Set<String> loaded = new LinkedHashSet<>();
      Set<String> deleted = new LinkedHashSet<>();
      for (AstStep step : capDecl.workflow().steps()) {
         if (step instanceof io.kcg.sir.ast.AstLoadStep load) {
            loaded.add(load.result().text());
         } else if (step instanceof io.kcg.sir.ast.AstDeleteStep delete) {
            String name = delete.target().text();
            if (!loaded.contains(name)) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-012", "delete 只能删除 load 读出的记录: " + name, delete.target().span()));
            } else if (!deleted.add(name)) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-012", "记录已被删除: " + name, delete.target().span()));
            }
         } else if (step instanceof AstUpdateStep update && deleted.contains(update.target().text())) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-012", "记录删除之后不能再 update: " + update.target().text(), update.target().span()));
         } else if (step instanceof AstPersistStep persist && deleted.contains(persist.target().text())) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-VALID-012", "记录删除之后不能再 persist: " + persist.target().text(), persist.target().span()));
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
