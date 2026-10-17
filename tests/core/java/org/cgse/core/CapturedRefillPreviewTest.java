// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** A dynamic tool must not discard a verified ordinary-material refill plan. */
public final class CapturedRefillPreviewTest {
    private static int checks;

    private record Hammer() implements StateFamily<String> {
        public String id() { return "indestructible-hammer"; }
        public boolean accepts(String key) { return key.equals("hammer"); }
        public long capacity(String key) { return accepts(key) ? Long.MAX_VALUE : 0; }
        public String nominalAfterOne(String key) { return key; }
    }

    public static void main(String[] args) throws Exception {
        for (boolean reverse : List.of(false, true)) {
            var entries = entries();
            if (reverse) java.util.Collections.reverse(entries);
            var catalog = new CapturedCatalog<>(entries);
            for (boolean enabled : List.of(false, true)) {
                var b = budget();
                try (var work = catalog.begin("book", 1, stock(false), false, true, b).conditionalPreviews(enabled)) {
                    while (!work.step()) {}
                    var p = work.result().plan();
                    check(!work.coverage().complete(), "Opaque tool became a complete proof");
                    check(!p.feasible(), "Unfunded plan became executable");
                    if (enabled) verify(p, stock(false));
                    else check(p.result() == GraphPlan.Result.UNKNOWN && p.missingExact().isEmpty(), "Internal view exported a refill");
                }
                check(b.reservedBytes() == 0, "Refill work leaked workspace");
            }
            for (int workers : new int[]{1, 4}) for (boolean supplied : List.of(false, true, false)) {
                var b = budget();
                try (var scheduler = new PlanningScheduler(workers, 1, 64, 2_000_000)) {
                    var request = request(catalog, stock(supplied), false, b);
                    var p = scheduler.submit(request, b).get(30, TimeUnit.SECONDS);
                    check(!request.fallbackMode(), "Conditional preview entered ordinary fallback");
                    check(p.feasible() == supplied, "Request cached stale missing stock");
                    check(request.reuseCandidate() != null == supplied, "Unfunded preview became a reusable feasible plan");
                    if (!supplied) verify(p, stock(false));
                    else PlanVerifier.verify(p);
                }
                check(b.reservedBytes() == 0, "Request leaked workspace");
            }
        }
        laterCandidate(); limitHandoff(); durabilityRefills(); interruptedRecovery();
        System.out.println("CapturedRefillPreviewTest: assertions=" + checks);
    }

