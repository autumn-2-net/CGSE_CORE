// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.cgse.core.CapturedRecipeDomainTest.*;

public final class CapturedCatalogTest {

    public static void main(String[] args) throws Exception {
        lateCombination();
        laterDependencies();
        laterReturns();
        sharedResources();
        jointSupplyAndAmounts();
        orderPermutations();
        coverageAndRefresh();
        limitsAndScheduler();
        System.out.println("CapturedCatalogTest passed");
    }

    private static void lateCombination() {
        var recipe = captured(List.of(input("a", 20, 1), input("b", 20, 1)), "out");
        // The previous public path really cannot see the funded 400th variant.
        var prefix = new ArrayList<GraphRecipe<String>>();
        var expansion = recipe.expand(budget());
        int id = 0;
        while (expansion.hasNext()) {
            var value = expansion.next();
            prefix.add(new GraphRecipe<>("r#" + id++, "binding", value.slots(), value.outputs()));
        }
        var stock = Map.of("a19", 1L, "b19", 1L);
        var old = new GraphPlanner<>(new GraphCompiler<>(prefix)).plan("out", 1, stock, false, true, budget());
        check(!old.feasible(), "baseline unexpectedly has the omitted variant");
        var catalog = new CapturedCatalog<>(List.of(entry("r", recipe)));
        var budget = budget();
        var result = catalog.plan("out", 1, stock, false, true, budget);
        feasible(result, stock);
        check(result.plan().patternTimes().getOrDefault("r#399", 0L) == 1, "late ordinal not restored");
        check(result.coverage().revision() == 2 && !result.coverage().complete(), "delayed expansion boundary");
        check(result.coverage().domains().get(0).expanded().intValueExact() == 256 &&
                result.coverage().domains().get(0).additional().equals(List.of(BigInteger.valueOf(399))), "sparse coverage count");
        check(budget.reservedBytes() == 0, "late combination memory leak");
        var first = catalog.plan("out", 1, Map.of("a0", 1L, "b0", 1L), false, true, budget());
        feasible(first, Map.of("a0", 1L, "b0", 1L));
        check(first.coverage().revision() == 1 && !first.coverage().complete(), "successful first window unnecessarily expanded");
        check(first.plan().result() == GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL && first.plan().seedOptimality() == null,
                "restricted seed optimum escaped");
    }

    private static void laterDependencies() {
        var main = entry("r", captured(List.of(input("a", 20, 1), input("b", 20, 1)), "out"));
        var upstream = entry("make-a19", captured(List.of(single("raw", 1)), "a19"));
        var unused = entry("unused", captured(List.of(input("noise", 300, 1)), "unused-output"));
        for (var entries : List.of(List.of(main, upstream, unused), List.of(unused, upstream, main))) {
            var result = new CapturedCatalog<>(entries).plan("out", 1, Map.of("raw", 1L, "b19", 1L), false, true, budget());
            feasible(result, Map.of("raw", 1L, "b19", 1L));
            check(result.plan().patternTimes().getOrDefault("make-a19#0", 0L) == 1, "new dependency provider not discovered");
            check(result.coverage().domains().stream().noneMatch(d -> d.id().equals("unused")), "unrelated Cartesian domain expanded");
        }
    }

