package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.RelatedLocation;
import io.kcg.sir.ast.AstActorClause;
import io.kcg.sir.ast.AstBinaryExpression;
import io.kcg.sir.ast.AstBinding;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstCreateStep;
import io.kcg.sir.ast.AstDeclaration;
import io.kcg.sir.ast.AstEntityDecl;
import io.kcg.sir.ast.AstEnumDecl;
import io.kcg.sir.ast.AstEnumMember;
import io.kcg.sir.ast.AstErrorDecl;
import io.kcg.sir.ast.AstExpression;
import io.kcg.sir.ast.AstFailsClause;
import io.kcg.sir.ast.AstField;
import io.kcg.sir.ast.AstFindPage;
import io.kcg.sir.ast.AstFindOrder;
import io.kcg.sir.ast.AstFindOrderKey;
import io.kcg.sir.ast.AstFindStep;
import io.kcg.sir.ast.AstGenerationStrategy;
import io.kcg.sir.ast.AstGroupedExpression;
import io.kcg.sir.ast.AstIdentity;
import io.kcg.sir.ast.AstInputClause;
import io.kcg.sir.ast.AstInputDecl;
import io.kcg.sir.ast.AstListTypeRef;
import io.kcg.sir.ast.AstLoadStep;
import io.kcg.sir.ast.AstMemberExpression;
import io.kcg.sir.ast.AstName;
import io.kcg.sir.ast.AstNameExpression;
import io.kcg.sir.ast.AstNameRef;
import io.kcg.sir.ast.AstNamedTypeRef;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstOptionalTypeRef;
import io.kcg.sir.ast.AstPageTypeRef;
import io.kcg.sir.ast.AstPersistStep;
import io.kcg.sir.ast.AstPresentExpression;
import io.kcg.sir.ast.AstRefTypeRef;
import io.kcg.sir.ast.AstReturnStep;
import io.kcg.sir.ast.AstSoftware;
import io.kcg.sir.ast.AstStep;
import io.kcg.sir.ast.AstTypeRef;
import io.kcg.sir.ast.AstUnaryExpression;
import io.kcg.sir.ast.AstUpdateStep;
import io.kcg.sir.ast.AstValidateStep;
import io.kcg.sir.ast.AstViewDecl;
import io.kcg.sir.ast.AstViewField;
import io.kcg.sir.semantic.api.ReferenceRole;
import io.kcg.sir.semantic.api.ReferenceSite;
import io.kcg.sir.semantic.api.ReferenceSiteBinding;
import io.kcg.sir.semantic.api.ReferenceSiteBindings;
import io.kcg.sir.semantic.context.ResolvedContext;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.semantic.symbol.SymbolTable;
import io.kcg.sir.semantic.type.DeclaredType;
import io.kcg.sir.semantic.type.ListType;
import io.kcg.sir.semantic.type.OptionalType;
import io.kcg.sir.semantic.type.PageType;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.RefType;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.source.SourcePosition;
import io.kcg.sir.source.SourceSpan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

final class ResolvePass {
   private final AstSoftware software;
   private final String softwareName;
   private final SymbolId projectScopeId;
   private final List<Symbol> allSymbols = new ArrayList<>();
   private final Map<SymbolId, List<Symbol>> scopeSymbols = new LinkedHashMap<>();
   private final Map<SymbolId, SymbolId> scopeParents = new LinkedHashMap<>();
   private final Map<String, Symbol> projectNames = new LinkedHashMap<>();
   private final Map<SymbolId, Symbol> symbolsById = new LinkedHashMap<>();
   private final Set<AstNodeId> registeredDeclIds = new LinkedHashSet<>();
   private final Map<AstNodeId, SymbolId> referenceBindings = new LinkedHashMap<>();
   private final Map<AstNodeId, SirType> typeRefTypes = new LinkedHashMap<>();
   private final Map<AstNodeId, SymbolId> findItemBindings = new LinkedHashMap<>();
   private final Map<AstNodeId, ReferenceSiteBinding> referenceSiteBindings = new LinkedHashMap<>();
   private final Map<AstNodeId, SymbolId> declarationBindings = new LinkedHashMap<>();
   private final List<Diagnostic> diagnostics = new ArrayList<>();
   private final Map<String, SirType> typeByName = new LinkedHashMap<>();
   private final Map<String, SymbolKind> declarationKinds = new LinkedHashMap<>();
   private final Map<String, AstEntityDecl> entityDecls = new LinkedHashMap<>();
   private final Map<String, AstInputDecl> inputDecls = new LinkedHashMap<>();
   private final Map<String, AstViewDecl> viewDecls = new LinkedHashMap<>();
   private final Map<String, Map<String, SirType>> entityFieldTypes = new LinkedHashMap<>();
   private final Map<String, Map<String, SymbolId>> entityFieldSymbols = new LinkedHashMap<>();
   private final Map<String, SirType> entityIdentityTypes = new LinkedHashMap<>();
   private final Map<String, SymbolId> entityIdentitySymbols = new LinkedHashMap<>();
   private final Map<String, Map<String, SirType>> inputFieldTypes = new LinkedHashMap<>();
   private final Map<String, Map<String, SymbolId>> inputFieldSymbols = new LinkedHashMap<>();
   private final Map<String, Set<String>> enumMemberSets = new LinkedHashMap<>();
   private final Map<String, Map<String, SymbolId>> enumMemberSymbols = new LinkedHashMap<>();
   private final Map<String, Set<String>> capabilityFails = new LinkedHashMap<>();
   private final Map<String, SymbolId> capabilityScopeIds = new LinkedHashMap<>();
   /** Input scope id -> the entity a patch payload changes. */
   private final Map<SymbolId, SymbolId> patchInputEntities = new LinkedHashMap<>();
   /** The input field symbols that belong to a patch payload, i.e. the fields a presence test may read. */
   private final Set<SymbolId> patchPayloadFieldSymbols = new LinkedHashSet<>();
   /** Patch payload input field symbol -> the entity member it changes. */
   private final Map<SymbolId, SymbolId> patchFieldBindings = new LinkedHashMap<>();

   ResolvePass(AstSoftware software) {
      this.software = software;
      this.softwareName = software.name().text();
      this.projectScopeId = SymbolIdFactory.projectScope(this.softwareName);
   }

