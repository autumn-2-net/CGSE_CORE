// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Old successful stock keys are a hint, never evidence of present inventory or closure. */
public final class CapturedStockReuseTest {
    private static final AtomicInteger checks = new AtomicInteger();
    private record Rule() implements StateFamily<String> {
        public String id() { return "durability"; }
        public boolean accepts(String k) { return k.startsWith("tool:") && Integer.parseInt(k.substring(5)) > 0; }
        public long capacity(String k) { return accepts(k) ? Integer.parseInt(k.substring(5)) : 0; }
        public String nominalAfterOne(String k) { long n = capacity(k); return n <= 1 ? null : "tool:" + (n - 1); }
    }
    private record Run(GraphPlan<String> plan, boolean projected, boolean fullStock, PlanningBudget budget) {}

    public static void main(String[] args) throws Exception {
        changedStock(); quantitiesAndScopes(); simultaneous(); interruption(); concurrent();
        System.out.println("CapturedStockReuseTest: assertions=" + checks.get());
    }

    private static void changedStock() {
        var sources = sources(1); var catalog = new CapturedCatalog<>(sources);
        warm(catalog, stock(10, 1, 7), Map.of());
        var hot = solve(catalog, stock(10, 1, 7), Set.of(), 7, Map.of(), false, false, 10_000);
        check(hot.projected && !hot.fullStock && hot.plan.feasible(), "Warm request did not use fresh projected stock");
        var changed = stock(20, 1, 7);
        var retry = solve(catalog, changed, Set.of(), 7, Map.of(), false, false, 200_000);
        check(retry.projected && retry.fullStock && retry.plan.feasible(), "New worn key was pruned instead of retried with full stock");
        var retained = solve(catalog, changed, Set.of(), 7, Map.of(), false, false, 10_000);
        check(retained.projected && !retained.fullStock && retained.plan.feasible(), "Verified new worn state was not retained as a hint");
        for (var absent : List.of(stock(0, 0, 7), stock(10, 1, 6), stock(2, 1, 7))) {
            var run = solve(catalog, absent, Set.of(), 7, Map.of(), false, false, 200_000);
            check(run.projected && run.fullStock && !run.plan.feasible() && run.plan.result() != GraphPlan.Result.INFEASIBLE,
                    "Removed stock/insufficient durability became an old witness or a global proof");
        }
        var made = stock(0, 0, 7); made.put("metal", 1L);
        var run = solve(catalog, made, Set.of(), 7, Map.of(), false, false, 200_000);
        check(run.projected && run.plan.feasible() && run.plan.recipes().values().stream().anyMatch(r -> r.binding().equals("forge")),
                "Declared manufacturing inputs were omitted from projected inventory");
        // A different immutable capture may reuse recipe IDs, never the old hint.
        var different = new ArrayList<>(sources); different.set(0, use(1, "other-raw"));
        var next = new CapturedCatalog<>(different);
        var wrong = solve(next, stock(10, 1, 7), Set.of(), 7, Map.of(), false, false, 200_000);
        check(!wrong.plan.feasible() && !wrong.projected && next.stateSupportMetrics().hits() == 0, "Stock hint crossed source semantics");
    }

    private static void quantitiesAndScopes() {
        for (boolean reverse : List.of(false, true)) {
            var sources = sources(1); if (reverse) Collections.reverse(sources);
            var catalog = new CapturedCatalog<>(sources); var stock = stock(20, 1, 8);
            stock.put("product", 3L); stock.put("gold", 2L);
            warm(catalog, stock, Map.of("token", 1L));
            for (boolean preserve : List.of(false, true)) for (boolean force : List.of(false, true)) for (long seed : new long[]{1, 2}) {
                var run = solve(catalog, stock, Set.of(), 8, Map.of("token", seed), preserve, force, 60_000);
                check(run.projected && run.plan.feasible(), "Changed amount/seed/preservation/force lost projected witness: " + run.budget.diagnostics());
                check(run.budget.cachePreparationWork() == 0, "Projected success rebuilt a future cache");
            }
            var emitted = stock(20, 1, 0);
            var run = solve(catalog, emitted, Set.of("raw"), 7, Map.of(), false, false, 200_000);
            check(run.plan.feasible(), "External ordinary input was lost");
            run = solve(catalog, emitted, Set.of("raw"), 7, Map.of(), false, false, 60_000);
            check(run.projected && run.plan.feasible(), "External input was pruned by stock hints");
            run = solve(catalog, emitted, Set.of(), 7, Map.of(), false, false, 200_000);
            check(!run.plan.feasible() && run.fullStock, "Old external availability survived the next request");
        }
    }

