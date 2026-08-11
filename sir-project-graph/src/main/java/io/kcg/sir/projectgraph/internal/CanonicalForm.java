package io.kcg.sir.projectgraph.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

final class CanonicalForm {
   private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

   CanonicalForm field(String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      this.writeAscii(Integer.toString(bytes.length));
      this.buffer.write(58);
      this.buffer.write(bytes, 0, bytes.length);
      this.buffer.write(10);
      return this;
   }

   CanonicalForm field(CanonicalForm.OptionalValue value) {
      return value != null && value.isPresent() ? this.field(value.value()) : this.field("");
   }

   CanonicalForm raw(String asciiTag) {
      this.writeAscii(asciiTag);
      this.buffer.write(10);
      return this;
   }

   private void writeAscii(String ascii) {
      byte[] bytes = ascii.getBytes(StandardCharsets.US_ASCII);
      this.buffer.write(bytes, 0, bytes.length);
   }

   byte[] toByteArray() {
      return this.buffer.toByteArray();
   }

   static CanonicalForm.OptionalValue optional(final Optional<String> opt) {
      return opt == null ? empty() : new CanonicalForm.OptionalValue() {
         @Override
         public boolean isPresent() {
            return opt.isPresent();
         }

         @Override
         public String value() {
            return opt.orElse("");
         }
      };
   }

   static CanonicalForm.OptionalValue empty() {
      return new CanonicalForm.OptionalValue() {
         @Override
         public boolean isPresent() {
            return false;
         }

         @Override
         public String value() {
            return "";
         }
      };
   }

   interface OptionalValue {
      boolean isPresent();

      String value();
   }
}
