package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.RelatedLocation;
import io.kcg.sir.ast.AstFeatureArgument;
import io.kcg.sir.ast.AstFeatureDefinitionDecl;
import io.kcg.sir.semantic.feature.Features;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the {@code feature} declarations of a software into features a {@code use} can name, next to the language's own.
 *
 * <p>A definition is checked here, whether or not anything uses it: the name must be free, every parameter must have a
 * kind of value the language knows and a default of that kind, and the text may only mention parameters the definition
 * declares, and its loops and conditions must be well formed and read only an entity a name parameter names. What
 * the text expands into is checked when a {@code use} expands it, by the ordinary analysis.
 */
final class FeatureDefinitions {
   private FeatureDefinitions() {
   }

   private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,63}");
   private static final Pattern NAME_OR_EMPTY = Pattern.compile("([A-Za-z][A-Za-z0-9]{0,63})?");
   private static final long LONGEST_SECONDS = 3650L * 86_400;

   /** @param refused the names of the definitions that were refused, whose uses have nothing more to report */
   record Result(Map<String, Features.Feature> features, List<AstFeatureDefinitionDecl> definitions, Set<String> refused) {
   }

   static Result build(List<AstFeatureDefinitionDecl> definitions, List<Diagnostic> diagnostics) {
      Map<String, Features.Feature> features = new LinkedHashMap<>();
      Map<String, AstFeatureDefinitionDecl> first = new LinkedHashMap<>();
      List<AstFeatureDefinitionDecl> kept = new ArrayList<>();
      Set<String> refused = new HashSet<>();
      for (AstFeatureDefinitionDecl definition : definitions) {
         String name = definition.name().text();
         if (Features.named(name).isPresent()) {
            diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-006", "功能名已被语言占用: " + name, definition.name().span()));
         } else if (first.containsKey(name)) {
            diagnostics.add(DiagnosticBuilder.errorWithRelated("SIR-FEATURE-006", "功能重复定义: " + name, definition.name().span(),
               new RelatedLocation("首次定义在此处", first.get(name).span())));
         } else {
            first.put(name, definition);
            kept.add(definition);
            feature(definition, diagnostics).ifPresentOrElse(f -> features.put(name, f), () -> refused.add(name));
         }
      }

      return new Result(features, kept, refused);
   }

   private static Optional<Features.Feature> feature(AstFeatureDefinitionDecl definition, List<Diagnostic> diagnostics) {
      boolean valid = true;
      List<Features.Parameter> parameters = new ArrayList<>();
      Set<String> names = new HashSet<>();
      for (AstFeatureDefinitionDecl.Parameter parameter : definition.parameters()) {
         String name = parameter.name().text();
         if (!names.add(name)) {
            diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-006", "参数重复定义: " + name, parameter.name().span()));
            valid = false;
            continue;
         }

         Optional<Features.Parameter> built = parameter(parameter, diagnostics);
         if (built.isEmpty()) {
            valid = false;
         } else {
            parameters.add(built.get());
         }
      }

      // The template is read now, so a mistake in it is reported where the feature is defined, used or not.
      try {
         Set<String> entityParameters = new HashSet<>();
         parameters.stream().filter(p -> p.kind() == Features.Kind.NAME).forEach(p -> entityParameters.add(p.name()));
         for (String problem : io.kcg.sir.semantic.feature.FeatureTemplate.parse(definition.template()).problems(names, entityParameters)) {
            diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-006", "模板有误: " + problem, definition.templateSpan()));
            valid = false;
         }
      } catch (io.kcg.sir.semantic.feature.FeatureTemplate.Problem problem) {
         diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-006", "模板有误: " + problem.getMessage(), definition.templateSpan()));
         valid = false;
      }

      if (!valid) {
         return Optional.empty();
      }

      String template = definition.template();
      return Optional.of(new Features.Feature(definition.name().text(), parameters, () -> template, false));
   }

   private static Optional<Features.Parameter> parameter(AstFeatureDefinitionDecl.Parameter parameter, List<Diagnostic> diagnostics) {
      String name = parameter.name().text();
      String kind = parameter.kind().text();
      if (kind.equals("name")) {
         Optional<String> defaultValue;
         if (parameter.emptyDefault()) {
            defaultValue = Optional.of("");
         } else if (parameter.defaultValue().isPresent() && parameter.defaultValue().get() instanceof AstFeatureArgument.Word word) {
            if (!NAME.matcher(word.text()).matches()) {
               diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-006", "参数 " + name + " 的默认值不是名字", word.span()));
               return Optional.empty();
            }

            defaultValue = Optional.of(word.text());
         } else if (parameter.defaultValue().isPresent()) {
            diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-006", "参数 " + name + " 是名字，默认值不能是时长", parameter.defaultValue().get().span()));
            return Optional.empty();
         } else {
            defaultValue = Optional.empty();
         }

         return Optional.of(new Features.Parameter(name, Features.Kind.NAME, defaultValue, parameter.emptyDefault() ? NAME_OR_EMPTY : NAME, 0, 0,
            name + " 必须是名字" + (parameter.emptyDefault() ? "或留空" : "")));
      }

      if (kind.equals("duration")) {
         Optional<String> defaultValue = Optional.empty();
         if (parameter.emptyDefault() || parameter.defaultValue().isPresent() && !(parameter.defaultValue().get() instanceof AstFeatureArgument.Duration)) {
            diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-006", "参数 " + name + " 是时长，默认值必须是时长，例如 15 minutes", parameter.span()));
            return Optional.empty();
         }

         if (parameter.defaultValue().isPresent()) {
            AstFeatureArgument.Duration duration = (AstFeatureArgument.Duration) parameter.defaultValue().get();
            long seconds = duration.amount().multiply(BigInteger.valueOf(switch (duration.unit()) {
               case SECONDS -> 1L;
               case MINUTES -> 60L;
               case HOURS -> 3_600L;
               case DAYS -> 86_400L;
            })).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
            if (seconds < 1 || seconds > LONGEST_SECONDS) {
               diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-006", "参数 " + name + " 的默认时长超出范围", duration.span()));
               return Optional.empty();
            }

            defaultValue = Optional.of(duration.text());
         }

         return Optional.of(new Features.Parameter(name, Features.Kind.DURATION, defaultValue, NAME, 1, LONGEST_SECONDS,
            name + " 必须是 1 秒到 3650 天之间的时长"));
      }

      diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-006", "参数 " + name + " 的值种类必须是 name 或 duration", parameter.kind().span()));
      return Optional.empty();
   }
}