    private static void simultaneous() {
        var catalog = new CapturedCatalog<>(sources(2)); warm(catalog, stock(10, 2, 7), Map.of());
        var mixed = stock(10, 1, 7); mixed.put("tool:12", 1L);
        var run = solve(catalog, mixed, Set.of(), 7, Map.of(), false, false, 200_000);
        check(run.projected && run.fullStock && run.plan.feasible(), "Unknown second entity was lost after a projected ownership failure");
        check(run.plan.recipes().values().stream().filter(r -> r.binding().equals("use"))
                .allMatch(r -> r.slots().stream().filter(s -> s.inputSlot() == 0).mapToLong(GraphRecipe.Slot::amount).sum() == 2),
                "Changed simultaneous source demand");
        run = solve(catalog, mixed, Set.of(), 7, Map.of(), false, false, 60_000);
        check(run.projected && !run.fullStock && run.plan.feasible(), "Verified mixed physical entities did not become reusable keys");
        run = solve(catalog, stock(10, 1, 7), Set.of(), 7, Map.of(), false, false, 200_000);
        check(!run.plan.feasible() && run.fullStock, "Old two-tool quantity allowed one entity in two simultaneous slots");
    }

    private static void interruption() {
        var catalog = new CapturedCatalog<>(sources(1)); warm(catalog, stock(10, 1, 7), Map.of());
        for (int stop : new int[]{0, 3, 6, 10, 20, 60, 120}) {
            var b = budget(40_000_000);
            try (var work = catalog.begin("product", 7, stock(10, 1, 7), false, false, b)) {
                for (int i = 0; i < stop; i++) check(!work.step(), "Unexpected early completion in cancellation probe");
                b.cancel();
                try { work.step(); throw new AssertionError("Cancelled projected request resumed"); } catch (CancellationException expected) {}
            }
            check(b.reservedBytes() == 0, "Cancelled stock/state continuation leaked");
        }
        for (long cap : new long[]{512, 1024, 2048, 4096, 10_000}) {
            var run = solve(catalog, stock(30, 1, 7), Set.of(), 7, Map.of(), false, false, cap);
            check(run.plan.result() != GraphPlan.Result.INFEASIBLE && run.budget.searchWork() <= cap + 1,
                    "Stock retry renewed the search budget or exported a private miss");
        }
        var b = budget(60_000); check(b.tryCacheCharge(PlanningBudget.CACHE_PREPARATION_WORK), "Cannot fill retention account");
        var run = solve(catalog, stock(10, 1, 7), Set.of(), 7, Map.of(), false, false, b);
        check(run.plan.feasible() && run.projected, "Optional retention limit blocked existing stock hints");
        run = solve(catalog, stock(31, 1, 7), Set.of(), 7, Map.of(), false, false, budget(60_000));
        check(run.plan.feasible(), "Interrupted request corrupted later physical discovery");
    }

