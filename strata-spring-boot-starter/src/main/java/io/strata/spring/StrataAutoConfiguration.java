package io.strata.spring;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.strata.EntityType;
import io.strata.Strata;
import io.strata.StrataRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Starts Strata inside a Spring Boot app. Settings come from the same keys as everywhere else:
 * {@code strata.*} in application.yaml (or env/properties) override Strata's reference.conf,
 * so a Spring app needs no separate application.conf. Every {@link EntityType} bean is registered.
 */
@AutoConfiguration
public class StrataAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public StrataRuntime strataRuntime(ConfigurableEnvironment env, ObjectProvider<EntityType<?>> types) {
        StrataRuntime runtime = Strata.start(toConfig(env));
        types.orderedStream().forEach(runtime::register);
        return runtime;
    }

    /** Spring's strata.* properties layered over Strata's HOCON defaults (Spring wins). */
    static Config toConfig(ConfigurableEnvironment env) {
        Map<String, Object> props = new HashMap<>();
        for (var source : env.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> e) {
                for (String name : e.getPropertyNames()) {
                    if (name.startsWith("strata.") && !props.containsKey(name)) {
                        props.put(name, env.getProperty(name)); // resolved value, honours precedence
                    }
                }
            }
        }
        return ConfigFactory.parseMap(props, "Spring environment (strata.*)")
                .withFallback(ConfigFactory.load())
                .resolve();
    }
}
