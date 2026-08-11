package io.kcg.sir.lowering.api;

import java.util.Objects;

public record LoweredIrVersion(String value) {
   public static final LoweredIrVersion V0_1 = new LoweredIrVersion("0.1");
   public static final LoweredIrVersion V0_2 = new LoweredIrVersion("0.2");

   public LoweredIrVersion {
      Objects.requireNonNull(value, "value");
      if (value.isBlank()) {
         throw new IllegalArgumentException("value must not be blank");
      }
   }
}
