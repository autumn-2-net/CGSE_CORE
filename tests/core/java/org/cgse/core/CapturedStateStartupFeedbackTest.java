// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Finite state samples cannot certify absence of an unobserved repair loop. */
public final class CapturedStateStartupFeedbackTest {
    private static int checks;
    private static String lastGuard;

    public static void main(String[] args) {
        for (boolean reverse : List.of(false, true)) for (boolean sameId : List.of(false, true)) {
            var feedback = fixture(reverse, sameId, true, 1, true);
            // Every sampled state is even (plus terminal tool:1). A decreases
            // on all samples, but tool:21 is reachable from the actual tool:50
            // and restores tool:8192. The delivered word consumes the entity.
            check(!verify(feedback, 1), "Sampled decreases hid reachable positive feedback");
            check(verify(fixture(reverse, sameId, true, 1, false), 1), "Ordinary mode inherited implicit seeds");
            check(verify(feedback, 2), "A complete actual startup entity was refused");

            var ordinaryWear = fixture(reverse, sameId, false, 1, true);
            check(verify(ordinaryWear, 1), "Finite ordinary wear with raw manufacture was refused: " + lastGuard);
            check(verify(withUnrelatedCycle(ordinaryWear, reverse), 1),
                    "An unrelated state's closed cycle blocked finite ordinary wear: " + lastGuard);
            check(verify(fixture(reverse, sameId, 0, 1, true, true), 1),
                    "A conservative constant capacity hid an ordinary finite wear path: " + lastGuard);
            check(!verify(fixture(reverse, sameId, 1, 1, true, true), 1),
                    "Constant guaranteed capacities bypassed hidden feedback coverage: " + lastGuard);

            var simultaneous = fixture(reverse, sameId, true, 2, true);
            check(!verify(simultaneous, 3), "One surviving entity funded a two-entity restart");
            check(verify(simultaneous, 4), "Two surviving actual entities did not cover both simultaneous slots");

            var materialReturn = materialReturn(reverse);
            check(!verify(materialReturn, 1), "An unlisted dynamic material return was mistaken for raw manufacture");
        }
        System.out.println("CapturedStateStartupFeedbackTest PASS: " + checks + " checks");
    }

    private record Fixture(List<CapturedCatalog.Entry<String>> entries, GraphPlan<String> plan,
                           StateFamily<String> a, int width) {}

    private static Fixture withUnrelatedCycle(Fixture fixture, boolean reverse) {
        var unrelated = new StateFamily<String>() {
            public String id() { return "unrelated"; }
            public boolean accepts(String state) { return state.startsWith("unrelated:"); }
            public long capacity(String state) { return 1; }
            public String nominalAfterOne(String state) {
                return state.equals("unrelated:a") ? "unrelated:b" : "unrelated:a";
            }
        };
        var entries = new ArrayList<>(fixture.entries);
        entries.add(reverse ? 0 : entries.size(), entry("unrelated", unrelated, "unrelated:a", 1));
        return new Fixture(entries, fixture.plan, fixture.a, fixture.width);
    }

