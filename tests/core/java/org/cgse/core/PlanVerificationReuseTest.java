// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Assembly reuse must still validate every physical prefix, target and seed;
 * forced production and arbitrary externally supplied plans retain full checks. */
public final class PlanVerificationReuseTest {
    private static int checks;

    public static void main(String[] args) {
        for (boolean force : List.of(false, true)) {
            var budget = budget();
            try (var assembly = chain(96, force, budget)) {
                while (!assembly.step()) {}
                check(assembly.plan != null, "Fixture did not assemble");
                var plan = assembly.plan;
                PlanVerifier.verify(plan);
                var standalone = budget();
                long start = budget.nodes();
                try (var reused = new PlanVerification<>(plan, budget, assembly);
                     var independent = new PlanVerification<>(plan, standalone)) {
                    while (!reused.step()) {}
                    while (!independent.step()) {}
                    same(reused.summary(), SequenceSummary.of(plan.steps(), plan.recipes()));
                    same(reused.summary(), independent.summary());
                    if (force) {
                        check(independent.physicalProduced("product").equals(BigInteger.valueOf(96)), "Changed standalone physical output total");
                        check(independent.physicalProduced("catalyst").signum() == 0, "Counted returned tool as physical production");
                        try (var proof = new ForceCraftProof<>(plan, reused, Map.of("tool96", 1L), budget)) {
                            while (!proof.step()) {}
                            check(proof.proved(), "Lost forced-production proof");
                        }
                    }
                    try { reused.patternCounts(); throw new AssertionError("Computed unused counts / invented uncomputed counts"); }
                    catch (IllegalStateException expected) { checks++; }
                    try { reused.physicalProduced("product"); throw new AssertionError("Invented uncomputed production"); }
                    catch (IllegalStateException expected) { checks++; }
                    check(budget.nodes() - start < standalone.nodes(), "Assembly repeated the completed fold");
                }
                if (!force) isolation(assembly);
                check(standalone.reservedBytes() == 0, "Standalone verifier leaked");
            }
            check(budget.reservedBytes() == 0, "Assembly verifier leaked");
        }
        partialGain(); returnedGain(); wideGain(); interrupted();
        System.out.println("PlanVerificationReuseTest: assertions=" + checks);
    }

    /** A startup target can be consumed and remade. Net gain below the order
     * must retain the original gross-production and submultiset proof. */
    private static void partialGain() {
        for (int kind = 0; kind < 4; kind++) {
            var recipes = new LinkedHashMap<String, GraphRecipe<String>>(); var program = new ArrayList<PlanStep>();
            if (kind == 0) {
                recipes.put("start", new GraphRecipe<>("start", "start", List.of(new GraphRecipe.Slot<>("target", 1), new GraphRecipe.Slot<>("raw", 1)), Map.of("mid", 1L)));
                recipes.put("finish", new GraphRecipe<>("finish", "finish", List.of(new GraphRecipe.Slot<>("mid", 1)), Map.of("target", 2L)));
            } else if (kind == 3) {
                recipes.put("gain", new GraphRecipe<>("gain", "gain", List.of(new GraphRecipe.Slot<>("target", 1, 0, true, true),
                        new GraphRecipe.Slot<>("raw", 1)), Map.of("target", 2L)));
            } else {
                recipes.put("gain", new GraphRecipe<>("gain", "gain", List.of(new GraphRecipe.Slot<>("raw", 1)), Map.of("target", 1L)));
                // The optional bonus keeps this firing through flow pruning,
                // but cannot justify an otherwise needless target turnover.
                if (kind == 1) recipes.put("turnover", new GraphRecipe<>("turnover", "turnover", List.of(new GraphRecipe.Slot<>("target", 1)), Map.of("target", 1L, "bonus", 1L)));
            }
            recipes.keySet().forEach(id -> program.add(new PlanStep.Batch(id, 1)));
            var budget = budget();
            try (var assembly = new AllocationSearch.Candidate<>(new PlanStep.Sequence(program), recipes, "target", 2,
                    Map.of("target", 1L, "raw", 1L), Map.of(), Set.of(), false, true, false, budget, System.nanoTime())) {
                while (!assembly.step()) {}
                check(assembly.proposed != null, "Partial-gain fixture did not reach production proof");
                check((assembly.plan != null) == (kind == 0), "Changed productive startup / needless turnover decision");
                var plan = assembly.proposed; PlanVerifier.verify(plan);
                try (var reused = new PlanVerification<>(plan, budget, assembly); var standalone = new PlanVerification<>(plan, budget)) {
                    while (!reused.step()) {} while (!standalone.step()) {}
                    check(reused.summary().delta("target").equals(BigInteger.ONE), "Partial gain was hidden");
                    check(reused.patternCounts().equals(standalone.patternCounts()), "Skipped necessary source counts");
                    check(reused.physicalProduced("target").equals(standalone.physicalProduced("target")), "Skipped necessary physical production");
                    if (kind == 1) check(reused.physicalProduced("target").equals(BigInteger.TWO) && reused.patternCounts().containsKey("turnover"),
                            "Pruning hid the gross-output / optional-coproduct counterexample");
                    try (var proof = new ForceCraftProof<>(plan, reused, Map.of(), budget)) {
                        while (!proof.step()) {}
                        check(proof.proved() == (kind == 0), "Returned stock or needless turnover discharged force crafting");
                    }
                }
            }
            check(budget.reservedBytes() == 0, "Partial-gain proof leaked");
        }
    }

