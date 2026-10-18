package io.kcg.sir.semantic.feature;

import io.kcg.sir.semantic.type.ListType;
import io.kcg.sir.semantic.type.OptionalType;
import io.kcg.sir.semantic.type.PageType;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.SemanticType;
import io.kcg.sir.semantic.type.SirType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The semantic types of one software: what an author writes for a field instead of a machine type. The language has
 * a fixed set; a software can add its own ({@code type Percent = Count where min(0), max(100);}).
 *
 * <p>A semantic type is a meaning with a kind of value underneath it. Some also bring rules that come with the
 * meaning: a {@code Name} is never blank and at most 100 characters, a {@code Quantity} is at least 1, a
 * {@code Secret} is stored as a digest. An author can state a different bound on the field
 * ({@code Name where length(1, 64)}); a rule written on the field replaces the rule of the same name that the type
 * brings.
 */
public final class SemanticTypes {
   /** A rule a type brings: a constraint name and its numeric arguments as written ({@code 1}, {@code -10.50}). */
   public record Rule(String name, List<String> arguments) {
      public Rule {
         arguments = List.copyOf(arguments);
      }

      /** The rule as it is written after {@code where}. */
      public String text() {
         return this.arguments.isEmpty() ? this.name : this.name + "(" + String.join(", ", this.arguments) + ")";
      }
   }

   /** One type with everything it means: the kind of value, the rules and flags it brings (its base's included). */
   public record Entry(String name, PrimitiveType kind, List<Rule> rules, boolean versioned, boolean secret, boolean builtin) {
      public Entry {
         rules = List.copyOf(rules);
      }

      public List<String> ruleTexts() {
         return this.rules.stream().map(Rule::text).toList();
      }
   }

   private static Entry plain(String name, PrimitiveType kind) {
      return new Entry(name, kind, List.of(), false, false, true);
   }

   private static Entry ruled(String name, PrimitiveType kind, Rule... rules) {
      return new Entry(name, kind, List.of(rules), false, false, true);
   }

   private static final List<Entry> BUILTIN = List.of(
      plain("Id", PrimitiveType.INT64),
      plain("Serial", PrimitiveType.INT64),
      plain("Token", PrimitiveType.UUID),
      plain("Count", PrimitiveType.INT32),
      ruled("Quantity", PrimitiveType.INT32, new Rule("min", List.of("1"))),
      plain("PageNo", PrimitiveType.INT32),
      plain("PageSize", PrimitiveType.INT32),
      new Entry("Version", PrimitiveType.INT64, List.of(), true, false, true),
      plain("Text", PrimitiveType.STRING),
      ruled("Name", PrimitiveType.STRING, new Rule("notBlank", List.of()), new Rule("length", List.of("1", "100"))),
      ruled("Code", PrimitiveType.STRING, new Rule("notBlank", List.of()), new Rule("length", List.of("1", "32"))),
      ruled("Remark", PrimitiveType.STRING, new Rule("length", List.of("0", "500"))),
      ruled("Email", PrimitiveType.STRING, new Rule("email", List.of())),
      new Entry("Secret", PrimitiveType.STRING, List.of(), false, true, true),
      plain("Moment", PrimitiveType.DATE_TIME),
      plain("Day", PrimitiveType.DATE),
      plain("Flag", PrimitiveType.BOOLEAN),
      plain("Money", PrimitiveType.DECIMAL)
   );

   private static final SemanticTypes BUILTIN_ONLY = new SemanticTypes(BUILTIN);

   private final Map<String, Entry> byName;

   private SemanticTypes(List<Entry> entries) {
      Map<String, Entry> map = new LinkedHashMap<>();
      entries.forEach(e -> map.put(e.name(), e));
      this.byName = java.util.Collections.unmodifiableMap(map);
   }

   /** The types every software has. */
   public static SemanticTypes builtin() {
      return BUILTIN_ONLY;
   }

   /** These types and {@code more}, which must not reuse a name. */
   public SemanticTypes plus(List<Entry> more) {
      List<Entry> all = new ArrayList<>(this.byName.values());
      all.addAll(more);
      return new SemanticTypes(all);
   }

   public List<Entry> all() {
      return List.copyOf(this.byName.values());
   }

   public Optional<Entry> named(String name) {
      return Optional.ofNullable(this.byName.get(name));
   }

   /** The same type as the model keeps it. */
   public SemanticType type(String name) {
      return new SemanticType(name, this.byName.get(name).kind());
   }

   /** The rule texts of a type of this set, in the order they are written. */
   public List<String> rulesOf(String name) {
      return this.named(name).map(Entry::ruleTexts).orElse(List.of());
   }

   public String describe() {
      return this.byName.values().stream().map(Entry::name).collect(Collectors.joining(", "));
   }

   /**
    * The type reduced to the kinds of value the analysis reasons about: every semantic type inside it is replaced by
    * its kind.
    */
   public static SirType kind(SirType type) {
      return switch (type) {
         case SemanticType semantic -> semantic.kind();
         case OptionalType optional -> new OptionalType(kind(optional.element()));
         case ListType list -> new ListType(kind(list.element()));
         case PageType page -> new PageType(kind(page.element()));
         default -> type;
      };
   }
}
