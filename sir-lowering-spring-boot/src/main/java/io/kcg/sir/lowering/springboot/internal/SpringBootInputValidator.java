package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.ast.AstBuildTool;
import io.kcg.sir.ast.AstDatabase;
import io.kcg.sir.ast.AstFramework;
import io.kcg.sir.ast.AstInterfaceKind;
import io.kcg.sir.ast.AstLanguage;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstPersistence;
import io.kcg.sir.ast.AstTarget;
import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedBinding;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedConstraint;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedEnum;
import io.kcg.sir.semantic.model.NormalizedError;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.model.NormalizedInput;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.model.NormalizedEnum.NormalizedEnumMember;
import io.kcg.sir.semantic.model.NormalizedExpression.BinaryExpression;
import io.kcg.sir.semantic.model.NormalizedExpression.DecimalLiteral;
import io.kcg.sir.semantic.model.NormalizedExpression.IntegerLiteral;
import io.kcg.sir.semantic.model.NormalizedExpression.MemberExpression;
import io.kcg.sir.semantic.model.NormalizedExpression.NameExpression;
import io.kcg.sir.semantic.model.NormalizedExpression.UnaryExpression;
import io.kcg.sir.semantic.model.NormalizedStep.CreateStep;
import io.kcg.sir.semantic.model.NormalizedStep.FindStep;
import io.kcg.sir.semantic.model.NormalizedStep.LoadStep;
import io.kcg.sir.semantic.model.NormalizedStep.PersistStep;
import io.kcg.sir.semantic.model.NormalizedStep.ReturnStep;
import io.kcg.sir.semantic.model.NormalizedStep.UpdateStep;
import io.kcg.sir.semantic.model.NormalizedStep.ValidateStep;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.Symbol.EnumMemberSymbol;
import io.kcg.sir.semantic.symbol.Symbol.FieldSymbol;
import io.kcg.sir.semantic.symbol.Symbol.VariableSymbol;
import io.kcg.sir.semantic.type.DeclaredType;
import io.kcg.sir.semantic.type.ListType;
import io.kcg.sir.semantic.type.OptionalType;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.RefType;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.semantic.type.DeclaredType.DeclaredKind;
import io.kcg.sir.source.SourceSpan;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SpringBootInputValidator {
   private static final Set<String> JAVA_KEYWORDS = Set.of(
      "abstract",
      "assert",
      "boolean",
      "break",
      "byte",
      "case",
      "catch",
      "char",
      "class",
      "const",
      "continue",
      "default",
      "do",
      "double",
      "else",
      "enum",
      "extends",
      "final",
      "finally",
      "float",
      "for",
      "goto",
      "if",
      "implements",
      "import",
      "instanceof",
      "int",
      "interface",
      "long",
      "native",
      "new",
      "package",
      "private",
      "protected",
      "public",
      "return",
      "short",
      "static",
      "strictfp",
      "super",
      "switch",
      "synchronized",
      "this",
      "throw",
      "throws",
      "transient",
      "try",
      "void",
      "volatile",
      "while",
      "true",
      "false",
      "null"
   );
   private final NormalizedSemanticModel model;
   private final List<LoweringDiagnostic> diagnostics = new ArrayList<>();
   private final Map<SymbolId, NormalizedDeclaration> declarationsById = new LinkedHashMap<>();

   public SpringBootInputValidator(NormalizedSemanticModel model) {
      this.model = model;
   }

   public List<LoweringDiagnostic> validate() {
      this.validateTrustedSnapshot();
      this.validateTarget();
      this.validateNames();
      this.indexDeclarations();

      for (NormalizedDeclaration declaration : this.model.declarations()) {
         this.validateDeclaration(declaration);
      }

      return LoweringDiagnostics.sorted(this.diagnostics);
   }

   private void validateTrustedSnapshot() {
      if (this.model.diagnostics().stream().anyMatch(Diagnostic::isError)) {
         this.error(
            "SIR-LOWER-INPUT-001",
            "NormalizedSemanticModel contains error diagnostics and is not a trusted lowering input",
            this.model.metadata().span(),
            null,
            this.model.metadata().id()
         );
      }
   }

   private void validateTarget() {
      AstTarget target = this.model.target();
      boolean supported = target.language().value() == AstLanguage.JAVA
         && target.languageVersion().equals(BigInteger.valueOf(21L))
         && target.framework().value() == AstFramework.SPRING_BOOT
         && target.persistence().value() == AstPersistence.MYBATIS_PLUS
         && target.database().value() == AstDatabase.MYSQL
         && target.build().value() == AstBuildTool.MAVEN
         && target.interfaceKind().value() == AstInterfaceKind.REST;
      if (!supported) {
         this.error(
            "SIR-LOWER-TARGET-001",
            "Spring Boot target v0.1 requires Java 21, Spring Boot, MyBatis-Plus, MySQL, Maven and REST",
            target.span(),
            null,
            target.id()
         );
      }
   }

   private void validateNames() {
      if (!this.isJavaPackage(this.model.metadata().namespace())) {
         this.error(
            "SIR-LOWER-NAME-001",
            "namespace is not a valid Java package: " + this.model.metadata().namespace(),
            this.model.metadata().namespaceSpan(),
            null,
            this.model.metadata().id()
         );
      }

      for (NormalizedDeclaration declaration : this.model.declarations()) {
         if (!this.isJavaIdentifier(declaration.name())) {
            this.error(
               "SIR-LOWER-NAME-001",
               "declaration name is not a valid Java identifier: " + declaration.name(),
               declaration.span(),
               declaration.id(),
               declaration.sourceNodeId()
            );
         }
      }
   }

   private void indexDeclarations() {
      Set<String> javaNames = new HashSet<>();

      for (NormalizedDeclaration declaration : this.model.declarations()) {
         NormalizedDeclaration previous = this.declarationsById.putIfAbsent(declaration.id(), declaration);
         if (previous != null) {
            this.error(
               "SIR-LOWER-INPUT-001", "duplicate declaration SymbolId: " + declaration.id(), declaration.span(), declaration.id(), declaration.sourceNodeId()
            );
         }

         String javaName = this.targetJavaName(declaration);
         if (!javaNames.add(javaName)) {
            this.error("SIR-LOWER-NAME-001", "duplicate target Java type name: " + javaName, declaration.span(), declaration.id(), declaration.sourceNodeId());
         }

         this.validateBound(declaration.id(), declaration.span(), declaration.sourceNodeId(), null);
      }
   }

   private void validateDeclaration(NormalizedDeclaration declaration) {
      switch (declaration) {
         case NormalizedEnum value:
            for (NormalizedEnumMember member : value.members()) {
               this.validateBound(member.id(), member.span(), member.sourceNodeId(), EnumMemberSymbol.class);
            }
            break;
         case NormalizedEntity value:
            if (!value.persistent()) {
               this.error("SIR-LOWER-FEATURE-001", "non-persistent entities are not supported", value.span(), value.id(), value.sourceNodeId());
            }

            this.validateBound(value.identity().id(), value.identity().span(), value.identity().sourceNodeId(), FieldSymbol.class);
            this.validateType(value.identity().type(), value.identity().span(), value.id(), value.identity().sourceNodeId());
            if (value.identity().type() != PrimitiveType.INT64 && value.identity().type() != PrimitiveType.UUID) {
               this.error(
                  "SIR-LOWER-TYPE-001", "entity identity must lower to Long or UUID", value.identity().span(), value.id(), value.identity().sourceNodeId()
               );
            }

            value.fields().forEach(field -> this.validateField(field, true));
            break;
         case NormalizedInput value:
            value.fields().forEach(field -> this.validateField(field, false));
            break;
         case NormalizedError ignored:
            break;
         case NormalizedCapability value:
            this.validateCapability(value);
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private void validateField(NormalizedField field, boolean persistent) {
      this.validateBound(field.id(), field.span(), field.sourceNodeId(), FieldSymbol.class);
      this.validateType(field.type(), field.span(), field.id(), field.sourceNodeId());

      for (NormalizedConstraint constraint : field.constraints()) {
         if (!Set.of("notBlank", "email", "length", "min", "max").contains(constraint.name())) {
            this.error("SIR-LOWER-FEATURE-001", "unsupported constraint: " + constraint.name(), constraint.span(), field.id(), constraint.sourceNodeId());
         }

         constraint.arguments().forEach(expression -> this.validateExpression(expression, field.id()));
         this.validateConstraintArguments(constraint, field);
      }

      if (!persistent && field.type() instanceof RefType) {
      }
   }

   private void validateConstraintArguments(NormalizedConstraint constraint, NormalizedField field) {
      boolean valid = switch (constraint.name()) {
         case "notBlank", "email" -> constraint.arguments().isEmpty();
         case "length" -> constraint.arguments().size() == 2 && constraint.arguments().stream().allMatch(IntegerLiteral.class::isInstance);
         case "min", "max" -> constraint.arguments().size() == 1 && this.isNumericConstant(constraint.arguments().get(0));
         default -> true;
      };
      if (!valid) {
         this.error(
            "SIR-LOWER-INPUT-001",
            "constraint arguments are not canonical for target lowering: " + constraint.name(),
            constraint.span(),
            field.id(),
            constraint.sourceNodeId()
         );
      }
   }

   private boolean isNumericConstant(NormalizedExpression expression) {
      return !(expression instanceof IntegerLiteral) && !(expression instanceof DecimalLiteral)
         ? expression instanceof UnaryExpression unary
            && unary.operator().name().equals("NEGATE")
            && (unary.operand() instanceof IntegerLiteral || unary.operand() instanceof DecimalLiteral)
         : true;
   }

   private void validateCapability(NormalizedCapability capability) {
      capability.actorSymbol().ifPresent(symbol -> this.validateVariable(symbol, capability));
      capability.inputSymbol().ifPresent(symbol -> this.validateVariable(symbol, capability));
      SpringBootInputValidator.ActorAccess actorAccess = this.actorAccess(capability);
      this.validateType(capability.outputType(), capability.span(), capability.id(), capability.sourceNodeId());

      for (SymbolId failure : capability.fails()) {
         this.validateDeclarationKind(failure, NormalizedError.class, capability);
      }

      for (NormalizedStep step : capability.workflow().steps()) {
         this.validateStep(step, capability, actorAccess);
      }
   }

   private SpringBootInputValidator.ActorAccess actorAccess(NormalizedCapability capability) {
      if (capability.actorSymbol().isEmpty()) {
         return null;
      }

      SymbolId actorSymbol = capability.actorSymbol().get();
      Symbol symbol = this.model.symbols().byId(actorSymbol).orElse(null);
      SymbolId entityId = null;
      if (symbol instanceof VariableSymbol variable) {
         if (variable.type() instanceof RefType ref) {
            entityId = ref.entityId();
         } else if (variable.type() instanceof DeclaredType declared && declared.kind() == DeclaredKind.ENTITY) {
            entityId = declared.symbolId();
         }
      }

      return (entityId == null ? null : (NormalizedDeclaration)this.declarationsById.get(entityId)) instanceof NormalizedEntity entity
         ? new SpringBootInputValidator.ActorAccess(actorSymbol, entity.identity().id())
         : null;
   }

   private void validateStep(NormalizedStep step, NormalizedCapability capability, SpringBootInputValidator.ActorAccess actorAccess) {
      switch (step) {
         case ValidateStep value:
            this.validateExpression(value.condition(), capability.id(), actorAccess);
            this.validateDeclarationKind(value.errorSymbol(), NormalizedError.class, capability);
            break;
         case LoadStep value:
            this.validateDeclarationKind(value.entitySymbol(), NormalizedEntity.class, capability);
            this.validateExpression(value.idExpression(), capability.id(), actorAccess);
            this.validateVariable(value.resultVariable(), capability);
            this.validateDeclarationKind(value.errorSymbol(), NormalizedError.class, capability);
            break;
         case FindStep value:
            this.validateDeclarationKind(value.entitySymbol(), NormalizedEntity.class, capability);
            this.validateExpression(value.predicate(), capability.id(), actorAccess);
            this.validateVariable(value.resultVariable(), capability);
            this.validateVariable(value.itemVariable(), capability);
            break;
         case CreateStep value:
            this.validateDeclarationKind(value.entitySymbol(), NormalizedEntity.class, capability);
            this.validateVariable(value.resultVariable(), capability);
            value.bindings().forEach(binding -> this.validateBinding(binding, capability, actorAccess));
            break;
         case UpdateStep value:
            this.validateVariable(value.targetVariable(), capability);
            value.bindings().forEach(binding -> this.validateBinding(binding, capability, actorAccess));
            break;
         case PersistStep value:
            this.validateVariable(value.targetVariable(), capability);
            break;
         case ReturnStep value:
            this.validateExpression(value.value(), capability.id(), actorAccess);
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private void validateBinding(NormalizedBinding binding, NormalizedCapability capability, SpringBootInputValidator.ActorAccess actorAccess) {
      this.validateBound(binding.fieldSymbol(), binding.value().span(), binding.value().sourceNodeId(), FieldSymbol.class);
      this.validateExpression(binding.value(), capability.id(), actorAccess);
   }

   private void validateExpression(NormalizedExpression expression, SymbolId owner) {
      this.validateExpression(expression, owner, null);
   }

   private void validateExpression(NormalizedExpression expression, SymbolId owner, SpringBootInputValidator.ActorAccess actorAccess) {
      this.validateType(expression.type(), expression.span(), owner, expression.sourceNodeId());
      switch (expression) {
         case NameExpression value:
            this.validateBound(value.resolvedSymbol(), value.span(), value.sourceNodeId(), null);
            break;
         case MemberExpression value:
            this.validateExpression(value.receiver(), owner, actorAccess);
            this.validateBound(value.resolvedMember(), value.span(), value.sourceNodeId(), null);
            if (actorAccess != null
               && value.receiver() instanceof NameExpression receiver
               && receiver.resolvedSymbol().equals(actorAccess.actorSymbol())
               && !value.resolvedMember().equals(actorAccess.identitySymbol())) {
               this.error(
                  "SIR-LOWER-FEATURE-001",
                  "actor identity transport cannot access non-identity member: " + value.resolvedMember(),
                  value.span(),
                  owner,
                  value.sourceNodeId()
               );
            }
            break;
         case UnaryExpression value:
            this.validateExpression(value.operand(), owner, actorAccess);
            break;
         case BinaryExpression value:
            this.validateExpression(value.left(), owner, actorAccess);
            this.validateExpression(value.right(), owner, actorAccess);
            break;
         default:
      }
   }

   private void validateType(SirType type, SourceSpan span, SymbolId owner, AstNodeId nodeId) {
      switch (type) {
         case PrimitiveType ignored:
            break;
         case OptionalType value:
            this.validateType(value.element(), span, owner, nodeId);
            break;
         case ListType value:
            this.validateType(value.element(), span, owner, nodeId);
            break;
         case RefType value:
            this.validateDeclarationKind(value.entityId(), NormalizedEntity.class, span, owner, nodeId);
            break;
         case DeclaredType value:
            Class<? extends NormalizedDeclaration> expected = switch (value.kind()) {
               case ENUM -> NormalizedEnum.class;
               case ENTITY -> NormalizedEntity.class;
               case INPUT -> NormalizedInput.class;
            };
            this.validateDeclarationKind(value.symbolId(), expected, span, owner, nodeId);
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private void validateVariable(SymbolId symbolId, NormalizedCapability capability) {
      this.validateBound(symbolId, capability.span(), capability.sourceNodeId(), VariableSymbol.class);
   }

   private void validateDeclarationKind(SymbolId id, Class<? extends NormalizedDeclaration> expected, NormalizedCapability capability) {
      this.validateDeclarationKind(id, expected, capability.span(), capability.id(), capability.sourceNodeId());
   }

   private void validateDeclarationKind(SymbolId id, Class<? extends NormalizedDeclaration> expected, SourceSpan span, SymbolId owner, AstNodeId nodeId) {
      if (this.isUnknown(id)) {
         this.error("SIR-LOWER-BINDING-001", "unresolved SymbolId is not lowerable: " + id, span, owner, nodeId);
      } else {
         NormalizedDeclaration declaration = this.declarationsById.get(id);
         if (!expected.isInstance(declaration)) {
            this.error("SIR-LOWER-BINDING-001", "SymbolId does not reference a " + expected.getSimpleName() + ": " + id, span, owner, nodeId);
         }
      }
   }

   private void validateBound(SymbolId id, SourceSpan span, AstNodeId nodeId, Class<? extends Symbol> expected) {
      if (this.isUnknown(id)) {
         this.error("SIR-LOWER-BINDING-001", "unresolved SymbolId is not lowerable: " + id, span, id, nodeId);
      } else {
         Symbol symbol = this.model.symbols().byId(id).orElse(null);
         if (symbol == null || expected != null && !expected.isInstance(symbol)) {
            String expectedName = expected == null ? "symbol" : expected.getSimpleName();
            this.error("SIR-LOWER-BINDING-001", "SymbolId is missing or has the wrong kind; expected " + expectedName + ": " + id, span, id, nodeId);
         }
      }
   }

   private String targetJavaName(NormalizedDeclaration declaration) {
      return declaration instanceof NormalizedError ? declaration.name() + "Exception" : declaration.name();
   }

   private boolean isUnknown(SymbolId id) {
      return id == null || id.value().equals("sir://unknown") || id.value().startsWith("sir://unknown/");
   }

   private boolean isJavaPackage(String value) {
      if (value != null && !value.isBlank()) {
         for (String segment : value.split("\\.", -1)) {
            if (!this.isJavaIdentifier(segment)) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private boolean isJavaIdentifier(String value) {
      if (value != null && !value.isBlank() && !JAVA_KEYWORDS.contains(value)) {
         if (!Character.isJavaIdentifierStart(value.codePointAt(0))) {
            return false;
         }

         int offset = Character.charCount(value.codePointAt(0));

         while (offset < value.length()) {
            int point = value.codePointAt(offset);
            if (!Character.isJavaIdentifierPart(point)) {
               return false;
            }

            offset += Character.charCount(point);
         }

         return true;
      } else {
         return false;
      }
   }

   private void error(String code, String message, SourceSpan span, SymbolId symbol, AstNodeId nodeId) {
      this.diagnostics.add(LoweringDiagnostics.error(code, message, span, symbol, nodeId));
   }

   private record ActorAccess(SymbolId actorSymbol, SymbolId identitySymbol) {
   }
}
