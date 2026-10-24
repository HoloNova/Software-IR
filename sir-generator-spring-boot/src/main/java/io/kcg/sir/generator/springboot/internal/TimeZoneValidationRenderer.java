package io.kcg.sir.generator.springboot.internal;

/** Uses the target JVM's tzdb, never a catalogue frozen on the compiler's machine. */
final class TimeZoneValidationRenderer {
    private TimeZoneValidationRenderer() {}

    static String render(String fieldError) {
        return """
                    @java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.TYPE_USE})
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @jakarta.validation.Constraint(validatedBy = RegionTimeZoneValidator.class)
                    public @interface RegionTimeZone {
                        String message() default "must be a supported region time zone or UTC";
                        Class<?>[] groups() default {};
                        Class<? extends jakarta.validation.Payload>[] payload() default {};
                    }

                    public static final class RegionTimeZoneValidator implements jakarta.validation.ConstraintValidator<RegionTimeZone, String> {
                        public boolean isValid(String value, jakarta.validation.ConstraintValidatorContext context) {
                            return acceptsTimeZone(value);
                        }
                    }

                    public static boolean acceptsTimeZone(String value) {
                        return value == null || value.equals("UTC")
                            || value.contains("/") && java.time.ZoneId.getAvailableZoneIds().contains(value);
                    }

                    public static void timeZone(List<%s> violations, String path, String value) {
                        if (!acceptsTimeZone(value)) reject(violations, path, "timeZone", "must be a supported region time zone or UTC");
                    }

                """.formatted(fieldError);
    }
}
