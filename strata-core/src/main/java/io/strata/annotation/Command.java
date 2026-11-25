package io.strata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A method that may change the entity's state. The change is committed before the caller gets
 * the result; if the method throws, nothing changes. Parameters become the command's argument:
 * one parameter is sent as-is, several (or a generic type) as a JSON object by parameter name.
 * Set at most one HTTP method; its path is relative to the entity's {@link Entity#mount}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Command {
    /** Command name; defaults to the method name. */
    String name() default "";
    String post() default Http.NONE;
    String put() default Http.NONE;
    String patch() default Http.NONE;
    String delete() default Http.NONE;
}
