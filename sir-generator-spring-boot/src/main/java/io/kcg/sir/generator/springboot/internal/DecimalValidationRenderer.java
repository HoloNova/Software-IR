package io.kcg.sir.generator.springboot.internal;

/** Exact-decimal support rendered only when the lowering requests the precision constraint. */
final class DecimalValidationRenderer {
    private DecimalValidationRenderer() {}

    static String render(String fieldError) {
        return """
                    @java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.TYPE_USE})
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @jakarta.validation.Constraint(validatedBy = DecimalValidator.class)
                    public @interface ExactDecimal {
                        String message() default "must be exactly representable at the declared precision and scale";
                        Class<?>[] groups() default {};
                        Class<? extends jakarta.validation.Payload>[] payload() default {};
                        int precision();
                        int scale();
                    }

                    public static final class DecimalValidator implements jakarta.validation.ConstraintValidator<ExactDecimal, BigDecimal> {
                        private int precision;
                        private int scale;
                        public void initialize(ExactDecimal rule) { precision = rule.precision(); scale = rule.scale(); }
                        public boolean isValid(BigDecimal value, jakarta.validation.ConstraintValidatorContext context) {
                            return acceptsDecimal(value, precision, scale);
                        }
                    }

                    public static boolean acceptsDecimal(BigDecimal value, int precision, int scale) {
                        if (value == null || value.signum() == 0) return true;
                        long integerDigits = Math.max(0L, (long) value.precision() - value.scale());
                        if (integerDigits > (long) precision - scale) return false;
                        return value.scale() <= scale || value.stripTrailingZeros().scale() <= scale;
                    }

                    public static void precision(List<%s> violations, String path, BigDecimal value, int precision, int scale) {
                        if (!acceptsDecimal(value, precision, scale))
                            reject(violations, path, "precision", "must be exactly representable at precision " + precision + " and scale " + scale);
                    }

                    public static void notNull(List<%s> violations, String path, Object value) {
                        if (value == null) reject(violations, path, "NotNull", "must not be null");
                    }

                    public static void decimalScale(List<%s> violations, String path, BigDecimal value, int scale) {
                        if (value != null && value.signum() != 0 && value.scale() > scale && value.stripTrailingZeros().scale() > scale)
                            reject(violations, path, "precision", "amount must be exactly representable at scale " + scale);
                    }

                    /** Check the same Spring-bound MyBatis session the following mapper write uses. */
                    public static void requireStrictDecimalSession(Class<?> entity) {
                        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                            throw new IllegalStateException("atomic decimal writes require an active transaction");
                        var factory = com.baomidou.mybatisplus.extension.toolkit.SqlHelper.sqlSessionFactory(entity);
                        var session = org.mybatis.spring.SqlSessionUtils.getSqlSession(factory);
                        try {
                            if (!org.mybatis.spring.SqlSessionUtils.isSqlSessionTransactional(session, factory))
                                throw new IllegalStateException("decimal write session is not bound to the transaction");
                            try (var statement = session.getConnection().createStatement();
                                 var modes = statement.executeQuery("SELECT @@SESSION.sql_mode")) {
                                if (!modes.next()) throw new IllegalStateException("missing decimal session mode");
                                String mode = modes.getString(1);
                                boolean strict = mode != null && java.util.Arrays.stream(mode.split(","))
                                    .map(String::trim).anyMatch(value -> value.equalsIgnoreCase("STRICT_TRANS_TABLES") || value.equalsIgnoreCase("STRICT_ALL_TABLES"));
                                if (!strict) throw new IllegalStateException("atomic decimal writes require strict SQL mode");
                            }
                        } catch (java.sql.SQLException failure) {
                            throw new IllegalStateException("cannot verify decimal write session", failure);
                        } finally {
                            org.mybatis.spring.SqlSessionUtils.closeSqlSession(session, factory);
                        }
                    }

                """.formatted(fieldError, fieldError, fieldError);
    }
}
