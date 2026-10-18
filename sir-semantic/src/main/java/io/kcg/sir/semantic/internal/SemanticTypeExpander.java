package io.kcg.sir.semantic.internal;

import io.kcg.sir.ast.AstConstraint;
import io.kcg.sir.ast.AstDeclaration;
import io.kcg.sir.ast.AstEntityDecl;
import io.kcg.sir.ast.AstExpression;
import io.kcg.sir.ast.AstField;
import io.kcg.sir.ast.AstInputDecl;
import io.kcg.sir.ast.AstIntegerLiteral;
import io.kcg.sir.ast.AstNameRef;
import io.kcg.sir.ast.AstNamedTypeRef;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstOptionalTypeRef;
import io.kcg.sir.ast.AstTypeRef;
import io.kcg.sir.semantic.feature.SemanticTypes;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Gives each field the rules its semantic type brings: a {@code Name} is never blank and at most 100 characters, a
 * {@code Secret} is kept as a digest. A rule the author wrote on the field replaces the rule of the same name that the
 * type brings, so {@code Name where length(1, 64)} is a name of at most 64 characters.
 *
 * <p>After this the rules are ordinary constraints and flags of the field, checked and implemented like the ones the
 * author wrote. The brought rules are located at the field's type, and their ids derive from the field's id.
 */
final class SemanticTypeExpander {
   private SemanticTypeExpander() {
   }

   static List<AstDeclaration> expand(List<AstDeclaration> declarations, SemanticTypes types) {
      List<AstDeclaration> out = new ArrayList<>();
      for (AstDeclaration declaration : declarations) {
         out.add(switch (declaration) {
            case AstEntityDecl entity -> new AstEntityDecl(entity.id(), entity.span(), entity.name(), entity.identity(),
               entity.fields().stream().map(f -> field(f, types)).toList(), entity.credential());
            case AstInputDecl input -> new AstInputDecl(input.id(), input.span(), input.name(), input.patchSourceEntity(),
               input.fields().stream().map(f -> field(f, types)).toList());
            default -> declaration;
         });
      }

      return List.copyOf(out);
   }

   private static AstField field(AstField field, SemanticTypes types) {
      Optional<SemanticTypes.Entry> type = semanticTypeOf(field.type(), types);
      if (type.isEmpty()) {
         return field;
      }

      SemanticTypes.Entry entry = type.get();
      List<AstConstraint> constraints = new ArrayList<>();
      for (SemanticTypes.Rule rule : entry.rules()) {
         if (field.constraints().stream().noneMatch(c -> c.name().text().equals(rule.name()))) {
            constraints.add(constraint(field, rule));
         }
      }

      constraints.addAll(field.constraints());
      return new AstField(field.id(), field.span(), field.name(), field.type(), constraints, field.versioned() || entry.versioned(),
         field.unique(), field.secret() || entry.secret(), field.defaultValue(), field.declaredId());
   }

   private static Optional<SemanticTypes.Entry> semanticTypeOf(AstTypeRef type, SemanticTypes types) {
      return switch (type) {
         case AstNamedTypeRef named -> types.named(named.name().text());
         case AstOptionalTypeRef optional -> semanticTypeOf(optional.elementType(), types);
         default -> Optional.empty();
      };
   }

   /** A numeric constant of a rule as the parser would have built it: a literal, under a negation when it is negative. */
   private static AstExpression argument(String path, io.kcg.sir.source.SourceSpan span, String text) {
      if (text.startsWith("-")) {
         return new io.kcg.sir.ast.AstUnaryExpression(new AstNodeId(path), span, io.kcg.sir.ast.AstUnaryOperator.NEGATE,
            argument(path + "/expression/0001", span, text.substring(1)));
      }

      return text.matches("[0-9]+")
         ? new AstIntegerLiteral(new AstNodeId(path), span, new BigInteger(text))
         : new io.kcg.sir.ast.AstDecimalLiteral(new AstNodeId(path), span, new java.math.BigDecimal(text));
   }

   private static AstConstraint constraint(AstField field, SemanticTypes.Rule rule) {
      String path = field.id().value() + "/type-rule/" + rule.name();
      List<AstExpression> arguments = new ArrayList<>();
      for (int i = 0; i < rule.arguments().size(); i++) {
         arguments.add(argument(path + "/argument/" + i, field.type().span(), rule.arguments().get(i)));
      }

      return new AstConstraint(new AstNodeId(path), field.type().span(), new AstNameRef(new AstNodeId(path + "/name"), field.type().span(), rule.name()), arguments);
   }
}