    private static void laterCandidate() {
        var choices = new ArrayList<CapturedRecipe.Candidate<String>>();
        for (int i = 0; i < 300; i++) choices.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("raw" + i, 1), null, false));
        var recipe = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, choices)), List.of(new CapturedRecipe.Amount<>("book", 1)), false, false);
        var catalog = new CapturedCatalog<>(List.of(new CapturedCatalog.Entry<>("make", "make", recipe)));
        var b = budget();
        try (var work = catalog.begin("book", 1, Map.of("raw299", 1L), false, true, b).conditionalPreviews(true)) {
            while (!work.step()) {}
            check(work.result().plan().feasible(), "Refill preview preempted a funded later candidate");
            check(work.result().plan().missingExact().isEmpty(), "Omitted candidate was charged as missing");
            PlanVerifier.verify(work.result().plan());
        }
        check(b.reservedBytes() == 0, "Later candidate leaked");
    }

    private static void limitHandoff() throws Exception {
        var catalog = new CapturedCatalog<>(entries());
        var b = budget();
        try (var work = catalog.begin("book", 1, stock(false), false, true, b).conditionalPreviews(true)) {
            var f = work.getClass().getDeclaredField("solving"); f.setAccessible(true);
            GraphPlanningWork<?> child = null;
            for (int i = 0; i < 1_000_000 && child == null; i++) {
                check(!work.step(), "Completed before refill publication boundary");
                child = (GraphPlanningWork<?>) f.get(work);
            }
            check(child != null, "Did not reach concrete planning");
            while (!child.step()) {}
            check(!child.result().missingExact().isEmpty(), "Child did not verify a refill");
            var result = work.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT));
            verify(result.plan(), stock(false));
            check(!result.coverage().complete(), "Limit promoted a local proof");
        }
        check(b.reservedBytes() == 0, "Handoff leaked");
    }

    private static RequestPlanningWork<String> request(CapturedCatalog<String> catalog, Map<String, Long> stock, boolean craftLess, PlanningBudget b) {
        return new RequestPlanningWork<>(new GraphCompiler<String>(List.of()), "book", 1, stock, Set.of(), null,
                false, craftLess, "REPORT_MISSING_ITEMS", CatalystPolicy.MINIMAL, false, true, b, () -> false,
                report -> { throw new AssertionError("Unexpected fallback"); }).capturedCatalog(catalog);
    }

    private static void verify(GraphPlan<String> p, Map<String, Long> stock) {
        check(p.conditionalPreview() && p.result() == GraphPlan.Result.INCOMPLETE_MODEL, "Lost conditional scope: " + p.result());
        check(p.missing().equals(Map.of("raw", 2L, "other", 1L)), "Changed refill quantities: " + p.missing());
        verifyFunded(p, stock);
    }

    private static void verifyFunded(GraphPlan<String> p, Map<String, Long> stock) {
        check(p.seedOptimality() == null && p.alternatives().isEmpty(), "Exported unsupported optimality");
        for (var e : p.initialExact().entrySet()) {
            var deficit = e.getValue().subtract(BigInteger.valueOf(stock.getOrDefault(e.getKey(), 0L))).max(BigInteger.ZERO);
            check(deficit.equals(p.missingExact().getOrDefault(e.getKey(), BigInteger.ZERO)), "Unaccounted initial stock");
        }
        var funded = new GraphPlan<>(p.target(), p.amount(), p.preserveSeeds(), p.steps(), p.recipes(), p.initialExact(),
                p.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, p.searchNodes(), p.planningNanos());
        PlanVerifier.verify(funded);
        PlanVerifier.verifyRuntimeInventory(funded);
    }

    private record Worn() implements StateFamily<String> {
        public String id() { return "finite-tool"; }
        public boolean accepts(String key) { return key.startsWith("tool:"); }
        public long capacity(String key) { return accepts(key) ? Long.parseLong(key.substring(5)) : 0; }
        public String nominalAfterOne(String key) { return capacity(key) <= 1 ? null : "tool:" + (capacity(key) - 1); }
    }

    private static void durabilityRefills() throws Exception {
        for (int scenario = 0; scenario < 5; scenario++) for (boolean reverse : List.of(false, true)) {
            var rule = new Worn();
            var tool = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool:3", 1), "tool:2", false, false, rule)));
            var inputs = new ArrayList<>(List.of(input("raw", 2), tool));
            if (scenario == 1) inputs.add(tool); // Two simultaneous tools, with distinct durability.
            var entries = new ArrayList<>(List.of(entry("ring", inputs, "ring"),
                    entry("bundle", List.of(input("ring", 3), input("other", 4)), "book")));
            var stock = new java.util.LinkedHashMap<>(Map.of("raw", 4L, "other", 3L));
            if (scenario == 0) stock.put("tool:3", 1L);
            if (scenario == 1) { stock.put("tool:4", 1L); stock.put("tool:3", 1L); }
            if (scenario == 2) { stock.put("tool:1", 1L); entries.add(entry("forge", List.of(input("metal", 2)), "tool:3")); }
            if (scenario == 3) { /* Truly absent tool: retain a verified physical-tool refill. */ }
            if (scenario == 4) stock.put("tool:1", 3L); // Multiple short-lived physical entities.
            if (reverse) java.util.Collections.reverse(entries);
            var b = budget(); var catalog = new CapturedCatalog<>(entries);
            try (var scheduler = new PlanningScheduler(1, 1, 32, 2_000_000)) {
                var request = request(catalog, stock, false, b);
                var plan = scheduler.submit(request, b).get(30, TimeUnit.SECONDS);
                check(plan.conditionalPreview() && !request.fallbackMode(), "Tool refill lost its scope");
                verifyFunded(plan, stock);
                if (scenario != 3) check(plan.missing().keySet().stream().noneMatch(rule::accepts), "Counted tool uses as missing entities: " + scenario + "/" + plan.missing());
                if (scenario == 2) check(plan.missing().getOrDefault("metal", 0L) == 2, "Failed to manufacture exactly one replacement tool: " + plan.missing());
                if (scenario == 3) check(plan.missing().keySet().stream().anyMatch(rule::accepts), "Invented an absent tool");
                var supplied = new java.util.LinkedHashMap<>(stock);
                plan.missing().forEach((key, count) -> supplied.merge(key, count, Math::addExact));
                var retryBudget = budget();
                var retried = scheduler.submit(request(catalog, supplied, false, retryBudget), retryBudget).get(30, TimeUnit.SECONDS);
                check(retried.feasible(), "The advertised refill did not solve on a fresh request");
                PlanVerifier.verify(retried);
                check(retryBudget.reservedBytes() == 0, "Funded request leaked");
            }
            check(b.reservedBytes() == 0, "Tool refill recovery leaked");
        }
    }

    private static void interruptedRecovery() throws Exception {
        for (var limit : List.of(PlanningBudget.Limit.SEARCH_LIMIT, PlanningBudget.Limit.MEMORY_LIMIT, PlanningBudget.Limit.TIMEOUT)) {
            var b = budget();
            try (var work = new CapturedCatalog<>(entries()).begin("book", 1, stock(false), false, true, b).conditionalPreviews(true)) {
                var field = work.getClass().getDeclaredField("refillRecovery"); field.setAccessible(true);
                int steps = 0;
                while (field.get(work) == null) {
                    check(++steps < 1_000_000 && !work.step(), "No optional recovery boundary");
                }
                var result = work.limited(new PlanningBudget.Exhausted(limit));
                verify(result.plan(), stock(false));
                check(!result.coverage().complete(), "Interrupted recovery gained a global proof");
            }
            check(b.reservedBytes() == 0, "Interrupted recovery leaked");
        }
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var b = new PlanningBudget(0, 4_000_000, 64L << 20, cancelled::get, System::nanoTime);
        try (var work = new CapturedCatalog<>(entries()).begin("book", 1, stock(false), false, true, b).conditionalPreviews(true)) {
            var field = work.getClass().getDeclaredField("refillRecovery"); field.setAccessible(true);
            while (field.get(work) == null) check(!work.step(), "No cancellation boundary");
            cancelled.set(true);
            try { work.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT)); throw new AssertionError("Cancellation returned a refill"); }
            catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(b.reservedBytes() == 0, "Cancelled refill leaked");
    }

    private static List<CapturedCatalog.Entry<String>> entries() {
        var tool = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("hammer", 1), "hammer", false, false, new Hammer())));
        return new ArrayList<>(List.of(entry("ring", List.of(input("raw", 2), tool), "ring"),
                entry("bundle", List.of(input("ring", 3), input("other", 4)), "book")));
    }
    private static CapturedRecipe.Input<String> input(String key, long amount) {
        return new CapturedRecipe.Input<>(amount, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)));
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static Map<String, Long> stock(boolean supplied) { return Map.of("raw", supplied ? 6L : 4L, "other", supplied ? 4L : 3L, "hammer", 1L); }
    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
