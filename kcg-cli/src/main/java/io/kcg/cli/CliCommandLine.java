package io.kcg.cli;

import io.kcg.sir.change.api.ChangeIrVersion;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class CliCommandLine {
   private static final Pattern LOWER_HEX_64 = Pattern.compile("[0-9a-f]{64}");
   public static final String PROTOCOL_VERSION = "KCG-CLI-CHANGE-PLANNING-V1";

   private CliCommandLine() {
   }

   public static CliCommandLine.ParseResult parse(String[] args) {
      if (args != null && args.length != 0) {
         String first = args[0];

         return (CliCommandLine.ParseResult)(switch (first) {
            case "--help", "-h", "help" -> new CliCommandLine.Help(null);
            case "--version", "-V", "version" -> new CliCommandLine.Version();
            case "context" -> parseContext(args);
            case "plan" -> parsePlan(args);
            default -> new CliCommandLine.UsageError("KCG-CLI-USAGE-001", "unknown command: " + first);
         });
      } else {
         return new CliCommandLine.UsageError("KCG-CLI-USAGE-001", "no command given");
      }
   }

   private static CliCommandLine.ParseResult parseContext(String[] args) {
      if (args.length >= 2 && isHelp(args[1])) {
         return new CliCommandLine.Help("context");
      } else {
         // Declared order, not Map.of: the missing-required-option diagnostic below reports the
         // first absent option, and an unspecified iteration order would make that message differ
         // between two runs of the same command line.
         Map<String, String> opts = new LinkedHashMap<>();
         opts.put("--state-root", "stateRoot");
         opts.put("--output-root", "outputRoot");
         opts.put("--candidate-sir", "candidateSir");
         Object om = parseOptions(args, 1, opts, "context");
         if (om instanceof CliCommandLine.UsageError ue) {
            return ue;
         } else {
            CliCommandLine.OptionMap ok = (CliCommandLine.OptionMap)om;
            Path stateRoot = requireAbsPath(ok, "--state-root");
            if (stateRoot == null) {
               return new CliCommandLine.UsageError("KCG-CLI-USAGE-003", "--state-root must be an absolute path");
            }

            Path outputRoot = requireAbsPath(ok, "--output-root");
            if (outputRoot == null) {
               return new CliCommandLine.UsageError("KCG-CLI-USAGE-003", "--output-root must be an absolute path");
            }

            Path candidateSir = requireAbsPath(ok, "--candidate-sir");
            return candidateSir == null
               ? new CliCommandLine.UsageError("KCG-CLI-USAGE-003", "--candidate-sir must be an absolute path")
               : new CliCommandLine.ParsedContext(new ContextArguments(stateRoot, outputRoot, candidateSir));
         }
      }
   }

   private static CliCommandLine.ParseResult parsePlan(String[] args) {
      if (args.length >= 2 && isHelp(args[1])) {
         return new CliCommandLine.Help("plan");
      } else {
         // Declared order, not Map.of: see the note in parseContext — the first missing required
         // option must be the same one on every run.
         Map<String, String> opts = new LinkedHashMap<>();
         opts.put("--state-root", "stateRoot");
         opts.put("--output-root", "outputRoot");
         opts.put("--candidate-sir", "candidateSir");
         opts.put("--expected-context-id", "expectedContextId");
         opts.put("--target-key", "targetKey");
         opts.put("--change-ir-version", "changeIrVersion");
         opts.put("--operation", "operation");
         Object om = parseOptions(args, 1, opts, "plan");
         if (om instanceof CliCommandLine.UsageError ue) {
            return ue;
         } else {
            CliCommandLine.OptionMap ok = (CliCommandLine.OptionMap)om;
            Path stateRoot = requireAbsPath(ok, "--state-root");
            if (stateRoot == null) {
               return new CliCommandLine.UsageError("KCG-CLI-USAGE-003", "--state-root must be an absolute path");
            }

            Path outputRoot = requireAbsPath(ok, "--output-root");
            if (outputRoot == null) {
               return new CliCommandLine.UsageError("KCG-CLI-USAGE-003", "--output-root must be an absolute path");
            }

            Path candidateSir = requireAbsPath(ok, "--candidate-sir");
            if (candidateSir == null) {
               return new CliCommandLine.UsageError("KCG-CLI-USAGE-003", "--candidate-sir must be an absolute path");
            }

            String expectedContextId = ok.get("--expected-context-id");
            if (!LOWER_HEX_64.matcher(expectedContextId).matches()) {
               return new CliCommandLine.UsageError("KCG-CLI-USAGE-003", "--expected-context-id must be 64 lower-case hex characters");
            }

            String targetKey = ok.get("--target-key");
            if (!LOWER_HEX_64.matcher(targetKey).matches()) {
               return new CliCommandLine.UsageError("KCG-CLI-USAGE-003", "--target-key must be 64 lower-case hex characters");
            }

            ChangeIrVersion version = parseVersion(ok.get("--change-ir-version"));
            if (version == null) {
               return new CliCommandLine.UsageError("KCG-CLI-USAGE-003", "--change-ir-version must be one of V0_1..V0_6");
            }

            OperationToken op = OperationToken.parse(ok.get("--operation")).orElse(null);
            return op == null
               ? new CliCommandLine.UsageError("KCG-CLI-USAGE-003", "--operation must be a frozen operation token")
               : new CliCommandLine.ParsedPlan(new PlanArguments(stateRoot, outputRoot, candidateSir, expectedContextId, targetKey, version, op));
         }
      }
   }

   private static boolean isHelp(String arg) {
      return "--help".equals(arg) || "-h".equals(arg) || "help".equals(arg);
   }

   private static ChangeIrVersion parseVersion(String value) {
      for (ChangeIrVersion v : ChangeIrVersion.values()) {
         if (v.name().equals(value)) {
            return v;
         }
      }

      return null;
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
      CliCommandLine.OptionMap om = new CliCommandLine.OptionMap();
      Set<String> seen = new LinkedHashSet<>();

      for (int i = start; i < args.length; i += 2) {
         String key = args[i];
         if (!key.startsWith("--") || key.length() <= 2) {
            return new CliCommandLine.UsageError("KCG-CLI-USAGE-002", cmd + ": unexpected positional argument or malformed option: " + key);
         }

         if (!known.containsKey(key)) {
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

   public sealed interface ParseResult
      permits CliCommandLine.ParsedContext,
      CliCommandLine.ParsedPlan,
      CliCommandLine.Help,
      CliCommandLine.Version,
      CliCommandLine.UsageError {
   }

   public record ParsedContext(ContextArguments arguments) implements CliCommandLine.ParseResult {
   }

   public record ParsedPlan(PlanArguments arguments) implements CliCommandLine.ParseResult {
   }

   public record UsageError(String code, String message) implements CliCommandLine.ParseResult {
   }

   public record Version() implements CliCommandLine.ParseResult {
   }
}
