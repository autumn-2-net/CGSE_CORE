// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Same capture, changing requests: only complete physical producer structure is shared. */
public final class CapturedIndexReuseTest {
    private static final AtomicInteger checks = new AtomicInteger();
    private static int positives;
    private record Stable() implements StateFamily<String> {
        public String id() { return "stable"; }
        public boolean accepts(String key) { return key.equals("tool"); }
        public long capacity(String key) { return accepts(key) ? Long.MAX_VALUE : 0; }
        public String nominalAfterOne(String key) { return key; }
    }

    public static void main(String[] args) throws Exception {
        lowBudgets(); scopesAndReturns(); wornStock(); priorityOracle(); refusals(); concurrent();
        System.out.println("CapturedIndexReuseTest: positives=" + positives + "; assertions=" + checks.get());
    }

    private static void lowBudgets() {
        for (boolean state : List.of(false, true)) for (int noise : new int[]{1024, 16384}) for (boolean reverse : List.of(false, true)) {
            var entries = entries(noise, state); if (reverse) Collections.reverse(entries);
            var catalog = new CapturedCatalog<>(entries);
            for (int run = 0; run < 6; run++) {
                long amount = 7 + run; var stock = Map.of("raw", amount, "tool", 1L);
                var budget = budget(run < 2 ? 40_000_000 : 5000, 64L << 20);
                var result = solve(catalog, run % 2 == 0 ? "product" : "part", amount, stock, budget);
                check(result.plan().feasible(), "Repeated request exhausted compilation: " + budget.diagnostics());
                check(result.coverage().complete() != state, "Cache changed state coverage"); positives++;
                if (run == 0) check(catalog.producerIndexMetrics().estimatedBytes() == 0, "One-off capture retained its index");
                if (run == 1) check(budget.cachePreparationWork() == 1, "Index transfer did not use its independent account");
                if (run >= 2) {
                    check(budget.cachePreparationWork() == 0 && budget.compilationWork() < (state ? 3000 : 256), "Hot request rebuilt unrelated sources");
                    check(budget.peakBytes() < (1L << 20), "Borrower recharged or copied the full retained index");
                }
            }
            metrics(catalog, entries); check(catalog.producerIndexMetrics().hits() == 4, "Missing hot lookup");
            // A retained index has an independent memory allowance; a small
            // request can borrow it without allocating a second full catalog.
            var budget = budget(5000, 64L << 10);
            check(solve(catalog, "product", 5, Map.of("raw", 5L, "tool", 1L), budget).plan().feasible(), "Retained structure stole request workspace");
            check(budget.peakBytes() <= (64L << 10), "Request workspace overflowed");
        }
    }

