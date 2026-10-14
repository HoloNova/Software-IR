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
            JsonFields.comma(sb);
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
            JsonFields.comma(sb);
            receipt(sb, "base", applied.base());
            JsonFields.comma(sb);
            receipt(sb, "candidate", applied.candidate());
            JsonFields.comma(sb);
            JsonFields.field(sb, "beforeCommit", applied.beforeCommit().orElse(""));
            JsonFields.comma(sb);
            JsonFields.field(sb, "afterCommit", applied.afterCommit().orElse(""));
         }
         case WorkspaceApplyResult.NoChanges none -> {
            envelope(sb, "version-apply", "NO_CHANGES", "APPLY");
            JsonFields.comma(sb);
            receipt(sb, "baseline", none.baseline());
         }
         case WorkspaceApplyResult.Failure failure -> envelope(sb, "version-apply", "FAILURE", failure.failedStage().name());
         case WorkspaceApplyResult.RerunRequired rerun -> {
            envelope(sb, "version-apply", "RERUN_REQUIRED", "WRITE");
            JsonFields.comma(sb);
            JsonFields.field(sb, "beforeCommit", rerun.beforeCommit().orElse(""));
         }
      }

      diagnostics(sb, result.diagnostics());
      sb.append('}');
      return sb.toString();
   }

   private static void envelope(StringBuilder sb, String command, String outcome, String stage) {
      JsonFields.field(sb, "protocolVersion", PROTOCOL);
      JsonFields.comma(sb);
      JsonFields.field(sb, "command", command);
      JsonFields.comma(sb);
      JsonFields.field(sb, "outcome", outcome);
      JsonFields.comma(sb);
      JsonFields.field(sb, "stage", stage);
   }

   private static void plan(StringBuilder sb, WorkspacePlan plan) {
      JsonFields.comma(sb);
      JsonFields.field(sb, "planId", plan.sha256Hex());
      JsonFields.comma(sb);
      receipt(sb, "base", plan.base());
      JsonFields.comma(sb);
      receipt(sb, "candidate", plan.candidate());
      JsonFields.comma(sb);
      sb.append("\"summary\":{");
      JsonFields.field(sb, "create", plan.count(VersionPlan.Action.CREATE));
      JsonFields.comma(sb);
      JsonFields.field(sb, "update", plan.count(VersionPlan.Action.UPDATE));
      JsonFields.comma(sb);
      JsonFields.field(sb, "delete", plan.count(VersionPlan.Action.DELETE));
      JsonFields.comma(sb);
      JsonFields.field(sb, "unchanged", plan.unchangedFiles());
      sb.append('}');
      JsonFields.comma(sb);
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
         JsonFields.field(sb, "source", entry.source().value());
         JsonFields.comma(sb);
         JsonFields.field(sb, "action", entry.action().name());
         sb.append('}');
      }

      sb.append(']');
      JsonFields.comma(sb);
      sb.append("\"files\":[");
      for (int i = 0; i < plan.files().size(); i++) {
         if (i > 0) {
            sb.append(',');
         }

         VersionPlan.FileOp op = plan.files().get(i);
         sb.append('{');
         JsonFields.field(sb, "path", op.path());
         JsonFields.comma(sb);
         JsonFields.field(sb, "action", op.action().name());
         sb.append('}');
      }

      sb.append(']');
   }

   private static void receipt(StringBuilder sb, String name, ProjectBaselineReceipt receipt) {
      sb.append(JsonStringEncoder.encode(name)).append(":{");
      JsonFields.field(sb, "baselineId", receipt.baselineId());
      JsonFields.comma(sb);
      JsonFields.field(sb, "sourceSetSha256", receipt.sourceSetSha256Hex());
      JsonFields.comma(sb);
      JsonFields.field(sb, "manifestDigest", receipt.manifestDigest());
      sb.append('}');
   }

   private static void diagnostics(StringBuilder sb, List<ExecutionDiagnostic> diagnostics) {
      JsonFields.comma(sb);
      sb.append("\"diagnostics\":[");
      for (int i = 0; i < diagnostics.size(); i++) {
         if (i > 0) {
            sb.append(',');
         }

         ExecutionDiagnostic d = diagnostics.get(i);
         sb.append('{');
         JsonFields.field(sb, "code", d.code());
         JsonFields.comma(sb);
         JsonFields.field(sb, "stage", d.stage().name());
         JsonFields.comma(sb);
         JsonFields.field(sb, "severity", d.severity().name());
         JsonFields.comma(sb);
         JsonFields.field(sb, "message", d.message());
         if (d.relativePath().isPresent()) {
            JsonFields.comma(sb);
            JsonFields.field(sb, "relativePath", d.relativePath().get());
         }

         sb.append('}');
      }

      sb.append(']');
   }
}
