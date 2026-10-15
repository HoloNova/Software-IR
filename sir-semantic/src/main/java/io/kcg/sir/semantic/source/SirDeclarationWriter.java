package io.kcg.sir.semantic.source;

import io.kcg.sir.semantic.schema.StructNode;
import io.kcg.sir.semantic.schema.StructValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes one declaration of a software draft as SIR source text.
 *
 * <p>This is the only place that knows how a draft node is spelled in the language. It does not
 * judge whether the software is valid: it writes what the draft says, and the text it returns is
 * then read back by the formal Parse → Resolve → Type → Validate → Normalize run. A draft the writer
 * cannot spell (a string with a control character, an unknown node kind) is refused here rather than
 * written as something that merely looks similar.
 *
 * <p>Layout is fixed and deterministic: two-space steps from the base indentation given by the
 * caller, one clause per line, constraints and constant arguments on the field line.
 */
public final class SirDeclarationWriter {
   private static final String STEP = "  ";

   // Binding strength, loosest first. Postfix and primary expressions are the tightest.
   private static final int OR = 1;
   private static final int AND = 2;
   private static final int EQUALITY = 3;
   private static final int CONTAINS = 4;
   private static final int RELATIONAL = 5;
   private static final int ADDITIVE = 6;
   private static final int UNARY = 7;
   private static final int POSTFIX = 8;

   private static final Map<String, String> OPERATOR_TEXT = Map.ofEntries(
      Map.entry("OR", "or"), Map.entry("AND", "and"), Map.entry("EQ", "=="), Map.entry("NE", "!="),
      Map.entry("GT", ">"), Map.entry("GE", ">="), Map.entry("LT", "<"), Map.entry("LE", "<="),
      Map.entry("CONTAINS_LITERAL", "containsLiteral"), Map.entry("ADD", "+"), Map.entry("SUB", "-")
   );
   private static final Map<String, Integer> OPERATOR_LEVEL = Map.ofEntries(
      Map.entry("OR", OR), Map.entry("AND", AND), Map.entry("EQ", EQUALITY), Map.entry("NE", EQUALITY),
      Map.entry("GT", RELATIONAL), Map.entry("GE", RELATIONAL), Map.entry("LT", RELATIONAL), Map.entry("LE", RELATIONAL),
      Map.entry("CONTAINS_LITERAL", CONTAINS), Map.entry("ADD", ADDITIVE), Map.entry("SUB", ADDITIVE)
   );
   private static final Map<String, String> PRIMITIVE_TEXT = Map.of(
      "BOOLEAN", "Boolean", "INT32", "Int32", "INT64", "Int64", "DECIMAL", "Decimal", "STRING", "String", "UUID", "Uuid", "DATE", "Date", "DATE_TIME", "DateTime", "UNIT", "Unit"
   );

   private static final List<String> REQUIREMENT_ORDER = List.of("AUTHENTICATED", "ATOMIC", "READONLY");

   private final String base;

   /** @param baseIndent the indentation the declaration's first line already has in the file */
   public SirDeclarationWriter(String baseIndent) {
      this.base = baseIndent;
   }

   /** The declaration text; the first line has no leading indentation, later lines carry the base. */
   public String write(StructNode declaration) {
      Lines out = new Lines(this.base);
      switch (declaration.kind()) {
         case "NormalizedEnum" -> this.writeEnum(declaration, out);
         case "NormalizedEntity" -> this.writeEntity(declaration, out);
         case "NormalizedInput" -> this.writeInput(declaration, out);
         case "NormalizedView" -> this.writeView(declaration, out);
         case "NormalizedError" -> this.writeError(declaration, out);
         case "NormalizedCapability" -> this.writeCapability(declaration, out);
         default -> throw new IllegalArgumentException("not a declaration kind: " + declaration.kind());
      }

      return out.render();
   }

   // ----- declarations -------------------------------------------------------------------------

   private void writeEnum(StructNode node, Lines out) {
      out.line(0, "enum " + text(node, "name") + " {");
      List<StructNode> members = nodes(node, "members");
      for (int i = 0; i < members.size(); i++) {
         out.line(1, text(members.get(i), "name") + (i + 1 < members.size() ? "," : ""));
      }

      out.line(0, "}");
   }

