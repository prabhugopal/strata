package io.strata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A collaborator an entity uses, not part of its state: never stored, filled in from
 * {@link io.strata.Services} whenever the entity is created, loaded or copied.
 * {@code jakarta.inject.Inject} works the same (without a name).
 *
 * <pre>{@code
 * @Inject Payments payments;
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Inject {
    /** Pick a named service (a bean name in Spring); empty means by type. */
    String name() default "";
}
