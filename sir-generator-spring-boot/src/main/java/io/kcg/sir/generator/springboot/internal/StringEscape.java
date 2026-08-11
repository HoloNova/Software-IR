package io.kcg.sir.generator.springboot.internal;

final class StringEscape {
   private StringEscape() {
   }

   static String javaString(String value) {
      StringBuilder out = new StringBuilder(value.length() + 2);
      out.append('"');

      for (int i = 0; i < value.length(); i++) {
         char c = value.charAt(i);
         switch (c) {
            case '\t':
               out.append("\\t");
               break;
            case '\n':
               out.append("\\n");
               break;
            case '\r':
               out.append("\\r");
               break;
            case '"':
               out.append("\\\"");
               break;
            case '\\':
               out.append("\\\\");
               break;
            default:
               out.append(c);
         }
      }

      out.append('"');
      return out.toString();
   }

   static String xml(String value) {
      StringBuilder out = new StringBuilder(value.length());

      for (int i = 0; i < value.length(); i++) {
         char c = value.charAt(i);
         switch (c) {
            case '"':
               out.append("&quot;");
               break;
            case '&':
               out.append("&amp;");
               break;
            case '\'':
               out.append("&apos;");
               break;
            case '<':
               out.append("&lt;");
               break;
            case '>':
               out.append("&gt;");
               break;
            default:
               out.append(c);
         }
      }

      return out.toString();
   }
}
