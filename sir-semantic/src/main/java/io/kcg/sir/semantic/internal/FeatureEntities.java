package io.kcg.sir.semantic.internal;

import io.kcg.sir.ast.AstBooleanLiteral;
import io.kcg.sir.ast.AstConstraint;
import io.kcg.sir.ast.AstDecimalLiteral;
import io.kcg.sir.ast.AstDeclaration;
import io.kcg.sir.ast.AstEntityDecl;
import io.kcg.sir.ast.AstEntityExtensionDecl;
import io.kcg.sir.ast.AstExpression;
import io.kcg.sir.ast.AstField;
import io.kcg.sir.ast.AstIntegerLiteral;
import io.kcg.sir.ast.AstListTypeRef;
import io.kcg.sir.ast.AstNamedTypeRef;
import io.kcg.sir.ast.AstOptionalTypeRef;
import io.kcg.sir.ast.AstPageTypeRef;
import io.kcg.sir.ast.AstRefTypeRef;
import io.kcg.sir.ast.AstStringLiteral;
import io.kcg.sir.ast.AstTypeRef;
import io.kcg.sir.ast.AstUnaryExpression;
import io.kcg.sir.semantic.feature.FeatureTemplate;
import io.kcg.sir.semantic.feature.SemanticTypes;
import io.kcg.sir.semantic.type.PrimitiveType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The entities of a software as a feature template sees them, for a {@code use} whose template goes through an entity's
 * fields.
 *
 * <p>An entity's fields are the ones it declares, then the ones {@code extend} declarations add, in the order the
 * entity's own fields are merged later: so a feature over an account sees the login fields another feature gave it.
 * Only the entity's name decides which entity is meant here; whether the use's file may name it is checked on the
 * expanded declarations, like for any declaration.
 */
final class FeatureEntities {
   private FeatureEntities() {
   }

   /** The plain type of each kind of value: what a field of a ruled type is written as where rules may not go. */
   private static final Map<PrimitiveType, String> PLAIN = Map.of(
      PrimitiveType.BOOLEAN, "Flag", PrimitiveType.INT32, "Count", PrimitiveType.INT64, "Id", PrimitiveType.DECIMAL, "Money",
      PrimitiveType.STRING, "Text", PrimitiveType.UUID, "Token", PrimitiveType.DATE, "Day", PrimitiveType.DATE_TIME, "Moment");

   static Function<String, Optional<FeatureTemplate.Entity>> of(List<List<AstDeclaration>> units, SemanticTypes types) {
      Map<String, AstEntityDecl> entities = new LinkedHashMap<>();
      Map<String, List<AstField>> added = new LinkedHashMap<>();
      for (List<AstDeclaration> declarations : units) {
         for (AstDeclaration declaration : declarations) {
            if (declaration instanceof AstEntityDecl entity) {
               entities.putIfAbsent(entity.name().text(), entity);
            } else if (declaration instanceof AstEntityExtensionDecl extension) {
               added.computeIfAbsent(extension.entity().text(), n -> new ArrayList<>()).addAll(extension.fields());
            }
         }
      }

      return name -> Optional.ofNullable(entities.get(name)).map(entity -> {
         List<FeatureTemplate.Field> fields = new ArrayList<>();
         for (AstField field : entity.fields()) fields.add(field(field, types));
         for (AstField field : added.getOrDefault(name, List.of())) fields.add(field(field, types));
         return new FeatureTemplate.Entity(name, entity.identity().name().text(), type(entity.identity().type()), fields);
      });
   }

   private static FeatureTemplate.Field field(AstField field, SemanticTypes types) {
      Optional<SemanticTypes.Entry> entry = semanticType(field.type(), types);
      boolean secret = field.secret() || entry.map(SemanticTypes.Entry::secret).orElse(false);
      boolean versioned = field.versioned() || entry.map(SemanticTypes.Entry::versioned).orElse(false);
      String rules = field.constraints().isEmpty() ? ""
         : " where " + field.constraints().stream().map(FeatureEntities::constraint).collect(Collectors.joining(", "));
      return new FeatureTemplate.Field(field.name().text(), type(field.type()), base(field.type(), types), rules, secret, versioned,
         field.defaultValue().isPresent());
   }

   private static Optional<SemanticTypes.Entry> semanticType(AstTypeRef type, SemanticTypes types) {
      return switch (type) {
         case AstNamedTypeRef named -> types.named(named.name().text());
         case AstOptionalTypeRef optional -> semanticType(optional.elementType(), types);
         default -> Optional.empty();
      };
   }

   static String type(AstTypeRef type) {
      return switch (type) {
         case AstNamedTypeRef named -> named.name().text();
         case AstOptionalTypeRef optional -> "Optional<" + type(optional.elementType()) + ">";
         case AstListTypeRef list -> "List<" + type(list.elementType()) + ">";
         case AstPageTypeRef page -> "Page<" + type(page.elementType()) + ">";
         case AstRefTypeRef ref -> "Ref<" + ref.targetName().text() + ">";
         default -> throw new IllegalStateException("unknown type reference " + type);
      };
   }

   /** The type with every ruled semantic type replaced by the plain type of its kind of value. */
   private static String base(AstTypeRef type, SemanticTypes types) {
      return switch (type) {
         case AstNamedTypeRef named -> types.named(named.name().text())
            .filter(entry -> !entry.rules().isEmpty() || entry.secret() || entry.versioned())
            .map(entry -> PLAIN.get(entry.kind()))
            .orElse(named.name().text());
         case AstOptionalTypeRef optional -> "Optional<" + base(optional.elementType(), types) + ">";
         default -> type(type);
      };
   }

   private static String constraint(AstConstraint constraint) {
      if (constraint.arguments().isEmpty()) return constraint.name().text();
      return constraint.name().text() + "(" + constraint.arguments().stream().map(FeatureEntities::constant).collect(Collectors.joining(", ")) + ")";
   }

   private static String constant(AstExpression expression) {
      return switch (expression) {
         case AstIntegerLiteral integer -> integer.value().toString();
         case AstDecimalLiteral decimal -> decimal.value().toPlainString();
         case AstBooleanLiteral bool -> Boolean.toString(bool.value());
         case AstStringLiteral string -> quoted(string.value());
         case AstUnaryExpression unary -> "-" + constant(unary.operand());
         default -> throw new IllegalStateException("a rule argument is a constant: " + expression);
      };
   }

   private static String quoted(String value) {
      StringBuilder out = new StringBuilder("\"");
      for (char c : value.toCharArray()) {
         switch (c) {
            case '"' -> out.append("\\\"");
            case '\\' -> out.append("\\\\");
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            case '\b' -> out.append("\\b");
            case '\f' -> out.append("\\f");
            default -> out.append(c);
         }
      }
      return out.append('"').toString();
   }
}