    private static void scopesAndReturns() {
        var entries = entries(128, false);
        var choices = new ArrayList<CapturedRecipe.Candidate<String>>();
        for (int i = 0; i < 300; i++) choices.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("filled:" + i, 1), "can:" + i, false));
        entries.add(entry("drain", List.of(new CapturedRecipe.Input<>(1, choices, CapturedRecipe.InputMode.INDIVISIBLE)), "waste"));
        entries.add(entry("virtual", List.of(new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("kept", 1), null, true, true)))), "virtual-output"));
        entries.add(entry("empty-domain", List.of(new CapturedRecipe.Input<>(1, List.of())), "ghost"));
        entries.add(new CapturedCatalog.Entry<>("zero", "zero", new CapturedRecipe<>(List.of(), List.of(new CapturedRecipe.Amount<>("zero-output", 0)), false, false)));
        var catalog = new CapturedCatalog<>(entries);
        for (int i = 0; i < 2; i++) solve(catalog, "product", 1, Map.of("raw", 1L), budget());
        metrics(catalog, entries);
        check(catalog.hasPotentialProducer("can:299") && !catalog.hasPotentialProducer("kept") &&
                !catalog.hasPotentialProducer("ghost") && !catalog.hasPotentialProducer("zero-output"), "Cached producer scope differs from physical capture");
        var late = solve(catalog, "can:299", 1, Map.of("filled:299", 1L), budget());
        check(late.plan().feasible() && late.plan().recipes().values().stream().anyMatch(r -> r.id().equals("drain#299")), "Omitted a return beyond the first concrete window");
        for (long raw : new long[]{0, 7, 0, 8}) {
            var stock = raw == 0 ? Map.<String, Long>of() : Map.of("raw", raw);
            var hot = solve(catalog, "product", 7, stock, budget());
            var cold = solve(new CapturedCatalog<>(entries), "product", 7, stock, budget());
            check(hot.plan().result() == cold.plan().result() && hot.coverage().equals(cold.coverage()), "Cached failure/stock/coverage escaped request scope");
        }
        for (boolean preserve : List.of(false, true)) for (boolean force : List.of(false, true)) {
            var stock = Map.of("raw", 5L, "product", 3L, "seed", 1L);
            try (var hot = catalog.begin("product", 4, stock, Set.of(), Map.of("seed", 1L), preserve, force, budget());
                 var cold = new CapturedCatalog<>(entries).begin("product", 4, stock, Set.of(), Map.of("seed", 1L), preserve, force, budget())) {
                while (!hot.step()) {} while (!cold.step()) {}
                equivalent(hot.result().plan(), cold.result().plan());
                var proof = hot.certificate(5_000_000, 64L << 20);
                var replay = CapturedProof.verify(proof, 5_000_000, 64L << 20);
                check(replay.coverage() == CountProof.Verdict.VERIFIED && replay.execution() == CountProof.Verdict.VERIFIED && replay.witnessPresent(), "Hot request certificate did not replay: " + replay);
            }
        }
        var changed = new ArrayList<>(entries); changed.set(0, entry("make-part", List.of(in("different-raw")), "part"));
        var replacement = new CapturedCatalog<>(changed);
        check(!solve(replacement, "product", 1, Map.of("raw", 1L), budget()).plan().feasible(), "Replaced capture borrowed an old producer body");
        check(solve(replacement, "product", 1, Map.of("different-raw", 1L), budget()).plan().feasible(), "Replacement failed to build its own structure");
        var incomplete = new CapturedCatalog<>(entries, false);
        for (int i = 0; i < 3; i++) {
            var result = solve(incomplete, "absent", 1, Map.of(), budget());
            check(result.plan().result() == GraphPlan.Result.UNKNOWN && !result.coverage().complete(), "Cached absence became a complete negative proof");
        }
    }

    private record Wear() implements StateFamily<String> {
        public String id() { return "wear-one"; }
        public boolean accepts(String key) { return key.startsWith("worn:"); }
        public long capacity(String key) { return accepts(key) ? Long.parseLong(key.substring(5)) : 0; }
        public String nominalAfterOne(String key) { long left = capacity(key) - 1; return left <= 0 ? null : "worn:" + left; }
    }

    private static void wornStock() {
        var family = new Wear(); var entries = entries(128, false);
        entries.set(0, entry("make-part", List.of(in("raw"), new CapturedRecipe.Input<>(1,
                List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("worn:4", 1), "worn:3", false, false, family)))), "part"));
        entries.add(entry("make-tool", List.of(in("metal")), "worn:3"));
        var catalog = new CapturedCatalog<>(entries);
        var stocks = List.of(Map.of("raw", 5L, "worn:10", 1L), Map.of("raw", 5L, "worn:1", 1L),
                Map.of("raw", 5L, "worn:3", 1L, "metal", 1L), Map.of("raw", 5L, "worn:1", 5L),
                Map.of("raw", 5L, "worn:1", 1L), Map.of("raw", 5L, "worn:3", 2L));
        for (int i = 0; i < stocks.size(); i++) {
            var hot = solve(catalog, "product", 5, stocks.get(i), budget());
            var cold = solve(new CapturedCatalog<>(entries), "product", 5, stocks.get(i), budget());
            check(!hot.coverage().complete() && hot.coverage().equals(cold.coverage()), "Worn stock changed cached coverage");
            check(hot.plan().result() == cold.plan().result(), "Cached state discovery reused an old inventory result");
            if (i == 1 || i == 4) check(!hot.plan().feasible() && hot.plan().result() != GraphPlan.Result.INFEASIBLE, "Insufficient tool capacity became a witness or a global proof");
            else {
                equivalent(hot.plan(), cold.plan());
                for (var recipe : hot.plan().recipes().values()) if (recipe.binding().equals("make-part")) {
                    var used = recipe.slots().stream().filter(s -> family.accepts(s.key())).toList();
                    check(used.size() == 1 && used.get(0).amount() == 1, "Lost separate physical tool ownership");
                    var outputs = new HashMap<String, Long>(); outputs.put("part", 1L);
                    String after = family.nominalAfterOne(used.get(0).key()); if (after != null) outputs.put(after, 1L);
                    check(recipe.executionOutputs().equals(outputs), "Reused a previous request's physical return");
                }
            }
        }
        metrics(catalog, entries);
    }

    private static void priorityOracle() {
        var random = new Random(0x4016bc92);
        for (int run = 0; run < 120; run++) {
            var entries = new ArrayList<CapturedCatalog.Entry<String>>();
            for (int i = 0; i < 4; i++) entries.add(entry("provider:" + i, List.of(in("raw")), "product"));
            for (int i = 0; i < 40; i++) entries.add(entry("noise:" + i, List.of(in("nothing:" + i)), "noise:" + i));
            Collections.shuffle(entries, random);
            var literal = new ArrayList<GraphRecipe<String>>();
            for (var source : entries) {
                var expansion = source.recipe().expand(budget());
                var value = expansion.next();
                literal.add(new GraphRecipe<>(source.id() + "#0", source.binding(), value.slots(), value.outputs()));
            }
            var stock = Map.of("raw", 7L);
            var oracle = new GraphPlanner<>(new GraphCompiler<>(literal)).plan("product", 7, stock, false, false, budget());
            var catalog = new CapturedCatalog<>(entries);
            for (int mode = 0; mode < 3; mode++) equivalent(solve(catalog, "product", 7, stock, budget()).plan(), oracle);
            check(catalog.producerIndexMetrics().hits() == 1, "Priority case did not exercise reuse");
        }
    }

    private static void refusals() {
        var catalog = new CapturedCatalog<>(entries(1024, false));
        for (int i = 0; i < 2; i++) {
            var b = budget(); check(b.tryCacheCharge(PlanningBudget.CACHE_PREPARATION_WORK), "Cannot fill optional cache account");
            check(solve(catalog, "product", 1, Map.of("raw", 1L), b).plan().feasible(), "Optional retention refusal failed the request");
            check(catalog.producerIndexMetrics().estimatedBytes() == 0, "Exhausted optional account published storage");
        }
        solve(catalog, "product", 1, Map.of("raw", 1L), budget());
        check(catalog.producerIndexMetrics().estimatedBytes() > 0, "Refusal poisoned future publication");
        catalog = new CapturedCatalog<>(entries(2048, false));
        solve(catalog, "product", 1, Map.of("raw", 1L), budget());
        var cancel = new AtomicBoolean(); var b = new PlanningBudget(0, 40_000_000, 64L << 20, cancel::get, System::nanoTime);
        try (var work = catalog.begin("product", 1, Map.of("raw", 1L), false, false, b)) {
            for (int i = 0; i < 30; i++) check(!work.step(), "Partial index unexpectedly completed");
            cancel.set(true);
            try { work.step(); throw new AssertionError("Index ignored cancellation"); } catch (CancellationException expected) {}
        }
        check(b.reservedBytes() == 0 && catalog.producerIndexMetrics().estimatedBytes() == 0, "Cancelled partial index was published");
        var partial = solve(catalog, "product", 1, Map.of("raw", 1L), budget(64, 64L << 20));
        check(partial.plan().result() == GraphPlan.Result.SEARCH_LIMIT && catalog.producerIndexMetrics().estimatedBytes() == 0, "Limited index became a complete cache");
        solve(catalog, "product", 1, Map.of("raw", 1L), budget()); metrics(catalog, entries(2048, false));
        var tooLarge = new CapturedCatalog<>(entries(60_000, false));
        for (int i = 0; i < 2; i++) solve(tooLarge, "absent", 1, Map.of(), budget());
        check(tooLarge.producerIndexMetrics().estimatedBytes() == 0, "Retained index exceeded its independent cap");
    }

    private static void concurrent() throws Exception {
        var entries = entries(4096, false); var catalog = new CapturedCatalog<>(entries);
        var pool = Executors.newFixedThreadPool(4); var barrier = new CyclicBarrier(4);
        try {
            var jobs = new ArrayList<Future<?>>();
            for (int t = 0; t < 4; t++) {
                final int worker = t;
                jobs.add(pool.submit(() -> {
                    var b = budget(); var stock = Map.of("raw", 9L + worker);
                    try (var work = catalog.begin("product", 9 + worker, stock, false, false, b)) {
                        work.step(); barrier.await(20, TimeUnit.SECONDS);
                        while (!work.step()) {} verify(work.result().plan(), stock);
                    }
                    check(b.reservedBytes() == 0, "Losing publisher leaked its duplicate index");
                    for (int i = 0; i < 8; i++) {
                        var result = solve(catalog, (i & 1) == 0 ? "part" : "product", 3 + worker, stock, budget(5000, 64L << 10));
                        check(result.plan().feasible(), "Another borrower cleared shared producers");
                    }
                    return null;
                }));
            }
            for (var job : jobs) job.get(45, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        metrics(catalog, entries);
        check(catalog.producerIndexMetrics().misses() == 4 && catalog.producerIndexMetrics().hits() == 32, "Concurrent publication duplicated or lost cache ownership");
    }

    private static void metrics(CapturedCatalog<String> catalog, List<CapturedCatalog.Entry<String>> entries) {
        var index = new HashMap<String, Set<Integer>>();
        for (int i = 0; i < entries.size(); i++) {
            var recipe = entries.get(i).recipe(); if (recipe.size() == 0) continue;
            var emitted = new HashSet<String>();
            for (var out : recipe.outputs()) if (out.amount() > 0) emitted.add(out.what());
            for (var input : recipe.inputs()) for (var choice : input.candidates()) if (choice.remaining() != null) emitted.add(choice.remaining());
            for (String key : emitted) index.computeIfAbsent(key, ignored -> new HashSet<>()).add(i);
        }
        var m = catalog.producerIndexMetrics();
        check(m.keys() == index.size() && m.references() == index.values().stream().mapToLong(Set::size).sum(), "Retained index lost or duplicated captured producer incidence");
        check(m.estimatedBytes() > 0 && m.estimatedBytes() <= m.maximumBytes(), "Unbounded or unaccounted retained memory");
        for (String key : index.keySet()) check(catalog.hasPotentialProducer(key), "Lost a complete producer key");
    }

    static ArrayList<CapturedCatalog.Entry<String>> entries(int count, boolean state) {
        var inputs = new ArrayList<>(List.of(in("raw")));
        if (state) inputs.add(new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool", 1), "tool", false, false, new Stable()))));
        var result = new ArrayList<>(List.of(entry("make-part", inputs, "part"), entry("finish", List.of(in("part")), "product")));
        for (int i = 0; i < count; i++) result.add(entry("noise:" + i, List.of(in("noise-in:" + i)), "noise-out:" + i));
        return result;
    }
    static CapturedRecipe.Input<String> in(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static CapturedCatalog.Result<String> solve(CapturedCatalog<String> catalog, String target, long n, Map<String, Long> stock, PlanningBudget budget) {
        budget.enableMetrics(); var result = catalog.plan(target, n, stock, false, false, budget);
        if (result.plan().feasible()) verify(result.plan(), stock);
        check(budget.reservedBytes() == 0, "Request ownership leaked"); PlanningCostTraceTest.conserved(budget); return result;
    }
    private static void equivalent(GraphPlan<String> actual, GraphPlan<String> expected) {
        check(actual.feasible() && expected.feasible() && actual.steps().equals(expected.steps()) && actual.initialExact().equals(expected.initialExact()), "Changed canonical source priority / execution word");
        check(actual.recipes().keySet().equals(expected.recipes().keySet()), "Changed selected variant identity");
        actual.recipes().forEach((id, recipe) -> {
            var original = expected.recipes().get(id);
            check(recipe.binding().equals(original.binding()) && recipe.slots().equals(original.slots()) && recipe.executionOutputs().equals(original.executionOutputs()), "Changed source binding or physical returns");
        });
    }
    private static void verify(GraphPlan<String> plan, Map<String, Long> stock) {
        check(plan.feasible(), "Expected executable physical witness"); PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        plan.initialExact().forEach((key, count) -> check(count.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0, "Cache supplied a previous request's inventory"));
    }
    static PlanningBudget budget() { return budget(40_000_000, 64L << 20); }
    static PlanningBudget budget(long work, long memory) { return new PlanningBudget(0, work, memory, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks.incrementAndGet(); }
}
