// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.gtlcore.aedump;

import appeng.api.stacks.*;
import com.google.gson.*;
import java.util.*;

public final class Data {
    public static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    public static JsonObject object(Object... pairs) {
        var o = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) o.add((String) pairs[i], JSON.toJsonTree(pairs[i + 1]));
        return o;
    }
    public static final class Keys {
        private final Map<AEKey, String> ids = new LinkedHashMap<>();
        public final JsonArray resources = new JsonArray();
        public long estimatedBytes;
        public int failed;
        public synchronized String id(AEKey key) {
            if (key == null) return null;
            String known = ids.get(key); if (known != null) return known;
            String id = "k" + ids.size(); ids.put(key, id);
            var o = object("id", id, "class", key.getClass().getName(), "registry_id", key.getId().toString());
            try {
                // Old GTLCore caches a mutable generic tag. Never mutate or identify keys by that tag.
                var clean = key.toTag().copy(); clean.putString("#c", key.getType().getId().toString());
                o.addProperty("snbt", clean.toString());
                String observed = key.toTagGeneric().copy().toString();
                if (!observed.equals(clean.toString())) o.addProperty("observed_generic_snbt", observed);
                o.addProperty("amount_per_unit", Long.toString(key.getAmountPerUnit()));
            } catch (RuntimeException e) { failed++; o.addProperty("error", Trace.stack(e)); }
            estimatedBytes += 256 + o.toString().length() * 2L;
            resources.add(o); return id;
        }
        public JsonObject amounts(Map<AEKey, ? extends Number> values) {
            var o = new JsonObject(); values.forEach((k, v) -> o.addProperty(id(k), v.toString())); return o;
        }
        public JsonObject amounts(KeyCounter values) {
            var o = new JsonObject(); for (var e : values) o.addProperty(id(e.getKey()), Long.toString(e.getLongValue())); return o;
        }
        public JsonArray keys(Collection<AEKey> values) {
            var a = new JsonArray(); values.forEach(k -> a.add(id(k))); return a;
        }
        public JsonObject stack(GenericStack s) { return object("key", id(s.what()), "amount", Long.toString(s.amount())); }
        public JsonArray stacks(GenericStack[] values) {
            var a = new JsonArray(); for (var s : values) if (s != null) a.add(stack(s)); return a;
        }
    }
    private Data() {}
}
