// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Structural ranks are optional words; neither hints nor their cycles are proofs. */
public final class StateOrderHintsTest {
    private static int checks, words;
    private record Input(Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> counts, Map<String, Long> stock) {}

    public static void main(String[] args) {
        var random = new Random(181026);
        for (int sample = 0; sample < 60; sample++) dag(random, sample);
        fallback(); cycleAndGate(); wide(); limits();
        System.out.println("StateOrderHintsTest: dag_cases=60; words=" + words + "; assertions=" + checks);
    }

    private static void dag(Random random, int sample) {
        int size = 4 + sample % 5; var input = input(size, false);
        var order = new ArrayList<String>(input.recipes().keySet()); Collections.shuffle(order, random);
        var budget = budget();
        try (var work = create(input, budget)) {
            prepare(work, input);
            check(work.beginFeedback(2) > 0, "Missing structural allowance");
            check(work.beginFeedback(2) == 0, "Structural analysis renewed itself");
            // Independently planted total/partial orders on recipes whose
            // original material prefixes all fit. Ranking must retain every id.
            var edges = new ArrayList<List<String>>();
            for (int i = 0; i < size; i++) for (int j = i + 1; j < size; j++) if (sample % 2 == 0 || random.nextBoolean()) {
                var edge = List.of(order.get(i), order.get(j)); edges.add(edge);
                check(work.preferBefore(edge.get(0), edge.get(1)), "Lost a distinct hint");
                check(!work.preferBefore(edge.get(0), edge.get(1)), "Duplicate hint retained again");
            }
            check(work.reject("r2"), "Ranked continuation lost");
            var actual = word(work, input);
            for (var edge : edges) check(actual.indexOf(edge.get(0)) < actual.indexOf(edge.get(1)), "Acyclic preference order lost");
        }
        check(budget.reservedBytes() == 0, "Hint ranking leaked");
    }

    private static void fallback() {
        var input = input(4, false); var plainBudget = budget(); List<String> deferred;
        try (var plain = create(input, plainBudget)) {
            prepare(plain, input); check(plain.reject("r2"), "Missing plain promotion"); deferred = word(plain, input);
        }
        check(plainBudget.reservedBytes() == 0, "Plain control leaked");
        var budget = budget();
        try (var work = create(input, budget)) {
            prepare(work, input); check(work.beginFeedback(2) > 0, "Missing hint admission");
            work.preferBefore("r0", "r3"); work.preferBefore("r3", "r1"); work.preferBefore("r1", "r2");
            check(work.reject("r2"), "Missing ranked candidate");
            check(word(work, input).equals(List.of("r0", "r3", "r1", "r2")), "Lost structural chain");
            check(work.reject("r0"), "Rejected structural witness discarded plain alternative");
            check(word(work, input).equals(deferred), "Did not resume the interrupted plain word");
            int emitted = 4;
            while (work.reject("r" + (emitted % 4))) {
                while (!work.step()) {}
                if (work.witness() == null) break;
                replay(work.witness(), input); emitted++;
                check(emitted <= 9, "More than eight ordinary words plus one structural word");
            }
            check(emitted == 9, "The extra word removed an ordinary attempt");
            check(work.beginFeedback(2) == 0, "Rejected hint renewed analysis");
        }
        check(budget.reservedBytes() == 0 && budget.searchWork() < 16_000, "Fallback renewed lifetime or leaked");
    }

    private static void cycleAndGate() {
        for (boolean gated : List.of(false, true)) {
            var input = input(4, gated); var budget = budget();
            try (var work = create(input, budget)) {
                prepare(work, input); check(work.beginFeedback(2) > 0, "Missing feedback scope");
                work.preferBefore("r1", "r0"); work.preferBefore("r0", "r1");
                work.preferBefore("r3", "r2");
                check(!work.preferBefore("r0", "absent") && !work.preferBefore("r1", "r1"), "Invented a hint endpoint");
                check(work.reject("r2"), "Cyclic preference became infeasibility");
                word(work, input); // Real gates override even an acyclic portion.
            }
            check(budget.reservedBytes() == 0, "Cyclic hints leaked");
        }
        var input = input(4, true); var budget = budget();
        try (var work = create(input, budget)) {
            prepare(work, input); work.beginFeedback(2);
            work.preferBefore("r2", "r1"); work.preferBefore("r1", "r0");
            check(work.reject("r2"), "Backward preference prevented physical ordering");
            var order = word(work, input);
            check(order.indexOf("r0") < order.indexOf("r1") && order.indexOf("r1") < order.indexOf("r2"), "Hint removed a material gate");
        }
        check(budget.reservedBytes() == 0, "Backward hints leaked");
    }

