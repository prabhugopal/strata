package io.strata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an entity type. Its fields are the entity's state; methods marked
 * {@link Command} change it and methods marked {@link Query} read it. At compile time
 * strata-processor generates the {@code EntityType} (registered automatically when
 * {@code strata.entity.discover} is on) and a typed client.
 *
 * <pre>{@code
 * @Entity(mount = "/counters/{id}")
 * public class Counter {
 *     long count;
 *     @Command(post = "/increment") public long increment() { return ++count; }
 *     @Query(get = "")              public long count()     { return count; }
 * }
 * }</pre>
 *
 * The class needs a no-argument constructor (the initial state) and non-final fields Jackson can
 * store (fields marked {@code transient} aren't state).
 * If a command throws, the entity keeps its last committed state.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Entity {
    /** Type name; defaults to the class name in kebab-case ({@code ShoppingCart} → {@code shopping-cart}). */
    String name() default "";

    /** {@code ephemeral} or {@code persistent}; empty uses {@code strata.entity.default-durability}. */
    String durability() default "";

    /**
     * HTTP path the entity is served under, with exactly one variable for the entity id,
     * e.g. {@code /carts/{cartId}}. Empty means no HTTP routes.
     */
    String mount() default "";

    /**
     * Give every command and query a route under the mount without spelling it out: commands get
     * {@code POST /<name>}, queries {@code GET /<name>} ({@code POST} when they take arguments),
     * with names in kebab-case. A path set on a method still wins. Off by default, so nothing is
     * served unless asked; the routes are listed by the management API and {@code strata types}.
     */
    boolean expose() default false;
}
