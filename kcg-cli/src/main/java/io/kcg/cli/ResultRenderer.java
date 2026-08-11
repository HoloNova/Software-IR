package io.kcg.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class ResultRenderer {
   private ResultRenderer() {
   }

   public static void render(String json) {
      PrintStream out = System.out;
      byte[] b = json.getBytes(StandardCharsets.UTF_8);
      out.write(b, 0, b.length);
      out.write(10);
      out.flush();
   }

   public static String usageError(String code, String message) {
      StringBuilder sb = new StringBuilder();
      sb.append('{');
      ContextResultDocument.field(sb, "protocolVersion", "KCG-CLI-CHANGE-PLANNING-V1");
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "command", "kcg");
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "outcome", "USAGE_ERROR");
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "stage", "USAGE");
      ContextResultDocument.comma(sb);
      sb.append("\"diagnostics\":[");
      sb.append('{');
      ContextResultDocument.field(sb, "code", code);
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "stage", "USAGE");
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "severity", "ERROR");
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "message", message);
      sb.append('}');
      sb.append(']');
      sb.append('}');
      return sb.toString();
   }

   public static String cliPlanFailure(String code, String message) {
      StringBuilder sb = new StringBuilder();
      sb.append('{');
      ContextResultDocument.field(sb, "protocolVersion", "KCG-CLI-CHANGE-PLANNING-V1");
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "command", "plan");
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "outcome", "FAILURE");
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "stage", "CONTEXT");
      ContextResultDocument.comma(sb);
      sb.append("\"diagnostics\":[");
      sb.append('{');
      ContextResultDocument.field(sb, "code", code);
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "stage", "CONTEXT");
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "severity", "ERROR");
      ContextResultDocument.comma(sb);
      ContextResultDocument.field(sb, "message", message);
      sb.append('}');
      sb.append(']');
      sb.append('}');
      return sb.toString();
   }
}
