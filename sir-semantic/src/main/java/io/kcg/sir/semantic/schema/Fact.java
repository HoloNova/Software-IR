package io.kcg.sir.semantic.schema;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A software fact: something the author wrote that changes what the software is (names, types, constraints, steps, error status). It takes part in semantic comparison and is an editable slot.
 *
 * <p>Every component of a model record carries exactly one of {@link Fact}, {@link Identity},
 * {@link Reference}, {@link Source} and {@link Derived}. {@link SoftwareSchema} reads them and
 * refuses to build when one is missing, so a new field cannot silently stay out of the comparison.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Fact {
}
