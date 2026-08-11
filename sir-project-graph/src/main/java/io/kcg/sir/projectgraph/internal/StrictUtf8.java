package io.kcg.sir.projectgraph.internal;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class StrictUtf8 {
   private StrictUtf8() {
   }

   static String decodeStrict(byte[] bytes, int offset, int length) {
      CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
      ByteBuffer in = ByteBuffer.wrap(bytes, offset, length);
      CharBuffer out = CharBuffer.allocate(length);
      CoderResult result = decoder.decode(in, out, true);
      if (result.isError()) {
         return null;
      }

      CoderResult flush = decoder.flush(out);
      if (flush.isError()) {
         return null;
      }

      out.flip();
      return out.toString();
   }

   static byte[] encodeStrict(String value) {
      int n = value.length();
      int i = 0;

      while (i < n) {
         char c = value.charAt(i);
         if (Character.isHighSurrogate(c)) {
            if (i + 1 >= n || !Character.isLowSurrogate(value.charAt(i + 1))) {
               return null;
            }

            i += 2;
         } else {
            if (Character.isLowSurrogate(c)) {
               return null;
            }

            i++;
         }
      }

      return value.getBytes(StandardCharsets.UTF_8);
   }

   static boolean startsWithBom(String value) {
      return !value.isEmpty() && value.charAt(0) == '\ufeff';
   }
}
