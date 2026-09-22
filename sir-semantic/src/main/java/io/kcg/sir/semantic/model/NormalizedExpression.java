package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.ast.AstNodeId;
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
   NormalizedExpression.PresentExpression {
   AstNodeId sourceNodeId();

   SourceSpan span();

   SirType type();

   record BinaryExpression(
      AstNodeId sourceNodeId, SourceSpan span, SirType type, NormalizedExpression left, AstBinaryOperator operator, NormalizedExpression right
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

   record BooleanLiteral(AstNodeId sourceNodeId, SourceSpan span, SirType type, boolean value) implements NormalizedExpression {
      public BooleanLiteral {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
      }
   }

   record DecimalLiteral(AstNodeId sourceNodeId, SourceSpan span, SirType type, BigDecimal value) implements NormalizedExpression {
      public DecimalLiteral {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(value, "value");
      }
   }

   record IntegerLiteral(AstNodeId sourceNodeId, SourceSpan span, SirType type, BigInteger value) implements NormalizedExpression {
      public IntegerLiteral {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(value, "value");
      }
   }

   record MemberExpression(AstNodeId sourceNodeId, SourceSpan span, SirType type, NormalizedExpression receiver, String member, SymbolId resolvedMember)
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

   record NameExpression(AstNodeId sourceNodeId, SourceSpan span, SirType type, String name, SymbolId resolvedSymbol) implements NormalizedExpression {
      public NameExpression {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(name, "name");
         Objects.requireNonNull(resolvedSymbol, "resolvedSymbol");
      }
   }

   record NowExpression(AstNodeId sourceNodeId, SourceSpan span, SirType type) implements NormalizedExpression {
      public NowExpression {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
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
      AstNodeId sourceNodeId, SourceSpan span, SirType type, NormalizedExpression target, SymbolId field
   ) implements NormalizedExpression {
      public PresentExpression {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(target, "target");
         Objects.requireNonNull(field, "field");
      }
   }

   record StringLiteral(AstNodeId sourceNodeId, SourceSpan span, SirType type, String value) implements NormalizedExpression {
      public StringLiteral {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(value, "value");
      }
   }

   record UnaryExpression(AstNodeId sourceNodeId, SourceSpan span, SirType type, AstUnaryOperator operator, NormalizedExpression operand)
      implements NormalizedExpression {
      public UnaryExpression {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(operator, "operator");
         Objects.requireNonNull(operand, "operand");
      }
   }

   record UnitLiteral(AstNodeId sourceNodeId, SourceSpan span, SirType type) implements NormalizedExpression {
      public UnitLiteral {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(type, "type");
      }
   }
}
