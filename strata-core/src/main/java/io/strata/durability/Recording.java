package io.strata.durability;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Wraps an injected service interface so every call made while an invocation runs goes through
 * its {@link Journal}. Outside an invocation (queries, plain code) calls go straight through.
 */
public final class Recording {
    private Recording() {}

    public static <T> T wrap(Class<T> iface, T target) {
        InvocationHandler h = (proxy, m, args) -> {
            if (m.getDeclaringClass() == Object.class) return direct(m, target, args);
            Journal j = Journal.current();
            if (j == null) return direct(m, target, args);
            return j.call(iface.getSimpleName() + "." + m.getName(), m.getGenericReturnType(), () -> direct(m, target, args));
        };
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h));
    }

    private static Object direct(Method m, Object target, Object[] args) throws Exception {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception ex) throw ex;
            if (c instanceof Error err) throw err;
            throw e;
        }
    }
}
