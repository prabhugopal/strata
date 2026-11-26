package io.strata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Turns stored state of version {@code from} into version {@code from + 1}, for an entity whose
 * {@link Entity#version} went up. A static method taking and returning the state as a
 * {@code Map<String, Object>} (JSON objects as maps, arrays as lists). Each step from 1 to the
 * current version needs exactly one; Strata applies them in order when it loads older state.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Migrate {
    int from();
}
