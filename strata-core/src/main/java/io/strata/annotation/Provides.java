package io.strata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A class that provides a service to entities: Strata creates it at start (public no-argument
 * constructor) and offers it for every interface it implements, so entities just
 * {@code @Inject} the interface. Services passed to {@code Strata.start}/{@code run} (or Spring
 * beans) win over discovered ones. Listed at compile time by strata-processor; for classes
 * compiled without it, list them in {@code strata.services.classes}.
 *
 * <pre>{@code
 * @Provides
 * public class StripePayments implements Payments { … }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Provides {
    /** Also offer it under this name ({@code @Inject(name = "…")}). */
    String name() default "";
}
