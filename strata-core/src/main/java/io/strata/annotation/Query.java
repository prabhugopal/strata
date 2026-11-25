package io.strata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A method that only reads state: it never writes to the store. GET routes are for queries
 * without parameters; use {@code post} for a query that takes an argument.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Query {
    /** Query name; defaults to the method name. */
    String name() default "";
    String get() default Http.NONE;
    String post() default Http.NONE;
}
