package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.RelatedLocation;
import io.kcg.sir.ast.AstConstraint;
import io.kcg.sir.ast.AstDecimalLiteral;
import io.kcg.sir.ast.AstExpression;
import io.kcg.sir.ast.AstIntegerLiteral;
import io.kcg.sir.ast.AstSemanticTypeDecl;
import io.kcg.sir.ast.AstUnaryExpression;
import io.kcg.sir.ast.AstUnaryOperator;
import io.kcg.sir.semantic.feature.SemanticTypes;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.source.SourceSpan;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the {@code type} declarations of a software into the semantic types it has: the language's own and these.
 *
 * <p>A declared type is its base's kind of value, with the base's rules and flags, and with the rules written on the
 * declaration replacing the base's rules of the same name. The declaration is checked here, whether or not a field
 * uses it: the base must exist and not lead back to the type itself, the name must be free, and every rule must be one
 * the kind of value allows, with numeric constants for arguments.
 */
final class SemanticTypeDeclarations {
   private SemanticTypeDeclarations() {
   }

   /** The types of the software, and where each declared one was written (the language's own have no place). */
   record Result(SemanticTypes types, List<AstSemanticTypeDecl> declarations, Map<String, SourceSpan> spans) {
   }

   static Result build(List<AstSemanticTypeDecl> declarations, List<Diagnostic> diagnostics) {
      SemanticTypes builtin = SemanticTypes.builtin();
      Map<String, AstSemanticTypeDecl> declared = new LinkedHashMap<>();
      for (AstSemanticTypeDecl declaration : declarations) {
         String name = declaration.name().text();
         if (builtin.named(name).isPresent()) {
            diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-005", "语义类型名已被语言占用: " + name, declaration.name().span()));
         } else if (declared.containsKey(name)) {
            diagnostics.add(DiagnosticBuilder.errorWithRelated("SIR-SYMBOL-001", "重复定义: " + name, declaration.name().span(),
               new RelatedLocation("首次声明在此处", declared.get(name).span())));
         } else {
            declared.put(name, declaration);
         }
      }

      Map<String, SemanticTypes.Entry> resolved = new LinkedHashMap<>();
      Set<String> failed = new HashSet<>();
      for (AstSemanticTypeDecl declaration : declared.values()) {
         resolve(declaration, builtin, declared, resolved, failed, new ArrayList<>(), diagnostics);
      }

      Map<String, SourceSpan> spans = new HashMap<>();
      declared.forEach((name, declaration) -> spans.put(name, declaration.name().span()));
      return new Result(builtin.plus(new ArrayList<>(resolved.values())), List.copyOf(declared.values()), spans);
   }

