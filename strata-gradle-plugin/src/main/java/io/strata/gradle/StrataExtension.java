package io.strata.gradle;

import org.gradle.api.Action;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Nested;
import org.gradle.api.provider.Property;

/**
 * {@code strata { … }}: which Strata modules an app uses. Defaults come from the plugin (its
 * own version, the JVM flags this Strata version needs), so most apps set nothing.
 */
public abstract class StrataExtension {
    /** Strata version; defaults to the plugin's own. */
    public abstract Property<String> getVersion();

    /** Serve entity routes and the management API with Pekko HTTP (strata-http). Default: on, unless {@link #getSpring()}. */
    public abstract Property<Boolean> getHttp();

    /** Use Spring Boot (strata-spring-boot-starter) instead of strata-http. Default: off. */
    public abstract Property<Boolean> getSpring();

    /** Include the JDBC store (PostgreSQL/H2); select it at run time with strata.store.kind. Default: on. */
    public abstract Property<Boolean> getJdbc();

    /** Include defentity for Clojure (strata-clojure). Default: off. */
    public abstract Property<Boolean> getClojure();

    /** Run strata-processor on @Entity classes (javac, and kapt when applied). Default: on. */
    public abstract Property<Boolean> getProcessor();

    /** JVM flags for tests, `run` and start scripts. Default: what this Strata version needs. */
    public abstract ListProperty<String> getJvmArgs();

    /** The container image (strataDockerfile, strataImage). */
    @Nested public abstract ImageSpec getImage();

    public void image(Action<? super ImageSpec> action) { action.execute(getImage()); }

    /** Running on Kubernetes (strataManifests, strataDeploy, strataUndeploy). */
    @Nested public abstract KubernetesSpec getKubernetes();

    public void kubernetes(Action<? super KubernetesSpec> action) { action.execute(getKubernetes()); }
}
