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
      JsonFields.field(sb, "protocolVersion", VersionResultDocument.PROTOCOL);
      JsonFields.comma(sb);
      JsonFields.field(sb, "command", "kcg");
      JsonFields.comma(sb);
      JsonFields.field(sb, "outcome", "USAGE_ERROR");
      JsonFields.comma(sb);
      JsonFields.field(sb, "stage", "USAGE");
      JsonFields.comma(sb);
      sb.append("\"diagnostics\":[");
      sb.append('{');
      JsonFields.field(sb, "code", code);
      JsonFields.comma(sb);
      JsonFields.field(sb, "stage", "USAGE");
      JsonFields.comma(sb);
      JsonFields.field(sb, "severity", "ERROR");
      JsonFields.comma(sb);
      JsonFields.field(sb, "message", message);
      sb.append('}');
      sb.append(']');
      sb.append('}');
      return sb.toString();
   }

   /** The document for a failure no command handled: still one canonical line, never a stack trace. */
   public static String internalFailure(String code, String message) {
      StringBuilder sb = new StringBuilder();
      sb.append('{');
      JsonFields.field(sb, "protocolVersion", VersionResultDocument.PROTOCOL);
      JsonFields.comma(sb);
      JsonFields.field(sb, "command", "kcg");
      JsonFields.comma(sb);
      JsonFields.field(sb, "outcome", "FAILURE");
      JsonFields.comma(sb);
      JsonFields.field(sb, "stage", "INTERNAL");
      JsonFields.comma(sb);
      sb.append("\"diagnostics\":[");
      sb.append('{');
      JsonFields.field(sb, "code", code);
      JsonFields.comma(sb);
      JsonFields.field(sb, "stage", "INTERNAL");
      JsonFields.comma(sb);
      JsonFields.field(sb, "severity", "ERROR");
      JsonFields.comma(sb);
      JsonFields.field(sb, "message", message);
      sb.append('}');
      sb.append(']');
      sb.append('}');
      return sb.toString();
   }
}
