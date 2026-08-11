package io.kcg.cli;

import io.kcg.sir.application.api.ChangeBaselinePlanningRequest;
import io.kcg.sir.application.api.ChangeBaselinePlanningResult;
import io.kcg.sir.application.api.ChangeExecutionApplication;
import io.kcg.sir.application.api.ChangePlanningContext;
import io.kcg.sir.application.api.ChangePlanningContextRequest;
import io.kcg.sir.application.api.ChangePlanningContextResult;
import io.kcg.sir.application.api.ChangePlanningTarget;
import io.kcg.sir.application.api.RecoveryHandle;
import io.kcg.sir.application.api.ChangePlanningContextResult.Failure;
import io.kcg.sir.application.api.ChangePlanningContextResult.RecoveryRequired;
import io.kcg.sir.application.api.ChangePlanningContextResult.Success;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeOperation;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.change.api.ChangeAnalysis.Planned;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class KcgCli {
   public static final String VERSION = "kcg 0.1.0 (KCG-CLI-CHANGE-PLANNING-V1)";
   static volatile RuntimeException crashInjection = null;
   static volatile Runnable afterContextHook = null;
   private static final String TOP_HELP = "Usage: kcg <command> [options]\nRead-only Change Planning CLI V1.\n\nCommands:\n  context   Inspect the authoritative planning context and target catalog.\n  plan      Re-derive the context and invoke Change SIR planning for one target.\n\nGlobal options:\n  --help, -h     Show this help (or command help with `kcg <command> --help`).\n  --version, -V  Show the CLI version.";
   private static final String CONTEXT_HELP = "Usage: kcg context --state-root <absolute-path> --output-root <absolute-path> --candidate-sir <absolute-path>\nInspects the locked CURRENT Bundle, recompiles base and candidate, and emits the\nauthoritative contextId plus the deterministic typed target catalog.\n\nAll options are required and must occur exactly once. Paths must be absolute.";
   private static final String PLAN_HELP = "Usage: kcg plan --state-root <absolute-path> --output-root <absolute-path>\n               --candidate-sir <absolute-path> --expected-context-id <64-lower-hex>\n               --target-key <64-lower-hex>\n               --change-ir-version <V0_1|V0_2|V0_3|V0_4|V0_5|V0_6>\n               --operation <token>\nRe-derives the context, binds the candidate digest, and invokes Change SIR planning\nfor the selected target.\n\nOperation tokens:\n  modify-capability-workflow, add-capability, remove-capability,\n  modify-input-field-constraints, modify-unreferenced-input-field-type,\n  modify-actorless-readonly-capability-exposure\n\nAll options are required and must occur exactly once. Paths must be absolute.";

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

         ResultRenderer.render(ResultRenderer.cliPlanFailure("KCG-CLI-INTERNAL-001", "unexpected internal error"));
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
         case CliCommandLine.ParsedContext pc -> runContext(pc.arguments());
         case CliCommandLine.ParsedPlan pp -> runPlan(pp.arguments());
         default -> throw new MatchException(null, null);
      };
   }

   private static int runContext(ContextArguments args) {
      ChangeExecutionApplication app = new ChangeExecutionApplication();
      ChangePlanningContextRequest req = new ChangePlanningContextRequest(args.stateRoot(), args.outputRoot(), args.candidateSirFile());
      ChangePlanningContextResult result = app.inspectChangePlanningContext(req);
      ResultRenderer.render(ContextResultDocument.render(result));

      return switch (result) {
         case Success s -> 0;
         case Failure f -> 3;
         case RecoveryRequired r -> 4;
         default -> throw new MatchException(null, null);
      };
   }

   private static int runPlan(PlanArguments args) {
      ChangeExecutionApplication app = new ChangeExecutionApplication();
      ChangePlanningContextRequest ctxReq = new ChangePlanningContextRequest(args.stateRoot(), args.outputRoot(), args.candidateSirFile());
      ChangePlanningContextResult ctxResult = app.inspectChangePlanningContext(ctxReq);
      if (ctxResult instanceof Failure f) {
         ChangeBaselinePlanningResult mapped = ChangeBaselinePlanningResult.failure(f.failedStage(), Optional.empty(), f.diagnostics());
         ResultRenderer.render(PlanResultDocument.render(mapped));
         return 3;
      } else if (ctxResult instanceof RecoveryRequired r) {
         ChangeBaselinePlanningResult mapped = ChangeBaselinePlanningResult.recoveryRequired(RecoveryHandle.any(), r.diagnostics());
         ResultRenderer.render(PlanResultDocument.render(mapped));
         return 4;
      } else {
         ChangePlanningContext ctx = ((Success)ctxResult).context();
         Runnable hook = afterContextHook;
         if (hook != null) {
            hook.run();
         }

         if (!ctx.contextId().equals(args.expectedContextId())) {
            ResultRenderer.render(
               ResultRenderer.cliPlanFailure(
                  "KCG-CLI-CONTEXT-002", "expected context id mismatch: expected=" + args.expectedContextId() + " actual=" + ctx.contextId()
               )
            );
            return 3;
         }

         List<ChangePlanningTarget> matches = new ArrayList<>();

         for (ChangePlanningTarget t : ctx.targets()) {
            if (t.targetKey().equals(args.targetKey())) {
               matches.add(t);
            }
         }

         if (matches.isEmpty()) {
            ResultRenderer.render(ResultRenderer.cliPlanFailure("KCG-CLI-CONTEXT-003", "target key absent from fresh context: " + args.targetKey()));
            return 3;
         }

         if (matches.size() > 1) {
            ResultRenderer.render(ResultRenderer.cliPlanFailure("KCG-CLI-CONTEXT-004", "duplicate target key in fresh context: " + args.targetKey()));
            return 3;
         }

         ChangePlanningTarget selected = matches.get(0);
         ChangeOperation op = args.operation().create(selected.target());
         ChangeBaseRevision revision = ctx.baseline().revision();
         ChangeSet changeSet = new ChangeSet(args.changeIrVersion(), revision, List.of(op));
         ChangeBaselinePlanningRequest planReq = ChangeBaselinePlanningRequest.digestBound(
            args.stateRoot(), ctx.baseline().baselineId(), args.candidateSirFile(), args.outputRoot(), changeSet, ctx.candidateSourceSha256Hex()
         );
         ChangeBaselinePlanningResult planResult = app.plan(planReq);
         ResultRenderer.render(PlanResultDocument.render(planResult));

         return switch (planResult) {
            case io.kcg.sir.application.api.ChangeBaselinePlanningResult.Success s -> s.analysis() instanceof Planned ? 0 : 0;
            case io.kcg.sir.application.api.ChangeBaselinePlanningResult.Failure f -> 3;
            case io.kcg.sir.application.api.ChangeBaselinePlanningResult.RecoveryRequired r -> 4;
            default -> throw new MatchException(null, null);
         };
      }
   }

   private static void printHelp(CliCommandLine.Help h) {
      String text = switch (h.command()) {
         case null -> "Usage: kcg <command> [options]\nRead-only Change Planning CLI V1.\n\nCommands:\n  context   Inspect the authoritative planning context and target catalog.\n  plan      Re-derive the context and invoke Change SIR planning for one target.\n\nGlobal options:\n  --help, -h     Show this help (or command help with `kcg <command> --help`).\n  --version, -V  Show the CLI version.";
         case "context" -> "Usage: kcg context --state-root <absolute-path> --output-root <absolute-path> --candidate-sir <absolute-path>\nInspects the locked CURRENT Bundle, recompiles base and candidate, and emits the\nauthoritative contextId plus the deterministic typed target catalog.\n\nAll options are required and must occur exactly once. Paths must be absolute.";
         case "plan" -> "Usage: kcg plan --state-root <absolute-path> --output-root <absolute-path>\n               --candidate-sir <absolute-path> --expected-context-id <64-lower-hex>\n               --target-key <64-lower-hex>\n               --change-ir-version <V0_1|V0_2|V0_3|V0_4|V0_5|V0_6>\n               --operation <token>\nRe-derives the context, binds the candidate digest, and invokes Change SIR planning\nfor the selected target.\n\nOperation tokens:\n  modify-capability-workflow, add-capability, remove-capability,\n  modify-input-field-constraints, modify-unreferenced-input-field-type,\n  modify-actorless-readonly-capability-exposure\n\nAll options are required and must occur exactly once. Paths must be absolute.";
         default -> "Usage: kcg <command> [options]\nRead-only Change Planning CLI V1.\n\nCommands:\n  context   Inspect the authoritative planning context and target catalog.\n  plan      Re-derive the context and invoke Change SIR planning for one target.\n\nGlobal options:\n  --help, -h     Show this help (or command help with `kcg <command> --help`).\n  --version, -V  Show the CLI version.";
      };
      byte[] b = text.getBytes(StandardCharsets.UTF_8);
      System.out.write(b, 0, b.length);
      System.out.write(10);
      System.out.flush();
   }

   private static void printVersion() {
      byte[] b = "kcg 0.1.0 (KCG-CLI-CHANGE-PLANNING-V1)".getBytes(StandardCharsets.UTF_8);
      System.out.write(b, 0, b.length);
      System.out.write(10);
      System.out.flush();
   }
}
