package org.gtlcore.aedump;

import java.lang.reflect.Field;
import java.util.*;

/** Only named diagnostic fields; never recursively walks arbitrary world objects. */
public final class Reflect {
    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<>() {
        protected Map<String, Field> computeValue(Class<?> type) {
            var result = new HashMap<String, Field>();
            for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Field f : c.getDeclaredFields()) {
                if (f.trySetAccessible()) result.putIfAbsent(f.getName(), f);
            }
            return result;
        }
    };
    @SuppressWarnings("unchecked") public static <T> T get(Object object, String field) {
        if (object == null) return null;
        try { Field f = FIELDS.get(object.getClass()).get(field); return f == null ? null : (T) f.get(object); }
        catch (ReflectiveOperationException e) { return null; }
    }
    public static Object call(Object object, String method) {
        if (object == null) return null;
        try { return object.getClass().getMethod(method).invoke(object); }
        catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }
    public static Map<String, Object> scalars(Object object, String... names) {
        var result = new LinkedHashMap<String, Object>();
        for (String name : names) {
            Object value = get(object, name);
            if (value instanceof Boolean || value instanceof Integer || value instanceof String) result.put(name, value);
            else if (value instanceof Number || value instanceof Enum<?>) result.put(name, value.toString());
            else if (value instanceof Map<?, ?> m) result.put(name + "_size", m.size());
            else if (value instanceof Collection<?> c) result.put(name + "_size", c.size());
        }
        return result;
    }
    private Reflect() {}
}
