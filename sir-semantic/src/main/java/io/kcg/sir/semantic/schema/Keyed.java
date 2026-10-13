package io.kcg.sir.semantic.schema;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a collection whose order carries no meaning: its elements are matched by their own
 * {@link Identity}. Collections without this marker are sequences, so reordering them is a change.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Keyed {
}
