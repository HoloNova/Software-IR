package io.kcg.cli;

import io.kcg.sir.application.api.ChangeBaselineReceipt;
import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeExecutionStage;
import io.kcg.sir.application.api.ChangePlanningContext;
import io.kcg.sir.application.api.ChangePlanningContextResult;
import io.kcg.sir.application.api.ChangePlanningTarget;
import io.kcg.sir.application.api.ChangePlanningContextResult.Failure;
import io.kcg.sir.application.api.ChangePlanningContextResult.RecoveryRequired;
import io.kcg.sir.application.api.ChangePlanningContextResult.Success;
import io.kcg.sir.change.api.ChangeBaseRevision;
import java.util.List;

public final class ContextResultDocument {
   private ContextResultDocument() {
   }

   public static String render(ChangePlanningContextResult result) {
      StringBuilder sb = new StringBuilder();
      sb.append('{');
      envelope(sb, "context", result);
      switch (result) {
         case Success s:
            renderSuccess(sb, s.context(), s.diagnostics());
            break;
         case Failure f:
            break;
         case RecoveryRequired var6:
            break;
         default:
            throw new MatchException(null, null);
      }

      sb.append('}');
      return sb.toString();
   }

   private static void renderSuccess(StringBuilder sb, ChangePlanningContext ctx, List<ChangeExecutionDiagnostic> diags) {
      comma(sb);
      field(sb, "contextId", ctx.contextId());
      comma(sb);
      sb.append("\"baseline\":{");
      ChangeBaselineReceipt b = ctx.baseline();
      field(sb, "baselineId", b.baselineId());
      comma(sb);
      sb.append("\"basedOn\":{");
      ChangeBaseRevision rev = b.revision();
      field(sb, "sourceId", rev.sourceId().value());
      comma(sb);
      field(sb, "baseSirSha256Hex", rev.baseSourceSha256Hex());
      comma(sb);
      field(sb, "graphVersion", rev.graphVersion().name());
      comma(sb);
      field(sb, "canonicalGraphDigest", rev.graphCanonicalDigest());
      comma(sb);
      field(sb, "snapshotFormatVersion", rev.snapshotFormatVersion().name());
      sb.append('}');
      comma(sb);
      field(sb, "outputRoot", b.boundOutputRoot().toString());
      comma(sb);
      field(sb, "targetProfile", b.targetId());
      comma(sb);
      field(sb, "loweredIrVersion", b.loweredIrVersion().value());
      sb.append('}');
      comma(sb);
      field(sb, "candidateSourceSha256Hex", ctx.candidateSourceSha256Hex());
      comma(sb);
      sb.append("\"targets\":[");
      List<ChangePlanningTarget> targets = ctx.targets();

      for (int i = 0; i < targets.size(); i++) {
         if (i > 0) {
            sb.append(',');
         }

         renderTarget(sb, targets.get(i));
      }

      sb.append(']');
   }

   private static void renderTarget(StringBuilder sb, ChangePlanningTarget t) {
      sb.append('{');
      field(sb, "targetKey", t.targetKey());
      comma(sb);
      field(sb, "side", t.side().name());
      comma(sb);
      field(sb, "kind", t.kind().name());
      comma(sb);
      field(sb, "declarationSymbol", t.target().declarationSymbol().value());
      comma(sb);
      field(sb, "declarationNodeId", t.target().declarationNodeId().value());
      comma(sb);
      field(sb, "targetNodeId", t.target().targetNodeId().value());
      comma(sb);
      field(sb, "declarationDisplayName", t.declarationDisplayName());
      if (t.targetDisplayName().isPresent()) {
         comma(sb);
         field(sb, "targetDisplayName", t.targetDisplayName().get());
      }

      sb.append('}');
   }

   private static void renderFailure(StringBuilder sb, ChangeExecutionStage stage, List<ChangeExecutionDiagnostic> diags) {
   }

   private static void renderRecovery(StringBuilder sb, List<ChangeExecutionDiagnostic> diags) {
   }

   static void envelope(StringBuilder sb, String command, ChangePlanningContextResult result) {
      String outcome;
      String stage;
      switch (result) {
         case Success s:
            outcome = "CONTEXT_READY";
            stage = "SUCCESS";
            break;
         case Failure f:
            outcome = "FAILURE";
            stage = f.failedStage().name();
            break;
         case RecoveryRequired r:
            outcome = "RECOVERY_REQUIRED";
            stage = "BASELINE";
            break;
         default:
            throw new MatchException(null, null);
      }

      field(sb, "protocolVersion", "KCG-CLI-CHANGE-PLANNING-V1");
      comma(sb);
      field(sb, "command", command);
      comma(sb);
      field(sb, "outcome", outcome);
      comma(sb);
      field(sb, "stage", stage);
      comma(sb);
      sb.append("\"diagnostics\":[");
      List<ChangeExecutionDiagnostic> diags = result.diagnostics();

      for (int i = 0; i < diags.size(); i++) {
         if (i > 0) {
            sb.append(',');
         }

         renderDiagnostic(sb, diags.get(i));
      }

      sb.append(']');
   }

   static void renderDiagnostic(StringBuilder sb, ChangeExecutionDiagnostic d) {
      sb.append('{');
      field(sb, "code", d.code());
      comma(sb);
      field(sb, "stage", d.stage().name());
      comma(sb);
      field(sb, "severity", d.severity().name());
      comma(sb);
      field(sb, "message", d.message());
      if (d.relativePath().isPresent()) {
         comma(sb);
         field(sb, "relativePath", d.relativePath().get());
      }

      sb.append('}');
   }

   static void field(StringBuilder sb, String name, String value) {
      sb.append(JsonStringEncoder.encode(name)).append(':').append(JsonStringEncoder.encode(value));
   }

   static void field(StringBuilder sb, String name, long value) {
      sb.append(JsonStringEncoder.encode(name)).append(':').append(value);
   }

   static void comma(StringBuilder sb) {
      sb.append(',');
   }
}
