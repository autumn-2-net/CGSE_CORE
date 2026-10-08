package org.cgse.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/** Local offline replay tool. Uses the actual solver, with symbolic resource IDs and exact integers. */
public final class ReplayCgseFailure {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: ReplayCgseFailure failure.zip");
        JsonObject input, report;
        try (var zip = new ZipFile(args[0], StandardCharsets.UTF_8)) {
            if (!json(zip, "export-status.json").getAsJsonObject().get("complete").getAsBoolean())
                throw new IllegalArgumentException("Incomplete export; inspect export-status.json first");
            input = json(zip, "solver-input.json").getAsJsonObject();
            report = json(zip, "report.json").getAsJsonObject();
        }
        if (!input.get("complete").getAsBoolean()) throw new IllegalArgumentException("Snapshot/catalog incomplete: capture.json contains the partial state");
        var compiler = new GraphCompiler<>(recipes(input.getAsJsonArray("recipes")));
        String target = input.get("target").getAsString();
        long amount = input.get("amount").getAsLong();
        var stock = quantities(input.getAsJsonObject("stock"));
        var seeds = quantities(input.getAsJsonObject("required_seeds"));
        Set<String> external = new LinkedHashSet<>();
        for (var e : input.getAsJsonArray("external")) external.add(e.getAsString());
        var limits = input.getAsJsonObject("budget");
        var budget = new PlanningBudget(Long.getLong("replay.timeoutMs", limits.get("timeout_ms").getAsLong()),
                Long.getLong("replay.maxWork", limits.get("max_work").getAsLong()),
                Long.getLong("replay.maxBytes", limits.get("max_bytes").getAsLong()), () -> false, System::nanoTime);
        budget.debug("offline replay");
        if (report.has("context") && report.getAsJsonObject("context").has("before_solve_budget")) {
            var prior = report.getAsJsonObject("context").getAsJsonObject("before_solve_budget");
            budget.reserve(prior.get("reserved_bytes").getAsLong());
            budget.charge(prior.get("work").getAsLong());
        }
        var policyJson = input.getAsJsonObject("catalysts");
        var policy = new CatalystPolicy(policyJson.get("parallelism").getAsInt(), policyJson.get("maxExtraCopies").getAsInt());
        boolean preserve = input.get("preserve_seeds").getAsBoolean(), force = input.get("force_craft").getAsBoolean();
        boolean craftLess = input.get("calculation_strategy").getAsString().equals("CRAFT_LESS");
        // The report retains the host's reductions; this tool starts with the full requested amount.
        var work = new CatalystPlanningWork<String>(craftLess || !force ? CatalystPolicy.MINIMAL : policy, budget,
                p -> new GraphPlanningWork<>(compiler, target, amount, stock, external, seeds, preserve, force, budget).catalysts(p));
        GraphPlan<String> plan;
        try {
            try { while (!work.step()) {} plan = work.result(); }
            catch (PlanningBudget.Exhausted limit) { plan = work.limited(limit); }
            if (plan.feasible()) PlanVerifier.verifyRuntimeInventory(plan);
            System.out.println("REPLAY file=" + Path.of(args[0]).getFileName() + " result=" + plan.result() + " amount=" + plan.amount()
                    + " work=" + budget.nodes() + " peak_bytes=" + budget.peakBytes() + " missing=" + plan.missingExact());
            if (craftLess) System.out.println("CRAFT_LESS host binary probes are not replayed: above is the full-amount solver result.");
            System.out.println("Cold cache, synchronous core replay; prior snapshot/build work and reserved bytes restored when recorded. Wall time restarts at replay.");
        } finally { work.close(); }
    }

    static JsonElement json(ZipFile zip, String name) throws Exception {
        try (var reader = new java.io.InputStreamReader(zip.getInputStream(zip.getEntry(name)), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }

    public static Map<String, Long> quantities(JsonObject object) {
        Map<String, Long> result = new LinkedHashMap<>();
        object.entrySet().forEach(e -> result.put(e.getKey(), e.getValue().getAsLong()));
        return result;
    }

    public static List<GraphRecipe<String>> recipes(JsonArray array) {
        List<GraphRecipe<String>> recipes = new ArrayList<>();
        for (var e : array) {
            var r = e.getAsJsonObject();
            List<GraphRecipe.Slot<String>> slots = new ArrayList<>();
            for (var s : r.getAsJsonArray("slots")) {
                var slot = s.getAsJsonObject();
                slots.add(new GraphRecipe.Slot<>(slot.get("key").getAsString(), slot.get("amount").getAsLong(),
                        slot.get("input_slot").getAsInt(), slot.get("configuration").getAsBoolean(), slot.get("reusable").getAsBoolean()));
            }
            recipes.add(new GraphRecipe<>(r.get("id").getAsString(), r.get("binding").getAsString(), slots, quantities(r.getAsJsonObject("outputs"))));
        }
        return recipes;
    }
}
