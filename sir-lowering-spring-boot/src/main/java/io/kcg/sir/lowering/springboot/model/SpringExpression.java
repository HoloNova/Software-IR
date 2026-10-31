package io.kcg.sir.lowering.springboot.model;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

public sealed interface SpringExpression
   permits SpringExpression.BooleanLiteral,
   SpringExpression.IntegerLiteral,
   SpringExpression.DecimalLiteral,
   SpringExpression.StringLiteral,
   SpringExpression.UnitLiteral,
   SpringExpression.NowExpression,
   SpringExpression.NameExpression,
   SpringExpression.MemberExpression,
   SpringExpression.UnaryExpression,
   SpringExpression.BinaryExpression,
   SpringExpression.TimeShiftExpression,
   SpringExpression.DigestExpression,
   SpringExpression.PayloadPresence,
    SpringExpression.OptionalPayloadValue,
    SpringExpression.IntegralToDecimal,
   SpringExpression.ExistsPredicate {
   LoweredNodeId id();

   LoweredOrigin origin();

   LoweredJavaType type();

   private static void requireNode(LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type) {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(type, "type");
   }

   private static void requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) {
         throw new IllegalArgumentException(name + " must not be blank");
      }
   }

   record BinaryExpression(
      LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type, SpringExpression left, SpringExpression.BinaryOperator operator, SpringExpression right,
      java.util.Optional<PresentComparison> presentComparison
   ) implements SpringExpression {
      public BinaryExpression {
         SpringExpression.requireNode(id, origin, type);
         Objects.requireNonNull(left, "left");
         Objects.requireNonNull(operator, "operator");
         Objects.requireNonNull(right, "right");
         Objects.requireNonNull(presentComparison, "presentComparison");
      }

      public BinaryExpression(LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type,
            SpringExpression left, BinaryOperator operator, SpringExpression right) {
         this(id, origin, type, left, operator, right, java.util.Optional.empty());
      }
   }

   /** A comparison is false unless its optional operands have values. The target chooses the
    * underlying representation and guards here; renderers never infer a null policy. */
   record PresentComparison(LoweredJavaType operandType, boolean checkLeft, boolean checkRight) {
      public PresentComparison {
         Objects.requireNonNull(operandType, "operandType");
         if (!checkLeft && !checkRight) throw new IllegalArgumentException("a present comparison guards an optional operand");
      }
   }

   enum BinaryOperator {
      OR,
      AND,
      EQ,
      NE,
      LT,
      LE,
      GT,
      GE,
      CONTAINS_LITERAL,
      MATCHES,
      ADD,
      SUB,
      MUL,
      DAYS_BETWEEN,
      MAX,
      MIN,
      ADD_DAYS;
   }

   /**
   * The digest of a text, written where a secret field is bound: the service stores what the value
   * produces, never the value itself. Only a binding to a secret field gets one.
   */
   record DigestExpression(
      LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type, SpringExpression operand
   ) implements SpringExpression {
      public DigestExpression {
         SpringExpression.requireNode(id, origin, type);
         Objects.requireNonNull(operand, "operand");
      }
   }

   /**
   * A date or date-time moved by a whole number of units: {@code base.plus(amount, unit)} or
   * {@code base.minus(amount, unit)}. The amount is already known to fit a 32-bit integer, and
   * the unit is one the base's own Java type can move by.
   */
   record TimeShiftExpression(
      LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type, SpringExpression base,
      SpringExpression.ShiftDirection direction, long amount, SpringExpression.ShiftUnit unit
   ) implements SpringExpression {
      public TimeShiftExpression {
         SpringExpression.requireNode(id, origin, type);
         Objects.requireNonNull(base, "base");
         Objects.requireNonNull(direction, "direction");
         Objects.requireNonNull(unit, "unit");
         if (amount < 0 || amount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("a time shift amount must fit a 32-bit integer: " + amount);
         }
      }
   }

   enum ShiftDirection {
      ADD,
      SUBTRACT
   }

   enum ShiftUnit {
      SECONDS,
      MINUTES,
      HOURS,
      DAYS
   }

   /**
   * The literal-match plan for one {@code CONTAINS_LITERAL} node of a find predicate.
   *
   * <p>{@link #expressionId()} is the lowered node of the operator it describes, so the plan and the
   * predicate can be checked against each other in both directions. {@link #escapeCharacter()} and
   * {@link #escapedLiterals()} come from the target query policy, never from the renderer.
   * {@link #absentIsFalse()} says that an operand is optional, so an absent value must make the match false
   * rather than UNKNOWN (the same truth table as an optional comparison, also under {@code not}).
   */
   record StringMatch(
      LoweredNodeId id, LoweredOrigin origin, LoweredNodeId expressionId, char escapeCharacter, List<String> escapedLiterals,
      boolean absentIsFalse
   ) {
      public StringMatch {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(origin, "origin");
         Objects.requireNonNull(expressionId, "expressionId");
         if (escapeCharacter == 0) {
         throw new IllegalArgumentException("escapeCharacter must not be NUL");
         }

         escapedLiterals = List.copyOf(Objects.requireNonNull(escapedLiterals, "escapedLiterals"));
         if (escapedLiterals.isEmpty()) {
         throw new IllegalArgumentException("escapedLiterals must not be empty");
         }
      }
   }

   record BooleanLiteral(LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type, boolean value) implements SpringExpression {
      public BooleanLiteral {
         SpringExpression.requireNode(id, origin, type);
      }
   }

   record DecimalLiteral(LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type, BigDecimal value) implements SpringExpression {
      public DecimalLiteral {
         SpringExpression.requireNode(id, origin, type);
         Objects.requireNonNull(value, "value");
      }
   }

   record IntegerLiteral(LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type, BigInteger value) implements SpringExpression {
      public IntegerLiteral {
         SpringExpression.requireNode(id, origin, type);
         Objects.requireNonNull(value, "value");
      }
   }

   record MemberExpression(
      LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type, SpringExpression receiver, SymbolId resolvedMember, String targetMember
   ) implements SpringExpression {
      public MemberExpression {
         SpringExpression.requireNode(id, origin, type);
         Objects.requireNonNull(receiver, "receiver");
         Objects.requireNonNull(resolvedMember, "resolvedMember");
         SpringExpression.requireText(targetMember, "targetMember");
      }
   }

   record NameExpression(LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type, SymbolId resolvedSymbol, String targetName) implements SpringExpression {
      public NameExpression {
         SpringExpression.requireNode(id, origin, type);
         Objects.requireNonNull(resolvedSymbol, "resolvedSymbol");
         SpringExpression.requireText(targetName, "targetName");
      }
   }

   /**
   * Whether a patch request carried the named change property.
   *
   * <p>This is the normalized form of {@code input.x.present}: the property name comes from resolution,
   * so the renderer asks the target's own presence flag instead of re-deriving a name.
   */
   record PayloadPresence(LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type, String sourcePropertyName) implements SpringExpression {
      public PayloadPresence {
         SpringExpression.requireNode(id, origin, type);
         SpringExpression.requireText(sourcePropertyName, "sourcePropertyName");
      }
   }

   /** Reads a DTO Optional as its nullable scalar value; entity columns already use nullable values. */
   record OptionalPayloadValue(LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type, SpringExpression payloadMember)
      implements SpringExpression {
      public OptionalPayloadValue {
         SpringExpression.requireNode(id, origin, type);
         Objects.requireNonNull(payloadMember, "payloadMember");
      }
   }

    /** A target-decided exact conversion; renderers must not infer numeric promotion. */
    record IntegralToDecimal(LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type, SpringExpression operand)
       implements SpringExpression {
       public IntegralToDecimal {
          SpringExpression.requireNode(id, origin, type);
          Objects.requireNonNull(operand, "operand");
       }
    }

    record NowExpression(LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type) implements SpringExpression {
      public NowExpression {
         SpringExpression.requireNode(id, origin, type);
      }
   }

   record StringLiteral(LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type, String value) implements SpringExpression {
      public StringLiteral {
         SpringExpression.requireNode(id, origin, type);
         Objects.requireNonNull(value, "value");
      }
   }

   record UnaryExpression(LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type, SpringExpression.UnaryOperator operator, SpringExpression operand)
      implements SpringExpression {
      public UnaryExpression {
         SpringExpression.requireNode(id, origin, type);
         Objects.requireNonNull(operator, "operator");
         Objects.requireNonNull(operand, "operand");
      }
   }

   enum UnaryOperator {
      NOT,
      NEGATE,
      IS_NONE,
      IS_PRESENT;
   }

   record UnitLiteral(LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type) implements SpringExpression {
      public UnitLiteral {
         SpringExpression.requireNode(id, origin, type);
      }
   }

   /**
   * The correlated {@code EXISTS} an existence predicate lowers to.
   *
   * <p>{@link #subquerySql()} is the subquery body with the target's value placeholders already in
   * place and {@link #arguments()} the values for them in order. The correlated form — the related
   * table, its alias, the join columns and the compared constants — is decided here, so the renderer
   * substitutes placeholder syntax and passes the values through without choosing anything. Values
   * stay typed because the renderer writes them as parameters, never into the text.
   */
   record ExistsPredicate(
      LoweredNodeId id, LoweredOrigin origin, LoweredJavaType type, String subquerySql, List<SpringExpression.ExistsArgument> arguments
   ) implements SpringExpression {
      public ExistsPredicate {
         SpringExpression.requireNode(id, origin, type);
         SpringExpression.requireText(subquerySql, "subquerySql");
         arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
      }
   }

   /** One value a correlated subquery binds, in the target's own value kinds. */
   sealed interface ExistsArgument
      permits SpringExpression.ExistsArgument.Text,
      SpringExpression.ExistsArgument.Integral,
      SpringExpression.ExistsArgument.Decimal,
      SpringExpression.ExistsArgument.Flag,
      SpringExpression.ExistsArgument.Value {
      /** A request-time value captured by its existing binding; never part of the SQL text. */
      record Value(SpringExpression expression) implements SpringExpression.ExistsArgument {
         public Value { Objects.requireNonNull(expression, "expression"); }
      }

      record Text(String value) implements SpringExpression.ExistsArgument {
         public Text {
            Objects.requireNonNull(value, "value");
         }
      }

      record Integral(long value) implements SpringExpression.ExistsArgument {
      }

      record Decimal(BigDecimal value) implements SpringExpression.ExistsArgument {
         public Decimal {
            Objects.requireNonNull(value, "value");
         }
      }

      record Flag(boolean value) implements SpringExpression.ExistsArgument {
      }
   }
}
