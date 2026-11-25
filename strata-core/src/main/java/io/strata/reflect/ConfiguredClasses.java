package io.strata.reflect;

import io.strata.Codec;
import io.strata.EntityType;
import io.strata.StrataException;
import io.strata.StrataSettings;
import io.strata.spi.EntitySource;
import io.strata.spi.EntityTypeProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * The classes named in {@code strata.entity.classes}: each an {@code @Entity} class (built by
 * reflection) or an {@link EntityTypeProvider}.
 */
public final class ConfiguredClasses implements EntitySource {
    @Override
    public List<EntityType<?>> entityTypes(StrataSettings settings, Codec codec) {
        List<EntityType<?>> out = new ArrayList<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (String name : settings.config().getStringList("strata.entity.classes")) {
            Class<?> cls;
            try {
                cls = Class.forName(name.trim(), true, loader != null ? loader : ConfiguredClasses.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new StrataException("strata.entity.classes names " + name + ", which isn't on the classpath", e);
            }
            if (EntityTypeProvider.class.isAssignableFrom(cls)) {
                try {
                    out.add(((EntityTypeProvider) cls.getDeclaredConstructor().newInstance()).entityType());
                } catch (ReflectiveOperationException e) {
                    throw new StrataException("Couldn't create " + name, e);
                }
            } else {
                out.add(AnnotatedEntityTypes.of(cls, codec));
            }
        }
        return out;
    }
}
