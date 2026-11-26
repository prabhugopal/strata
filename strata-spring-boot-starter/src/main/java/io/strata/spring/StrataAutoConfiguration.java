package io.strata.spring;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.strata.EntityType;
import io.strata.Strata;
import io.strata.StrataRuntime;
import io.strata.Services;
import io.strata.StrataException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Starts Strata inside a Spring Boot app. Settings come from the same keys as everywhere else:
 * {@code strata.*} in application.yaml (or env/properties) override Strata's reference.conf,
 * so a Spring app needs no separate application.conf. Every {@link EntityType} bean is registered.
 */
@AutoConfiguration
public class StrataAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public StrataRuntime strataRuntime(ConfigurableEnvironment env, ObjectProvider<EntityType<?>> types, ListableBeanFactory beans) {
        StrataRuntime runtime = Strata.start(toConfig(env), new BeanServices(beans));
        types.orderedStream().forEach(runtime::register);
        return runtime;
    }

    /**
     * Entities' {@code @Inject} fields come from Spring beans: by type (honouring @Primary), or by
     * bean name with {@code @Inject(name = "…")}. Strata looks them up once per entity type at
     * start; Spring never manages entities themselves.
     */
    static final class BeanServices implements Services {
        private final ListableBeanFactory beans;

        BeanServices(ListableBeanFactory beans) { this.beans = beans; }

        @Override
        public <T> Optional<T> find(Class<T> type, String name) {
            if (name != null && !name.isEmpty()) {
                return beans.containsBean(name) && beans.isTypeMatch(name, type) ? Optional.of(beans.getBean(name, type)) : Optional.empty();
            }
            T unique = beans.getBeanProvider(type).getIfUnique();
            if (unique == null) {
                String[] names = beans.getBeanNamesForType(type);
                if (names.length > 1) {
                    throw new StrataException("Several beans of type " + type.getName() + " (" + String.join(", ", names)
                            + "): pick one with @Inject(name = \"…\") or mark one @Primary");
                }
            }
            return Optional.ofNullable(unique);
        }
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
