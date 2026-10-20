package io.kcg.cli;

import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class CliCommandLine {
   private static final Pattern LOWER_HEX_64 = Pattern.compile("[0-9a-f]{64}");
   public static final String PROTOCOL_VERSION = VersionResultDocument.PROTOCOL;

   private CliCommandLine() {
   }

   public static CliCommandLine.ParseResult parse(String[] args) {
      if (args != null && args.length != 0) {
         String first = args[0];

         return (CliCommandLine.ParseResult)(switch (first) {
            case "--help", "-h", "help" -> new CliCommandLine.Help(null);
            case "--version", "-V", "version" -> new CliCommandLine.Version();
            case "generate", "package", "inspect", "edit" -> parseWorkflow(args, first);
            case "version-plan" -> parseVersion(args, "version-plan", false);
            case "version-apply" -> parseVersion(args, "version-apply", true);
            case "check" -> args.length == 1 ? new ParsedCheck() : args.length == 2 && isHelp(args[1])
                ? new Help("check") : new UsageError("KCG-CLI-USAGE-002", "check accepts no options; read JSONL from stdin");
            default -> new CliCommandLine.UsageError("KCG-CLI-USAGE-001", "unknown command: " + first);
         });
      } else {
         return new CliCommandLine.UsageError("KCG-CLI-USAGE-001", "no command given");
      }
   }

   private static ParseResult parseWorkflow(String[] args, String command) {
      if (args.length == 2 && isHelp(args[1])) return new Help(command);
      Map<String, String> opts = new LinkedHashMap<>();
      opts.put("--source-root", "sourceRoot");
      opts.put("--entry", "entry");
      String destination = (command.equals("generate") || command.equals("package")) ? "--output-root" : command.equals("edit") ? "--candidate-root" : null;
      if (destination != null) opts.put(destination, "destination");
      // package may carry a separate business acceptance suite; it is copied, never compiled or interpreted.
      Set<String> optional = command.equals("package") ? Set.of("--acceptance", "--base-package", "--confirm-backup") : Set.of();
      Object parsed = parseOptions(args, 1, opts, optional, command);
      if (parsed instanceof UsageError error) return error;
      OptionMap values = (OptionMap) parsed;
      try {
         Path acceptance = null;
         if (values.get("--acceptance") != null) {
            acceptance = requireAbsPath(values, "--acceptance");
            if (acceptance == null || !acceptance.equals(acceptance.normalize()))
               return new UsageError("KCG-CLI-USAGE-003", "--acceptance must be an absolute normalized path");
         }
         Path basePackage = null;
         if (values.get("--base-package") != null) {
            basePackage = requireAbsPath(values, "--base-package");
            if (basePackage == null || !basePackage.equals(basePackage.normalize()))
               return new UsageError("KCG-CLI-USAGE-003", "--base-package must be an absolute normalized path");
         }
         boolean confirmBackup = false;
         if (values.get("--confirm-backup") != null) {
            if (!values.get("--confirm-backup").equals("yes") || basePackage == null)
               return new UsageError("KCG-CLI-USAGE-003", "--confirm-backup takes the value yes and only goes with --base-package");
            confirmBackup = true;
         }
         Path source = requireAbsPath(values, "--source-root");
         Path target = destination == null ? null : requireAbsPath(values, destination);
         if (source == null || !source.equals(source.normalize()) || (destination != null && (target == null || !target.equals(target.normalize()))))
            return new UsageError("KCG-CLI-USAGE-003", "roots must be absolute normalized paths");
         return new ParsedWorkflow(command, source, SourceId.of(values.get("--entry")), target, acceptance, basePackage, confirmBackup);
      } catch (IllegalArgumentException e) {
         return new UsageError("KCG-CLI-USAGE-003", "invalid source entry or path");
      }
   }

   /** {@code version-plan} and {@code version-apply}: the SIR on disk, the generated project, a candidate directory and (to apply) the plan id seen. */
   private static CliCommandLine.ParseResult parseVersion(String[] args, String command, boolean apply) {
      if (args.length >= 2 && isHelp(args[1])) {
         return new CliCommandLine.Help(command);
      }

      Map<String, String> opts = new LinkedHashMap<>();
      opts.put("--repository-root", "repositoryRoot");
      opts.put("--source-root", "sourceRoot");
      opts.put("--entry", "entry");
      opts.put("--output-root", "outputRoot");
      opts.put("--candidate-root", "candidateRoot");
      if (apply) {
         opts.put("--expected-plan-id", "expectedPlanId");
      }

      Object om = parseOptions(args, 1, opts, command);
      if (om instanceof CliCommandLine.UsageError ue) {
         return ue;
      }

      CliCommandLine.OptionMap ok = (CliCommandLine.OptionMap)om;
      Path[] roots = new Path[4];
      String[] names = {"--repository-root", "--source-root", "--output-root", "--candidate-root"};
      for (int i = 0; i < names.length; i++) {
         roots[i] = requireAbsPath(ok, names[i]);
         if (roots[i] == null) {
            return new CliCommandLine.UsageError("KCG-CLI-USAGE-003", names[i] + " must be an absolute path");
         }
      }

      SourceId entry;
      try {
         entry = SourceId.of(ok.get("--entry"));
      } catch (IllegalArgumentException e) {
         return new CliCommandLine.UsageError("KCG-CLI-USAGE-003", "--entry must be a relative source path such as project.sir");
      }

      String expectedPlanId = null;
      if (apply) {
         expectedPlanId = ok.get("--expected-plan-id");
         if (!LOWER_HEX_64.matcher(expectedPlanId).matches()) {
            return new CliCommandLine.UsageError("KCG-CLI-USAGE-003", "--expected-plan-id must be 64 lower-case hex characters");
         }
      }

      VersionArguments arguments = new VersionArguments(roots[0], roots[1], entry, roots[2], roots[3], expectedPlanId);
      return apply ? new CliCommandLine.ParsedVersionApply(arguments) : new CliCommandLine.ParsedVersionPlan(arguments);
   }

   private static boolean isHelp(String arg) {
      return "--help".equals(arg) || "-h".equals(arg) || "help".equals(arg);
   }

   private static Path requireAbsPath(CliCommandLine.OptionMap om, String key) {
      String value = om.get(key);
      if (value == null) {
         return null;
      }

      Path p = Path.of(value);
      return p.isAbsolute() ? p : null;
   }

   private static Object parseOptions(String[] args, int start, Map<String, String> known, String cmd) {
      return parseOptions(args, start, known, Set.of(), cmd);
   }

   /** {@code known} options must each occur once; {@code optional} ones may occur once or not at all. */
   private static Object parseOptions(String[] args, int start, Map<String, String> known, Set<String> optional, String cmd) {
      CliCommandLine.OptionMap om = new CliCommandLine.OptionMap();
      Set<String> seen = new LinkedHashSet<>();

      for (int i = start; i < args.length; i += 2) {
         String key = args[i];
         if (!key.startsWith("--") || key.length() <= 2) {
            return new CliCommandLine.UsageError("KCG-CLI-USAGE-002", cmd + ": unexpected positional argument or malformed option: " + key);
         }

         if (!known.containsKey(key) && !optional.contains(key)) {
            return new CliCommandLine.UsageError("KCG-CLI-USAGE-002", cmd + ": unknown option: " + key);
         }

         if (!seen.add(key)) {
            return new CliCommandLine.UsageError("KCG-CLI-USAGE-002", cmd + ": duplicate option: " + key);
         }

         if (i + 1 >= args.length) {
            return new CliCommandLine.UsageError("KCG-CLI-USAGE-002", cmd + ": option requires a value: " + key);
         }

         String value = args[i + 1];
         if (value.startsWith("--")) {
            return new CliCommandLine.UsageError("KCG-CLI-USAGE-002", cmd + ": option " + key + " is missing its value");
         }

         om.put(key, value);
      }

      for (String required : known.keySet()) {
         if (!seen.contains(required)) {
            return new CliCommandLine.UsageError("KCG-CLI-USAGE-002", cmd + ": missing required option: " + required);
         }
      }

      return om;
   }

   public record Help(String command) implements CliCommandLine.ParseResult {
   }

   private static final class OptionMap {
      private final Map<String, String> values = new LinkedHashMap<>();

      void put(String key, String value) {
         this.values.put(key, value);
      }

      String get(String key) {
         return this.values.get(key);
      }
   }

   /** {@code acceptanceRoot}, {@code basePackage} and {@code confirmBackup} are only accepted by {@code package}; the paths are null when absent. */
   public record ParsedWorkflow(String command, Path sourceRoot, SourceId entry, Path destination, Path acceptanceRoot, Path basePackage,
      boolean confirmBackup) implements ParseResult {
   }

   public sealed interface ParseResult
      permits CliCommandLine.ParsedWorkflow,
      CliCommandLine.ParsedVersionPlan,
      CliCommandLine.ParsedVersionApply,
      CliCommandLine.ParsedCheck,
      CliCommandLine.Help,
      CliCommandLine.Version,
      CliCommandLine.UsageError {
   }

   public record ParsedCheck() implements CliCommandLine.ParseResult {
   }

   public record ParsedVersionPlan(VersionArguments arguments) implements CliCommandLine.ParseResult {
   }

   public record ParsedVersionApply(VersionArguments arguments) implements CliCommandLine.ParseResult {
   }

   public record UsageError(String code, String message) implements CliCommandLine.ParseResult {
   }

   public record Version() implements CliCommandLine.ParseResult {
   }
}