    private static void returnedGain() {
        var recipe = new GraphRecipe<>("grow", "grow", List.of(new GraphRecipe.Slot<>("raw", 1),
                new GraphRecipe.Slot<>("target", 1, 1, true, true)), Map.of("target", 3L));
        var budget = budget();
        try (var assembly = new AllocationSearch.Candidate<>(new PlanStep.Batch("grow", 1), Map.of("grow", recipe),
                "target", 2, Map.of("raw", 1L, "target", 1L), Map.of(), Set.of(), false, true, false, budget, System.nanoTime())) {
            while (!assembly.step()) {}
            check(assembly.plan != null, "Returned target hid two fresh outputs");
            try (var verified = new PlanVerification<>(assembly.plan, budget, assembly);
                 var standalone = new PlanVerification<>(assembly.plan, budget)) {
                while (!verified.step()) {} while (!standalone.step()) {}
                check(verified.summary().delta("target").equals(BigInteger.TWO) && standalone.physicalProduced("target").equals(BigInteger.TWO),
                        "Returned target entered the net-production certificate");
                try (var proof = new ForceCraftProof<>(assembly.plan, verified, Map.of(), budget)) {
                    while (!proof.step()) {}
                    check(proof.proved(), "Exact net production unnecessarily required gross metadata");
                }
            }
        }
        check(budget.reservedBytes() == 0, "Returned-target proof leaked");
    }