    private static Fixture materialReturn(boolean reverse) {
        // Use only one wear rule so cross-family capacity checks cannot reject
        // this case before the unlisted metal return is followed into forge.
        var family = family("return-material", 1, 2);
        var finish = new GraphRecipe<>("finish", "finish", List.of(new GraphRecipe.Slot<>("tool:50", 1)), Map.of("G", 1L));
        var raw = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("metal", 1), null, false)));
        var consumed = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool:50", 1), null, false)));
        var entries = new ArrayList<>(List.of(entry("A", family, "tool:8192", 1),
                new CapturedCatalog.Entry<>("forge", "forge", new CapturedRecipe<>(List.of(raw),
                        List.of(new CapturedRecipe.Amount<>("tool:8192", 1)), false, false)),
                new CapturedCatalog.Entry<>("finish", "finish", new CapturedRecipe<>(List.of(consumed),
                        List.of(new CapturedRecipe.Amount<>("G", 1)), false, false))));
        if (reverse) Collections.reverse(entries);
        var plan = new GraphPlan<>("G", 1, true, new PlanStep.Sequence(List.of(new PlanStep.Batch("finish", 1))),
                Map.of("finish", finish), Map.of("tool:50", 1L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        PlanVerifier.verify(plan);
        String state = "tool:50";
        for (int i = 0; i < 29; i++) state = family.nominalAfterOne(state);
        check(state.equals("tool:21"), "Material feedback is not reachable");
        long uses = 0;
        do {
            state = state.equals("metal") ? "tool:8192" : family.nominalAfterOne(state);
            uses++;
            if (state == null) throw new AssertionError("Material feedback destroyed the entity");
        } while (!state.equals("tool:21") && uses <= 8192);
        check(uses == 8173 && state.equals("tool:21"), "Material-return cycle changed");
        return new Fixture(entries, plan, family, 1);
    }

    private static Fixture fixture(boolean reverse, boolean sameId, boolean feedback, int width, boolean preserve) {
        return fixture(reverse, sameId, feedback ? 1 : 0, width, preserve);
    }

    private static Fixture fixture(boolean reverse, boolean sameId, int feedback, int width, boolean preserve) {
        return fixture(reverse, sameId, feedback, width, preserve, false);
    }

    private static Fixture fixture(boolean reverse, boolean sameId, int feedback, int width, boolean preserve, boolean constantCapacity) {
        var a = family("A", 1, feedback, constantCapacity);
        var b = family(sameId ? "A" : "B", 2, 0, constantCapacity);
        var entries = new ArrayList<>(List.of(entry("A", a, "tool:1", width), entry("B", b, "tool:8192", width)));
        if (feedback != 1) {
            var input = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                    new CapturedRecipe.Amount<>("metal", 1), null, false)));
            entries.add(new CapturedCatalog.Entry<>("forge", "forge", new CapturedRecipe<>(List.of(input),
                    List.of(new CapturedRecipe.Amount<>(feedback == 2 ? "tool:8192" : "tool:8", 1)), false, false)));
        }
        if (reverse) Collections.reverse(entries);
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>(); var word = new ArrayList<PlanStep>();
        for (int state = 50; state >= 2; state -= 2) {
            var outputs = new LinkedHashMap<String, Long>(); outputs.put("G", 1L);
            if (state > 2) outputs.put("tool:" + (state - 2), (long) width);
            var slots = new ArrayList<GraphRecipe.Slot<String>>();
            for (int i = 0; i < width; i++) slots.add(new GraphRecipe.Slot<>("tool:" + state, 1));
            String id = "word:" + state;
            recipes.put(id, new GraphRecipe<>(id, "B", slots, outputs)); word.add(new PlanStep.Batch(id, 1));
        }
        var plan = new GraphPlan<>("G", 25, preserve, new PlanStep.Sequence(word), recipes,
                Map.of("tool:50", (long) width), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        PlanVerifier.verify(plan);
        // Independent exact state witness, not a property inferred from guard
        // acceptance: a nominal-use capacity may be a conservative lower bound.
        if (feedback != 0) {
            String state = "tool:50";
            for (int i = 0; i < 29; i++) state = a.nominalAfterOne(state);
            check(state.equals("tool:21"), "Feedback is not reachable from the initial entity");
            long uses = 0;
            do {
                // The material-return cycle has one additional ordinary forge
                // operation; metal is not interchangeable with a tool state.
                state = state.equals("metal") ? "tool:8192" : a.nominalAfterOne(state);
                uses++;
                if (state == null) throw new AssertionError("Repair cycle destroys its entity");
            }
            while (!state.equals("tool:21") && uses <= 8192);
            check(uses == (feedback == 2 ? 8173 : 8172) && state.equals("tool:21"), "Independent state feedback witness changed");
        }
        return new Fixture(entries, plan, a, width);
    }

    private static StateFamily<String> family(String id, int cost, int feedback) {
        return family(id, cost, feedback, false);
    }

    private static StateFamily<String> family(String id, int cost, int feedback, boolean constantCapacity) {
        return new StateFamily<>() {
            public String id() { return id; }
            public boolean accepts(String state) { return state.startsWith("tool:"); }
            public long capacity(String state) {
                if (constantCapacity) return 1;
                long n = value(state);
                // A state-to-material return ends this family's usable chain.
                // Its guarantee is n-20 uses, not n; capacity remains valid.
                return feedback == 2 && n >= 21 ? n - 20 : n / cost;
            }
            public String nominalAfterOne(String state) {
                long n = value(state);
                return feedback != 0 && n == 21 ? feedback == 2 ? "metal" : "tool:8192" :
                        n > cost ? "tool:" + (n - cost) : null;
            }
        };
    }

    private static long value(String key) { return Long.parseLong(key.substring(5)); }

    private static CapturedCatalog.Entry<String> entry(String id, StateFamily<String> family, String state, int width) {
        var inputs = new ArrayList<CapturedRecipe.Input<String>>();
        for (int i = 0; i < width; i++) inputs.add(new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                new CapturedRecipe.Amount<>(state, 1), family.nominalAfterOne(state), false, false, family))));
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs,
                List.of(new CapturedRecipe.Amount<>("G", 1)), false, false));
    }

    private static boolean verify(Fixture fixture, int count) {
        var stock = Map.of("tool:50", (long) count);
        // Literal physical execution separately checks that the rejected
        // candidate really is executable, and counts its surviving entities.
        var held = new LinkedHashMap<String, BigInteger>(); held.put("tool:50", BigInteger.valueOf(count));
        for (var step : ((PlanStep.Sequence) fixture.plan.steps()).children()) {
            var recipe = fixture.plan.recipes().get(((PlanStep.Batch) step).recipe());
            recipe.inputs().forEach((key, amount) -> {
                var left = held.getOrDefault(key, BigInteger.ZERO).subtract(BigInteger.valueOf(amount));
                check(left.signum() >= 0, "Candidate borrows a future entity"); held.put(key, left);
            });
            recipe.outputs().forEach((key, amount) -> held.merge(key, BigInteger.valueOf(amount), BigInteger::add));
        }
        check(held.get("G").equals(BigInteger.valueOf(fixture.plan.amount())), "Changed target delivery");
        check(held.getOrDefault("tool:50", BigInteger.ZERO).equals(BigInteger.valueOf(count - fixture.width)),
                "Actual retained entity count changed");
        var producers = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < fixture.entries.size(); i++) {
            var recipe = fixture.entries.get(i).recipe();
            for (var output : recipe.outputs()) producers.computeIfAbsent(output.what(), ignored -> new ArrayList<>()).add(i);
            for (var input : recipe.inputs()) for (var candidate : input.candidates()) if (candidate.remaining() != null)
                producers.computeIfAbsent(candidate.remaining(), ignored -> new ArrayList<>()).add(i);
        }
        var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
        boolean accepted;
        try (var guard = new CapturedStartupVerification<>(fixture.entries, producers, fixture.plan, stock, Set.of(), budget, true)) {
            while (!guard.step()) { }
            accepted = guard.accepted();
            lastGuard = guard.reason() + "; work=" + budget.nodes();
        }
        check(budget.reservedBytes() == 0, "State feedback guard leaked workspace");
        return accepted;
    }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
