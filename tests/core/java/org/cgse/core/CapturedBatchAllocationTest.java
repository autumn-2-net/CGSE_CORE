// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.cgse.core.CapturedRecipeDomainTest.*;

public final class CapturedBatchAllocationTest {

    public static void main(String[] args) throws Exception {
        differentialBatches();
        wideSplitSupply();
        longCountsAndBounds();
        System.out.println("CapturedBatchAllocationTest passed: 600 exhaustive batch comparisons, 24 wide permutations");
    }

    private static void differentialBatches() {
        Random random = new Random(0xBA7CA110C1L);
        for (int trial = 0; trial < 600; trial++) {
            boolean mix = random.nextBoolean(); int firings = 1 + random.nextInt(3);
            var inputs = new ArrayList<CapturedRecipe.Input<String>>();
            List<List<long[]>> slotOptions = new ArrayList<>();
            for (int slot = 0; slot < 2; slot++) {
                int copies = 1 + random.nextInt(2);
                var candidates = new ArrayList<CapturedRecipe.Candidate<String>>();
                for (int n = 0; n < 2; n++) candidates.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("k" + random.nextInt(3), 1 + random.nextInt(3)), "can" + n, false));
                var input = new CapturedRecipe.Input<>(copies, candidates); inputs.add(input);
                var options = new ArrayList<long[]>();
                for (int first = 0; first <= copies; first++) {
                    if (!mix && first != 0 && first != copies) continue;
                    long[] consumed = new long[3];
                    for (int i = 0; i < 2; i++) {
                        var candidate = candidates.get(i);
                        consumed[Integer.parseInt(candidate.stack().what().substring(1))] += candidate.stack().amount() * (i == 0 ? first : copies - first);
                    }
                    options.add(consumed);
                }
                slotOptions.add(options);
            }
            var combinations = new ArrayList<long[]>();
            for (long[] a : slotOptions.get(0)) for (long[] b : slotOptions.get(1))
                combinations.add(new long[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]});
            long[] available = new long[]{random.nextInt(20), random.nextInt(20), random.nextInt(20)};
            boolean infinite = trial % 7 == 0;
            Map<String, Long> stock = Map.of("k0", available[0], "k1", available[1], "k2", available[2]);
            Set<String> external = infinite ? Set.of("k0") : Set.of();
            boolean possible = possible(combinations, available, firings, infinite);
            var recipe = new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>("out", 1)), !mix, false);
            var budget = budget();
            try (var domain = recipe.domain(budget);
                 var allocation = new CapturedInputAllocation<>(recipe, domain, stock, external, firings, budget)) {
                while (!allocation.step()) {}
                var ordinals = allocation.results();
                check(!ordinals.isEmpty() == possible, "batch allocation oracle mismatch: " + trial);
                if (possible) {
                    List<long[]> restored = new ArrayList<>();
                    for (var ordinal : ordinals) {
                        var variant = domain.resolve(ordinal); long[] consumed = new long[3];
                        for (var slot : variant.slots()) consumed[Integer.parseInt(slot.key().substring(1))] += slot.amount();
                        restored.add(consumed);
                        check(combinations.stream().anyMatch(value -> Arrays.equals(value, consumed)), "indivisible firing was mixed");
                    }
                    check(possible(restored, available, firings, infinite), "restored variants lost the multi-firing assignment: " + trial);
                }
            }
            check(budget.reservedBytes() == 0, "batch allocation reservation leaked");
        }
    }

    private static boolean possible(List<long[]> variants, long[] stock, int firings, boolean infinite) {
        if (firings == 0) return true;
        for (long[] variant : variants) {
            long[] next = stock.clone(); boolean funded = true;
            for (int i = 0; i < next.length; i++) if (!infinite || i != 0) { next[i] -= variant[i]; funded &= next[i] >= 0; }
            if (funded && possible(variants, next, firings - 1, infinite)) return true;
        }
        return false;
    }

    private static void wideSplitSupply() throws Exception {
        Random random = new Random(0x256BA7C);
        for (int trial = 0; trial < 24; trial++) {
            var inputs = new ArrayList<CapturedRecipe.Input<String>>(); var stock = new LinkedHashMap<String, Long>();
            for (int slot = 0; slot < 12; slot++) {
                var candidates = new ArrayList<>(input("s" + slot + "-", 300, 1).candidates());
                if (trial != 0) Collections.shuffle(candidates, random);
                inputs.add(new CapturedRecipe.Input<>(1, candidates));
                stock.put("s" + slot + "-298", 1000L); stock.put("s" + slot + "-299", 1000L);
            }
            var recipe = new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
            var catalog = new CapturedCatalog<>(List.of(new CapturedCatalog.Entry<>("r", "binding", recipe)));
            int workers = trial % 2 == 0 ? 1 : 4;
            var budget = new PlanningBudget(0, PlanningBudget.parallelWorkLimit(200_000, workers, trial % 3 == 0), 64L << 20, () -> false, System::nanoTime);
            var work = catalog.begin("out", 2000, stock, false, true, budget);
            CapturedCatalog.Result<String> result;
            try (var scheduler = new PlanningScheduler(workers, 4, 31, 1_000_000)) { result = scheduler.submit(work, budget).get(30, TimeUnit.SECONDS); }
            check(result.plan().feasible(), "split-stock domain still exceeded its budget: " + trial + "/" + result.plan().result());
            PlanVerifier.verify(result.plan());
            for (var initial : result.plan().initialExact().entrySet())
                check(initial.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(initial.getKey(), 0L))) <= 0, "shared stock exceeded");
            check(result.coverage().domains().get(0).expanded().intValueExact() == 256 && result.coverage().domains().get(0).additional().size() == 2,
                    "batch repair enumerated Cartesian product");
            check(result.plan().result() == GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL && result.plan().seedOptimality() == null, "support optimum escaped");
            check(result.plan().patternTimesExact().values().stream().reduce(BigInteger.ZERO, BigInteger::add).equals(BigInteger.valueOf(2000)), "batch production lost");
            check(budget.reservedBytes() == 0 && budget.nodes() < 150_000, "batch repair ownership or work regression");
            var proof = work.certificate(20_000_000, 128L << 20);
            var replay = CapturedProof.verify(proof, 20_000_000, 128L << 20);
            check(replay.coverage() == CountProof.Verdict.VERIFIED && replay.execution() == CountProof.Verdict.VERIFIED, "independent batch replay failed");
            if (trial == 0) System.out.println("split-stock wide domain: work=" + budget.nodes() + "; peakBytes=" + budget.peakBytes() + "; instantiated=258");
        }
    }

    private static void longCountsAndBounds() {
        var recipe = new CapturedRecipe<>(List.of(input("a", 3, 2)), List.of(new CapturedRecipe.Amount<>("out", 1)), false, false);
        var budget = budget();
        try (var domain = recipe.domain(budget);
             var allocation = new CapturedInputAllocation<>(recipe, domain,
                     Map.of("a0", Long.MAX_VALUE, "a1", Long.MAX_VALUE, "a2", Long.MAX_VALUE), Set.of(), Long.MAX_VALUE, budget)) {
            while (!allocation.step()) {}
            check(!allocation.results().isEmpty() && allocation.results().size() <= 5, "large firing count expanded by magnitude or overflowed");
            for (var ordinal : allocation.results()) {
                var concrete = domain.resolve(ordinal);
                check(concrete.slots().stream().mapToLong(GraphRecipe.Slot::amount).sum() == 2, "large aggregate count became a single giant firing");
            }
        }
        check(budget.reservedBytes() == 0 && budget.nodes() < 100_000, "long batch accounting");
        var small = new PlanningBudget(0, 100_000, 2048, () -> false, System::nanoTime);
        try (var domain = recipe.domain(small);
             var allocation = new CapturedInputAllocation<>(recipe, domain, Map.of("a0", 100L, "a1", 100L), Set.of(), 10, small)) {
            while (!allocation.step()) {}
            check(allocation.results().isEmpty(), "partial rejected allocation escaped");
        }
        check(small.reservedBytes() == 0, "optional batch memory refusal leaked");
        var cancelled = budget();
        try (var domain = recipe.domain(cancelled);
             var allocation = new CapturedInputAllocation<>(recipe, domain, Map.of("a0", 100L), Set.of(), 10, cancelled)) {
            allocation.step(); cancelled.cancel(); expect(java.util.concurrent.CancellationException.class, allocation::step);
        }
        check(cancelled.reservedBytes() == 0, "cancelled batch allocation leaked");
    }
}
