package io.kcg.sir.projectgraph.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class Sha256Helper {
   private Sha256Helper() {
   }

   static String hexDigest(byte[] bytes) {
      try {
         byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
         return toHexLower(hash);
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException("SHA-256 algorithm not available", e);
      }
   }

   static boolean isLowerCaseHex64(String value) {
      if (value != null && value.length() == 64) {
         for (int i = 0; i < 64; i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private static String toHexLower(byte[] bytes) {
      StringBuilder sb = new StringBuilder(bytes.length * 2);

      for (byte b : bytes) {
         int v = b & 255;
         if (v < 16) {
            sb.append('0');
         }

         sb.append(Integer.toHexString(v));
      }

      return sb.toString().toLowerCase(Locale.ROOT);
   }

   static String hexDigestUtf8(String text) {
      return hexDigest(text.getBytes(StandardCharsets.UTF_8));
   }
}
