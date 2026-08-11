package io.kcg.sir.application.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class SourceReader {
   private SourceReader() {
   }

   public static String readStrictUtf8(Path sourceFile) throws SourceReader.InvalidUtf8Exception, IOException {
      Objects.requireNonNull(sourceFile, "sourceFile");
      byte[] bytes = Files.readAllBytes(sourceFile);
      return decodeStrictUtf8(bytes);
   }

   public static String decodeStrictUtf8(byte[] bytes) throws SourceReader.InvalidUtf8Exception {
      Objects.requireNonNull(bytes, "bytes");
      CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);

      try {
         return decoder.decode(ByteBuffer.wrap(bytes)).toString();
      } catch (CharacterCodingException ex) {
         throw new SourceReader.InvalidUtf8Exception(ex);
      }
   }

   public static final class InvalidUtf8Exception extends Exception {
      public InvalidUtf8Exception(Throwable cause) {
         super(cause);
      }
   }
}
