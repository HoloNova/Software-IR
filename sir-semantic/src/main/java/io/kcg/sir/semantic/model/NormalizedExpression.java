package io.kcg.sir.semantic.model;

import io.kcg.sir.semantic.schema.Derived;
import io.kcg.sir.semantic.schema.Fact;
import io.kcg.sir.semantic.schema.Reference;
import io.kcg.sir.semantic.schema.Source;
import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstShiftDirection;
import io.kcg.sir.ast.AstTimeUnit;
import io.kcg.sir.ast.AstUnaryOperator;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.source.SourceSpan;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

public sealed interface NormalizedExpression
   permits NormalizedExpression.BooleanLiteral,
   NormalizedExpression.IntegerLiteral,
   NormalizedExpression.DecimalLiteral,
   NormalizedExpression.StringLiteral,
   NormalizedExpression.UnitLiteral,
   NormalizedExpression.NowExpression,
   NormalizedExpression.NameExpression,
   NormalizedExpression.MemberExpression,
   NormalizedExpression.UnaryExpression,
   NormalizedExpression.BinaryExpression,
   NormalizedExpression.TimeShiftExpression,
   NormalizedExpression.PresentExpression,
   NormalizedExpression.ExistsExpression {
   AstNodeId sourceNodeId();

   SourceSpan span();

   SirType type();

   record BinaryExpression(
      @Source AstNodeId sourceNodeId, @Source SourceSpan span, @Derived SirType type, @Fact NormalizedExpression left, @Fact AstBinaryOperator operator, @Fact NormalizedExpression right
   ) implements NormalizedExpression {
      public BinaryExpression {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(left, "left");
         Objects.requireNonNull(operator, "operator");
         Objects.requireNonNull(right, "right");
      }
   }

   record BooleanLiteral(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Derived SirType type, @Fact boolean value) implements NormalizedExpression {
      public BooleanLiteral {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
      }
   }

   record DecimalLiteral(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Derived SirType type, @Fact BigDecimal value) implements NormalizedExpression {
      public DecimalLiteral {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(value, "value");
      }
   }

   record IntegerLiteral(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Derived SirType type, @Fact BigInteger value) implements NormalizedExpression {
      public IntegerLiteral {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(value, "value");
      }
   }

   record MemberExpression(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Derived SirType type, @Fact NormalizedExpression receiver, @Fact String member, @Derived SymbolId resolvedMember)
      implements NormalizedExpression {
      public MemberExpression {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(receiver, "receiver");
         Objects.requireNonNull(member, "member");
         Objects.requireNonNull(resolvedMember, "resolvedMember");
      }
   }

   record NameExpression(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Derived SirType type, @Fact String name, @Derived SymbolId resolvedSymbol) implements NormalizedExpression {
      public NameExpression {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(name, "name");
         Objects.requireNonNull(resolvedSymbol, "resolvedSymbol");
      }
   }

   record NowExpression(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Derived SirType type) implements NormalizedExpression {
      public NowExpression {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
      }
   }

   /**
   * A date or date-time moved by a whole number of units. The duration belongs to this expression and
   * is not a value of its own, so nothing downstream has a duration type to carry.
   */
   record TimeShiftExpression(
      @Source AstNodeId sourceNodeId, @Source SourceSpan span, @Derived SirType type,
      @Fact NormalizedExpression base, @Fact AstShiftDirection direction, @Fact BigInteger amount, @Fact AstTimeUnit unit
   ) implements NormalizedExpression {
      public TimeShiftExpression {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(base, "base");
         Objects.requireNonNull(direction, "direction");
         Objects.requireNonNull(amount, "amount");
         Objects.requireNonNull(unit, "unit");
      }
   }

   /**
   * The {@code <patch field>.present} test: whether a request carried that field.
   *
   * <p>{@link #target()} is the normalized member access the test reads, so a target
   * can render the same accessor chain and ask its own presence flag; {@link #field()}
   * is the patch payload field whose presence is being asked about.
   */
   record PresentExpression(
      @Source AstNodeId sourceNodeId, @Source SourceSpan span, @Derived SirType type, @Fact NormalizedExpression target, @Derived SymbolId field
   ) implements NormalizedExpression {
      public PresentExpression {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(target, "target");
         Objects.requireNonNull(field, "field");
      }
   }

   record StringLiteral(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Derived SirType type, @Fact String value) implements NormalizedExpression {
      public StringLiteral {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(value, "value");
      }
   }

   record UnaryExpression(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Derived SirType type, @Fact AstUnaryOperator operator, @Fact NormalizedExpression operand)
      implements NormalizedExpression {
      public UnaryExpression {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(operator, "operator");
         Objects.requireNonNull(operand, "operand");
      }
   }

   record UnitLiteral(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Derived SirType type) implements NormalizedExpression {
      public UnitLiteral {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
      }
   }

   /**
   * The {@code any(<Entity>, <conditions>)} existence test: whether one and the same related row
   * satisfies the conditions.
   *
   * <p>{@link #entity()} is the referenced entity and {@link #connectionField()} the field of that
   * entity which points back at the queried root. Recording the connection field here keeps the
   * target from re-deriving which reference the predicate meant, and keeps the conditions one tree
   * so that a target cannot split them into independent tests that mean something else.
   */
   record ExistsExpression(
      @Source AstNodeId sourceNodeId, @Source SourceSpan span, @Derived SirType type, @Reference SymbolId entity, @Derived SymbolId connectionField, @Fact NormalizedExpression conditions
   ) implements NormalizedExpression {
      public ExistsExpression {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(entity, "entity");
         Objects.requireNonNull(connectionField, "connectionField");
         Objects.requireNonNull(conditions, "conditions");
      }
   }
}