   ResolvedContext run() {
      this.scopeSymbols.put(this.projectScopeId, new ArrayList<>());
      this.registerPrimitives();
      this.registerDeclarations();
      this.resolveMembersAndTypes();
      this.resolvePatchPayloadBindings();
      this.resolveCapabilities();
      SymbolTable table = this.buildSymbolTable();
      return new ResolvedContext(
         this.softwareName,
         table,
         this.referenceBindings,
         this.typeRefTypes,
         this.findItemBindings,
         this.patchFieldBindings,
         ReferenceSiteBindings.of(this.referenceSiteBindings.values()),
         this.declarationBindings,
         List.copyOf(this.diagnostics)
      );
   }

   private void registerPrimitives() {
      for (PrimitiveType pt : PrimitiveType.values()) {
         String sirName = pt.sirName();
         SymbolId id = SymbolIdFactory.primitive(sirName);
         Symbol.TypeSymbol sym = new Symbol.TypeSymbol(id, sirName, this.zeroSpan(), SymbolKind.PRIMITIVE, null);
         this.addSymbol(sym, this.projectScopeId);
         this.typeByName.put(sirName, pt);
         this.declarationKinds.put(sirName, SymbolKind.PRIMITIVE);
         this.projectNames.put(sirName, sym);
      }
   }

   private void registerDeclarations() {
      for (AstDeclaration decl : this.software.declarations()) {
         String name = this.declarationName(decl);
         SymbolKind kind = this.declarationKindOf(decl);
         Symbol existing = this.projectNames.get(name);
         if (existing != null) {
            RelatedLocation related = new RelatedLocation("首次声明在此处", existing.declarationSpan());
            this.diagnostics.add(DiagnosticBuilder.errorWithRelated("SIR-SYMBOL-001", "重复定义: " + name, decl.span(), related));
         } else {
            this.registerDeclaration(decl, name, kind);
         }
      }
   }

   private void registerDeclaration(AstDeclaration decl, String name, SymbolKind kind) {
      SymbolId id = SymbolIdFactory.declaration(this.softwareName, this.kindString(kind), name);
      SourceSpan span = decl.span();
      Symbol symbol;
      switch (decl) {
         case AstEnumDecl e:
            symbol = new Symbol.TypeSymbol(id, name, span, SymbolKind.ENUM, e);
            this.declarationKinds.put(name, SymbolKind.ENUM);
            this.typeByName.put(name, new DeclaredType(DeclaredType.DeclaredKind.ENUM, name, id));
            break;
         case AstEntityDecl e:
            symbol = new Symbol.TypeSymbol(id, name, span, SymbolKind.ENTITY, e);
            this.entityDecls.put(name, e);
            this.declarationKinds.put(name, SymbolKind.ENTITY);
            this.typeByName.put(name, new DeclaredType(DeclaredType.DeclaredKind.ENTITY, name, id));
            break;
         case AstInputDecl e:
            symbol = new Symbol.TypeSymbol(id, name, span, SymbolKind.INPUT, e);
            this.inputDecls.put(name, e);
            this.declarationKinds.put(name, SymbolKind.INPUT);
            this.typeByName.put(name, new DeclaredType(DeclaredType.DeclaredKind.INPUT, name, id));
            break;
         case AstViewDecl e:
         symbol = new Symbol.TypeSymbol(id, name, span, SymbolKind.VIEW, e);
         this.viewDecls.put(name, e);
         this.declarationKinds.put(name, SymbolKind.VIEW);
         this.typeByName.put(name, new DeclaredType(DeclaredType.DeclaredKind.VIEW, name, id));
         break;
         case AstErrorDecl e:
            symbol = new Symbol.ErrorSymbol(id, name, span, e);
            this.declarationKinds.put(name, SymbolKind.ERROR);
            break;
         case AstCapabilityDecl e:
            symbol = new Symbol.TypeSymbol(id, name, span, SymbolKind.CAPABILITY, e);
            this.declarationKinds.put(name, SymbolKind.CAPABILITY);
            SymbolId capScopeId = SymbolIdFactory.declaration(this.softwareName, "capability", name);
            this.capabilityScopeIds.put(name, capScopeId);
            this.scopeSymbols.put(capScopeId, new ArrayList<>());
            this.scopeParents.put(capScopeId, this.projectScopeId);
            this.capabilityFails.put(name, new LinkedHashSet<>());

            for (AstFailsClause fails : e.failures()) {
               this.capabilityFails.get(name).add(fails.error().text());
            }
            break;
         default:
            throw new IllegalStateException("unexpected declaration: " + decl);
      }

      this.addSymbol(symbol, this.projectScopeId);
      this.projectNames.put(name, symbol);
      this.registeredDeclIds.add(decl.id());
      this.declarationBindings.put(decl.id(), symbol.id());
   }

   private void resolveMembersAndTypes() {
      for (AstDeclaration decl : this.software.declarations()) {
         if (this.registeredDeclIds.contains(decl.id())) {
            switch (decl) {
               case AstEnumDecl e:
                  this.resolveEnumMembers(e);
                  break;
               case AstEntityDecl e:
                  this.resolveEntityMembers(e);
                  break;
               case AstInputDecl e:
                  this.resolveInputMembers(e);
                  break;
               case AstViewDecl e:
                  this.resolveViewMembers(e);
                  break;
               default:
            }
         }
      }
   }

   private void resolveEnumMembers(AstEnumDecl enumDecl) {
      String enumName = enumDecl.name().text();
      SymbolId enumScopeId = SymbolIdFactory.declaration(this.softwareName, "enum", enumName);
      this.scopeSymbols.put(enumScopeId, new ArrayList<>());
      this.scopeParents.put(enumScopeId, this.projectScopeId);
      Set<String> seen = new LinkedHashSet<>();
      this.enumMemberSets.put(enumName, new LinkedHashSet<>());
      this.enumMemberSymbols.put(enumName, new LinkedHashMap<>());

      for (AstEnumMember member : enumDecl.members()) {
         String memberName = member.name().text();
         if (seen.contains(memberName)) {
            AstEnumMember first = this.findFirstMember(enumDecl, memberName);
            RelatedLocation related = new RelatedLocation("首次声明在此处", first.span());
            this.diagnostics.add(DiagnosticBuilder.errorWithRelated("SIR-SYMBOL-001", "重复枚举成员: " + memberName, member.span(), related));
         } else {
            seen.add(memberName);
            this.enumMemberSets.get(enumName).add(memberName);
            SymbolId memberId = SymbolIdFactory.enumMember(this.softwareName, enumName, memberName);
            this.enumMemberSymbols.get(enumName).put(memberName, memberId);
            Symbol.EnumMemberSymbol sym = new Symbol.EnumMemberSymbol(memberId, memberName, member.span(), enumScopeId);
            this.addSymbol(sym, enumScopeId);
         }
      }
   }

