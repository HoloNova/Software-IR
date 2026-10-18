package io.kcg.sir.semantic.feature;

import io.kcg.sir.semantic.type.ListType;
import io.kcg.sir.semantic.type.OptionalType;
import io.kcg.sir.semantic.type.PageType;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.SemanticType;
import io.kcg.sir.semantic.type.SirType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The semantic types the language has: what an author writes for a field instead of a machine type.
 *
 * <p>A semantic type is a meaning with a kind of value underneath it. Some also bring rules that come with the
 * meaning: a {@code Name} is never blank and at most 100 characters, a {@code Quantity} is at least 1, a
 * {@code Secret} is stored as a digest. An author can state a different bound on the field
 * ({@code Name where length(1, 64)}); a rule written on the field replaces the rule of the same name that the type
 * brings.
 */
public final class SemanticTypes {
   private SemanticTypes() {
   }

   /** A rule a type brings: a constraint name and its integer arguments. */
   public record Rule(String name, List<Long> arguments) {
      public Rule {
         arguments = List.copyOf(arguments);
      }

      /** The rule as it is written after {@code where}. */
      public String text() {
         return this.arguments.isEmpty() ? this.name : this.name + "(" + this.arguments.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ")";
      }
   }

   public record Entry(String name, PrimitiveType kind, List<Rule> rules, boolean versioned, boolean secret) {
      public Entry {
         rules = List.copyOf(rules);
      }
   }

   private static Entry plain(String name, PrimitiveType kind) {
      return new Entry(name, kind, List.of(), false, false);
   }

   private static Entry ruled(String name, PrimitiveType kind, Rule... rules) {
      return new Entry(name, kind, List.of(rules), false, false);
   }

   private static final List<Entry> ALL = List.of(
      plain("Id", PrimitiveType.INT64),
      plain("Serial", PrimitiveType.INT64),
      plain("Token", PrimitiveType.UUID),
      plain("Count", PrimitiveType.INT32),
      ruled("Quantity", PrimitiveType.INT32, new Rule("min", List.of(1L))),
      plain("PageNo", PrimitiveType.INT32),
      plain("PageSize", PrimitiveType.INT32),
      new Entry("Version", PrimitiveType.INT64, List.of(), true, false),
      plain("Text", PrimitiveType.STRING),
      ruled("Name", PrimitiveType.STRING, new Rule("notBlank", List.of()), new Rule("length", List.of(1L, 100L))),
      ruled("Code", PrimitiveType.STRING, new Rule("notBlank", List.of()), new Rule("length", List.of(1L, 32L))),
      ruled("Remark", PrimitiveType.STRING, new Rule("length", List.of(0L, 500L))),
      ruled("Email", PrimitiveType.STRING, new Rule("email", List.of())),
      new Entry("Secret", PrimitiveType.STRING, List.of(), false, true),
      plain("Moment", PrimitiveType.DATE_TIME),
      plain("Day", PrimitiveType.DATE),
      plain("Flag", PrimitiveType.BOOLEAN),
      plain("Money", PrimitiveType.DECIMAL)
   );

   private static final Map<String, Entry> BY_NAME = ALL.stream().collect(Collectors.toUnmodifiableMap(Entry::name, e -> e));

   public static List<Entry> all() {
      return ALL;
   }

   public static Optional<Entry> named(String name) {
      return Optional.ofNullable(BY_NAME.get(name));
   }

   public static PrimitiveType kindOf(String name) {
      return named(name).orElseThrow().kind();
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