   private void writeEntity(StructNode node, Lines out) {
      out.line(0, "entity " + text(node, "name") + " persistent {");
      StructNode identity = child(node, "identity");
      String strategy = switch (text(identity, "generation")) {
         case "AUTO" -> "auto";
         case "UUID" -> "uuid";
         default -> throw new IllegalArgumentException("unknown generation strategy " + text(identity, "generation"));
      };
      out.line(1, "identity " + text(identity, "name") + ": " + type(child(identity, "type")) + " generated " + strategy + ";");
      for (StructNode field : nodes(node, "fields")) {
         out.line(1, this.field(field, true));
      }

      out.line(0, "}");
   }

   private void writeInput(StructNode node, Lines out) {
      String patch = optionalText(node, "patchSourceEntity").map(e -> " patch of " + e).orElse("");
      out.line(0, "input " + text(node, "name") + patch + " {");
      for (StructNode field : nodes(node, "fields")) {
         out.line(1, this.field(field, false));
      }

      out.line(0, "}");
   }

   private void writeView(StructNode node, Lines out) {
      out.line(0, "view " + text(node, "name") + " from " + text(node, "sourceEntity") + " {");
      for (StructNode field : nodes(node, "fields")) {
         out.line(1, "field " + text(field, "name") + ": " + type(child(field, "type")) + ";");
      }

      out.line(0, "}");
   }

   private void writeError(StructNode node, Lines out) {
      String status = text(node, "httpStatus");
      out.line(0, "error " + text(node, "name") + (status.equals("400") ? "" : " http " + status) + ";");
   }

   private void writeCapability(StructNode node, Lines out) {
      String declared = DeclaredIds.of(node).map(id -> " @id(" + string(id) + ")").orElse("");
      out.line(0, "capability " + text(node, "name") + declared + " {");
      optionalText(node, "actorSymbol").ifPresent(t -> out.line(1, "actor Ref<" + t + ">;"));
      optionalText(node, "inputSymbol").ifPresent(t -> out.line(1, "input " + t + ";"));
      out.line(1, "output " + type(child(node, "outputType")) + ";");
      // The draft holds these as sets. The language wants errors by ascending name and the
      // requirements in their declared order, so that is how they are spelled.
      for (String error : texts(node, "fails").stream().sorted().toList()) {
         out.line(1, "fails " + error + ";");
      }

      for (String requirement : REQUIREMENT_ORDER) {
         if (texts(node, "requires").contains(requirement)) {
            out.line(1, "requires " + requirement.toLowerCase(java.util.Locale.ROOT) + ";");
         }
      }

      out.line(1, "expose " + text(node, "exposure").toLowerCase(java.util.Locale.ROOT) + ";");
      out.line(1, "workflow {");
      for (StructNode step : nodes(child(node, "workflow"), "steps")) {
         this.writeStep(step, out);
      }

      out.line(1, "}");
      out.line(0, "}");
   }

   private String field(StructNode field, boolean entityField) {
      StringBuilder line = new StringBuilder("field ").append(text(field, "name")).append(": ").append(type(child(field, "type")));
      List<StructNode> constraints = nodes(field, "constraints");
      if (!constraints.isEmpty()) {
         List<String> parts = new ArrayList<>();
         for (StructNode constraint : constraints) {
            List<StructNode> args = nodes(constraint, "arguments");
            parts.add(args.isEmpty() ? text(constraint, "name") : text(constraint, "name") + "(" + join(args.stream().map(a -> expression(a, 0)).toList()) + ")");
         }

         line.append(" where ").append(String.join(", ", parts));
      }

      if (text(field, "versioned").equals("true")) {
         line.append(" versioned");
      }

      if (entityField && text(field, "unique").equals("true")) {
         line.append(" unique");
      }

      if (entityField) {
         DeclaredIds.of(field).ifPresent(id -> line.append(" @id(").append(string(id)).append(")"));
      }

      return line.append(";").toString();
   }

   // ----- workflow steps -----------------------------------------------------------------------