   private AstEnumMember findFirstMember(AstEnumDecl enumDecl, String name) {
      for (AstEnumMember m : enumDecl.members()) {
         if (m.name().text().equals(name)) {
            return m;
         }
      }

      throw new IllegalStateException("member not found: " + name);
   }

   private void resolveEntityMembers(AstEntityDecl entityDecl) {
      String entityName = entityDecl.name().text();
      SymbolId entityScopeId = SymbolIdFactory.declaration(this.softwareName, "entity", entityName);
      this.scopeSymbols.put(entityScopeId, new ArrayList<>());
      this.scopeParents.put(entityScopeId, this.projectScopeId);
      this.entityFieldTypes.put(entityName, new LinkedHashMap<>());
      this.entityFieldSymbols.put(entityName, new LinkedHashMap<>());
      AstIdentity identity = entityDecl.identity();
      SirType idType = this.resolveTypeRef(identity.type(), entityName, "identity");
      if (idType != null) {
         this.entityIdentityTypes.put(entityName, idType);
         if (!(idType instanceof PrimitiveType pt && (pt == PrimitiveType.INT64 || pt == PrimitiveType.UUID))) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "identity 类型只允许 Int64 或 Uuid", identity.type().span()));
         }

         PrimitiveType ptx = idType instanceof PrimitiveType p ? p : null;
         if (identity.generation() == AstGenerationStrategy.AUTO && ptx != PrimitiveType.INT64) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "generated auto 只适用于 Int64", identity.span()));
         }

         if (identity.generation() == AstGenerationStrategy.UUID && ptx != PrimitiveType.UUID) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "generated uuid 只适用于 Uuid", identity.span()));
         }
      }

      SymbolId identitySymId = SymbolIdFactory.entityIdentity(this.softwareName, entityName);
      this.entityIdentitySymbols.put(entityName, identitySymId);
      Symbol.FieldSymbol identitySym = new Symbol.FieldSymbol(identitySymId, identity.name().text(), identity.span(), idType, entityScopeId);
      this.addSymbol(identitySym, entityScopeId);
      this.bind(identity.id(), identitySymId);
      Set<String> seenFields = new LinkedHashSet<>();

      for (AstField field : entityDecl.fields()) {
         String fieldName = field.name().text();
         if (seenFields.contains(fieldName)) {
            AstField first = this.findFirstField(entityDecl.fields(), fieldName);
            RelatedLocation related = new RelatedLocation("首次声明在此处", first.span());
            this.diagnostics.add(DiagnosticBuilder.errorWithRelated("SIR-SYMBOL-001", "重复字段: " + fieldName, field.span(), related));
         } else if (fieldName.equals(identity.name().text())) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-001", "字段与 identity 同名: " + fieldName, field.span()));
         } else {
            seenFields.add(fieldName);
            SirType fieldType = this.resolveTypeRef(field.type(), entityName, "field");
            if (fieldType != null) {
               this.entityFieldTypes.get(entityName).put(fieldName, fieldType);
            }

            SymbolId fieldSymId = SymbolIdFactory.entityField(this.softwareName, entityName, fieldName);
            this.entityFieldSymbols.get(entityName).put(fieldName, fieldSymId);
            Symbol.FieldSymbol fieldSym = new Symbol.FieldSymbol(fieldSymId, fieldName, field.span(), fieldType, entityScopeId);
            this.addSymbol(fieldSym, entityScopeId);
            this.bind(field.id(), fieldSymId);
         }
      }
   }

   /**
   * Binds every patch payload field to the entity member it changes.
   *
   * <p>This runs after all entity members are resolved, so a payload may be declared
   * before the entity it targets. The bound member is what makes a change applicable
   * without a later phase matching field names again, and the resolved field symbols
   * are what makes a {@code .present} test decidable.
   */
   private void resolvePatchPayloadBindings() {
      for (AstDeclaration decl : this.software.declarations()) {
         if (!(decl instanceof AstInputDecl inputDecl) || inputDecl.patchSourceEntity().isEmpty()) {
            continue;
         }

         if (!this.registeredDeclIds.contains(inputDecl.id())) {
            continue;
         }

         String inputName = inputDecl.name().text();
         SymbolId inputScopeId = SymbolIdFactory.declaration(this.softwareName, "input", inputName);
         AstNameRef sourceEntity = inputDecl.patchSourceEntity().orElseThrow();
         this.resolveEntityRef(sourceEntity, ReferenceRole.PATCH_SOURCE_ENTITY);
         SymbolId entitySymbolId = this.referenceBindings.get(sourceEntity.id());
         if (entitySymbolId == null) {
            continue;
         }

         this.patchInputEntities.put(inputScopeId, entitySymbolId);
         String entityName = sourceEntity.text();
         Map<String, SymbolId> entityMembers = this.entityFieldSymbols.getOrDefault(entityName, Map.of());
         Map<String, SymbolId> inputFields = this.inputFieldSymbols.getOrDefault(inputName, Map.of());
         for (AstField field : inputDecl.fields()) {
            String fieldName = field.name().text();
            SymbolId memberSymId = entityMembers.get(fieldName);
            if (memberSymId == null
               && this.entityDecls.containsKey(entityName)
               && this.entityDecls.get(entityName).identity().name().text().equals(fieldName)) {
               memberSymId = this.entityIdentitySymbols.get(entityName);
            }

            SymbolId inputFieldSymId = inputFields.get(fieldName);
            if (memberSymId == null) {
               this.diagnostics.add(DiagnosticBuilder.error(
                  "SIR-SYMBOL-002",
                  "未定义字段: " + entityName + "." + fieldName,
                  field.name().span()
               ));
            } else if (inputFieldSymId != null) {
               this.patchFieldBindings.put(inputFieldSymId, memberSymId);
            }

            if (inputFieldSymId != null) {
               this.patchPayloadFieldSymbols.add(inputFieldSymId);
            }
         }
      }
   }

   private void resolveInputMembers(AstInputDecl inputDecl) {
      String inputName = inputDecl.name().text();
      SymbolId inputScopeId = SymbolIdFactory.declaration(this.softwareName, "input", inputName);
      this.scopeSymbols.put(inputScopeId, new ArrayList<>());
      this.scopeParents.put(inputScopeId, this.projectScopeId);
      this.inputFieldTypes.put(inputName, new LinkedHashMap<>());
      this.inputFieldSymbols.put(inputName, new LinkedHashMap<>());
      Set<String> seenFields = new LinkedHashSet<>();

      for (AstField field : inputDecl.fields()) {
         String fieldName = field.name().text();
         if (seenFields.contains(fieldName)) {
            AstField first = this.findFirstField(inputDecl.fields(), fieldName);
            RelatedLocation related = new RelatedLocation("首次声明在此处", first.span());
            this.diagnostics.add(DiagnosticBuilder.errorWithRelated("SIR-SYMBOL-001", "重复字段: " + fieldName, field.span(), related));
         } else {
            seenFields.add(fieldName);
            SirType fieldType = this.resolveTypeRef(field.type(), inputName, "input field");
            if (fieldType != null) {
               this.inputFieldTypes.get(inputName).put(fieldName, fieldType);
            }

            SymbolId fieldSymId = SymbolIdFactory.inputField(this.softwareName, inputName, fieldName);
            this.inputFieldSymbols.get(inputName).put(fieldName, fieldSymId);
            Symbol.FieldSymbol fieldSym = new Symbol.FieldSymbol(fieldSymId, fieldName, field.span(), fieldType, inputScopeId);
            this.addSymbol(fieldSym, inputScopeId);
            this.bind(field.id(), fieldSymId);
         }
      }
   }

   /**
   * Registers the view's own field symbols in a view scope, then binds every projected name to the
   * field of the source entity it reads. The view must name a declared entity and each projected
   * name must exist on it; everything else about a projection is a later-phase rule.
   */
   private void resolveViewMembers(AstViewDecl viewDecl) {
      String viewName = viewDecl.name().text();
      SymbolId viewScopeId = SymbolIdFactory.declaration(this.softwareName, "view", viewName);
      this.scopeSymbols.put(viewScopeId, new ArrayList<>());
      this.scopeParents.put(viewScopeId, this.projectScopeId);
      this.resolveEntityRef(viewDecl.sourceEntity(), ReferenceRole.VIEW_SOURCE_ENTITY);
      String entityName = this.entityDecls.containsKey(viewDecl.sourceEntity().text()) ? viewDecl.sourceEntity().text() : null;
      Set<String> seenFields = new LinkedHashSet<>();

      for (AstViewField field : viewDecl.fields()) {
         String fieldName = field.name().text();
         if (!seenFields.add(fieldName)) {
         AstViewField first = this.findFirstViewField(viewDecl.fields(), fieldName);
         RelatedLocation related = new RelatedLocation("首次声明在此处", first.span());
         this.diagnostics.add(DiagnosticBuilder.errorWithRelated("SIR-SYMBOL-001", "重复字段: " + fieldName, field.span(), related));
         continue;
         }

         SirType declaredType = this.resolveTypeRef(field.type(), viewName, "view field");
         SymbolId fieldSymId = SymbolIdFactory.viewField(this.softwareName, viewName, fieldName);
         Symbol.FieldSymbol fieldSym = new Symbol.FieldSymbol(fieldSymId, fieldName, field.span(), declaredType, viewScopeId);
         this.addSymbol(fieldSym, viewScopeId);
         this.bindProjectedField(field, entityName, fieldName);
      }
   }

   private void bindProjectedField(AstViewField field, String entityName, String fieldName) {
      if (entityName == null) {
         return;
      }

      SymbolId fieldSymId = this.entityFieldSymbols.getOrDefault(entityName, Map.of()).get(fieldName);
      if (fieldSymId == null && this.entityDecls.get(entityName).identity().name().text().equals(fieldName)) {
         fieldSymId = this.entityIdentitySymbols.get(entityName);
      }

      if (fieldSymId != null) {
         this.bindReference(field.name(), ReferenceRole.VIEW_FIELD, fieldSymId);
      } else {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "未定义字段: " + entityName + "." + fieldName, field.name().span()));
      }
   }

   private AstViewField findFirstViewField(List<AstViewField> fields, String name) {
      for (AstViewField field : fields) {
         if (field.name().text().equals(name)) {
         return field;
         }
      }

      throw new IllegalStateException("view field not found: " + name);
   }

   private void resolveCapabilities() {
      for (AstDeclaration decl : this.software.declarations()) {
         if (decl instanceof AstCapabilityDecl capDecl && this.registeredDeclIds.contains(decl.id())) {
            this.resolveCapability(capDecl);
         }
      }
   }

   private void resolveCapability(AstCapabilityDecl capDecl) {
      String capName = capDecl.name().text();
      SymbolId capScopeId = this.capabilityScopeIds.get(capName);
      if (capDecl.actor().isPresent()) {
         AstActorClause actorClause = capDecl.actor().get();
         SirType actorType = this.resolveTypeRef(actorClause.type(), capName, "actor");
         if (actorType != null && !(actorType instanceof RefType)) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "actor 必须是 Ref<Entity>", actorClause.type().span()));
         }

         SymbolId varId = SymbolIdFactory.variable(this.softwareName, capName, "actor");
         Symbol.VariableSymbol varSym = new Symbol.VariableSymbol(varId, "actor", actorClause.span(), actorType, null);
         this.addSymbol(varSym, capScopeId);
         this.bind(actorClause.id(), varId);
      }

      if (capDecl.input().isPresent()) {
         AstInputClause inputClause = capDecl.input().get();
         SirType inputType = this.resolveTypeRef(inputClause.type(), capName, "input");
         if (inputType != null && !(inputType instanceof DeclaredType dt && dt.kind() == DeclaredType.DeclaredKind.INPUT)) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "capability input 必须是 Input 类型", inputClause.type().span()));
         }

         SymbolId varId = SymbolIdFactory.variable(this.softwareName, capName, "input");
         Symbol.VariableSymbol varSym = new Symbol.VariableSymbol(varId, "input", inputClause.span(), inputType, null);
         this.addSymbol(varSym, capScopeId);
         this.bind(inputClause.id(), varId);
      }

      SirType outputType = this.resolveTypeRef(capDecl.output().type(), capName, "output");
      this.checkOutputType(capDecl, outputType);
      this.resolveFailsClauses(capDecl);
      this.resolveWorkflow(capDecl, capScopeId, outputType);
   }

   private void checkOutputType(AstCapabilityDecl capDecl, SirType outputType) {
      if (outputType != null) {
         SourceSpan span = capDecl.output().type().span();
         if (outputType instanceof DeclaredType dt && dt.kind() == DeclaredType.DeclaredKind.ENTITY) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "output 必须使用 Ref<Entity>", span));
         }

         if (!(outputType instanceof PrimitiveType pt && pt == PrimitiveType.UNIT)) {
            if (outputType instanceof OptionalType ot) {
               this.checkNoEntityInside(ot.element(), span, "Optional");
            }

            if (outputType instanceof ListType lt) {
               this.checkNoEntityInside(lt.element(), span, "List");
            }
         }
      }
   }

   private void checkNoEntityInside(SirType type, SourceSpan span, String wrapper) {
      if (type instanceof DeclaredType dt && dt.kind() == DeclaredType.DeclaredKind.ENTITY) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "Entity 必须通过 Ref<Entity> 使用，" + wrapper + "<Entity> 非法", span));
      }

      if (type instanceof OptionalType ot) {
         this.checkNoEntityInside(ot.element(), span, "Optional");
      }

      if (type instanceof ListType lt) {
         this.checkNoEntityInside(lt.element(), span, "List");
      }
   }

   private void resolveFailsClauses(AstCapabilityDecl capDecl) {
      for (AstFailsClause fails : capDecl.failures()) {
         String errorName = fails.error().text();
         Symbol resolved = this.projectNames.get(errorName);
         if (resolved == null) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "未定义 Error: " + errorName, fails.error().span()));
         } else if (!(resolved instanceof Symbol.ErrorSymbol)) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "fails 必须引用 Error 声明: " + errorName, fails.error().span()));
         } else {
            this.bindReference(fails.error(), ReferenceRole.CAPABILITY_FAILS_ERROR, resolved.id());
         }
      }
   }

   private void resolveWorkflow(AstCapabilityDecl capDecl, SymbolId capScopeId, SirType outputType) {
      String capName = capDecl.name().text();
      Set<String> visibleVars = new LinkedHashSet<>();
      if (capDecl.actor().isPresent()) {
         visibleVars.add("actor");
      }

      if (capDecl.input().isPresent()) {
         visibleVars.add("input");
      }

      for (AstStep step : capDecl.workflow().steps()) {
         AstStep var8 = step;
         switch (var8) {
            case AstValidateStep s:
               this.resolveExpression(s.condition(), capName, capScopeId, visibleVars, null);
               this.resolveErrorRef(s.error(), capName, ReferenceRole.VALIDATE_ERROR);
               break;
            case AstLoadStep s:
               this.resolveExpression(s.idExpression(), capName, capScopeId, visibleVars, null);
               this.resolveEntityRef(s.entity(), ReferenceRole.LOAD_ENTITY);
               this.resolveErrorRef(s.error(), capName, ReferenceRole.LOAD_ERROR);
               this.registerStepResultVar(s.result(), s.id(), capName, capScopeId, visibleVars, this.buildRefType(s.entity().text()));
               break;
            case AstFindStep s:
               this.resolveEntityRef(s.entity(), ReferenceRole.FIND_ENTITY);
               SymbolId stepScopeId = SymbolIdFactory.stepScope(this.softwareName, capName, s.id().value());
               this.scopeSymbols.put(stepScopeId, new ArrayList<>());
               this.scopeParents.put(stepScopeId, capScopeId);
               SirType itemType = this.buildRefType(s.entity().text());
               SymbolId itemId = SymbolIdFactory.stepVariable(this.softwareName, capName, s.id().value(), "item");
               Symbol.VariableSymbol itemSym = new Symbol.VariableSymbol(itemId, "item", s.span(), itemType, null);
               this.addSymbol(itemSym, stepScopeId);
               this.findItemBindings.put(s.id(), itemId);
               Set<String> predVars = new LinkedHashSet<>(visibleVars);
               predVars.add("item");
               this.resolveExpression(s.predicate(), capName, stepScopeId, predVars, itemId);
            s.order().ifPresent(order -> this.resolveOrder(order, s.entity().text()));
            s.page().ifPresent(page -> this.resolvePage(page, capName, capScopeId, visibleVars));
            SirType resultType = s.page().isPresent() ? outputType : itemType == null ? null : new ListType(itemType);
               this.registerStepResultVar(s.result(), s.id(), capName, capScopeId, visibleVars, resultType);
               break;
            case AstCreateStep s:
               this.resolveEntityRef(s.entity(), ReferenceRole.CREATE_ENTITY);

               for (AstBinding binding : s.bindings()) {
                  this.resolveExpression(binding.value(), capName, capScopeId, visibleVars, null);
                  this.resolveBindingField(binding, s.entity().text());
               }

               this.registerStepResultVar(s.result(), s.id(), capName, capScopeId, visibleVars, this.buildRefType(s.entity().text()));
               break;
            case AstUpdateStep s:
               this.resolveVarTarget(s.target(), capName, capScopeId, visibleVars, ReferenceRole.UPDATE_TARGET);
               SymbolId targetVarSymId = this.referenceSiteBindingsTarget(s.target().id());
               String entityName = this.entityNameFromBinding(targetVarSymId);

               for (AstBinding binding : s.bindings()) {
                  this.resolveExpression(binding.value(), capName, capScopeId, visibleVars, null);
                  this.resolveBindingField(binding, entityName);
               }
               break;
            case AstPersistStep s:
               this.resolveVarTarget(s.target(), capName, capScopeId, visibleVars, ReferenceRole.PERSIST_TARGET);
               s.failure().ifPresent(failure -> this.resolveErrorRef(failure, capName, ReferenceRole.PERSIST_FAILURE));
               break;
            case AstReturnStep var29:
               AstReturnStep s = (AstReturnStep)var8;
               this.resolveExpression(s.value(), capName, capScopeId, visibleVars, null);
               break;
            default:
         }
      }
   }

   private void resolveOrder(AstFindOrder order, String entityName) {
      for (AstFindOrderKey key : order.keys()) {
         this.resolveOrderKey(key, entityName);
      }
   }

   private void resolveOrderKey(AstFindOrderKey key, String entityName) {
      if (entityName == null || !this.entityDecls.containsKey(entityName)) {
         return;
      }

      String fieldName = key.field().text();
      SymbolId fieldSymId = this.entityFieldSymbols.getOrDefault(entityName, Map.of()).get(fieldName);
      if (fieldSymId == null && this.entityDecls.get(entityName).identity().name().text().equals(fieldName)) {
         fieldSymId = this.entityIdentitySymbols.get(entityName);
      }

      if (fieldSymId != null) {
         this.bindReference(key.field(), ReferenceRole.ORDER_FIELD, fieldSymId);
      } else {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "未知字段: " + fieldName, key.field().span()));
      }
   }

   private void resolvePage(AstFindPage page, String capName, SymbolId capScopeId, Set<String> visibleVars) {
      this.resolveExpression(page.page(), capName, capScopeId, visibleVars, null);
      this.resolveExpression(page.size(), capName, capScopeId, visibleVars, null);
      this.resolveErrorRef(page.error(), capName, ReferenceRole.PAGE_ERROR);
   }

   private SymbolId referenceSiteBindingsTarget(AstNodeId siteId) {
      ReferenceSiteBinding binding = this.referenceSiteBindings.get(siteId);
      return binding == null ? null : binding.targetSymbol();
   }

   private RefType buildRefType(String entityName) {
      return this.entityDecls.containsKey(entityName) ? new RefType(entityName, SymbolIdFactory.declaration(this.softwareName, "entity", entityName)) : null;
   }

   private void registerStepResultVar(AstName result, AstNodeId stepNodeId, String capName, SymbolId capScopeId, Set<String> visibleVars, SirType varType) {
      String varName = result.text();
      if (visibleVars.contains(varName)) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-001", "重复局部变量: " + varName, result.span()));
      } else {
         visibleVars.add(varName);
         SymbolId varId = SymbolIdFactory.stepVariable(this.softwareName, capName, stepNodeId.value(), varName);
         Symbol.VariableSymbol varSym = new Symbol.VariableSymbol(varId, varName, result.span(), varType, null);
         this.addSymbol(varSym, capScopeId);
         this.bind(stepNodeId, varId);
      }
   }

   private void resolveEntityRef(AstNameRef ref, ReferenceRole role) {
      String entityName = ref.text();
      Symbol resolved = this.projectNames.get(entityName);
      if (resolved == null) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "未定义符号: " + entityName, ref.span()));
      } else if (resolved instanceof Symbol.TypeSymbol ts && ts.kind() == SymbolKind.ENTITY) {
         this.bindReference(ref, role, resolved.id());
      } else {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "目标必须是 Entity: " + entityName, ref.span()));
      }
   }

   private void resolveErrorRef(AstNameRef ref, String capName, ReferenceRole role) {
      String errorName = ref.text();
      Symbol resolved = this.projectNames.get(errorName);
      if (resolved == null) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "未定义 Error: " + errorName, ref.span()));
      } else if (!(resolved instanceof Symbol.ErrorSymbol)) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "必须引用 Error 声明: " + errorName, ref.span()));
      } else {
         this.bindReference(ref, role, resolved.id());
      }
   }

   private void resolveVarTarget(AstNameRef target, String capName, SymbolId capScopeId, Set<String> visibleVars, ReferenceRole role) {
      String varName = target.text();
      if (!visibleVars.contains(varName)) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-001", "引用未定义局部变量: " + varName, target.span()));
      } else {
         Symbol resolved = this.lookupInScope(varName, capScopeId);
         if (resolved instanceof Symbol.VariableSymbol) {
            this.bindReference(target, role, resolved.id());
         } else {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "目标必须是局部变量: " + varName, target.span()));
         }
      }
   }

   private void resolveBindingField(AstBinding binding, String entityName) {
      String fieldName = binding.fieldName().text();
      SymbolId fieldSymId = null;
      if (this.entityDecls.containsKey(entityName) && this.entityDecls.get(entityName).identity().name().text().equals(fieldName)) {
         fieldSymId = this.entityIdentitySymbols.get(entityName);
      } else if (this.entityFieldSymbols.containsKey(entityName)) {
         fieldSymId = this.entityFieldSymbols.get(entityName).get(fieldName);
      }

      if (fieldSymId != null) {
         this.bindReference(binding.fieldName(), ReferenceRole.BINDING_FIELD, fieldSymId);
      } else if (entityName != null) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "未知字段: " + fieldName, binding.fieldName().span()));
      }
   }

   private String entityNameFromBinding(SymbolId varSymId) {
      if (varSymId == null) {
         return null;
      }

      Symbol sym = this.symbolsById.get(varSymId);
      return sym instanceof Symbol.VariableSymbol vs && vs.type() instanceof RefType rt ? rt.entityName() : null;
   }

   private void resolveExpression(AstExpression expr, String capName, SymbolId scopeId, Set<String> visibleVars, SymbolId currentItemSymbol) {
      if (expr != null) {
         switch (expr) {
            case AstNameExpression e:
               this.resolveNameExpression(e, capName, scopeId, visibleVars, currentItemSymbol);
               break;
            case AstMemberExpression e:
               this.resolveMemberExpression(e, capName, scopeId, visibleVars, currentItemSymbol);
               break;
            case AstGroupedExpression e:
               this.resolveExpression(e.inner(), capName, scopeId, visibleVars, currentItemSymbol);
               break;
            case AstPresentExpression e:
               this.resolvePresentExpression(e, capName, scopeId, visibleVars, currentItemSymbol);
               break;
            case AstUnaryExpression e:
               this.resolveExpression(e.operand(), capName, scopeId, visibleVars, currentItemSymbol);
               break;
            case AstBinaryExpression e:
               this.resolveExpression(e.left(), capName, scopeId, visibleVars, currentItemSymbol);
               this.resolveExpression(e.right(), capName, scopeId, visibleVars, currentItemSymbol);
               break;
            default:
         }
      }
   }

   private void resolveNameExpression(AstNameExpression expr, String capName, SymbolId scopeId, Set<String> visibleVars, SymbolId currentItemSymbol) {
      String name = expr.name().text();
      if ("item".equals(name)) {
         if (currentItemSymbol != null) {
            this.bindReference(expr.name(), ReferenceRole.EXPRESSION_NAME, currentItemSymbol);
         } else {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-001", "item 仅在 Find 谓词内可用: " + name, expr.span()));
         }
      } else {
         Symbol found = this.lookupInScope(name, scopeId);
         if (found instanceof Symbol.VariableSymbol vs) {
            if (!visibleVars.contains(name)) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-001", "变量先使用后声明或不在作用域内: " + name, expr.span()));
            } else {
               this.bindReference(expr.name(), ReferenceRole.EXPRESSION_NAME, vs.id());
            }
         } else if (found instanceof Symbol.TypeSymbol ts && ts.kind() == SymbolKind.ENUM) {
            this.bindReference(expr.name(), ReferenceRole.EXPRESSION_NAME, ts.id());
         } else if (found == null) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-FLOW-001", "未定义符号: " + name, expr.span()));
         } else {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "类型名不能作为运行时表达式: " + name, expr.span()));
         }
      }
   }

   private void resolveMemberExpression(AstMemberExpression expr, String capName, SymbolId scopeId, Set<String> visibleVars, SymbolId currentItemSymbol) {
      this.resolveExpression(expr.receiver(), capName, scopeId, visibleVars, currentItemSymbol);
      String memberName = expr.member().text();
      SymbolId receiverSymbolId = this.referenceBindings.get(this.receiverReferenceId(expr.receiver()));
      SirType receiverType = this.receiverTypeFor(receiverSymbolId);
      SymbolId memberSymId = null;
      if (receiverType instanceof RefType rt) {
         Map<String, SymbolId> fields = this.entityFieldSymbols.get(rt.entityName());
         if (fields != null) {
            memberSymId = fields.get(memberName);
         }

         if (memberSymId == null
            && this.entityDecls.containsKey(rt.entityName())
            && this.entityDecls.get(rt.entityName()).identity().name().text().equals(memberName)) {
            memberSymId = this.entityIdentitySymbols.get(rt.entityName());
         }

         if (memberSymId == null) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "未定义字段: " + rt.entityName() + "." + memberName, expr.member().span()));
         }
      } else if (receiverType instanceof DeclaredType dt && dt.kind() == DeclaredType.DeclaredKind.INPUT) {
         Map<String, SymbolId> fields = this.inputFieldSymbols.get(dt.name());
         if (fields != null) {
            memberSymId = fields.get(memberName);
         }

         if (memberSymId == null) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "未定义字段: " + dt.name() + "." + memberName, expr.member().span()));
         }
      } else if (receiverType instanceof DeclaredType dt && dt.kind() == DeclaredType.DeclaredKind.ENUM) {
         Map<String, SymbolId> members = this.enumMemberSymbols.get(dt.name());
         if (members != null) {
            memberSymId = members.get(memberName);
         }

         if (memberSymId == null) {
            this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "未定义枚举成员: " + dt.name() + "." + memberName, expr.member().span()));
         }
      } else if (receiverType != null) {
         this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "不能访问此类型的成员: " + receiverType, expr.span()));
      }

      if (memberSymId != null) {
         this.bindReference(expr.member(), ReferenceRole.MEMBER_ACCESS, memberSymId);
      }
   }

   private AstNodeId receiverReferenceId(AstExpression receiver) {
      return receiverReferenceIdOf(receiver);
   }

   private static AstNodeId receiverReferenceIdOf(AstExpression receiver) {
      if (receiver instanceof AstGroupedExpression ge) {
         return receiverReferenceIdOf(ge.inner());
      } else if (receiver instanceof AstNameExpression ne) {
         return ne.name().id();
      } else {
         return receiver instanceof AstMemberExpression me ? me.member().id() : receiver.id();
      }
   }

   /**
   * Resolves {@code <reference>.present}.
   *
   * <p>Resolution records the binding the test reads and rejects the construct where it
   * cannot have meaning: presence exists only for a field of a patch payload, because a
   * payload is the only place where "absent" and "explicitly null" differ. The bound
   * field symbol is what a later phase renders the presence flag from.
   */
   private void resolvePresentExpression(AstPresentExpression expr, String capName, SymbolId scopeId, Set<String> visibleVars, SymbolId currentItemSymbol) {
      this.resolveExpression(expr.target(), capName, scopeId, visibleVars, currentItemSymbol);
      AstNodeId targetSiteId = receiverReferenceIdOf(expr.target());
      SymbolId targetSymbolId = this.referenceBindings.get(targetSiteId);
      if (targetSymbolId == null) {
         return;
      }

      if (!this.patchPayloadFieldSymbols.contains(targetSymbolId)) {
         ReferenceSiteBinding site = this.referenceSiteBindings.get(targetSiteId);
         this.diagnostics.add(DiagnosticBuilder.error(
            "SIR-FLOW-001", "present 只适用于 patch 载荷字段: " + site.site().text(), expr.span()
         ));
         return;
      }

      this.bindReference(expr.id(), expr.span(), "present", ReferenceRole.PATCH_FIELD_PRESENCE, targetSymbolId);
   }

   private SirType receiverTypeFor(SymbolId receiverSymbolId) {
      if (receiverSymbolId == null) {
         return null;
      } else {
         Symbol s = this.symbolsById.get(receiverSymbolId);
         if (s == null) {
            return null;
         } else if (s instanceof Symbol.VariableSymbol vs) {
            return vs.type();
         } else if (s instanceof Symbol.TypeSymbol ts) {
            return this.typeByName.get(ts.name());
         } else {
            return s instanceof Symbol.FieldSymbol fs ? fs.type() : null;
         }
      }
   }

   private SirType resolveTypeRef(AstTypeRef typeRef, String ownerName, String position) {
      if (typeRef == null) {
         return null;
      }

      switch (typeRef) {
         case AstNamedTypeRef ref:
            String namex = ref.name().text();
            SirType resolvedx = this.typeByName.get(namex);
            if (resolvedx == null) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "未定义类型: " + namex, ref.span()));
               return null;
            }

            this.typeRefTypes.put(ref.id(), resolvedx);
            Symbol sym = this.projectNames.get(namex);
            if (sym != null) {
               this.bindReference(ref.name(), ReferenceRole.NAMED_TYPE, sym.id());
            }

            if (resolvedx instanceof DeclaredType dt
               && dt.kind() == DeclaredType.DeclaredKind.ENTITY
               && !"output".equals(position)
               && !"actor".equals(position)) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "Entity 字段必须使用 Ref<Entity>", ref.span()));
            }

            if (resolvedx instanceof PrimitiveType pt && pt == PrimitiveType.UNIT && !"output".equals(position)) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "Unit 不能出现在此位置: " + position, ref.span()));
            }

            return resolvedx;
         case AstOptionalTypeRef ref:
            SirType innerx = this.resolveTypeRef(ref.elementType(), ownerName, "Optional");
            if (innerx == null) {
               return null;
            } else if (innerx instanceof OptionalType) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-003", "Optional<Optional<T>> 非法", ref.span()));
               return null;
            } else if (innerx instanceof PrimitiveType pt && pt == PrimitiveType.UNIT) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "Optional<Unit> 非法", ref.span()));
               return null;
            } else {
               if (innerx instanceof DeclaredType dt && dt.kind() == DeclaredType.DeclaredKind.ENTITY) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "Optional<Entity> 非法，必须使用 Ref<Entity>", ref.span()));
                  return null;
               }

               SirType result = new OptionalType(innerx);
               this.typeRefTypes.put(ref.id(), result);
               return result;
            }
         case AstListTypeRef ref:
            SirType inner = this.resolveTypeRef(ref.elementType(), ownerName, "List");
            if (inner == null) {
               return null;
            } else if (inner instanceof PrimitiveType pt && pt == PrimitiveType.UNIT) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "List<Unit> 非法", ref.span()));
               return null;
            } else {
               if (inner instanceof DeclaredType dt && dt.kind() == DeclaredType.DeclaredKind.ENTITY) {
                  this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "List<Entity> 非法，必须使用 Ref<Entity>", ref.span()));
                  return null;
               }

               SirType result = new ListType(inner);
               this.typeRefTypes.put(ref.id(), result);
               return result;
            }
         case AstPageTypeRef ref:
         SirType pageElement = this.resolveTypeRef(ref.elementType(), ownerName, "Page");
         if (pageElement == null) {
               return null;
         }

         if (pageElement instanceof PrimitiveType pt && pt == PrimitiveType.UNIT) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "Page<Unit> 非法", ref.span()));
               return null;
         }

         SirType pageType = new PageType(pageElement);
         this.typeRefTypes.put(ref.id(), pageType);
         return pageType;
         case AstRefTypeRef ref:
            String name = ref.targetName().text();
            SirType resolved = this.typeByName.get(name);
            if (resolved == null) {
               this.diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "未定义类型: " + name, ref.span()));
               return null;
            } else {
               if (resolved instanceof DeclaredType dt && dt.kind() == DeclaredType.DeclaredKind.ENTITY) {
                  RefType result = new RefType(name, dt.symbolId());
                  this.typeRefTypes.put(ref.id(), result);
                  this.bindReference(ref.targetName(), ReferenceRole.REF_TYPE_TARGET, dt.symbolId());
                  return result;
               }

               this.diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-002", "Ref 目标必须是 Entity: " + name, ref.span()));
               return null;
            }
         default:
            throw new IllegalStateException("unexpected type ref: " + typeRef);
      }
   }

   private SymbolTable buildSymbolTable() {
      return SymbolTable.of(List.copyOf(this.allSymbols), this.copyScopeSymbols(), new LinkedHashMap<>(this.scopeParents));
   }

   private Map<SymbolId, List<Symbol>> copyScopeSymbols() {
      Map<SymbolId, List<Symbol>> result = new LinkedHashMap<>();

      for (Entry<SymbolId, List<Symbol>> entry : this.scopeSymbols.entrySet()) {
         result.put(entry.getKey(), List.copyOf(entry.getValue()));
      }

      return result;
   }

   private void bind(AstNodeId nodeId, SymbolId symbolId) {
      SymbolId previous = this.referenceBindings.putIfAbsent(nodeId, symbolId);
      if (previous != null) {
         throw new IllegalStateException("AST node already has a symbol binding: " + nodeId + " -> " + previous);
      }
   }

   private void bindReference(AstNameRef ref, ReferenceRole role, SymbolId targetSymbolId) {
      this.bindReference(ref.id(), ref.span(), ref.text(), role, targetSymbolId);
   }

   private void bindReference(AstNodeId siteId, SourceSpan span, String text, ReferenceRole role, SymbolId targetSymbolId) {
      Symbol target = this.symbolsById.get(targetSymbolId);
      if (target == null) {
         throw new IllegalStateException("bindReference target Symbol not found: " + targetSymbolId + " (site " + siteId.value() + ")");
      }

      if (!role.accepts(target.kind())) {
         throw new IllegalStateException(
            "ReferenceRole " + role + " does not accept SymbolKind " + target.kind() + " (target " + targetSymbolId + ", site " + siteId.value() + ")"
         );
      }

      this.bind(siteId, targetSymbolId);
      ReferenceSite site = new ReferenceSite(siteId, span, text, role);
      ReferenceSiteBinding typedBinding = new ReferenceSiteBinding(site, targetSymbolId);
      ReferenceSiteBinding previous = this.referenceSiteBindings.put(siteId, typedBinding);
      if (previous != null) {
         throw new IllegalStateException(
            "ReferenceSite already bound: " + siteId.value() + " (first target: " + previous.targetSymbol() + ", second target: " + targetSymbolId + ")"
         );
      }
   }

   private void addSymbol(Symbol symbol, SymbolId scopeId) {
      this.allSymbols.add(symbol);
      this.symbolsById.put(symbol.id(), symbol);
      this.scopeSymbols.computeIfAbsent(scopeId, k -> new ArrayList<>()).add(symbol);
   }

   private Symbol lookupInScope(String name, SymbolId scopeId) {
      SymbolId current = scopeId;

      while (current != null) {
         for (Symbol s : this.scopeSymbols.getOrDefault(current, List.of())) {
            if (s.name().equals(name)) {
               return s;
            }
         }

         current = this.scopeParents.get(current);
      }

      return this.projectNames.get(name);
   }

   private SymbolKind declarationKindOf(AstDeclaration decl) {
      return switch (decl) {
         case AstEnumDecl e -> SymbolKind.ENUM;
         case AstEntityDecl e -> SymbolKind.ENTITY;
         case AstInputDecl e -> SymbolKind.INPUT;
         case AstViewDecl e -> SymbolKind.VIEW;
         case AstErrorDecl e -> SymbolKind.ERROR;
         case AstCapabilityDecl e -> SymbolKind.CAPABILITY;
         default -> throw new MatchException(null, null);
      };
   }

   private String declarationName(AstDeclaration decl) {
      return switch (decl) {
         case AstEnumDecl e -> e.name().text();
         case AstEntityDecl e -> e.name().text();
         case AstInputDecl e -> e.name().text();
         case AstViewDecl e -> e.name().text();
         case AstErrorDecl e -> e.name().text();
         case AstCapabilityDecl e -> e.name().text();
         default -> throw new MatchException(null, null);
      };
   }

   private String kindString(SymbolKind kind) {
      return switch (kind) {
         case ENUM -> "enum";
         case ENTITY -> "entity";
         case INPUT -> "input";
         case VIEW -> "view";
         case ERROR -> "error";
         case CAPABILITY -> "capability";
         case PRIMITIVE -> "primitive";
         default -> "other";
      };
   }

   private AstField findFirstField(List<AstField> fields, String name) {
      for (AstField f : fields) {
         if (f.name().text().equals(name)) {
            return f;
         }
      }

      throw new IllegalStateException("field not found: " + name);
   }

   private SourceSpan zeroSpan() {
      return new SourceSpan(this.software.span().source(), new SourcePosition(0, 1, 1), new SourcePosition(0, 1, 1));
   }
}
