package io.kcg.sir.semantic.internal;

import io.kcg.sir.ast.AstBinaryExpression;
import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.ast.AstExpression;
import io.kcg.sir.ast.AstGroupedExpression;
import io.kcg.sir.ast.AstNameExpression;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.api.ReferenceRole;
import io.kcg.sir.semantic.api.ReferenceSiteBindings;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Map;

/**
 * Finds the comparison in an existence predicate's conditions that connects the related entity to
 * the queried root.
 *
 * <p>The rule lives in one place because two phases need the same answer: the type phase rejects a
 * predicate that has no such comparison, and normalization records which field the connection uses
 * so the target never has to re-derive it.
 *
 * <p>Only the top-level conjunction chain is searched. A connection hidden inside an {@code or} or
 * under a {@code not} would make the related-row test mean something else — "some row is related,
 * or some other row is active" — so those positions deliberately do not count as a connection.
 */
final class ExistenceConnection {

   private ExistenceConnection() {
   }

   /**
    * The field of the related entity that the conditions compare with the {@code find} item, or
    * {@code null} when the conditions carry no such comparison.
    */
   static SymbolId fieldOf(
         AstExpression conditions, ReferenceSiteBindings bindings, Map<AstNodeId, SymbolId> findItemBindings) {
      if (conditions == null) {
         return null;
      }

      if (conditions instanceof AstGroupedExpression grouped) {
         return fieldOf(grouped.inner(), bindings, findItemBindings);
      }

      if (conditions instanceof AstBinaryExpression binary) {
         if (binary.operator() == AstBinaryOperator.AND) {
            SymbolId left = fieldOf(binary.left(), bindings, findItemBindings);
            return left != null ? left : fieldOf(binary.right(), bindings, findItemBindings);
         }

         if (binary.operator() == AstBinaryOperator.EQ) {
            SymbolId forward = connection(binary.left(), binary.right(), bindings, findItemBindings);
            return forward != null ? forward : connection(binary.right(), binary.left(), bindings, findItemBindings);
         }
      }

      return null;
   }

   private static SymbolId connection(
         AstExpression fieldSide, AstExpression itemSide, ReferenceSiteBindings bindings, Map<AstNodeId, SymbolId> findItemBindings) {
      if (!(fieldSide instanceof AstNameExpression field) || !(itemSide instanceof AstNameExpression item)) {
         return null;
      }

      boolean isConditionField = bindings
            .bindingFor(field.name().id())
            .map(binding -> binding.site().role() == ReferenceRole.EXISTS_CONDITION_FIELD)
            .orElse(false);
      if (!isConditionField) {
         return null;
      }

      SymbolId itemSymbol = bindings.targetFor(item.name().id()).orElse(null);
      if (itemSymbol == null || !findItemBindings.containsValue(itemSymbol)) {
         return null;
      }

      return bindings.targetFor(field.name().id()).orElse(null);
   }
}