    private static void concurrent() throws Exception {
        var catalog = new CapturedCatalog<>(sources(1)); warm(catalog, stock(10, 1, 7), Map.of());
        var pool = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) {
                int w = worker;
                jobs.add(() -> {
                    for (int i = 0; i < 8; i++) {
                        long n = 1 + i; var run = solve(catalog, stock(10 + w, 1, n), Set.of(), n, Map.of(), false, false, 200_000);
                        check(run.plan.feasible(), "Concurrent stock requests mixed state or amounts");
                    }
                    return null;
                });
            }
            for (var result : pool.invokeAll(jobs)) result.get();
        } finally { pool.shutdownNow(); check(pool.awaitTermination(10, TimeUnit.SECONDS), "Stock workers did not close"); }
        var m = catalog.stateSupportMetrics(); check(m.entries() == 1 && m.estimatedBytes() <= m.maximumBytes(), "Concurrent hints escaped scope/cap");
    }

    private static void warm(CapturedCatalog<String> catalog, Map<String, Long> stock, Map<String, Long> seeds) {
        for (int i = 0; i < 2; i++) check(solve(catalog, stock, Set.of(), 7, seeds, false, false, 40_000_000).plan.feasible(), "Cannot establish verified baseline");
    }
    private static Run solve(CapturedCatalog<String> catalog, Map<String, Long> stock, Set<String> external, long n,
                             Map<String, Long> seeds, boolean preserve, boolean force, long cap) {
        return solve(catalog, stock, external, n, seeds, preserve, force, budget(cap));
    }
    private static Run solve(CapturedCatalog<String> catalog, Map<String, Long> stock, Set<String> external, long n,
                             Map<String, Long> seeds, boolean preserve, boolean force, PlanningBudget b) {
        GraphPlan<String> plan; boolean projected = false, full = false;
        try (var work = catalog.begin("product", n, stock, external, seeds, preserve, force, b)) {
            try {
                boolean done;
                do {
                    done = work.step(); String trace = b.diagnostics();
                    projected |= trace.contains("candidate_state_stock") && trace.contains("projected="); full |= trace.contains("retry_full_stock");
                } while (!done);
                plan = work.result().plan();
            } catch (PlanningBudget.Exhausted limit) { plan = work.limited(limit).plan(); }
            check(!work.result().coverage().complete() && !b.failureDetail().startsWith("PROVEN_INFEASIBLE"), "State hint exported complete coverage/proof");
        }
        check(b.reservedBytes() == 0, "Stock reuse leaked workspace"); PlanningCostTraceTest.conserved(b);
        if (plan.feasible()) verify(plan, stock, external, n, seeds, force);
        return new Run(plan, projected, full, b);
    }

    private static void verify(GraphPlan<String> plan, Map<String, Long> stock, Set<String> external, long n, Map<String, Long> seeds, boolean force) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        plan.initialExact().forEach((k, v) -> check(external.contains(k) || v.compareTo(BigInteger.valueOf(stock.getOrDefault(k, 0L))) <= 0, "Invented initial stock"));
        var held = new HashMap<>(stock); var cursor = new PlanCursor(plan.steps()); long made = 0, firings = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            check(batch.runs() <= 64 && (firings += batch.runs()) < 256, "Unexpected primitive replay size");
            for (long i = 0; i < batch.runs(); i++) {
                var returns = new HashMap<String, Long>(); long ordinary = 0, tools = 0;
                for (var slot : recipe.slots()) {
                    check(!slot.configuration() && slot.amount() > 0, "Changed physical input mode");
                    long have = held.getOrDefault(slot.key(), 0L);
                    check(have >= slot.amount() || external.contains(slot.key()), "Reused a tool before all slots owned their inputs");
                    if (!external.contains(slot.key())) held.put(slot.key(), have - slot.amount());
                    if (slot.key().startsWith("tool:")) {
                        check(recipe.binding().equals("use") && slot.inputSlot() == 0, "Changed source tool slot"); tools += slot.amount();
                        long uses = Long.parseLong(slot.key().substring(5)); check(uses > 0, "Used a broken tool");
                        if (uses > 1) returns.merge("tool:" + (uses - 1), slot.amount(), Math::addExact);
                    } else {
                        String key = switch (recipe.binding()) { case "use" -> "raw"; case "forge" -> "metal"; case "seed" -> "gold"; default -> ""; };
                        check(slot.key().equals(key) && slot.inputSlot() == (recipe.binding().equals("use") ? 1 : 0), "Changed source ordinary input");
                        ordinary += slot.amount();
                    }
                }
                check(ordinary == 1 && (recipe.binding().equals("use") ? tools >= 1 && tools <= 2 : tools == 0), "Changed source quantities");
                switch (recipe.binding()) {
                    case "use" -> { returns.merge("product", 1L, Math::addExact); made++; }
                    case "forge" -> returns.merge("tool:8", 1L, Math::addExact);
                    case "seed" -> returns.merge("token", 1L, Math::addExact);
                    default -> throw new AssertionError("Executed an unrelated source");
                }
                check(returns.equals(recipe.executionOutputs()), "Changed source output/state-return semantics");
                returns.forEach((k, v) -> held.merge(k, v, Math::addExact));
            }
            cursor.dispatched(batch.runs());
        }
        check(held.getOrDefault("product", 0L) >= n && (!force || made >= n), "Target or force-craft promise failed");
        seeds.forEach((k, v) -> check(held.getOrDefault(k, 0L) >= v, "Required seed amount was stale"));
    }

    private static ArrayList<CapturedCatalog.Entry<String>> sources(int tools) {
        var result = new ArrayList<>(List.of(use(tools, "raw"), entry("forge", List.of(ordinary("metal")), "tool:8"), entry("seed", List.of(ordinary("gold")), "token")));
        for (int i = 0; i < 256; i++) result.add(entry("noise:" + i, List.of(ordinary("noise:" + i)), "out:" + i));
        return result;
    }
    private static CapturedCatalog.Entry<String> use(int tools, String raw) {
        var tool = new CapturedRecipe.Input<>(tools, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool:8192", 1), "tool:8191", false, false, new Rule())), CapturedRecipe.InputMode.MIXED);
        return entry("use", List.of(tool, ordinary(raw)), "product");
    }
    private static CapturedRecipe.Input<String> ordinary(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)));
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static Map<String, Long> stock(int uses, long tools, long raw) {
        var stock = new LinkedHashMap<String, Long>(); if (tools > 0) stock.put("tool:" + uses, tools); if (raw > 0) stock.put("raw", raw);
        for (int i = 0; i < 512; i++) stock.put("noise:" + i, 1L); return stock;
    }
    private static PlanningBudget budget(long work) { var b = new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime); b.enableMetrics(); return b; }
    private static void check(boolean value, String message) { checks.incrementAndGet(); if (!value) throw new AssertionError(message); }
}
