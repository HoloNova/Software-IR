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
   private static final String TOP_HELP = "Usage: kcg <command> [options]\nSIR generation, command editing and version change CLI.\n\nCommands:\n  generate       Generate a new Spring Boot project from SIR sources.\n  package        Export standalone source + database + Docker delivery. Does not deploy.\n  inspect        Read the software structure and command baseline. Read-only.\n  edit           Read command JSON from stdin and save a new candidate source directory.\n  check          Validate SIR software samples (one document each) from UTF-8 JSONL stdin.\n  version-plan   Plan a change of a project kept in a Git working tree. Read-only.\n  version-apply  Carry out a planned change; the previous and the new version are saved in Git.\n\nGlobal options:\n  --help, -h     Show this help (or command help with `kcg <command> --help`).\n  --version, -V  Show the CLI version.";
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
         ResultRenderer.render(ResultRenderer.internalFailure("KCG-CLI-INTERNAL-001", "unexpected internal error"));
         return CliExit.INTERNAL;
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
            yield CliExit.OK;
         }
         case CliCommandLine.Version v -> {
            printVersion();
            yield CliExit.OK;
         }
         case CliCommandLine.UsageError ue -> {
            ResultRenderer.render(ResultRenderer.usageError(ue.code(), ue.message()));
            yield CliExit.USAGE;
         }
         case CliCommandLine.ParsedVersionPlan vp -> runVersionPlan(vp.arguments());
         case CliCommandLine.ParsedVersionApply va -> runVersionApply(va.arguments());
         case CliCommandLine.ParsedCheck pc -> runCheck();
         case CliCommandLine.ParsedWorkflow workflow -> runWorkflow(workflow);
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
      return result instanceof WorkspacePlanningResult.Failure ? CliExit.FAILURE : CliExit.OK;
   }

   /** The only command that changes an existing project. Exit 4 means it stopped half way and must be run again. */
   private static int runVersionApply(VersionArguments args) {
      WorkspaceApplyResult result = new WorkspaceVersionApplication(new GitVersionControl()).apply(directoryRequest(args), args.expectedPlanId());
      ResultRenderer.render(VersionResultDocument.render(result));
      return switch (result) {
         case WorkspaceApplyResult.Applied a -> CliExit.OK;
         case WorkspaceApplyResult.NoChanges n -> CliExit.OK;
         case WorkspaceApplyResult.Failure f -> CliExit.FAILURE;
         case WorkspaceApplyResult.RerunRequired r -> CliExit.RERUN_REQUIRED;
      };
   }

   private static int runWorkflow(CliCommandLine.ParsedWorkflow args) {
      if (args.command().equals("generate")) {
         var result = new io.kcg.sir.application.api.ToolchainApplication().execute(
            new io.kcg.sir.application.api.ToolchainRequest(args.sourceRoot(), args.entry(), args.destination()));
         ResultRenderer.render(WorkflowResultDocument.render(result));
         return result instanceof io.kcg.sir.application.api.ToolchainResult.Success ? CliExit.OK : CliExit.FAILURE;
      }
      if (args.command().equals("package")) {
         var result = new io.kcg.sir.application.api.DeploymentPackageApplication().export(
            new io.kcg.sir.application.api.DeploymentPackageRequest(args.sourceRoot(), args.entry(), args.destination(),
               java.util.Optional.ofNullable(args.acceptanceRoot()), java.util.Optional.ofNullable(args.basePackage())));
         ResultRenderer.render(WorkflowResultDocument.render(result));
         return result instanceof io.kcg.sir.application.api.DeploymentPackageResult.Exported ? CliExit.OK : CliExit.FAILURE;
      }
      var app = new io.kcg.sir.application.api.SourceEditingApplication();
      if (args.command().equals("inspect")) {
         var result = app.inspect(new io.kcg.sir.application.api.SourceInspectionRequest(args.sourceRoot(), args.entry()));
         ResultRenderer.render(WorkflowResultDocument.render(result));
         return result instanceof io.kcg.sir.application.api.SourceInspectionResult.Success ? CliExit.OK : CliExit.FAILURE;
      }
      io.kcg.sir.change.edit.CommandBatch batch;
      try {
         batch = CommandJson.read(System.in);
      } catch (IllegalArgumentException e) {
         ResultRenderer.render(WorkflowResultDocument.inputFailure("USAGE_ERROR", "KCG-CLI-COMMAND-INPUT-001", e.getMessage()));
         return CliExit.USAGE;
      } catch (java.io.IOException e) {
         ResultRenderer.render(WorkflowResultDocument.inputFailure("FAILURE", "KCG-CLI-COMMAND-IO-001", "command input could not be read"));
         return CliExit.FAILURE;
      }
      var result = app.edit(new io.kcg.sir.application.api.SourceEditRequest(args.sourceRoot(), args.entry(), args.destination(), batch));
      ResultRenderer.render(WorkflowResultDocument.render(result));
      return result instanceof io.kcg.sir.application.api.SourceEditResult.Saved ? CliExit.OK : CliExit.FAILURE;
   }

   private static int runCheck() {
      try {
         var summary = new io.kcg.sir.application.api.SirValidationApplication().checkBatch(
            new io.kcg.sir.application.api.SirValidationBatchRequest(System.in, result -> {
               CheckResultRenderer.render(System.out, result);
               if (System.out.checkError()) throw new java.io.IOException("output unavailable");
            }));
         return summary.internalFailure() ? CliExit.INTERNAL : CliExit.OK;
      } catch (java.io.IOException e) {
         System.err.println("KCG-CHECK-IO-001: JSONL transport could not complete");
         return CliExit.FAILURE;
      } catch (RuntimeException | StackOverflowError e) {
         System.err.println("KCG-CHECK-INTERNAL-001: batch checking could not complete");
         return CliExit.INTERNAL;
      }
   }

   private static void printHelp(CliCommandLine.Help h) {
      String text = switch (h.command()) {
         case null -> TOP_HELP;
         case "check" -> "Usage: kcg check < samples.jsonl\nReads UTF-8 JSONL from stdin and writes one canonical JSON result per input line.\nStatic checking only; does not build, start or certify business behavior.\nEach object requires id and sir, with optional stopAfter: PARSE, SEMANTIC, LOWERING, GENERATION.\nDefault GENERATION. Limits: 1 MiB SIR, 8 MiB JSON line, 10000 accepted lines.\nOrdinary rejected samples do not make the process fail. No options; no files or state written.";
         case "generate" -> "Usage: kcg generate --source-root <absolute-path> --entry <relative-path> --output-root <absolute-path>\nGenerates a new Spring Boot project; --output-root must not exist and must not overlap the source root.\nDoes not change SIR, save Git snapshots, download dependencies or run the project.\nAll options required once; roots must be absolute normalized paths. Result: KCG-CLI-WORKFLOW-V1.";
         case "package" -> "Usage: kcg package --source-root <absolute-path> --entry <relative-path> --output-root <absolute-path>\n                   [--acceptance <absolute-path>] [--base-package <absolute-path>]\nExports a new standalone source directory with Docker/Compose, Flyway initialization, Swagger and a contract test.\n--acceptance copies a separate business acceptance Maven project (root pom.xml, UTF-8 text files) to acceptance/;\nit is not compiled or interpreted here. Run it with deploy.sh acceptance-test on a disposable instance.\n--base-package is the delivery an instance runs now: its migration chain is inherited and only new tables and\nnew nullable columns are appended; anything else is refused. Move the instance with deploy.sh update.\nNo Docker, builds, downloads or processes run during export. Target: Linux x86_64.\nDelivery v1 refuses unbounded unique text, Decimal storage and missing external actor adapters.\nUse sh deploy/deploy.sh in the exported package on CI or your selected deployment machine.\nDoes not update an existing deployment; output-root must be new and nonoverlapping.\nExit 0 exported, 2 usage, 3 refused/I/O failure, 70 unexpected error. Result: KCG-CLI-WORKFLOW-V1.";
         case "inspect" -> "Usage: kcg inspect --source-root <absolute-path> --entry <relative-path>\nRead-only software/schema inspection, using the formal semantic analysis.\nPrints the source-set baselineSha256, softwareName and generic editable draft templates.\nNo target generation, files, Git or processes. Result: KCG-CLI-WORKFLOW-V1.";
         case "edit" -> "Usage: kcg edit --source-root <absolute-path> --entry <relative-path> --candidate-root <absolute-path> < commands.json\nReads one UTF-8 JSON object: KCG-CLI-COMMANDS-V1 wrapping sir-command/1 add/delete/update.\nLimits: 8 MiB JSON, 1 MiB/string, 64 JSON levels, 100000 values, 1000 commands, 64 selector steps.\nOnly declarations can be edited; header/import edits, BOM and rewrites losing comments are refused.\nSaves all candidate source files to a new nonoverlapping root. Never changes the original sources or project.\nUse version-plan/apply to publish the candidate. No Git, builds or processes here.\nExit 0 saved, 2 input/usage error, 3 refusal/I/O failure, 70 unexpected internal error. Result: KCG-CLI-WORKFLOW-V1.";
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
