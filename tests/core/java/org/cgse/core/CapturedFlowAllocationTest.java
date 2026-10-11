// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.cgse.core.CapturedRecipeDomainTest.*;

public final class CapturedFlowAllocationTest {

    public static void main(String[] args) throws Exception {
        differentialFlows();
        wideUpstream();
        longUpstream();
        returnsAndStartup();
        goalsAndUnsupportedSemantics();
        recoveryAndBounds();
        System.out.println("CapturedFlowAllocationTest passed: 600 exhaustive flow comparisons, 24 wide upstream permutations");
    }

    /** Independent acyclic oracle: enumerate downstream firings, then ceil exact upstream deficits. */
    private static void differentialFlows() {
        var random = new Random(0xF10A110CA7EL);
        for (int trial = 0; trial < 600; trial++) {
            boolean mix = random.nextBoolean(); int requested = 1 + random.nextInt(3);
            int[] cost = new int[3], yield = new int[3], raw = new int[3];
            long[] initial = new long[3], available = new long[2];
            List<CapturedCatalog.Entry<String>> entries = new ArrayList<>();
            var stock = new LinkedHashMap<String, Long>();
            for (int i = 0; i < 2; i++) { available[i] = random.nextInt(18); stock.put("raw" + i, available[i]); }
            for (int i = 0; i < 3; i++) {
                cost[i] = 1 + random.nextInt(3); yield[i] = 1 + random.nextInt(3); raw[i] = random.nextInt(2);
                initial[i] = random.nextInt(4); stock.put("k" + i, initial[i]);
                entries.add(entry("up" + i, recipe(List.of(single("raw" + raw[i], cost[i])), "k" + i, yield[i], true)));
            }
            var inputs = new ArrayList<CapturedRecipe.Input<String>>();
            var options = new ArrayList<List<long[]>>();
            for (int slot = 0; slot < 2; slot++) {
                int copies = 1 + random.nextInt(2);
                var cs = new ArrayList<CapturedRecipe.Candidate<String>>();
                for (int c = 0; c < 2; c++) cs.add(candidate("k" + random.nextInt(3), 1 + random.nextInt(2), null));
                inputs.add(new CapturedRecipe.Input<>(copies, cs));
                var choices = new ArrayList<long[]>();
                for (int first = 0; first <= copies; first++) {
                    if (!mix && first != 0 && first != copies) continue;
                    var consumed = new long[3];
                    for (int c = 0; c < 2; c++) consumed[Integer.parseInt(cs.get(c).stack().what().substring(1))] +=
                            cs.get(c).stack().amount() * (c == 0 ? first : copies - first);
                    choices.add(consumed);
                }
                options.add(choices);
            }
            List<long[]> concrete = new ArrayList<>();
            for (long[] a : options.get(0)) for (long[] b : options.get(1)) concrete.add(new long[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]});
            boolean infinite = trial % 7 == 0;
            boolean expected = possible(concrete, new long[3], requested, initial, available, cost, yield, raw, infinite);
            entries.add(entry("make", recipe(inputs, "out", 1, !mix)));
            Collections.shuffle(entries, random);
            var budget = budget();
            List<CapturedFlowAllocation.Variant> selected;
            Set<String> external = infinite ? Set.of("raw0") : Set.of();
            try (var flow = new CapturedFlowAllocation<>(entries, producers(entries), "out", requested, stock, external, Map.of(), true, budget)) {
                while (!flow.step()) {}
                selected = flow.results();
            }
            check(!selected.isEmpty() == expected, "joint stock oracle differs at " + trial + ": " + expected);
            if (expected) {
                var plan = witness(entries, selected, "out", requested, stock, external, Map.of(), false, true, budget);
                check(plan != null && plan.feasible(), "restored flow lost an acyclic witness at " + trial);
                verifyStock(plan, stock, external);
            }
            check(budget.reservedBytes() == 0, "flow model leaked at " + trial);
        }
    }

    private static boolean possible(List<long[]> variants, long[] consumed, int left, long[] initial, long[] stock,
                                    int[] cost, int[] yield, int[] raw, boolean infinite) {
        if (left == 0) {
            long[] used = new long[2];
            for (int i = 0; i < 3; i++) {
                long deficit = Math.max(0, consumed[i] - initial[i]);
                used[raw[i]] += (deficit + yield[i] - 1) / yield[i] * cost[i];
            }
            return (infinite || used[0] <= stock[0]) && used[1] <= stock[1];
        }
        for (long[] variant : variants) {
            var next = consumed.clone(); for (int i = 0; i < 3; i++) next[i] += variant[i];
            if (possible(variants, next, left - 1, initial, stock, cost, yield, raw, infinite)) return true;
        }
        return false;
    }

    private static void wideUpstream() throws Exception {
        var random = new Random(0x300A1B2);
        for (int trial = 0; trial < 24; trial++) {
            var inputs = new ArrayList<CapturedRecipe.Input<String>>();
            var entries = new ArrayList<CapturedCatalog.Entry<String>>();
            var stock = new LinkedHashMap<String, Long>();
            for (int slot = 0; slot < 12; slot++) {
                var candidates = new ArrayList<>(input("s" + slot + "-", 300, 1).candidates());
                if (trial != 0) Collections.shuffle(candidates, random);
                inputs.add(new CapturedRecipe.Input<>(1, candidates));
                for (int c = 298; c < 300; c++) {
                    String raw = "raw" + slot + "-" + c;
                    stock.put(raw, 1000L);
                    entries.add(entry(raw, recipe(List.of(single(raw, 1)), "s" + slot + "-" + c, 1, true)));
                }
            }
            entries.add(0, entry("out", recipe(inputs, "out", 1, true)));
            if (trial != 0) Collections.shuffle(entries, random);
            int workers = trial % 2 == 0 ? 1 : 4;
            var budget = new PlanningBudget(0, PlanningBudget.parallelWorkLimit(200_000, workers, trial % 3 == 0), 64L << 20, () -> false, System::nanoTime);
            if (trial == 0) budget.enableMetrics();
            var work = new CapturedCatalog<>(entries).begin("out", 2000, stock, false, true, budget);
            CapturedCatalog.Result<String> result;
            try (var scheduler = new PlanningScheduler(workers, 4, 31, 1_000_000)) {
                result = scheduler.submit(work, budget).get(30, TimeUnit.SECONDS);
            }
            check(result.plan().feasible(), "upstream supply still exceeds budget " + trial + ": " + result.plan().result());
            verifyStock(result.plan(), stock, Set.of());
            check(!result.coverage().complete() && result.plan().seedOptimality() == null, "restricted flow claimed optimality");
            var scope = result.coverage().domains().stream().filter(d -> d.id().equals("out")).findFirst().orElseThrow();
            check(scope.expanded().intValueExact() == 256 && scope.additional().size() == 2, "full Cartesian expansion crept in");
            check(budget.nodes() < 150_000, "recovered counts were discarded and solved again");
            var replay = CapturedProof.verify(work.certificate(2_000_000, 32L << 20), 2_000_000, 32L << 20);
            check(replay.coverage() == CountProof.Verdict.VERIFIED && replay.execution() == CountProof.Verdict.VERIFIED, "upstream certificate failed replay");
            check(budget.reservedBytes() == 0, "wide flow leaked");
            if (trial == 0) {
                var metrics = budget.candidates(); var allocation = metrics.origins().get(PlanningBudget.CandidateOrigin.ALLOCATION);
                check(allocation != null && allocation.accepted() == 1 && allocation.validationWork() > 0 &&
                        metrics.firstVerifiedSearchWork() > 0, "recovered witness missing from end-to-end metrics");
                check(budget.pipelineMetrics().costs().entrySet().stream().anyMatch(e ->
                        e.getKey().origin() == PlanningCostTrace.Origin.ALLOCATION &&
                                e.getKey().stage() == PlanningCostTrace.Stage.RESTORE && e.getValue().compilationTicks() > 0), "flow restoration cost lost");
            }
        }
    }

    private static void longUpstream() {
        var inputs = new ArrayList<CapturedRecipe.Input<String>>();
        var entries = new ArrayList<CapturedCatalog.Entry<String>>(); var stock = new LinkedHashMap<String, Long>();
        for (int slot = 0; slot < 6; slot++) {
            inputs.add(input("s" + slot + "-", 300, 1));
            for (int c = 298; c < 300; c++) {
                String raw = "raw" + slot + "-" + c;
                stock.put(raw, c == 298 ? Long.MAX_VALUE / 2 : Long.MAX_VALUE - Long.MAX_VALUE / 2);
                entries.add(entry(raw, recipe(List.of(single(raw, 1)), "s" + slot + "-" + c, 1, true)));
            }
        }
        entries.add(0, entry("out", recipe(inputs, "out", 1, true)));
        var budget = new PlanningBudget(0, 200_000, 64L << 20, () -> false, System::nanoTime);
        var work = new CapturedCatalog<>(entries).begin("out", Long.MAX_VALUE, stock, false, true, budget);
        try {
            while (!work.step()) {}
            check(work.result().plan().feasible(), "long upstream aggregate overflow");
            verifyStock(work.result().plan(), stock, Set.of());
            var replay = CapturedProof.verify(work.certificate(2_000_000, 32L << 20), 2_000_000, 32L << 20);
            check(replay.execution() == CountProof.Verdict.VERIFIED, "long upstream witness cannot replay");
        } finally { work.close(); }
        check(budget.reservedBytes() == 0, "long upstream leaked");
    }

    private static void returnsAndStartup() {
        // Net-balanced choices can still have no executable first firing.
        var cs = new ArrayList<>(input("missing", 299, 1).candidates());
        cs.add(candidate("a", 1, "b"));
        var entries = List.of(entry("make", recipe(List.of(new CapturedRecipe.Input<>(1, cs)), "out", 1, true)),
                entry("recycle", recipe(List.of(single("b", 1)), "a", 1, true)));
        var budget = budget();
        List<CapturedFlowAllocation.Variant> selected;
        try (var flow = new CapturedFlowAllocation<>(entries, producers(entries), "out", 7, Map.of(), Set.of(), Map.of(), true, budget)) {
            while (!flow.step()) {} selected = flow.results();
        }
        check(!selected.isEmpty(), "startup counterexample did not reach the net-balanced proposal");
        check(witness(entries, selected, "out", 7, Map.of(), Set.of(), Map.of(), true, true, budget) == null, "unseeded ring became executable");
        check(budget.reservedBytes() == 0, "rejected startup leaked");
        var catalog = new CapturedCatalog<>(entries);
        check(!catalog.plan("out", 7, Map.of(), true, true, budget()).plan().feasible(), "unseeded ring accepted by catalog");
        var funded = catalog.plan("out", 7, Map.of("a", 1L), true, true, budget());
        check(funded.plan().feasible(), "prior failed capture poisoned replenished stock");
        verifyStock(funded.plan(), Map.of("a", 1L), Set.of());

        // A candidate-specific physical return can be the requested product.
        var returnEntries = List.of(entry("empty", new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(2,
                List.of(candidate("filled", 3, "can")))), List.of(new CapturedRecipe.Amount<>("junk", 1)), true, false)));
        var returnBudget = budget();
        try (var flow = new CapturedFlowAllocation<>(returnEntries, producers(returnEntries), "can", 6,
                Map.of("filled", 18L), Set.of(), Map.of(), true, returnBudget)) {
            while (!flow.step()) {}
            var plan = witness(returnEntries, flow.results(), "can", 6, Map.of("filled", 18L), Set.of(), Map.of(), false, true, returnBudget);
            check(plan != null && plan.patternTimes().get("empty#0") == 3, "return quantity used stack amount instead of copies");
            verifyStock(plan, Map.of("filled", 18L), Set.of());
        }
        check(returnBudget.reservedBytes() == 0, "return flow leaked");
    }

    private static void goalsAndUnsupportedSemantics() {
        var entries = List.of(entry("a", recipe(List.of(single("raw", 1)), "a", 1, true)),
                entry("b", recipe(List.of(single("raw", 1)), "b", 1, true)),
                entry("join", recipe(List.of(single("a", 1), single("b", 1)), "out", 1, true)));
        for (int available : new int[]{1, 2, 3}) {
            var budget = budget(); var stock = Map.of("raw", (long) available, "out", 100L);
            try (var flow = new CapturedFlowAllocation<>(entries, producers(entries), "out", 1, stock, Set.of(), Map.of("raw", 1L), true, budget)) {
                while (!flow.step()) {}
                check(!flow.results().isEmpty() == (available == 3), "shared resource, force or explicit reserve was ignored");
                if (available == 3) {
                    var plan = witness(entries, flow.results(), "out", 1, stock, Set.of(), Map.of("raw", 1L), true, true, budget);
                    check(plan != null, "explicit reserve witness failed"); verifyStock(plan, stock, Set.of());
                }
            }
            check(budget.reservedBytes() == 0, "goal flow leaked");
        }
        var targetSeeds = List.of(entry("r", recipe(List.of(single("raw", 1)), "out", 1, true)));
        var seedBudget = budget();
        try (var flow = new CapturedFlowAllocation<>(targetSeeds, producers(targetSeeds), "out", 1,
                Map.of("out", 10L, "raw", 1L), Set.of(), Map.of("out", 10L), true, seedBudget)) {
            while (!flow.step()) {}
            check(!flow.results().isEmpty(), "forced growth unnecessarily reproduced existing target seeds");
        }
        check(seedBudget.reservedBytes() == 0, "target seed flow leaked");
        // Configuration/read arcs are declined, without blocking canonical planning.
        for (boolean reusable : new boolean[]{false, true}) {
            var flagged = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, List.of(
                    new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("raw", 1), null, true, reusable)))),
                    List.of(new CapturedRecipe.Amount<>("a299", 1)), true, false);
            var catalogEntries = List.of(entry("make", recipe(List.of(input("a", 300, 1)), "out", 1, true)), entry("flagged", flagged));
            var budget = budget();
            try (var flow = new CapturedFlowAllocation<>(catalogEntries, producers(catalogEntries), "out", 2, Map.of("raw", 1L), Set.of(), Map.of(), true, budget)) {
                while (!flow.step()) {} check(flow.results().isEmpty(), "dispatch/read arc silently linearized");
            }
            check(budget.reservedBytes() == 0, "unsupported semantics leak");
            var result = new CapturedCatalog<>(catalogEntries).plan("out", 2, Map.of("raw", 2L), false, true, budget());
            check(result.plan().feasible(), "declined flow poisoned the canonical path");
            if (!reusable) {
                // A dispatch can group two firings, but Batch does not promise
                // a single provider call. Its serial proof reserves two raws.
                // Preserve that distinction instead of accepting an unfunded
                // program based only on the optimistic per-push equation.
                var referenceBudget = budget();
                try (var upstream = flagged.domain(referenceBudget); var downstream = catalogEntries.get(0).recipe().domain(referenceBudget)) {
                    var a = upstream.resolve(BigInteger.ZERO); var b = downstream.resolve(BigInteger.valueOf(299));
                    var recipes = Map.of("up", new GraphRecipe<>("up", "flagged", a.slots(), a.outputs()),
                            "down", new GraphRecipe<>("down", "make", b.slots(), b.outputs()));
                    var reference = new GraphPlan<>("out", 2, false, new PlanStep.Sequence(List.of(new PlanStep.Batch("up", 2),
                            new PlanStep.Batch("down", 2))), recipes, Map.of("raw", 1L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
                    check(recipes.get("up").dispatchInputs(2).get("raw") == 1, "per-call configuration semantics changed");
                    check(PlanVerifier.initialExact(reference.steps(), recipes, "out", 2, Map.of()).get("raw").equals(BigInteger.TWO),
                            "serial proof no longer reserves split dispatches");
                    expect(IllegalArgumentException.class, () -> PlanVerifier.verify(reference));
                }
                check(referenceBudget.reservedBytes() == 0, "dispatch reference leaked");
                var boundaryBudget = budget();
                var boundary = new CapturedCatalog<>(catalogEntries).plan("out", 2, Map.of("raw", 1L), false, true, boundaryBudget);
                if (boundary.plan().feasible()) verifyStock(boundary.plan(), Map.of("raw", 1L), Set.of());
                else check(boundary.plan().result() == GraphPlan.Result.SEARCH_LIMIT || boundary.plan().result() == GraphPlan.Result.UNKNOWN,
                        "declined per-push model escaped as a global negative: " + boundary.plan().result());
                check(boundaryBudget.reservedBytes() == 0, "dispatch capacity boundary leaked");
                System.out.println("Dispatch boundary: " + boundary.plan().result() + "; per-call funding differs from serial reservation");
            }
        }
    }

    private static void recoveryAndBounds() {
        var recipe = recipe(List.of(input("a", 2, 2), input("b", 2, 3)), "out", 1, false);
        var firings = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(3));
        var allocation = List.of(Map.of(0, firings, 1, firings), Map.of(0, firings.multiply(BigInteger.valueOf(3))));
        var budget = budget();
        try (var domain = recipe.domain(budget); var recovery = new CapturedAllocationRecovery<>(recipe, domain, firings, allocation, budget)) {
            while (!recovery.step()) {}
            check(recovery.complete(), "large aggregate recovery was truncated to long");
            var inputs = new HashMap<String, BigInteger>(); BigInteger total = BigInteger.ZERO;
            for (var e : recovery.batches().entrySet()) {
                total = total.add(e.getValue());
                for (var slot : domain.resolve(e.getKey()).slots()) inputs.merge(slot.key(), e.getValue().multiply(BigInteger.valueOf(slot.amount())), BigInteger::add);
            }
            check(total.equals(firings) && inputs.equals(Map.of("a0", firings, "a1", firings, "b0", firings.multiply(BigInteger.valueOf(3)))), "recovered marginals changed");
        }
        check(budget.reservedBytes() == 0, "large recovery leak");
        var entries = List.of(entry("r", recipe(List.of(single("raw", 1)), "out", 1, true)));
        for (long bytes : new long[]{1, 1024, 4096, 16384}) {
            var small = new PlanningBudget(0, 200000, bytes, () -> false, System::nanoTime);
            try (var flow = new CapturedFlowAllocation<>(entries, producers(entries), "out", 1, Map.of("raw", 1L), Set.of(), Map.of(), true, small)) {
                while (!flow.step()) {}
            } catch (PlanningBudget.Exhausted limit) { check(limit.limit() == PlanningBudget.Limit.MEMORY_LIMIT, "wrong flow limit"); }
            check(small.reservedBytes() == 0, "refused flow memory leak: " + bytes);
        }
        var cancelled = budget();
        try (var flow = new CapturedFlowAllocation<>(entries, producers(entries), "out", 1, Map.of("raw", 1L), Set.of(), Map.of(), true, cancelled)) {
            flow.step(); cancelled.cancel(); expect(CancellationException.class, flow::step);
        }
        check(cancelled.reservedBytes() == 0, "cancelled flow leaked");
    }

    private static GraphPlan<String> witness(List<CapturedCatalog.Entry<String>> entries, List<CapturedFlowAllocation.Variant> selected,
            String target, long amount, Map<String, Long> stock, Set<String> external, Map<String, Long> seeds,
            boolean preserve, boolean force, PlanningBudget budget) {
        var recipes = new ArrayList<GraphRecipe<String>>(); var counts = new LinkedHashMap<String, BigInteger>();
        var domains = new ArrayList<CapturedRecipeDomain<String>>();
        try {
            for (var choice : selected) {
                var entry = entries.get(choice.entry()); var domain = entry.recipe().domain(budget); domains.add(domain);
                var resolved = domain.resolve(choice.ordinal()); String id = entry.id() + "#" + choice.ordinal();
                recipes.add(new GraphRecipe<>(id, entry.binding(), resolved.slots(), resolved.outputs())); counts.put(id, choice.firings());
            }
            try (var witness = new CapturedFlowWitness<>(recipes, counts, target, amount, stock, external, seeds, preserve, force, budget)) {
                while (!witness.step()) {} return witness.result();
            }
        } finally { for (var domain : domains) domain.close(); }
    }

    private static void verifyStock(GraphPlan<String> plan, Map<String, Long> stock, Set<String> external) {
        PlanVerifier.verify(plan);
        for (var e : plan.initialExact().entrySet()) if (!external.contains(e.getKey()))
            check(e.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(e.getKey(), 0L))) <= 0, "physical stock spent twice");
    }

    private static Map<String, List<Integer>> producers(List<CapturedCatalog.Entry<String>> entries) {
        var result = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < entries.size(); i++) {
            var recipe = entries.get(i).recipe(); Set<String> keys = new LinkedHashSet<>();
            for (var output : recipe.outputs()) if (output.amount() > 0) keys.add(output.what());
            for (var input : recipe.inputs()) for (var candidate : input.candidates()) if (candidate.remaining() != null) keys.add(candidate.remaining());
            for (String key : keys) result.computeIfAbsent(key, unused -> new ArrayList<>()).add(i);
        }
        return result;
    }

    private static CapturedRecipe.Candidate<String> candidate(String key, long amount, String returned) {
        return new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, amount), returned, false);
    }
    private static CapturedRecipe.Input<String> single(String key, long amount) { return new CapturedRecipe.Input<>(1, List.of(candidate(key, amount, null))); }
    private static CapturedRecipe<String> recipe(List<CapturedRecipe.Input<String>> inputs, String output, long amount, boolean external) {
        return new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, amount)), external, false);
    }
    private static CapturedCatalog.Entry<String> entry(String id, CapturedRecipe<String> recipe) { return new CapturedCatalog.Entry<>(id, "binding-" + id, recipe); }
}
