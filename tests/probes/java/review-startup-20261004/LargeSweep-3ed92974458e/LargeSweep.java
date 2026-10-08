package org.cgse.core;

import com.google.gson.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Local replay only. Immutable captured recipes and stock; never touches a world. */
public final class LargeSweep {
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    static Map<String, Long> amounts(JsonObject input) {
        Map<String, Long> result = new LinkedHashMap<>();
        input.entrySet().forEach(e -> result.put(e.getKey(), e.getValue().getAsLong()));
        return Collections.unmodifiableMap(result);
    }
    record Catalog(List<GraphRecipe<String>> recipes, Map<String, List<GraphRecipe<String>>> producers,
                   Map<String, Long> stock, Set<String> external, int parallel, int extra) {
        GraphCompiler<String> compiler() { return new GraphCompiler<>(recipes, producers); }
    }
    static Catalog catalog(JsonObject input) {
        List<GraphRecipe<String>> recipes = new ArrayList<>();
        Map<String, GraphRecipe<String>> ids = new LinkedHashMap<>();
        for (var entry : input.getAsJsonArray("recipes")) {
            var recipe = entry.getAsJsonObject();
            List<GraphRecipe.Slot<String>> slots = new ArrayList<>();
            for (var element : recipe.getAsJsonArray("slots")) {
                var slot = element.getAsJsonObject();
                slots.add(new GraphRecipe.Slot<>(slot.get("key").getAsString(), slot.get("amount").getAsLong(),
                        slot.has("input_slot") ? slot.get("input_slot").getAsInt() : slots.size(),
                        slot.has("configuration") && slot.get("configuration").getAsBoolean(),
                        slot.has("reusable") && slot.get("reusable").getAsBoolean()));
            }
            String id = recipe.get("id").getAsString();
            var value = new GraphRecipe<>(id, recipe.has("binding") ? recipe.get("binding").getAsString() : id,
                    slots, amounts(recipe.getAsJsonObject("outputs")));
            recipes.add(value); ids.put(id, value);
        }
        Map<String, List<GraphRecipe<String>>> producers = new LinkedHashMap<>();
        if (input.has("producers")) {
            input.getAsJsonObject("producers").entrySet().forEach(e -> {
                List<GraphRecipe<String>> list = new ArrayList<>();
                e.getValue().getAsJsonArray().forEach(id -> list.add(Objects.requireNonNull(ids.get(id.getAsString()))));
                producers.put(e.getKey(), List.copyOf(list));
            });
        } else for (var recipe : recipes) for (var key : recipe.executionOutputs().keySet())
            producers.computeIfAbsent(key, k -> new ArrayList<>()).add(recipe);
        Set<String> external = new LinkedHashSet<>();
        if (input.has("external")) input.getAsJsonArray("external").forEach(v -> external.add(v.getAsString()));
        return new Catalog(List.copyOf(recipes), Collections.unmodifiableMap(producers),
                input.has("stock") ? amounts(input.getAsJsonObject("stock")) : Map.of(), Set.copyOf(external),
                input.has("parallelism") ? input.get("parallelism").getAsInt() : 4096,
                input.has("max_extra_copies") ? input.get("max_extra_copies").getAsInt() : 64);
    }
    static JsonObject solve(Catalog catalog, GraphCompiler<String> compiler, JsonObject group, JsonObject request) {
        String target = group.get("target").getAsString();
        long amount = request.get("amount").getAsLong();
        Map<String, Long> stock = group.has("stock") ? amounts(group.getAsJsonObject("stock")) : catalog.stock;
        long limit = group.has("work") ? group.get("work").getAsLong() : 20_000_000;
        long memory = group.has("memory") ? group.get("memory").getAsLong() : 128L << 20;
        long timeout = group.has("timeout") ? group.get("timeout").getAsLong() : 0;
        var budget = new PlanningBudget(timeout, limit, memory, () -> false, System::nanoTime);
        JsonObject row = new JsonObject();
        row.addProperty("group", group.get("id").getAsString());
        row.addProperty("kind", group.get("kind").getAsString());
        row.addProperty("target", target); row.add("request", request);
        row.addProperty("work_limit", limit); row.addProperty("memory_limit", memory); row.addProperty("timeout", timeout);
        if (group.has("known_feasible_max")) row.add("known_feasible_max", group.get("known_feasible_max"));
        long started = System.nanoTime();
        var policy = new CatalystPolicy(group.has("parallelism") ? group.get("parallelism").getAsInt() : catalog.parallel,
                group.has("max_extra_copies") ? group.get("max_extra_copies").getAsInt() : catalog.extra);
        var work = new CatalystPlanningWork<String>(policy, budget,
                p -> new GraphPlanningWork<>(compiler, target, amount, stock, catalog.external, Map.of(), true, true, budget).catalysts(p));
        GraphPlan<String> plan = null;
        try {
            try { while (!work.step()) {} plan = work.result(); }
            catch (PlanningBudget.Exhausted exhausted) { plan = work.limited(exhausted); }
            row.addProperty("result", plan.result().toString());
            row.addProperty("feasible", plan.feasible());
            row.addProperty("recipes", plan.patternTimesExact().size());
            row.addProperty("seed_types", plan.seeds().size());
            row.add("missing", JSON.toJsonTree(plan.missingExact()));
            if (plan.feasible()) {
                PlanVerifier.verifyRuntimeInventory(plan);
                plan.initialExact().forEach((key, needed) -> {
                    if (!catalog.external.contains(key) && needed.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) > 0)
                        throw new AssertionError("Feasible plan exceeds frozen inventory: " + key + " needs " + needed);
                });
                // Exercise exact accounting and the execution metadata built after a successful solve.
                plan.executionDependencies();
                for (var mode : CraftingCostModel.Mode.values())
                    if (CraftingCostModel.bytes(plan, mode, k -> k.contains("ae2:f") ? 8000 : 8).signum() < 0)
                        throw new AssertionError("Negative storage charge");
                row.addProperty("verified", true);
            }
            if (group.has("dump") && group.get("dump").getAsBoolean()) {
                row.add("initial", JSON.toJsonTree(plan.initialExact()));
                row.add("seeds", JSON.toJsonTree(plan.seeds()));
                row.add("counts", JSON.toJsonTree(plan.patternTimesExact()));
            }
        } catch (Throwable error) {
            row.addProperty("result", "ERROR"); row.addProperty("feasible", false);
            StringWriter trace = new StringWriter(); error.printStackTrace(new PrintWriter(trace));
            row.addProperty("error", trace.toString());
        } finally { work.close(); }
        row.addProperty("reserved_after_close", budget.reservedBytes());
        row.addProperty("ms", (System.nanoTime() - started) / 1e6);
        row.addProperty("work", budget.nodes()); row.addProperty("peak_bytes", budget.peakBytes());
        row.addProperty("diagnostics", budget.diagnostics());
        if (group.has("fallback") && group.get("fallback").getAsBoolean() && plan != null && !plan.feasible()
                && plan.missingExact().isEmpty() && Set.of("UNKNOWN", "INFEASIBLE", "TIMEOUT", "SEARCH_LIMIT", "MEMORY_LIMIT", "GRAPH_LIMIT").contains(plan.result().name())) {
            JsonObject fallback = new JsonObject();
            var quick = new PlanningBudget(250, 131_072, 16L << 20, () -> false, System::nanoTime);
            long start = System.nanoTime();
            try {
                var alternative = GraphFallback.plan(compiler, target, amount, stock, catalog.external, Map.of(), true, true, quick);
                fallback.addProperty("result", alternative.result().name());
                fallback.addProperty("feasible", alternative.feasible());
                fallback.add("missing", JSON.toJsonTree(alternative.missingExact()));
                if (alternative.feasible()) {
                    PlanVerifier.verifyRuntimeInventory(alternative);
                    alternative.initialExact().forEach((key, needed) -> {
                        if (!catalog.external.contains(key) && needed.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) > 0)
                            throw new AssertionError("Fallback exceeds stock: " + key);
                    });
                    fallback.addProperty("verified", true);
                }
            } catch (PlanningBudget.Exhausted e) {
                fallback.addProperty("result", e.limit().name()); fallback.addProperty("feasible", false);
            } catch (Throwable error) {
                fallback.addProperty("result", "ERROR"); fallback.addProperty("error", error.toString());
            }
            fallback.addProperty("work", quick.nodes()); fallback.addProperty("ms", (System.nanoTime()-start)/1e6);
            row.add("fallback", fallback);
        }
        return row;
    }
    public static void main(String[] args) throws Exception {
        Catalog global = catalog(JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject());
        JsonArray groups = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
        int workers = Integer.parseInt(args[3]);
        var pool = Executors.newFixedThreadPool(workers);
        AtomicInteger done = new AtomicInteger(), rows = new AtomicInteger(), errors = new AtomicInteger();
        long started = System.nanoTime();
        try (var writer = Files.newBufferedWriter(Path.of(args[2]))) {
            List<Future<?>> futures = new ArrayList<>();
            for (var element : groups) {
                JsonObject group = element.getAsJsonObject();
                futures.add(pool.submit(() -> {
                    Catalog catalog = group.has("catalog") ? catalog(group.getAsJsonObject("catalog")) : global;
                    GraphCompiler<String> warm = catalog.compiler();
                    for (var e : group.getAsJsonArray("requests")) {
                        JsonObject request = e.getAsJsonObject();
                        var compiler = request.has("cold") && request.get("cold").getAsBoolean() ? catalog.compiler() : warm;
                        JsonObject row = solve(catalog, compiler, group, request);
                        if (row.get("result").getAsString().equals("ERROR")) errors.incrementAndGet();
                        synchronized (writer) {
                            try { writer.write(JSON.toJson(row)); writer.newLine(); writer.flush(); }
                            catch (IOException ex) { throw new UncheckedIOException(ex); }
                        }
                        rows.incrementAndGet();
                    }
                    int completed = done.incrementAndGet();
                    if (completed % 250 == 0 || completed == groups.size())
                        System.out.printf("completed=%d/%d requests=%d errors=%d elapsed_s=%.1f%n", completed, groups.size(), rows.get(), errors.get(), (System.nanoTime()-started)/1e9);
                }));
            }
            for (var future : futures) future.get();
        } finally { pool.shutdownNow(); }
        if (errors.get() > 0) throw new AssertionError("Replay/verification exceptions: " + errors.get());
    }
}