   private void writeStep(StructNode step, Lines out) {
      switch (step.kind()) {
         case "ValidateStep" ->
            out.line(2, "validate " + expression(child(step, "condition"), 0) + " else " + text(step, "errorSymbol") + ";");
         case "LoadStep" ->
            out.line(2, "load " + text(step, "entitySymbol") + " by " + optionalText(step, "keyField").map(key -> key + ": ").orElse("") + expression(child(step, "idExpression"), 0) + " as " + text(step, "resultVariable") + " else " + text(step, "errorSymbol") + ";");
         case "FindStep" -> this.writeFind(step, out);
         case "CreateStep" -> {
            out.line(2, "create " + text(step, "entitySymbol") + " as " + text(step, "resultVariable") + " {");
            this.bindings(step, out);
            out.line(2, "}");
         }
         case "UpdateStep" -> {
            out.line(2, "update " + text(step, "targetVariable")
               + optionalChild(step, "guard").map(guard -> " when " + expression(guard, 0) + " else " + text(step, "guardError")).orElse("")
               + " {");
            this.bindings(step, out);
            out.line(2, "}");
         }
         case "PersistStep" ->
            out.line(2, "persist " + text(step, "targetVariable") + optionalText(step, "failure").map(f -> " else " + f).orElse("") + ";");
         case "ReturnStep" -> out.line(2, "return " + expression(child(step, "value"), 0) + ";");
         default -> throw new IllegalArgumentException("not a workflow step kind: " + step.kind());
      }
   }

   private void writeFind(StructNode step, Lines out) {
      out.line(2, "find " + text(step, "entitySymbol"));
      out.line(3, "where " + expression(child(step, "predicate"), 0));
      List<StructNode> keys = nodes(step, "orderKeys");
      if (!keys.isEmpty()) {
         out.line(3, "order by " + join(keys.stream().map(k -> text(k, "fieldSymbol") + (text(k, "descending").equals("true") ? " descending" : " ascending")).toList()));
      }

      optionalChild(step, "page").ifPresent(page -> out.line(3, "Page input." + text(page, "pageField") + ", input." + text(page, "sizeField") + " else " + text(page, "errorSymbol")));
      out.line(3, "as " + text(step, "resultVariable") + ";");
   }

   private void bindings(StructNode step, Lines out) {
      for (StructNode binding : nodes(step, "bindings")) {
         out.line(3, text(binding, "fieldName") + ": " + expression(child(binding, "value"), 0) + ";");
      }
   }

   // ----- types --------------------------------------------------------------------------------

   private static String type(StructNode node) {
      return switch (node.kind()) {
         case "PrimitiveType" -> {
            String constant = text(node, "constant");
            String sir = PRIMITIVE_TEXT.get(constant);
            if (sir == null) {
               throw new IllegalArgumentException("unknown primitive type " + constant);
            }

            yield sir;
         }
         case "DeclaredType" -> text(node, "name");
         case "RefType" -> "Ref<" + text(node, "entityName") + ">";
         case "OptionalType" -> "Optional<" + type(child(node, "element")) + ">";
         case "ListType" -> "List<" + type(child(node, "element")) + ">";
         case "PageType" -> "Page<" + type(child(node, "element")) + ">";
         default -> throw new IllegalArgumentException("not a type kind: " + node.kind());
      };
   }

   // ----- expressions --------------------------------------------------------------------------

   /** Writes {@code node}, adding parentheses only where its binding strength is below {@code needed}. */
   private static String expression(StructNode node, int needed) {
      int level = levelOf(node);
      String text = switch (node.kind()) {
         case "BinaryExpression" -> binary(node);
         case "UnaryExpression" -> {
            String operator = text(node, "operator");
            String prefix = switch (operator) {
               case "NOT" -> "not ";
               case "NEGATE" -> "-";
               default -> throw new IllegalArgumentException("unknown unary operator " + operator);
            };
            yield prefix + expression(child(node, "operand"), UNARY);
         }
         case "BooleanLiteral" -> text(node, "value");
         case "IntegerLiteral" -> text(node, "value");
         case "DecimalLiteral" -> decimal(text(node, "value"));
         case "StringLiteral" -> string(text(node, "value"));
         case "UnitLiteral" -> "unit";
         case "NowExpression" -> "now()";
         case "TimeShiftExpression" -> expression(child(node, "base"), ADDITIVE) + (text(node, "direction").equals("ADD") ? " + " : " - ")
            + text(node, "amount") + " " + text(node, "unit").toLowerCase(java.util.Locale.ROOT);
         case "NameExpression" -> text(node, "name");
         case "MemberExpression" -> expression(child(node, "receiver"), POSTFIX) + "." + text(node, "member");
         case "PresentExpression" -> expression(child(node, "target"), POSTFIX) + ".present";
         case "ExistsExpression" -> "any(" + text(node, "entity") + ", " + expression(child(node, "conditions"), 0) + ")";
         default -> throw new IllegalArgumentException("not an expression kind: " + node.kind());
      };
      return level < needed ? "(" + text + ")" : text;
   }

