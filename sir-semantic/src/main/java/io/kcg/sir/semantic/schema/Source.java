package io.kcg.sir.semantic.schema;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Where the node came from (span, syntax node id). It is kept and compared separately and is never a business change.
 *
 * <p>Every component of a model record carries exactly one of {@link Fact}, {@link Identity},
 * {@link Reference}, {@link Source} and {@link Derived}. {@link SoftwareSchema} reads them and
 * refuses to build when one is missing, so a new field cannot silently stay out of the comparison.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Source {
}
