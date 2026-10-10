// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.cgse.core.CapturedRecipeDomainTest.*;

public final class CapturedInputAllocationTest {

    public static void main(String[] args) {
        differentialAllocation();
        hugeCartesianDomain();
        indivisibleAndConfiguration();
        System.out.println("CapturedInputAllocationTest passed: 600 differential assignments and sparse-domain recovery");
    }

    private static void differentialAllocation() {
        Random random = new Random(0xCA7A10C);
        for (int trial = 0; trial < 600; trial++) {
            boolean externalPattern = random.nextBoolean();
            var inputs = new ArrayList<CapturedRecipe.Input<String>>();
            var stock = new LinkedHashMap<String, Long>();
            for (int i = 0; i < 5; i++) stock.put("k" + i, (long) random.nextInt(9));
            Set<String> external = trial % 7 == 0 ? Set.of("k0") : Set.of();
            var options = new ArrayList<List<Map<String, Long>>>();
            for (int slot = 0; slot < 2; slot++) {
                int n = 2 + random.nextInt(3), copies = 1 + random.nextInt(3);
                var candidates = new ArrayList<CapturedRecipe.Candidate<String>>();
                for (int at = 0; at < n; at++) candidates.add(new CapturedRecipe.Candidate<>(
                        new CapturedRecipe.Amount<>("k" + random.nextInt(5), 1 + random.nextInt(3)), "can" + at, false));
                var input = new CapturedRecipe.Input<>(copies, candidates);
                inputs.add(input);
                var values = new ArrayList<Map<String, Long>>();
                enumerate(input, 0, copies, new long[n], !externalPattern, values);
                options.add(values);
            }
            boolean possible = false;
            for (var a : options.get(0)) for (var b : options.get(1)) {
                var consumed = new HashMap<>(a);
                b.forEach((key, value) -> consumed.merge(key, value, Long::sum));
                if (funded(consumed, stock, external)) possible = true;
            }
            var recipe = new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>("out", 1)), externalPattern, false);
            var budget = budget();
            try (var domain = recipe.domain(budget); var allocation = new CapturedInputAllocation<>(recipe, domain, stock, external, budget)) {
                while (!allocation.step()) { }
                check((allocation.result() != null) == possible, "factorized/exhaustive mismatch: " + trial);
                if (allocation.result() != null) {
                    var concrete = domain.resolve(allocation.result());
                    var recipeValue = new GraphRecipe<>("r", "binding", concrete.slots(), concrete.outputs());
                    check(funded(recipeValue.inputs(), stock, external), "slot stock reused: " + trial);
                    long selectedCopies = inputs.stream().mapToLong(CapturedRecipe.Input::multiplier).sum();
                    long returns = concrete.outputs().entrySet().stream().filter(e -> e.getKey().startsWith("can")).mapToLong(Map.Entry::getValue).sum();
                    check(returns == selectedCopies, "returns must follow recovered candidate copies");
                }
            }
            check(budget.reservedBytes() == 0, "allocation cleanup: " + trial);
        }
    }

    private static void enumerate(CapturedRecipe.Input<String> input, int at, long left, long[] counts, boolean mix,
                                  List<Map<String, Long>> result) {
        if (at == counts.length) {
            if (left != 0) return;
            var consumed = new LinkedHashMap<String, Long>();
            int selected = 0;
            for (int i = 0; i < counts.length; i++) if (counts[i] != 0) {
                selected++;
                var value = input.candidates().get(i);
                consumed.merge(value.stack().what(), value.stack().amount() * counts[i], Long::sum);
            }
            if (mix || selected == 1) result.add(consumed);
            return;
        }
        for (long value = 0; value <= left; value++) {
            counts[at] = value;
            enumerate(input, at + 1, left - value, counts, mix, result);
        }
    }

    private static boolean funded(Map<String, Long> consumed, Map<String, Long> stock, Set<String> external) {
        return consumed.entrySet().stream().allMatch(e -> external.contains(e.getKey()) || e.getValue() <= stock.getOrDefault(e.getKey(), 0L));
    }

    private static void hugeCartesianDomain() {
        var inputs = new ArrayList<CapturedRecipe.Input<String>>();
        var stock = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 12; i++) {
            inputs.add(input("s" + i + "-", 300, 1));
            stock.put("s" + i + "-299", 1L);
        }
        var recipe = new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        var catalog = new CapturedCatalog<>(List.of(new CapturedCatalog.Entry<>("r", "binding", recipe)));
        var budget = budget();
        var result = catalog.plan("out", 1, stock, false, true, budget);
        check(result.plan().feasible(), "factorized stock view could not recover huge-domain candidate: " + result.plan().result() + "/" + budget.failureDetail() + "/" + budget.diagnostics());
        PlanVerifier.verify(result.plan());
        var coverage = result.coverage().domains().get(0);
        var total = BigInteger.valueOf(300).pow(12);
        check(coverage.total().equals(total) && coverage.expanded().intValueExact() == 256 &&
                coverage.additional().equals(List.of(total.subtract(BigInteger.ONE))), "sparse domain falsely claimed full coverage");
        check(!result.coverage().complete() && result.plan().patternTimes().getOrDefault("r#" + total.subtract(BigInteger.ONE), 0L) == 1,
                "canonical variant restoration");
        check(budget.reservedBytes() == 0 && budget.nodes() < 200_000, "factorized view enumerated Cartesian domain");
        System.out.println("wide domain=" + total + " instantiated=257 work=" + budget.nodes() + " peakBytes=" + budget.peakBytes());
    }

    private static void indivisibleAndConfiguration() {
        var input = input("a", 2, 2);
        var stock = Map.of("a0", 1L, "a1", 1L);
        for (boolean external : List.of(false, true)) {
            var recipe = new CapturedRecipe<>(List.of(input), List.of(new CapturedRecipe.Amount<>("out", 1)), external, false);
            var budget = budget();
            try (var domain = recipe.domain(budget); var allocation = new CapturedInputAllocation<>(recipe, domain, stock, Set.of(), budget)) {
                while (!allocation.step()) { }
                check((allocation.result() == null) == external, "indivisible slot accepted arbitrary copy mixing");
            }
        }
        var configured = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, List.of(
                new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("circuit", 1), null, true)))),
                List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        var budget = budget();
        try (var domain = configured.domain(budget); var allocation = new CapturedInputAllocation<>(configured, domain, Map.of("circuit", 1L), Set.of(), budget)) {
            while (!allocation.step()) { }
            check(allocation.result() == null, "configuration entered independent linear view");
        }
    }
}