    private static void laterReturns() {
        var choices = new ArrayList<CapturedRecipe.Candidate<String>>();
        for (int i = 0; i < 300; i++) choices.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("filled" + i, 1),
                i == 299 ? "can" : null, false));
        var recipe = captured(List.of(new CapturedRecipe.Input<>(1, choices)), "byproduct");
        var result = new CapturedCatalog<>(List.of(entry("drain", recipe))).plan("can", 1, Map.of("filled299", 1L), false, true, budget());
        feasible(result, Map.of("filled299", 1L));
        check(result.plan().patternTimes().getOrDefault("drain#299", 0L) == 1, "later return producer missing");
    }

    private static void sharedResources() {
        var a = new ArrayList<CapturedRecipe.Candidate<String>>();
        var b = new ArrayList<CapturedRecipe.Candidate<String>>();
        for (int i = 0; i < 20; i++) {
            a.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(i == 19 ? "shared" : "a" + i, 1), null, false));
            b.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(i == 19 ? "shared" : "b" + i, 1), null, false));
        }
        var recipe = captured(List.of(new CapturedRecipe.Input<>(1, a), new CapturedRecipe.Input<>(1, b)), "out");
        var catalog = new CapturedCatalog<>(List.of(entry("r", recipe)));
        var insufficient = catalog.plan("out", 1, Map.of("shared", 1L), false, true, budget());
        check(!insufficient.plan().feasible() && insufficient.coverage().complete(), "one physical resource counted once per slot");
        var enough = catalog.plan("out", 1, Map.of("shared", 2L), false, true, budget());
        feasible(enough, Map.of("shared", 2L));
    }

    private static void coverageAndRefresh() {
        var truncated = new CapturedRecipe<>(List.of(single("missing", 1)), List.of(new CapturedRecipe.Amount<>("out", 1)), true, true);
        var catalog = new CapturedCatalog<>(List.of(entry("r", truncated)));
        var failure = catalog.plan("out", 1, Map.of(), false, true, budget());
        check(failure.plan().result() == GraphPlan.Result.UNKNOWN && failure.plan().missing().isEmpty(), "truncated capture claimed missing input");
        var success = catalog.plan("out", 1, Map.of("missing", 1L), false, true, budget());
        feasible(success, Map.of("missing", 1L));
        check(!success.coverage().complete(), "stock refill changed capture coverage");
        var omittedProvider = new CapturedCatalog<>(List.of(entry("r", captured(List.of(single("missing", 1)), "out"))), false);
        check(omittedProvider.plan("out", 1, Map.of(), false, true, budget()).plan().result() == GraphPlan.Result.UNKNOWN, "incomplete registry proof");
        var replacement = new CapturedCatalog<>(List.of(entry("r", captured(List.of(single("new", 1)), "out"))));
        feasible(replacement.plan("out", 1, Map.of("new", 1L), false, true, budget()), Map.of("new", 1L));
        var budget = budget();
        var journal = new CountProof.Journal(1L << 20);
        budget.proofJournal(journal);
        catalog.plan("out", 1, Map.of(), false, true, budget);
        check(journal.truncated(), "restricted proof archive lost coverage boundary");
        var forced = replacement.plan("out", 1, Map.of("out", 10L), false, true, budget());
        check(!forced.plan().feasible(), "forced crafting silently used existing target stock");
    }

    private static void jointSupplyAndAmounts() {
        var catalog = new CapturedCatalog<>(List.of(entry("r", captured(List.of(input("a", 300, 1)), "out"))));
        var stock = Map.of("a0", 1L, "a299", 1L);
        var result = catalog.plan("out", 2, stock, false, true, budget());
        feasible(result, stock);
        check(result.plan().patternTimes().getOrDefault("r#0", 0L) == 1 && result.plan().patternTimes().getOrDefault("r#299", 0L) == 1,
                "joint supply stopped after first funded variant");
        var laterStock = Map.of("a298", 1L, "a299", 1L);
        var later = catalog.plan("out", 2, laterStock, false, true, budget());
        feasible(later, laterStock);
        check(!later.coverage().complete() && later.coverage().domains().get(0).additional().size() == 2,
                "joint sparse allocation unnecessarily enumerated the remaining prefix");
        var produced = new CapturedCatalog<>(List.of(entry("r", captured(List.of(input("a", 300, 1)), "out")),
                entry("supply", captured(List.of(single("raw", 1)), "a298"))));
        var upstreamStock = Map.of("raw", 1L, "a299", 1L);
        var caughtUp = produced.plan("out", 2, upstreamStock, false, true, budget());
        feasible(caughtUp, upstreamStock);
        check(caughtUp.coverage().complete() && caughtUp.coverage().domains().stream().allMatch(d -> d.additional().isEmpty()),
                "sparse repair not absorbed when upstream supply needs the full prefix");
        for (long amount : new long[]{1, 1000, Long.MAX_VALUE}) {
            var stocked = Map.of("a299", amount);
            var large = catalog.plan("out", amount, stocked, false, true, budget());
            feasible(large, stocked);
            check(large.plan().patternTimesExact().get("r#299").equals(BigInteger.valueOf(amount)), "request-dependent candidate loss");
        }
        var alternatives = new ArrayList<>(input("missing", 300, 1).candidates());
        alternatives.set(299, new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("shared", 1), null, false));
        var input = new CapturedRecipe.Input<>(1, alternatives);
        var multi = new CapturedCatalog<>(List.of(entry("a", captured(List.of(input), "a")), entry("b", captured(List.of(input), "b")),
                entry("join", captured(List.of(single("a", 1), single("b", 1)), "out"))));
        var conflict = multi.plan("out", 1, Map.of("shared", 1L), false, true, budget());
        check(!conflict.plan().feasible(), "factorized proposals in separate patterns spent the same inventory twice");
    }

    private static void orderPermutations() {
        var random = new java.util.Random(0x256CA7);
        for (int trial = 0; trial < 120; trial++) {
            var inputs = new ArrayList<CapturedRecipe.Input<String>>();
            var stock = new java.util.LinkedHashMap<String, Long>();
            long capacity = Long.MAX_VALUE;
            for (String side : List.of("a", "b")) {
                var candidates = new ArrayList<>(input(side, 20, 1).candidates());
                Collections.shuffle(candidates, random);
                inputs.add(new CapturedRecipe.Input<>(1, candidates));
                long total = 0;
                for (int n = 0; n < 3; n++) {
                    int which = (trial + n * 7) % 20;
                    long available = random.nextInt(3);
                    stock.put(side + which, available);
                    total += available;
                }
                capacity = Math.min(capacity, total);
            }
            long requested = 1 + random.nextInt(6);
            var main = entry("r", captured(inputs, "out"));
            var noise = entry("noise", captured(List.of(input("unused", 300, 1)), "noise"));
            var catalog = new CapturedCatalog<>(trial % 2 == 0 ? List.of(main, noise) : List.of(noise, main));
            var result = catalog.plan("out", requested, stock, false, true, budget());
            check(result.plan().feasible() == (requested <= capacity), "order/joint supply mismatch " + trial + ": " + result.plan().result());
            if (result.plan().feasible()) feasible(result, stock);
            else check(result.coverage().complete(), "definitive negative without exhaustive relevant domain");
        }
    }

    private static void limitsAndScheduler() throws Exception {
        var catalog = new CapturedCatalog<>(List.of(entry("r", captured(List.of(input("a", 20, 1), input("b", 20, 1)), "out"))));
        for (int cap : new int[]{1, 16, 128, 1024}) {
            var budget = new PlanningBudget(0, cap, 16L << 20, () -> false, System::nanoTime);
            var result = catalog.plan("out", 1, Map.of("a19", 1L, "b19", 1L), false, true, budget);
            check(result.plan().result() == GraphPlan.Result.SEARCH_LIMIT, "small work falsely concluded missing: " + cap + "/" + result.plan().result());
            check(budget.reservedBytes() == 0, "small-work ownership: " + cap);
        }
        var small = new PlanningBudget(0, 1_000_000, 4096, () -> false, System::nanoTime);
        check(catalog.plan("out", 1, Map.of(), false, true, small).plan().result() == GraphPlan.Result.MEMORY_LIMIT, "memory outcome");
        check(small.reservedBytes() == 0, "memory refusal ownership");
        var cancelled = budget();
        try (var work = catalog.begin("out", 1, Map.of(), false, true, cancelled)) {
            for (int i = 0; i < 100; i++) work.step();
            cancelled.cancel();
            expect(CancellationException.class, work::step);
        }
        check(cancelled.reservedBytes() == 0, "cancel ownership");
        for (int workers : new int[]{1, 4}) try (var scheduler = new PlanningScheduler(workers, 4, 31, 1_000_000)) {
            var stock = Map.of("a19", 1L, "b19", 1L);
            var budget = budget();
            var result = scheduler.submit(catalog.begin("out", 1, stock, false, true, budget), budget).get(60, TimeUnit.SECONDS);
            feasible(result, stock);
            check(result.coverage().revision() == 2, "scheduler coverage");
        }
    }

    private static CapturedRecipe.Input<String> single(String key, long copies) {
        return new CapturedRecipe.Input<>(copies, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)));
    }

    private static CapturedRecipe<String> captured(List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), true, false);
    }

    private static CapturedCatalog.Entry<String> entry(String id, CapturedRecipe<String> recipe) {
        return new CapturedCatalog.Entry<>(id, "binding-" + id, recipe);
    }

    private static void feasible(CapturedCatalog.Result<String> result, Map<String, Long> stock) {
        check(result.plan().feasible(), "expected feasible, got " + result.plan().result() + " at " + result.coverage());
        PlanVerifier.verify(result.plan());
        for (var e : result.plan().initialExact().entrySet()) {
            check(e.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(e.getKey(), 0L))) <= 0, "plan invented stock: " + e);
        }
    }
}