   private static String binary(StructNode node) {
      String operator = text(node, "operator");
      Integer level = OPERATOR_LEVEL.get(operator);
      if (level == null) {
         throw new IllegalArgumentException("unknown binary operator " + operator);
      }

      // or/and and plus/minus chain to the left; every other operator takes exactly one operand per side.
      boolean chains = level == OR || level == AND || level == ADDITIVE;
      int leftNeeds = chains ? level : level + 1;
      return expression(child(node, "left"), leftNeeds) + " " + OPERATOR_TEXT.get(operator) + " " + expression(child(node, "right"), level + 1);
   }

   private static int levelOf(StructNode node) {
      return switch (node.kind()) {
         case "BinaryExpression" -> OPERATOR_LEVEL.getOrDefault(text(node, "operator"), POSTFIX);
         case "UnaryExpression" -> UNARY;
         case "TimeShiftExpression" -> ADDITIVE;
         case "IntegerLiteral" -> text(node, "value").startsWith("-") ? UNARY : POSTFIX;
         case "DecimalLiteral" -> text(node, "value").startsWith("-") ? UNARY : POSTFIX;
         default -> POSTFIX;
      };
   }

   private static String decimal(String value) {
      String plain = new BigDecimal(value).toPlainString();
      return plain.contains(".") ? plain : plain + ".0";
   }

   private static String string(String value) {
      StringBuilder out = new StringBuilder("\"");
      for (int i = 0; i < value.length(); i++) {
         char c = value.charAt(i);
         switch (c) {
            case '"' -> out.append("\\\"");
            case '\\' -> out.append("\\\\");
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            case '\b' -> out.append("\\b");
            case '\f' -> out.append("\\f");
            default -> {
               if (c < 0x20) {
                  throw new IllegalArgumentException("a string with control character U+" + String.format("%04X", (int)c) + " cannot be written in SIR");
               }

               out.append(c);
            }
         }
      }

      return out.append('"').toString();
   }

   // ----- draft access -------------------------------------------------------------------------

   private static String text(StructNode node, String key) {
      StructNode.Slot slot = node.slot(key);
      if (slot.value() instanceof StructValue.Text t) {
         return t.value();
      }

      throw new IllegalArgumentException(node.kind() + "." + key + " is not a single value");
   }

   private static Optional<String> optionalText(StructNode node, String key) {
      return node.slot(key).value() instanceof StructValue.Text t ? Optional.of(t.value()) : Optional.empty();
   }

   private static StructNode child(StructNode node, String key) {
      if (node.slot(key).value() instanceof StructValue.Child c) {
         return c.node();
      }

      throw new IllegalArgumentException(node.kind() + "." + key + " is not a node");
   }

   private static Optional<StructNode> optionalChild(StructNode node, String key) {
      return node.slot(key).value() instanceof StructValue.Child c ? Optional.of(c.node()) : Optional.empty();
   }

   private static List<StructNode> nodes(StructNode node, String key) {
      return seq(node, key).stream().map(v -> ((StructValue.Child)v).node()).toList();
   }

   private static List<String> texts(StructNode node, String key) {
      return seq(node, key).stream().map(v -> ((StructValue.Text)v).value()).toList();
   }

   private static List<StructValue> seq(StructNode node, String key) {
      if (node.slot(key).value() instanceof StructValue.Seq s) {
         return s.elements();
      }

      throw new IllegalArgumentException(node.kind() + "." + key + " is not a sequence");
   }

   private static String join(List<String> parts) {
      return String.join(", ", parts);
   }

   /** Collects lines with the base indentation applied to every line after the first. */
   private static final class Lines {
      private final String base;
      private final List<String> lines = new ArrayList<>();

      Lines(String base) {
         this.base = base;
      }

      void line(int level, String text) {
         this.lines.add((this.lines.isEmpty() ? "" : this.base) + STEP.repeat(level) + text);
      }

      String render() {
         return String.join("\n", this.lines);
      }
   }
}
