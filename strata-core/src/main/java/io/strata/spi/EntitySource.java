package io.strata.spi;

import io.strata.Codec;
import io.strata.EntityType;
import io.strata.StrataSettings;

import java.util.List;

/**
 * Finds entity types to register at start, from configuration (ServiceLoader). Core lists the
 * classes in {@code strata.entity.classes}; strata-clojure loads {@code strata.clojure.entities}.
 */
public interface EntitySource {
    List<EntityType<?>> entityTypes(StrataSettings settings, Codec codec);
}
