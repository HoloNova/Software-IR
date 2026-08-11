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
               if (Character.isISOControl(c)) {
                  appendUnicodeEscape(out, c);
               } else {
                  out.append(c);
               }
         }
      }

      out.append('"');
      return out.toString();
   }

   private static void appendUnicodeEscape(StringBuilder out, char value) {
      final char[] hex = "0123456789abcdef".toCharArray();
      out.append("\\u");
      out.append(hex[value >>> 12 & 15]);
      out.append(hex[value >>> 8 & 15]);
      out.append(hex[value >>> 4 & 15]);
      out.append(hex[value & 15]);
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
