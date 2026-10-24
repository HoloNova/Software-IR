package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;

/** Bean Validation's ordinary NotBlank rejects Optional.empty(); the element rule must allow absence. */
final class OptionalTextValidationRenderer {
    private OptionalTextValidationRenderer() {}

    static boolean needed(GenerationContext context) {
        return context.model().declarations().stream()
                .filter(SpringBootDeclaration.InputDeclaration.class::isInstance)
                .map(SpringBootDeclaration.InputDeclaration.class::cast)
                .flatMap(input -> input.fields().stream())
                .anyMatch(field -> field.type() instanceof LoweredJavaType.OptionalValue
                        && field.constraints().stream().anyMatch(c -> c.kind() == SpringBootDeclaration.ConstraintKind.NOT_BLANK));
    }

    static String render() {
        return """
                    /** A non-blank element when present; Optional absence is not a mandatory value. */
                    @java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.TYPE_USE})
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @jakarta.validation.Constraint(validatedBy = PresentNotBlankValidator.class)
                    public @interface NotBlank {
                        String message() default "{jakarta.validation.constraints.NotBlank.message}";
                        Class<?>[] groups() default {};
                        Class<? extends jakarta.validation.Payload>[] payload() default {};
                    }

                    public static final class PresentNotBlankValidator implements jakarta.validation.ConstraintValidator<NotBlank, String> {
                        public boolean isValid(String value, jakarta.validation.ConstraintValidatorContext context) {
                            return value == null || !value.isBlank();
                        }
                    }

                """;
    }
}
