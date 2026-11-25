package io.strata;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * A full generic type, for results like {@code List<Order>} that a {@code Class} can't describe:
 * {@code new TypeRef<List<Order>>() {}}.
 */
public abstract class TypeRef<T> {
    private final Type type;

    protected TypeRef() {
        Type sup = getClass().getGenericSuperclass();
        if (!(sup instanceof ParameterizedType p)) {
            throw new IllegalStateException("TypeRef needs a type argument: new TypeRef<List<String>>() {}");
        }
        this.type = p.getActualTypeArguments()[0];
    }

    public Type type() { return type; }
}
