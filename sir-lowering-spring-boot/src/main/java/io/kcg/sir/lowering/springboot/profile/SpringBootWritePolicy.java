package io.kcg.sir.lowering.springboot.profile;

import java.util.List;
import java.util.Objects;

/**
 * Target decisions owned by the Spring Boot lowering profile for versioned writes.
 *
 * <p>These are the target's concurrency and payload-envelope contract, not SIR surface: the value a
 * new row's version starts at, how much a successful change increments it, and the property names the
 * patch envelope uses. Keeping them here means the generator renders names it was handed instead of
 * inventing them, and a change of envelope shape is one profile edit rather than a template edit.
 */
public record SpringBootWritePolicy(
   long versionInitialValue,
   long versionIncrement,
   String patchIdentityPropertyName,
   String patchChangesPropertyName,
   String patchExpectedVersionPropertyName,
   String invalidRequestCode
) {
   public static final SpringBootWritePolicy V0_1 = new SpringBootWritePolicy(
      0L, 1L, "id", "changes", "expectedVersion", "INVALID_REQUEST"
   );

   public SpringBootWritePolicy {
      if (versionInitialValue < 0) {
         throw new IllegalArgumentException("versionInitialValue must not be negative");
      }

      if (versionIncrement < 1) {
         throw new IllegalArgumentException("versionIncrement must be positive");
      }

      requireText(patchIdentityPropertyName, "patchIdentityPropertyName");
      requireText(patchChangesPropertyName, "patchChangesPropertyName");
      requireText(patchExpectedVersionPropertyName, "patchExpectedVersionPropertyName");
      requireText(invalidRequestCode, "invalidRequestCode");
   }

   /** The field-error codes the target's candidate checks report, one per supported constraint. */
   public List<String> validatedConstraintCodes() {
      return List.of("notBlank", "email", "length", "min", "max");
   }

   private static void requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) {
         throw new IllegalArgumentException(name + " must not be blank");
      }
   }
}