   private static Optional<SemanticTypes.Entry> resolve(AstSemanticTypeDecl declaration, SemanticTypes builtin,
         Map<String, AstSemanticTypeDecl> declared, Map<String, SemanticTypes.Entry> resolved, Set<String> failed,
         List<String> path, List<Diagnostic> diagnostics) {
      String name = declaration.name().text();
      if (resolved.containsKey(name)) {
         return Optional.of(resolved.get(name));
      }

      if (failed.contains(name)) {
         return Optional.empty();
      }

      if (path.contains(name)) {
         diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-005", "语义类型的基类型形成环: " + String.join(" -> ", path) + " -> " + name, declaration.base().span()));
         failed.add(name);
         return Optional.empty();
      }

      path.add(name);
      String baseName = declaration.base().text();
      Optional<SemanticTypes.Entry> base = builtin.named(baseName);
      if (base.isEmpty() && declared.containsKey(baseName)) {
         base = resolve(declared.get(baseName), builtin, declared, resolved, failed, path, diagnostics);
      } else if (base.isEmpty()) {
         diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "未知类型: " + baseName, declaration.base().span()));
      }

      path.remove(path.size() - 1);
      if (base.isEmpty()) {
         failed.add(name);
         return Optional.empty();
      }

      Map<String, SemanticTypes.Rule> rules = new LinkedHashMap<>();
      base.get().rules().forEach(rule -> rules.put(rule.name(), rule));
      boolean valid = true;
      Set<String> written = new HashSet<>();
      for (AstConstraint constraint : declaration.constraints()) {
         Optional<SemanticTypes.Rule> rule = rule(constraint, base.get().kind(), diagnostics);
         if (rule.isEmpty()) {
            valid = false;
         } else if (!written.add(rule.get().name())) {
            diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-001", "重复约束: " + rule.get().name(), constraint.name().span()));
            valid = false;
         } else {
            rules.put(rule.get().name(), rule.get());
         }
      }

      if (!valid) {
         failed.add(name);
         return Optional.empty();
      }

      SemanticTypes.Entry entry = new SemanticTypes.Entry(name, base.get().kind(), new ArrayList<>(rules.values()),
         base.get().versioned(), base.get().secret(), false);
      resolved.put(name, entry);
      return Optional.of(entry);
   }

   private static Optional<SemanticTypes.Rule> rule(AstConstraint constraint, PrimitiveType kind, List<Diagnostic> diagnostics) {
      String name = constraint.name().text();
      int expected = switch (name) {
         case "notBlank", "email" -> 0;
         case "min", "max" -> 1;
         case "length", "precision" -> 2;
         default -> -1;
      };
      SourceSpan span = constraint.span();
      if (expected < 0) {
         diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "未知约束名: " + name, constraint.name().span()));
         return Optional.empty();
      }

      if (constraint.arguments().size() != expected) {
         diagnostics.add(DiagnosticBuilder.error("SIR-SYMBOL-002", "约束参数数量错误: " + name + " 需要 " + expected + " 个参数", span));
         return Optional.empty();
      }

      boolean textRule = !name.equals("min") && !name.equals("max") && !name.equals("precision");
      if (name.equals("precision") && kind != PrimitiveType.DECIMAL) {
         diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "precision 只适用于十进制类型", span));
         return Optional.empty();
      }
      boolean numeric = kind == PrimitiveType.INT32 || kind == PrimitiveType.INT64 || kind == PrimitiveType.DECIMAL;
      if (textRule && kind != PrimitiveType.STRING) {
         diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "约束 " + name + " 只适用于文本", span));
         return Optional.empty();
      }

      if (!textRule && !numeric) {
         diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "约束 " + name + " 只适用于数字类型", span));
         return Optional.empty();
      }

      List<String> arguments = new ArrayList<>();
      for (AstExpression argument : constraint.arguments()) {
         Optional<String> text = constant(argument);
         if (text.isEmpty()) {
            diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "类型规则的参数必须是数字常量", argument.span()));
            return Optional.empty();
         }

         arguments.add(text.get());
      }

      if (name.equals("length")) {
         if (!arguments.stream().allMatch(a -> a.matches("[0-9]+"))) {
            diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "length 参数必须是非负整数常量", span));
            return Optional.empty();
         }

         if (new BigInteger(arguments.get(0)).compareTo(new BigInteger(arguments.get(1))) > 0) {
            diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "length 的 min 不能大于 max", span));
            return Optional.empty();
         }
      }

      if (name.equals("precision") && io.kcg.sir.semantic.type.DecimalPrecision.parse(arguments).isEmpty()) {
         diagnostics.add(DiagnosticBuilder.error("SIR-TYPE-001", "precision 参数必须为整数，且 p >= 1、0 <= s <= p", span));
         return Optional.empty();
      }
      return Optional.of(new SemanticTypes.Rule(name, arguments));
   }

   /** A numeric constant as written: {@code 1}, {@code -10.50}; empty for anything else. */
   private static Optional<String> constant(AstExpression expression) {
      return switch (expression) {
         case AstIntegerLiteral literal -> Optional.of(literal.value().toString());
         case AstDecimalLiteral literal -> Optional.of(literal.value().toPlainString());
         case AstUnaryExpression unary when unary.operator() == AstUnaryOperator.NEGATE ->
            constant(unary.operand()).filter(t -> !t.startsWith("-")).map(t -> "-" + t);
         default -> Optional.empty();
      };
   }
}