    /** Net gain can exceed long even while the requested quantity and initial
     * physical stock fit. The proof must neither clip nor require counting. */
    private static void wideGain() {
        var recipe = new GraphRecipe<>("wide", "wide", List.of(new GraphRecipe.Slot<>("raw", 1)), Map.of("target", 2L));
        var budget = budget();
        try (var assembly = new AllocationSearch.Candidate<>(new PlanStep.Batch("wide", Long.MAX_VALUE), Map.of("wide", recipe),
                "target", Long.MAX_VALUE, Map.of("raw", Long.MAX_VALUE), Map.of(), Set.of(), false, true, false, budget, System.nanoTime())) {
            while (!assembly.step()) {}
            check(assembly.plan != null, "Wide net gain was clipped");
            try (var verified = new PlanVerification<>(assembly.plan, budget, assembly)) {
                while (!verified.step()) {}
                check(verified.summary().delta("target").equals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO)), "Wrong exact wide gain");
                try (var proof = new ForceCraftProof<>(assembly.plan, verified, Map.of(), budget)) {
                    while (!proof.step()) {}
                    check(proof.proved(), "Wide exact gain required gross counts");
                }
            }
        }
        check(budget.reservedBytes() == 0, "Wide net proof leaked");
    }

    private static void isolation(AllocationSearch.Candidate<String> assembly) {
        var plan = assembly.plan;
        var altered = new LinkedHashMap<>(plan.recipes());
        var first = altered.get("use0");
        var slots = new ArrayList<>(first.slots()); slots.add(new GraphRecipe.Slot<>("unowned", 1));
        altered.put("use0", new GraphRecipe<>(first.id(), first.binding(), slots, first.outputs()));
        rejects(copy(plan, plan.steps(), altered, plan.initialExact(), plan.seeds(), plan.amount()), assembly);
        var stock = new LinkedHashMap<>(plan.initialExact()); stock.remove("raw");
        rejects(copy(plan, plan.steps(), plan.recipes(), stock, plan.seeds(), plan.amount()), assembly);
        rejects(copy(plan, plan.steps(), plan.recipes(), plan.initialExact(), Map.of("tool96", 2L), plan.amount()), assembly);
        rejects(copy(plan, plan.steps(), plan.recipes(), plan.initialExact(), plan.seeds(), plan.amount() + 1), assembly);
        var backwards = new ArrayList<PlanStep>();
        for (int i = 0; i < 96; i++) backwards.add(new PlanStep.Batch("use" + i, 1));
        Collections.reverse(backwards);
        rejects(copy(plan, new PlanStep.Sequence(backwards), plan.recipes(), plan.initialExact(), plan.seeds(), plan.amount()), assembly);
        // A copied valid plan must retain the ordinary public contract too;
        // merely equal contents are not this assembly's completion token.
        var clone = copy(plan, plan.steps(), plan.recipes(), plan.initialExact(), plan.seeds(), plan.amount());
        var budget = budget();
        try (var work = new PlanVerification<>(clone, budget, assembly)) {
            while (!work.step()) {}
            check(work.physicalProduced("product").equals(BigInteger.valueOf(96)), "Copy inherited ordinary-order shortcut");
        }
        check(budget.reservedBytes() == 0, "Copy verification leaked");
    }

    private static void rejects(GraphPlan<String> plan, AllocationSearch.Candidate<String> assembly) {
        var budget = budget();
        try (var work = new PlanVerification<>(plan, budget, assembly)) {
            try { while (!work.step()) {} throw new AssertionError("Reused a summary for changed plan contents"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().startsWith("Unfunded prefix:"), "Unexpected invalid-plan failure"); }
        }
        check(budget.reservedBytes() == 0, "Rejected plan leaked");
    }

    private static void interrupted() {
        var budget = budget();
        try (var assembly = chain(32, false, budget)) {
            while (!assembly.step()) {}
            try (var work = new PlanVerification<>(assembly.plan, budget, assembly)) {
                check(!work.step(), "Skipped prefix verification");
                budget.cancel();
                try { work.step(); throw new AssertionError("Ignored cancellation during prefix checks"); }
                catch (CancellationException expected) { checks++; }
            }
        }
        check(budget.reservedBytes() == 0, "Cancelled reuse leaked");
    }

    private static AllocationSearch.Candidate<String> chain(int length, boolean force, PlanningBudget budget) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>(); var program = new ArrayList<PlanStep>();
        for (int i = 0; i < length; i++) {
            String id = "use" + i;
            recipes.put(id, new GraphRecipe<>(id, "use", List.of(new GraphRecipe.Slot<>("raw", 1),
                    new GraphRecipe.Slot<>("tool" + i, 1), new GraphRecipe.Slot<>("catalyst", 1, 2, true, true)),
                    Map.of("product", 1L, "tool" + (i + 1), 1L, "catalyst", 1L)));
            program.add(new PlanStep.Batch(id, 1));
        }
        return new AllocationSearch.Candidate<>(new PlanStep.Sequence(program), recipes, "product", length,
                Map.of("raw", (long) length, "tool0", 1L, "catalyst", 1L), Map.of("tool" + length, 1L), Set.of(),
                false, force, false, budget, System.nanoTime());
    }

    private static GraphPlan<String> copy(GraphPlan<String> plan, PlanStep steps, Map<String, GraphRecipe<String>> recipes,
                                           Map<String, BigInteger> initial, Map<String, Long> seeds, long amount) {
        return new GraphPlan<>(plan.target(), amount, false, steps, recipes, initial, seeds, Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }
    private static void same(SequenceSummary<String> actual, SequenceSummary<String> expected) {
        var keys = new LinkedHashSet<>(expected.keys()); keys.addAll(actual.keys());
        for (String key : keys) check(actual.required(key).equals(expected.required(key)) && actual.delta(key).equals(expected.delta(key)) &&
                actual.peak(key).equals(expected.peak(key)), "Changed prefix summary at " + key);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 16L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
