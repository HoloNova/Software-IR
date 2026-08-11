package io.kcg.cli;

public final class JsonStringEncoder {
   private static final char[] HEX = "0123456789abcdef".toCharArray();

   private JsonStringEncoder() {
   }

   public static String encode(String value) {
      if (value == null) {
         return "null";
      }

      StringBuilder sb = new StringBuilder(value.length() + 2);
      sb.append('"');

      for (int i = 0; i < value.length(); i++) {
         char c = value.charAt(i);
         switch (c) {
            case '\b':
               sb.append("\\b");
               break;
            case '\t':
               sb.append("\\t");
               break;
            case '\n':
               sb.append("\\n");
               break;
            case '\f':
               sb.append("\\f");
               break;
            case '\r':
               sb.append("\\r");
               break;
            case '"':
               sb.append("\\\"");
               break;
            case '\\':
               sb.append("\\\\");
               break;
            default:
               if (c < ' ') {
                  sb.append('\\');
                  sb.append('u');
                  sb.append(HEX[c >>> '\f' & 15]);
                  sb.append(HEX[c >>> '\b' & 15]);
                  sb.append(HEX[c >>> 4 & 15]);
                  sb.append(HEX[c & 15]);
               } else {
                  sb.append(c);
               }
         }
      }

      sb.append('"');
      return sb.toString();
   }
}
