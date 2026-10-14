package io.kcg.cli;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ProjectBaselineReceipt;
import io.kcg.sir.application.api.SourceDelta;
import io.kcg.sir.application.api.VersionPlan;
import io.kcg.sir.application.api.WorkspaceApplyResult;
import io.kcg.sir.application.api.WorkspacePlan;
import io.kcg.sir.application.api.WorkspacePlanningResult;
import java.util.List;

/**
 * The canonical JSON documents of {@code version-plan} and {@code version-apply}: one line, fixed field order,
 * so the same input prints the same bytes. The old {@code context} and {@code plan} documents keep their own
 * protocol version; these two commands have a protocol of their own.
 */
public final class VersionResultDocument {
   public static final String PROTOCOL = "KCG-CLI-VERSION-CHANGE-V1";

   private VersionResultDocument() {
   }

   public static String render(WorkspacePlanningResult result) {
      StringBuilder sb = new StringBuilder();
      sb.append('{');
      switch (result) {
         case WorkspacePlanningResult.Planned planned -> {
            envelope(sb, "version-plan", "PLANNED", "PLAN");
            plan(sb, planned.plan());
         }
         case WorkspacePlanningResult.NoChanges none -> {
            envelope(sb, "version-plan", "NO_CHANGES", "PLAN");
            ContextResultDocument.comma(sb);
            receipt(sb, "baseline", none.baseline());
         }
         case WorkspacePlanningResult.Failure failure -> envelope(sb, "version-plan", "FAILURE", failure.failedStage().name());
      }

      diagnostics(sb, result.diagnostics());
      sb.append('}');
      return sb.toString();
   }

   public static String render(WorkspaceApplyResult result) {
      StringBuilder sb = new StringBuilder();
      sb.append('{');
      switch (result) {
         case WorkspaceApplyResult.Applied applied -> {
            envelope(sb, "version-apply", "APPLIED", "APPLY");
            ContextResultDocument.comma(sb);
            receipt(sb, "base", applied.base());
            ContextResultDocument.comma(sb);
            receipt(sb, "candidate", applied.candidate());
            ContextResultDocument.comma(sb);
            ContextResultDocument.field(sb, "beforeCommit", applied.beforeCommit().orElse(""));
            ContextResultDocument.comma(sb);
            ContextResultDocument.field(sb, "afterCommit", applied.afterCommit().orElse(""));
         }
         case WorkspaceApplyResult.NoChanges none -> {
            envelope(sb, "version-apply", "NO_CHANGES", "APPLY");
            ContextResultDocument.comma(sb);
            receipt(sb, "baseline", none.baseline());
         }
         case WorkspaceApplyResult.Failure failure -> envelope(sb, "version-apply", "FAILURE", failure.failedStage().name());
         case WorkspaceApplyResult.RerunRequired rerun -> {
            envelope(sb, "version-apply", "RERUN_REQUIRED", "WRITE");
            ContextResultDocument.comma(sb);
            ContextResultDocument.field(sb, "beforeCommit", rerun.beforeCommit().orElse(""));
         }
      }

      diagnostics(sb, result.diagnostics());
      sb.append('}');
      return sb.toString();
   }

   private static void envelope(StringBuilder sb, String command, String outcome, String stage) {
      ContextResultDocument.field(sb, "protocolVersion", PROTOCOL);
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "command", command);
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "outcome", outcome);
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "stage", stage);
   }

   private static void plan(StringBuilder sb, WorkspacePlan plan) {
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "planId", plan.sha256Hex());
      ContextResultDocument.comma(sb);
      receipt(sb, "base", plan.base());
      ContextResultDocument.comma(sb);
      receipt(sb, "candidate", plan.candidate());
      ContextResultDocument.comma(sb);
      sb.append("\"summary\":{");
      ContextResultDocument.field(sb, "create", plan.count(VersionPlan.Action.CREATE));
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "update", plan.count(VersionPlan.Action.UPDATE));
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "delete", plan.count(VersionPlan.Action.DELETE));
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "unchanged", plan.unchangedFiles());
      sb.append('}');
      ContextResultDocument.comma(sb);
      sb.append("\"sources\":[");
      boolean first = true;
      for (SourceDelta.Entry entry : plan.sources().entries()) {
         if (entry.action() == SourceDelta.Action.UNCHANGED) {
            continue;
         }

         if (!first) {
            sb.append(',');
         }

         first = false;
         sb.append('{');
         ContextResultDocument.field(sb, "source", entry.source().value());
         ContextResultDocument.comma(sb);
         ContextResultDocument.field(sb, "action", entry.action().name());
         sb.append('}');
      }

      sb.append(']');
      ContextResultDocument.comma(sb);
      sb.append("\"files\":[");
      for (int i = 0; i < plan.files().size(); i++) {
         if (i > 0) {
            sb.append(',');
         }

         VersionPlan.FileOp op = plan.files().get(i);
         sb.append('{');
         ContextResultDocument.field(sb, "path", op.path());
         ContextResultDocument.comma(sb);
         ContextResultDocument.field(sb, "action", op.action().name());
         sb.append('}');
      }

      sb.append(']');
   }

   private static void receipt(StringBuilder sb, String name, ProjectBaselineReceipt receipt) {
      sb.append(JsonStringEncoder.encode(name)).append(":{");
      ContextResultDocument.field(sb, "baselineId", receipt.baselineId());
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "sourceSetSha256", receipt.sourceSetSha256Hex());
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "manifestDigest", receipt.manifestDigest());
      sb.append('}');
   }

   private static void diagnostics(StringBuilder sb, List<ExecutionDiagnostic> diagnostics) {
      ContextResultDocument.comma(sb);
      sb.append("\"diagnostics\":[");
      for (int i = 0; i < diagnostics.size(); i++) {
         if (i > 0) {
            sb.append(',');
         }

         ExecutionDiagnostic d = diagnostics.get(i);
         sb.append('{');
         ContextResultDocument.field(sb, "code", d.code());
         ContextResultDocument.comma(sb);
         ContextResultDocument.field(sb, "stage", d.stage().name());
         ContextResultDocument.comma(sb);
         ContextResultDocument.field(sb, "severity", d.severity().name());
         ContextResultDocument.comma(sb);
         ContextResultDocument.field(sb, "message", d.message());
         if (d.relativePath().isPresent()) {
            ContextResultDocument.comma(sb);
            ContextResultDocument.field(sb, "relativePath", d.relativePath().get());
         }

         sb.append('}');
      }

      sb.append(']');
   }
}
