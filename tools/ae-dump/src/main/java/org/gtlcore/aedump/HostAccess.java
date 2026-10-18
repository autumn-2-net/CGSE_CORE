// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.gtlcore.aedump;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;

/** Named host diagnostic access, without coupling release builds to a GTL jar. */
public final class HostAccess {
    private static final String GRAPH = "org.gtlcore.gtlcore.integration.ae2.graph.";
    private static final ClassValue<Map<String, Method>> METHODS = new ClassValue<>() {
        @Override protected Map<String, Method> computeValue(Class<?> type) {
            var result = new HashMap<String, Method>();
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 0 && method.trySetAccessible())
                    result.putIfAbsent(method.getName(), method);
            }
            return Map.copyOf(result);
        }
    };

    private static final class Types {
        static final Class<?> ROUTER = type(GRAPH + "CraftingEngineRouter");
        static final Class<?> PLAN = type(GRAPH + "AeGraphPlan");
        static final Class<?> CONFIG = type("org.gtlcore.gtlcore.config.ConfigHolder");
    }

    private static Class<?> type(String name) {
        try { return Class.forName(name, false, HostAccess.class.getClassLoader()); }
        catch (ClassNotFoundException e) { throw new IllegalStateException("Missing CGSE host diagnostic API: " + name, e); }
    }

    @SuppressWarnings("unchecked")
    public static <T> T call(Object target, String name) {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        Method method = METHODS.get(type).get(name);
        if (method == null) throw new IllegalStateException("Missing host diagnostic method: " + type.getName() + "." + name);
        try { return (T) method.invoke(target instanceof Class<?> ? null : target); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Host diagnostic call failed: " + name, e.getCause());
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Host diagnostic call failed: " + name, e); }
    }

    public static boolean useGraph() { return call(Types.ROUTER, "useGraph"); }
    public static boolean isGraphPlan(Object plan) { return Types.PLAN.isInstance(plan); }
    public static Object configuration() {
        try { return Types.CONFIG.getField("INSTANCE").get(null); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Missing host configuration", e); }
    }
    public static String mode() {
        Object config = configuration();
        Object ae = Reflect.get(config, "ae");
        Object mode = Reflect.get(ae == null ? config : ae, "ae2CalculationMode");
        if (!(mode instanceof Enum<?> value)) throw new IllegalStateException("Missing host calculation mode");
        return value.name();
    }
    public static Map<String, Object> configurationValues() {
        Object config = configuration();
        var values = new LinkedHashMap<String, Object>();
        // Read only the known config categories, never recurse through arbitrary objects.
        readConfiguration(config, values);
        for (String category : new String[]{"ae", "performance", "diagnostics"}) {
            Object group = Reflect.get(config, category);
            if (group != null) readConfiguration(group, values);
        }
        return values;
    }
    private static void readConfiguration(Object config, Map<String, Object> values) {
        try {
            for (var field : config.getClass().getFields()) {
                String name = field.getName();
                if (name.startsWith("ae2") || name.startsWith("enableAe2")) values.put(name, field.get(config));
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot capture host configuration", e); }
    }
    private HostAccess() {}
}
