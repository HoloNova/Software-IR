package io.kcg.cli;

import io.kcg.sir.application.api.WorkspaceApplyResult;
import io.kcg.sir.application.api.WorkspaceDirectoryRequest;
import io.kcg.sir.application.api.WorkspacePlanningResult;
import io.kcg.sir.application.api.WorkspaceVersionApplication;
import io.kcg.sir.application.execution.GitVersionControl;
import java.nio.charset.StandardCharsets;

public final class KcgCli {
   public static final String VERSION = "kcg 0.2.0 (" + VersionResultDocument.PROTOCOL + ")";
   static volatile RuntimeException crashInjection = null;
   private static final String TOP_HELP = "Usage: kcg <command> [options]\nSIR validation and version change CLI.\n\nCommands:\n  check          Validate single-file SIR samples from UTF-8 JSONL stdin.\n  version-plan   Plan a change of a project kept in a Git working tree. Read-only.\n  version-apply  Carry out a planned change; the previous and the new version are saved in Git.\n\nGlobal options:\n  --help, -h     Show this help (or command help with `kcg <command> --help`).\n  --version, -V  Show the CLI version.";
   private static final String VERSION_OPTIONS = "--repository-root <absolute-path> --source-root <absolute-path> --entry <relative-path>\n                 --output-root <absolute-path> --candidate-root <absolute-path>";
   private static final String VERSION_PLAN_HELP = "Usage: kcg version-plan " + VERSION_OPTIONS + "\nCompiles the SIR sources in --source-root and the candidate sources in --candidate-root, requires the project\nfiles in --output-root to be exactly what the current SIR compiles to, and prints the plan with its planId.\nChanges nothing. All roots must be inside --repository-root, and --source-root and --output-root must be\nseparate directories. --entry is the entry source, for example project.sir.\n\nAll options are required and must occur exactly once. Paths must be absolute.";
   private static final String VERSION_APPLY_HELP = "Usage: kcg version-apply " + VERSION_OPTIONS + "\n                    --expected-plan-id <64-lower-hex>\nCarries out the plan that version-plan printed, only if planning now gives exactly that plan (same planId).\nRequires git: the previous version is saved in Git first, then the project files and the SIR sources are\nwritten, then the new version is saved. A directory that is not a repository yet becomes one.\nIf it stops half way (exit 4) nothing is repaired: run the same command again to finish, or return to the\nsaved previous version with git.\n\nAll options are required and must occur exactly once. Paths must be absolute.";

   private KcgCli() {
   }

   public static void main(String[] args) {
      int exit = run(args);
      System.out.flush();
      System.exit(exit);
   }

   static int run(String[] args) {
      try {
         return runInternal(args);
      } catch (Exception e) {
         if (e instanceof RuntimeException re && re == crashInjection) {
         }

         ResultRenderer.render(ResultRenderer.internalFailure("KCG-CLI-INTERNAL-001", "unexpected internal error"));
         return 70;
      }
   }

   private static int runInternal(String[] args) {
      if (crashInjection != null) {
         throw crashInjection;
      }

      CliCommandLine.ParseResult parsed = CliCommandLine.parse(args);

      return switch (parsed) {
         case CliCommandLine.Help h -> {
            printHelp(h);
            yield 0;
         }
         case CliCommandLine.Version v -> {
            printVersion();
            yield 0;
         }
         case CliCommandLine.UsageError ue -> {
            ResultRenderer.render(ResultRenderer.usageError(ue.code(), ue.message()));
            yield 2;
         }
         case CliCommandLine.ParsedVersionPlan vp -> runVersionPlan(vp.arguments());
         case CliCommandLine.ParsedVersionApply va -> runVersionApply(va.arguments());
         case CliCommandLine.ParsedCheck pc -> runCheck();
         default -> throw new MatchException(null, null);
      };
   }

   private static WorkspaceDirectoryRequest directoryRequest(VersionArguments args) {
      return new WorkspaceDirectoryRequest(args.repositoryRoot(), args.sourceRoot(), args.entry(), args.outputRoot(), args.candidateRoot());
   }

   /** Read-only: the application compiles both versions and compares them; the command opens no file itself. */
   private static int runVersionPlan(VersionArguments args) {
      WorkspacePlanningResult result = new WorkspaceVersionApplication(new GitVersionControl()).plan(directoryRequest(args));
      ResultRenderer.render(VersionResultDocument.render(result));
      return result instanceof WorkspacePlanningResult.Failure ? 3 : 0;
   }

   /** The only command that changes the project. Exit 4 means it stopped half way and must be run again. */
   private static int runVersionApply(VersionArguments args) {
      WorkspaceApplyResult result = new WorkspaceVersionApplication(new GitVersionControl()).apply(directoryRequest(args), args.expectedPlanId());
      ResultRenderer.render(VersionResultDocument.render(result));
      return switch (result) {
         case WorkspaceApplyResult.Applied a -> 0;
         case WorkspaceApplyResult.NoChanges n -> 0;
         case WorkspaceApplyResult.Failure f -> 3;
         case WorkspaceApplyResult.RerunRequired r -> 4;
      };
   }

   private static int runCheck() {
      try {
         var summary = new io.kcg.sir.application.api.SirValidationApplication().checkBatch(
            new io.kcg.sir.application.api.SirValidationBatchRequest(System.in, result -> {
               CheckResultRenderer.render(System.out, result);
               if (System.out.checkError()) throw new java.io.IOException("output unavailable");
            }));
         return summary.internalFailure() ? 70 : 0;
      } catch (java.io.IOException e) {
         System.err.println("KCG-CHECK-IO-001: JSONL transport could not complete");
         return 3;
      } catch (RuntimeException | StackOverflowError e) {
         System.err.println("KCG-CHECK-INTERNAL-001: batch checking could not complete");
         return 70;
      }
   }

   private static void printHelp(CliCommandLine.Help h) {
      String text = switch (h.command()) {
         case null -> TOP_HELP;
         case "check" -> "Usage: kcg check < samples.jsonl\nReads UTF-8 JSONL from stdin and writes one canonical JSON result per input line.\nStatic checking only; does not build, start or certify business behavior.\nEach object requires id and sir, with optional stopAfter: PARSE, SEMANTIC, LOWERING, GENERATION.\nDefault GENERATION. Limits: 1 MiB SIR, 8 MiB JSON line, 10000 accepted lines.\nOrdinary rejected samples do not make the process fail. No options; no files or state written.";
         case "version-plan" -> VERSION_PLAN_HELP;
         case "version-apply" -> VERSION_APPLY_HELP;
         default -> TOP_HELP;
      };
      byte[] b = text.getBytes(StandardCharsets.UTF_8);
      System.out.write(b, 0, b.length);
      System.out.write(10);
      System.out.flush();
   }

   private static void printVersion() {
      byte[] b = VERSION.getBytes(StandardCharsets.UTF_8);
      System.out.write(b, 0, b.length);
      System.out.write(10);
      System.out.flush();
   }
}
