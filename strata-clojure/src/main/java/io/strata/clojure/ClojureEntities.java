package io.strata.clojure;

import clojure.java.api.Clojure;
import io.strata.Codec;
import io.strata.EntityType;
import io.strata.StrataException;
import io.strata.StrataSettings;
import io.strata.spi.EntitySource;

import java.util.ArrayList;
import java.util.List;

/** Loads the entities named in {@code strata.clojure.entities} ("namespace/var"). */
public final class ClojureEntities implements EntitySource {
    @Override
    public List<EntityType<?>> entityTypes(StrataSettings settings, Codec codec) {
        List<EntityType<?>> out = new ArrayList<>();
        for (String ref : settings.config().getStringList("strata.clojure.entities")) {
            int slash = ref.indexOf('/');
            if (slash <= 0 || slash == ref.length() - 1) {
                throw new StrataException("strata.clojure.entities: \"" + ref + "\" must be namespace/var");
            }
            String ns = ref.substring(0, slash), var = ref.substring(slash + 1);
            Clojure.var("clojure.core", "require").invoke(Clojure.read(ns));
            Object value = ((clojure.lang.IDeref) Clojure.var(ns, var)).deref();
            if (!(value instanceof EntityType<?> t)) {
                throw new StrataException("strata.clojure.entities: " + ref + " isn't an entity (define it with strata.core/defentity)");
            }
            out.add(t);
        }
        return out;
    }
}