    private static void wide() {
        for (int size : new int[]{64, 65}) {
            var input = input(size, false); var budget = budget();
            try (var work = create(input, budget)) {
                prepare(work, input); long allowance = work.beginFeedback(2);
                if (size == 64) {
                    check(allowance > 0 && work.preferBefore("r63", "r0"), "Sign-bit endpoint lost");
                    check(work.reject("r0"), "Lost wide structural word");
                    var order = word(work, input); check(order.indexOf("r63") < order.indexOf("r0"), "Sign-bit mask truncated");
                } else {
                    check(allowance == 0 && !work.preferBefore("r64", "r0"), "Unbounded hint graph admitted");
                    check(work.reject("r0"), "Hint admission removed larger ordinary scheduler"); word(work, input);
                }
            }
            check(budget.reservedBytes() == 0, "Wide graph leaked");
        }
    }

    private static void limits() {
        var input = input(4, false); var budget = budget();
        try (var work = create(input, budget)) {
            prepare(work, input); work.beginFeedback(2);
            long held = (16L << 20) - budget.reservedBytes() - 64;
            check(budget.tryReserve(held), "Failed to establish memory boundary");
            check(!work.preferBefore("r3", "r0"), "Optional graph ignored memory admission");
            budget.release(held);
            check(work.reject("r2"), "Memory denial discarded ordinary ordering"); word(work, input);
            budget.charge(40_000); check(!work.reject("r0"), "Downstream work renewed the allowance");
        }
        check(budget.reservedBytes() == 0, "Denied/expired graph leaked");
        var cancel = new AtomicBoolean(); budget = new PlanningBudget(0, 1_000_000, 16L << 20, cancel::get, System::nanoTime);
        try (var work = create(input, budget)) {
            prepare(work, input); work.beginFeedback(2); work.preferBefore("r3", "r0"); cancel.set(true);
            try { work.reject("r0"); work.step(); throw new AssertionError("Ignored cancellation"); }
            catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancelled graph leaked");
    }

    private static Input input(int size, boolean gated) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>(); var counts = new LinkedHashMap<String, BigInteger>();
        for (int i = 0; i < size; i++) {
            String id = "r" + i, from = gated && i > 0 && i < 3 ? "part" + (i - 1) : "raw";
            recipes.put(id, new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>(from, 1, 0)), Map.of("part" + i, 1L)));
            counts.put(id, BigInteger.ONE);
        }
        return new Input(recipes, counts, Map.of("raw", (long) size));
    }
    private static StateScheduleRepair<String> create(Input input, PlanningBudget budget) {
        var work = StateScheduleRepair.create(input.recipes(), input.counts(), input.stock(), Set.of(), "r0", budget);
        check(work != null && work.beginFeedback(2) == 0, "Analysis ran before repeated failure"); return work;
    }
    private static void prepare(StateScheduleRepair<String> work, Input input) {
        word(work, input); check(work.reject("r1"), "Missing first ordinary retry"); word(work, input);
    }
    private static List<String> word(StateScheduleRepair<String> work, Input input) {
        while (!work.step()) {} check(work.witness() != null, "Lost known material witness"); return replay(work.witness(), input);
    }
    private static List<String> replay(PlanStep step, Input input) {
        var held = new LinkedHashMap<>(input.stock()); var actual = new LinkedHashMap<String, BigInteger>(); var word = new ArrayList<String>();
        var cursor = new PlanCursor(step);
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = input.recipes().get(batch.recipe());
            actual.merge(recipe.id(), BigInteger.valueOf(batch.runs()), BigInteger::add);
            for (long i = 0; i < batch.runs(); i++) {
                recipe.inputs().forEach((key, need) -> { check(held.getOrDefault(key, 0L) >= need, "Unfunded ordinary input"); held.merge(key, -need, Long::sum); });
                recipe.executionOutputs().forEach((key, count) -> held.merge(key, count, Long::sum)); word.add(recipe.id());
            }
            cursor.dispatched(batch.runs());
        }
        check(actual.equals(input.counts()), "Changed counts or recipe identity"); words++; return word;
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 16L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
