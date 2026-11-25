package io.strata.spi;

import io.strata.EntityType;

/**
 * Supplies an entity type for discovery (ServiceLoader). strata-processor generates one per
 * {@code @Entity} class; runtimes register them all at start when {@code strata.entity.discover}
 * is on.
 */
public interface EntityTypeProvider {
    EntityType<?> entityType();
}
