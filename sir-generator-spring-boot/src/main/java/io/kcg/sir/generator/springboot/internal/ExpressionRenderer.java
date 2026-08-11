package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.Declared;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.DeclaredKind;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.EntityReference;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.Scalar;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.ScalarKind;
import io.kcg.sir.lowering.springboot.model.SpringExpression.BinaryExpression;
import io.kcg.sir.lowering.springboot.model.SpringExpression.BooleanLiteral;
import io.kcg.sir.lowering.springboot.model.SpringExpression.DecimalLiteral;
import io.kcg.sir.lowering.springboot.model.SpringExpression.IntegerLiteral;
import io.kcg.sir.lowering.springboot.model.SpringExpression.MemberExpression;
import io.kcg.sir.lowering.springboot.model.SpringExpression.NameExpression;
import io.kcg.sir.lowering.springboot.model.SpringExpression.NowExpression;
import io.kcg.sir.lowering.springboot.model.SpringExpression.StringLiteral;
import io.kcg.sir.lowering.springboot.model.SpringExpression.UnaryExpression;
import io.kcg.sir.lowering.springboot.model.SpringExpression.UnitLiteral;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Map;
import java.util.Objects;

final class ExpressionRenderer {
   private ExpressionRenderer() {
   }

   static String renderExpression(SpringExpression expression, Map<SymbolId, String> variableNames) {
      Objects.requireNonNull(expression, "expression");
      Objects.requireNonNull(variableNames, "variableNames");

      return switch (expression) {
         case BooleanLiteral lit -> lit.value() ? "true" : "false";
         case IntegerLiteral lit -> {
            String text = lit.value().toString();
            yield isLongType(lit.type()) ? text + "L" : text;
         }
         case DecimalLiteral lit -> "new java.math.BigDecimal(" + StringEscape.javaString(lit.value().toPlainString()) + ")";
         case StringLiteral lit -> StringEscape.javaString(lit.value());
         case UnitLiteral ignored -> "null";
         case NowExpression now -> renderNow(now.type());
         case NameExpression name -> {
            String override = variableNames.get(name.resolvedSymbol());
            yield override != null ? override : name.targetName();
         }
         case MemberExpression member -> renderMember(member, variableNames);
         case UnaryExpression unary -> renderUnary(unary, variableNames);
         case BinaryExpression binary -> renderBinary(binary, variableNames);
         default -> throw new MatchException(null, null);
      };
   }

   private static String renderNow(LoweredJavaType type) {
      if (type instanceof Scalar s) {
         return switch (s.kind()) {
            case INSTANT -> "java.time.Instant.now()";
            case LOCAL_DATE -> "java.time.LocalDate.now()";
            default -> throw new IllegalStateException("NowExpression requires INSTANT or LOCAL_DATE type, got: " + s.kind());
         };
      } else {
         throw new IllegalStateException("NowExpression requires scalar type, got: " + type);
      }
   }

   private static String renderMember(MemberExpression member, Map<SymbolId, String> variableNames) {
      SpringExpression receiver = member.receiver();
      if (receiver instanceof NameExpression nameExpr && nameExpr.type() instanceof Declared declared && declared.kind() == DeclaredKind.ENUM) {
         String receiverName = variableNames.getOrDefault(nameExpr.resolvedSymbol(), nameExpr.targetName());
         return receiverName + "." + member.targetMember();
      } else {
         String receiverText = renderExpression(receiver, variableNames);
         return receiverText + "." + getter(member.targetMember());
      }
   }

   private static String renderUnary(UnaryExpression unary, Map<SymbolId, String> variableNames) {
      String operand = renderExpression(unary.operand(), variableNames);

      return switch (unary.operator()) {
         case NOT -> "!(" + operand + ")";
         case NEGATE -> "-(" + operand + ")";
      };
   }

   private static String renderBinary(BinaryExpression binary, Map<SymbolId, String> variableNames) {
      String left = renderExpression(binary.left(), variableNames);
      String right = renderExpression(binary.right(), variableNames);
      LoweredJavaType operandType = binary.left().type();

      return switch (binary.operator()) {
         case AND -> "(" + left + ") && (" + right + ")";
         case OR -> "(" + left + ") || (" + right + ")";
         case EQ -> renderEquality(left, right, operandType, "==", true);
         case NE -> renderEquality(left, right, operandType, "!=", false);
         case LT -> renderOrdering(left, right, operandType, "<");
         case LE -> renderOrdering(left, right, operandType, "<=");
         case GT -> renderOrdering(left, right, operandType, ">");
         case GE -> renderOrdering(left, right, operandType, ">=");
      };
   }

   private static String renderEquality(String left, String right, LoweredJavaType type, String primitiveOp, boolean equality) {
      if (isScalarKind(type, ScalarKind.BIG_DECIMAL)) {
         int target = equality ? 0 : 1;
         return "(" + left + ").compareTo(" + right + ") " + (equality ? "==" : "!=") + " 0";
      } else if (type instanceof Declared declared && declared.kind() == DeclaredKind.ENUM) {
         return "(" + left + ") " + primitiveOp + " (" + right + ")";
      } else if (!isScalarKind(type, ScalarKind.STRING)
         && !isScalarKind(type, ScalarKind.UUID)
         && !isScalarKind(type, ScalarKind.LOCAL_DATE)
         && !isScalarKind(type, ScalarKind.INSTANT)
         && !isScalarKind(type, ScalarKind.BOOLEAN)) {
         if (type instanceof EntityReference) {
            return equality ? "java.util.Objects.equals(" + left + ", " + right + ")" : "!java.util.Objects.equals(" + left + ", " + right + ")";
         } else if (!isScalarKind(type, ScalarKind.INTEGER) && !isScalarKind(type, ScalarKind.LONG)) {
            throw new IllegalStateException("Unsupported equality operand type: " + type);
         } else {
            return "(" + left + ") " + primitiveOp + " (" + right + ")";
         }
      } else {
         return equality ? "java.util.Objects.equals(" + left + ", " + right + ")" : "!java.util.Objects.equals(" + left + ", " + right + ")";
      }
   }

   private static String renderOrdering(String left, String right, LoweredJavaType type, String op) {
      if (isScalarKind(type, ScalarKind.BIG_DECIMAL)) {
         return "(" + left + ").compareTo(" + right + ") " + op + " 0";
      } else if (isScalarKind(type, ScalarKind.LOCAL_DATE) || isScalarKind(type, ScalarKind.INSTANT)) {
         return "(" + left + ").compareTo(" + right + ") " + op + " 0";
      } else if (isScalarKind(type, ScalarKind.STRING) || type instanceof EntityReference) {
         return "(" + left + ").compareTo(" + right + ") " + op + " 0";
      } else if (!isScalarKind(type, ScalarKind.INTEGER) && !isScalarKind(type, ScalarKind.LONG)) {
         throw new IllegalStateException("Unsupported ordering operand type: " + type);
      } else {
         return "(" + left + ") " + op + " (" + right + ")";
      }
   }

   private static boolean isScalarKind(LoweredJavaType type, ScalarKind kind) {
      return type instanceof Scalar s && s.kind() == kind;
   }

   private static boolean isLongType(LoweredJavaType type) {
      return isScalarKind(type, ScalarKind.LONG);
   }

   private static String getter(String propertyName) {
      if (propertyName.isEmpty()) {
         throw new IllegalStateException("propertyName must not be empty");
      }

      int first = propertyName.codePointAt(0);
      String head = new String(Character.toChars(Character.toUpperCase(first)));
      String tail = propertyName.substring(Character.charCount(first));
      return "get" + head + tail + "()";
   }
}
