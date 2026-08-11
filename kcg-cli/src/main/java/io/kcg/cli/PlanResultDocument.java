package io.kcg.cli;

import io.kcg.sir.application.api.ChangeBaselinePlanningResult;
import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeBaselinePlanningResult.Failure;
import io.kcg.sir.application.api.ChangeBaselinePlanningResult.RecoveryRequired;
import io.kcg.sir.application.api.ChangeBaselinePlanningResult.Success;
import io.kcg.sir.change.api.ArtifactAddition;
import io.kcg.sir.change.api.ArtifactChange;
import io.kcg.sir.change.api.ArtifactDeletion;
import io.kcg.sir.change.api.ChangeAnalysis;
import io.kcg.sir.change.api.ChangePlan;
import io.kcg.sir.change.api.FileAddition;
import io.kcg.sir.change.api.FileChange;
import io.kcg.sir.change.api.FileDeletion;
import io.kcg.sir.change.api.ImpactedArtifact;
import io.kcg.sir.change.api.ChangeAnalysis.NoChanges;
import io.kcg.sir.change.api.ChangeAnalysis.Planned;
import java.util.List;

public final class PlanResultDocument {
   private PlanResultDocument() {
   }

   public static String render(ChangeBaselinePlanningResult result) {
      StringBuilder sb = new StringBuilder();
      sb.append('{');
      envelope(sb, result);
      switch (result) {
         case Success s:
            renderSuccess(sb, s.analysis());
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

   private static void renderSuccess(StringBuilder sb, ChangeAnalysis analysis) {
      switch (analysis) {
         case Planned p:
            renderPlanned(sb, p.plan());
            break;
         case NoChanges n:
            ContextResultDocument.comma(sb);
            ContextResultDocument.field(sb, "noChangeReason", n.reason().name());
            break;
         case io.kcg.sir.change.api.ChangeAnalysis.Failure var6:
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private static void renderPlanned(StringBuilder sb, ChangePlan plan) {
      ContextResultDocument.comma(sb);
      sb.append("\"plan\":{");
      boolean first = true;
      first = appendArtifactChanges(sb, "artifactChanges", plan.artifactChanges(), first);
      first = appendFileChanges(sb, "fileChanges", plan.fileChanges(), first);
      first = appendArtifactAdditions(sb, "artifactAdditions", plan.artifactAdditions(), first);
      first = appendFileAdditions(sb, "fileAdditions", plan.fileAdditions(), first);
      first = appendArtifactDeletions(sb, "artifactDeletions", plan.artifactDeletions(), first);
      first = appendFileDeletions(sb, "fileDeletions", plan.fileDeletions(), first);
      sb.append('}');
   }

   private static boolean appendArtifactChanges(StringBuilder sb, String name, List<ArtifactChange> entries, boolean first) {
      if (entries.isEmpty()) {
         return first;
      }

      if (!first) {
         ContextResultDocument.comma(sb);
      }

      sb.append(JsonStringEncoder.encode(name)).append(':').append('[');

      for (int i = 0; i < entries.size(); i++) {
         if (i > 0) {
            sb.append(',');
         }

         ArtifactChange ac = entries.get(i);
         ImpactedArtifact a = ac.artifact();
         sb.append('{');
         ContextResultDocument.field(sb, "artifactId", a.artifactId().value());
         ContextResultDocument.comma(sb);
         ContextResultDocument.field(sb, "role", a.role().toString());
         ContextResultDocument.comma(sb);
         ContextResultDocument.field(sb, "qualifiedName", a.qualifiedName());
         if (a.ownerSymbol().isPresent()) {
            ContextResultDocument.comma(sb);
            ContextResultDocument.field(sb, "ownerSymbol", a.ownerSymbol().get().value());
         }

         ContextResultDocument.comma(sb);
         sb.append("\"fileChanges\":[");
         List<FileChange> fcs = ac.fileChanges();

         for (int j = 0; j < fcs.size(); j++) {
            if (j > 0) {
               sb.append(',');
            }

            renderFileChange(sb, fcs.get(j));
         }

         sb.append(']');
         sb.append('}');
      }

      sb.append(']');
      return false;
   }

   private static boolean appendFileChanges(StringBuilder sb, String name, List<FileChange> entries, boolean first) {
      if (entries.isEmpty()) {
         return first;
      }

      if (!first) {
         ContextResultDocument.comma(sb);
      }

      sb.append(JsonStringEncoder.encode(name)).append(':').append('[');

      for (int i = 0; i < entries.size(); i++) {
         if (i > 0) {
            sb.append(',');
         }

         renderFileChange(sb, entries.get(i));
      }

      sb.append(']');
      return false;
   }

   private static boolean appendArtifactAdditions(StringBuilder sb, String name, List<ArtifactAddition> entries, boolean first) {
      if (entries.isEmpty()) {
         return first;
      }

      if (!first) {
         ContextResultDocument.comma(sb);
      }

      sb.append(JsonStringEncoder.encode(name)).append(':').append('[');

      for (int i = 0; i < entries.size(); i++) {
         if (i > 0) {
            sb.append(',');
         }

         ArtifactAddition a = entries.get(i);
         sb.append('{');
         ContextResultDocument.field(sb, "artifactId", a.artifactId().value());
         ContextResultDocument.comma(sb);
         ContextResultDocument.field(sb, "role", a.role().toString());
         ContextResultDocument.comma(sb);
         ContextResultDocument.field(sb, "qualifiedName", a.qualifiedName());
         ContextResultDocument.comma(sb);
         ContextResultDocument.field(sb, "ownerSymbol", a.ownerSymbol().value());
         ContextResultDocument.comma(sb);
         sb.append("\"fileAdditions\":[");
         List<FileAddition> fas = a.fileAdditions();

         for (int j = 0; j < fas.size(); j++) {
            if (j > 0) {
               sb.append(',');
            }

            renderFileAddition(sb, fas.get(j));
         }

         sb.append(']');
         sb.append('}');
      }

      sb.append(']');
      return false;
   }

   private static boolean appendFileAdditions(StringBuilder sb, String name, List<FileAddition> entries, boolean first) {
      if (entries.isEmpty()) {
         return first;
      }

      if (!first) {
         ContextResultDocument.comma(sb);
      }

      sb.append(JsonStringEncoder.encode(name)).append(':').append('[');

      for (int i = 0; i < entries.size(); i++) {
         if (i > 0) {
            sb.append(',');
         }

         renderFileAddition(sb, entries.get(i));
      }

      sb.append(']');
      return false;
   }

   private static boolean appendArtifactDeletions(StringBuilder sb, String name, List<ArtifactDeletion> entries, boolean first) {
      if (entries.isEmpty()) {
         return first;
      }

      if (!first) {
         ContextResultDocument.comma(sb);
      }

      sb.append(JsonStringEncoder.encode(name)).append(':').append('[');

      for (int i = 0; i < entries.size(); i++) {
         if (i > 0) {
            sb.append(',');
         }

         ArtifactDeletion a = entries.get(i);
         sb.append('{');
         ContextResultDocument.field(sb, "artifactId", a.artifactId().value());
         ContextResultDocument.comma(sb);
         ContextResultDocument.field(sb, "role", a.role().toString());
         ContextResultDocument.comma(sb);
         ContextResultDocument.field(sb, "qualifiedName", a.qualifiedName());
         ContextResultDocument.comma(sb);
         ContextResultDocument.field(sb, "ownerSymbol", a.ownerSymbol().value());
         ContextResultDocument.comma(sb);
         sb.append("\"fileDeletions\":[");
         List<FileDeletion> fds = a.fileDeletions();

         for (int j = 0; j < fds.size(); j++) {
            if (j > 0) {
               sb.append(',');
            }

            renderFileDeletion(sb, fds.get(j));
         }

         sb.append(']');
         sb.append('}');
      }

      sb.append(']');
      return false;
   }

   private static boolean appendFileDeletions(StringBuilder sb, String name, List<FileDeletion> entries, boolean first) {
      if (entries.isEmpty()) {
         return first;
      }

      if (!first) {
         ContextResultDocument.comma(sb);
      }

      sb.append(JsonStringEncoder.encode(name)).append(':').append('[');

      for (int i = 0; i < entries.size(); i++) {
         if (i > 0) {
            sb.append(',');
         }

         renderFileDeletion(sb, entries.get(i));
      }

      sb.append(']');
      return false;
   }

   private static void renderFileChange(StringBuilder sb, FileChange f) {
      sb.append('{');
      ContextResultDocument.field(sb, "relativePath", f.relativePath());
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "artifactId", f.artifactId().value());
      if (f.ownerSymbol().isPresent()) {
         ContextResultDocument.comma(sb);
         ContextResultDocument.field(sb, "ownerSymbol", f.ownerSymbol().get().value());
      }

      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "baseByteCount", f.baseByteCount());
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "baseSha256Hex", f.baseSha256Hex());
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "candidateByteCount", f.candidateByteCount());
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "candidateSha256Hex", f.candidateSha256Hex());
      sb.append('}');
   }

   private static void renderFileAddition(StringBuilder sb, FileAddition f) {
      sb.append('{');
      ContextResultDocument.field(sb, "relativePath", f.relativePath());
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "artifactId", f.artifactId().value());
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "ownerSymbol", f.ownerSymbol().value());
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "candidateByteCount", f.candidateByteCount());
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "candidateSha256Hex", f.candidateSha256Hex());
      sb.append('}');
   }

   private static void renderFileDeletion(StringBuilder sb, FileDeletion f) {
      sb.append('{');
      ContextResultDocument.field(sb, "relativePath", f.relativePath());
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "artifactId", f.artifactId().value());
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "ownerSymbol", f.ownerSymbol().value());
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "baseByteCount", f.baseByteCount());
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "baseSha256Hex", f.baseSha256Hex());
      sb.append('}');
   }

   private static void envelope(StringBuilder sb, ChangeBaselinePlanningResult result) {
      String outcome;
      String stage;
      switch (result) {
         case Success s:
            outcome = s.analysis() instanceof Planned ? "PLANNED" : "NO_CHANGES";
            stage = "PLAN";
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

      ContextResultDocument.field(sb, "protocolVersion", "KCG-CLI-CHANGE-PLANNING-V1");
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "command", "plan");
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "outcome", outcome);
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "stage", stage);
      ContextResultDocument.comma(sb);
      sb.append("\"diagnostics\":[");
      List<ChangeExecutionDiagnostic> diags = result.diagnostics();

      for (int i = 0; i < diags.size(); i++) {
         if (i > 0) {
            sb.append(',');
         }

         ContextResultDocument.renderDiagnostic(sb, diags.get(i));
      }

      sb.append(']');
   }
}
