// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Restricted physical words must not hide unused productive seed sources. */
public final class CapturedStartupPublicationTest {
    private static int checks;
    public static void main(String[] args) {
        finiteGuard();
        alternativeDomain();
        stateSource();
        stateRepairBoundary();
        hiddenMaterialProducer();
        stockedTarget();
        interruptedGuard();
        publication(false);
        publication(true);
        System.out.println("CapturedStartupPublicationTest PASS: " + checks + " checks");
    }

    private static CapturedRecipe.Candidate<String> candidate(String key) {
        return new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Candidate<String>> choices, String out, long amount) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, choices)),
                List.of(new CapturedRecipe.Amount<>(out, amount)), false, false));
    }
    private static GraphPlan<String> proposal(boolean preserve, long retained) {
        var finish = new GraphRecipe<>("finish#0", "finish", List.of(new GraphRecipe.Slot<>("A", 1)), Map.of("G", 1L));
        return new GraphPlan<>("G", 1, preserve, new PlanStep.Batch("finish#0", 1), Map.of("finish#0", finish),
                Map.of("A", retained + 1), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static Map<String, List<Integer>> producers(List<CapturedCatalog.Entry<String>> entries) {
        var result = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < entries.size(); i++) {
            var source = entries.get(i).recipe();
            for (var out : source.outputs()) result.computeIfAbsent(out.what(), ignored -> new ArrayList<>()).add(i);
            for (var input : source.inputs()) for (var choice : input.candidates()) if (choice.remaining() != null)
                result.computeIfAbsent(choice.remaining(), ignored -> new ArrayList<>()).add(i);
        }
        return result;
    }
    private static boolean verify(List<CapturedCatalog.Entry<String>> entries, GraphPlan<String> plan, boolean states) {
        var budget = budget(); boolean accepted;
        try (var check = new CapturedStartupVerification<>(entries, producers(entries), plan, plan.initial(), Set.of(), budget, states)) {
            while (!check.step()) {}
            accepted = check.accepted();
        }
        eq(0L, budget.reservedBytes(), "guard leaked workspace");
        return accepted;
    }
    private static void finiteGuard() {
        var entries = List.of(entry("finish", List.of(candidate("A")), "G", 1), entry("grow", List.of(candidate("A")), "A", 2));
        eq(false, verify(entries, proposal(true, 0), false), "unused grower hidden by restricted bindings");
        eq(true, verify(entries, proposal(true, 1), false), "retained startup refused");
        eq(true, verify(entries, proposal(false, 0), false), "ordinary order restricted");
        eq(true, verify(List.of(entries.get(0)), proposal(true, 0), false), "raw A incorrectly protected");
    }
    private static void alternativeDomain() {
        var entries = List.of(entry("finish", List.of(candidate("A")), "G", 1),
                entry("grow", List.of(candidate("A"), candidate("B")), "A", 2),
                entry("return", List.of(candidate("A")), "B", 1));
        eq(false, verify(entries, proposal(true, 0), false), "alternative cycle mistaken for raw B");
        var rawAlternative = List.of(entries.get(0), entries.get(1));
        eq(true, verify(rawAlternative, proposal(true, 0), false), "real raw alternative not available to guard");
    }
    private static void stateSource() {
        StateFamily<String> wear = new StateFamily<>() {
            public String id() { return "wear"; }
            public boolean accepts(String key) { return key.startsWith("tool:"); }
            public long capacity(String key) { return Long.parseLong(key.substring(5)); }
            public String nominalAfterOne(String key) { long value = capacity(key); return value > 1 ? "tool:" + (value - 1) : null; }
        };
        var source = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, List.of(candidate("A"))),
                new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool:2", 1), "tool:1", false, false, wear)))),
                List.of(new CapturedRecipe.Amount<>("A", 2)), false, false);
        var entries = List.of(entry("finish", List.of(candidate("A")), "G", 1), new CapturedCatalog.Entry<>("grow", "grow", source));
        eq(false, verify(entries, proposal(true, 0), true), "state producer hid lost startup");
        eq(true, verify(entries, proposal(true, 1), true), "safe state producer rejected");
    }
    private static void publication(boolean preserve) {
        var choices = new ArrayList<CapturedRecipe.Candidate<String>>();
        for (int i = 0; i < 299; i++) choices.add(candidate("R" + i));
        choices.add(candidate("A"));
        var entries = List.of(entry("finish", choices, "G", 1), entry("grow", List.of(candidate("A")), "A", 2));
        var budget = budget(); GraphPlan<String> plan;
        try (var work = new CapturedCatalog<>(entries).begin("G", 1, Map.of("A", 1L), preserve, true, budget)) {
            while (!work.step()) {}
            plan = work.result().plan();
        }
        eq(true, plan.feasible(), "captured publication lost solvability: " + plan.result());
        PlanVerifier.verify(plan);
        BigInteger finalA = BigInteger.ONE.add(SequenceSummary.of(plan.steps(), plan.recipes()).delta("A"));
        eq(true, !preserve || finalA.signum() > 0, "published word exhausted productive startup");
        eq(0L, budget.reservedBytes(), "catalog leaked workspace");
    }
    private static void stockedTarget() {
        var entries = List.of(entry("grow", List.of(candidate("A")), "A", 2));
        var empty = new GraphPlan<String>("A", 1, true, new PlanStep.Sequence(List.of()), Map.of(),
                Map.of("A", 1L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        eq(false, verify(entries, empty, false), "empty delivery spent the only growth token");
        var budget = budget();
        try (var work = new CapturedCatalog<>(entries, false).begin("A", 1, Map.of("A", 1L), true, false, budget)) {
            while (!work.step()) {}
            var plan = work.result().plan();
            eq(true, plan.feasible(), "stocked target lost feasible growth: " + plan.result());
            var left = BigInteger.ONE.add(SequenceSummary.of(plan.steps(), plan.recipes()).delta("A")).subtract(BigInteger.ONE);
            eq(true, left.signum() > 0, "stock delivery bypassed startup guard");
        }
        eq(0L, budget.reservedBytes(), "stocked-target request leaked workspace");
    }
    private static void stateRepairBoundary() {
        StateFamily<String> wear = new StateFamily<>() {
            public String id() { return "wear"; }
            public boolean accepts(String key) { return key.startsWith("tool:"); }
            public long capacity(String key) { return Long.parseLong(key.substring(5)); }
            public String nominalAfterOne(String key) { long n = capacity(key); return n > 1 ? "tool:" + (n - 1) : null; }
        };
        var original = new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool:8192", 1), "tool:8191", false, false, wear);
        var use = entry("use", List.of(original), "G", 1);
        var word = new ArrayList<PlanStep>(); var bindings = new LinkedHashMap<String, GraphRecipe<String>>();
        for (int n = 20; n > 0; n--) {
            var outputs = new LinkedHashMap<String, Long>(); outputs.put("G", 1L);
            if (n > 1) outputs.put("tool:" + (n - 1), 1L);
            String id = "use#" + n;
            bindings.put(id, new GraphRecipe<>(id, "use", List.of(new GraphRecipe.Slot<>("tool:" + n, 1)), outputs));
            word.add(new PlanStep.Batch(id, 1));
        }
        var plan = new GraphPlan<>("G", 20, true, new PlanStep.Sequence(word), bindings, Map.of("tool:20", 1L),
                Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        eq(true, verify(List.of(use), plan, true), "ordinary finite wear became a seed obligation");
        for (String id : List.of("wear", "repair")) {
            StateFamily<String> repair = new StateFamily<>() {
                public String id() { return id; }
                public boolean accepts(String key) { return key.equals("tool:1"); }
                public long capacity(String key) { return accepts(key) ? 1 : 0; }
                public String nominalAfterOne(String key) { return "tool:8192"; }
            };
            var choice = new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool:1", 1), "tool:8192", false, false, repair);
            var restore = new CapturedCatalog.Entry<>("repair", "repair", new CapturedRecipe<>(
                    List.of(new CapturedRecipe.Input<>(1, List.of(choice))), List.<CapturedRecipe.Amount<String>>of(), false, false));
            eq(false, verify(List.of(use, restore), plan, true), "return-only repair cycle ignored: " + id);
        }
        var forge = entry("forge", List.of(candidate("G")), "tool:8192", 1);
        eq(false, verify(List.of(use, forge), plan, true), "product-to-tool feedback ignored");
    }
    private static void interruptedGuard() {
        var entries = List.of(entry("finish", List.of(candidate("A")), "G", 1), entry("grow", List.of(candidate("A")), "A", 2));
        for (int steps : List.of(0, 1, 2, 8, 20, 40)) {
            var budget = budget();
            try (var check = new CapturedStartupVerification<>(entries, producers(entries), proposal(true, 0), Map.of("A", 1L), Set.of(), budget, false)) {
                for (int i = 0; i < steps && !check.step(); i++) {}
            }
            eq(0L, budget.reservedBytes(), "interrupted startup guard leaked at " + steps);
        }
        var budget = new PlanningBudget(0, 128, 64L << 20, () -> false, System::nanoTime);
        try (var check = new CapturedStartupVerification<>(entries, producers(entries), proposal(true, 0), Map.of("A", 1L), Set.of(), budget, false)) {
            while (!check.step()) {}
            eq(false, check.accepted(), "local limit published an unverified word");
            eq(true, budget.remainingWork() > 0, "optional guard consumed the main search budget");
        }
        eq(0L, budget.reservedBytes(), "limited startup guard leaked");
    }
    private static void hiddenMaterialProducer() {
        StateFamily<String> wear = new StateFamily<>() {
            public String id() { return "hidden-material"; }
            public boolean accepts(String key) { return key.startsWith("tool:"); }
            public long capacity(String key) { return 1; }
            public String nominalAfterOne(String key) {
                long n = Long.parseLong(key.substring(5));
                return n == 21 ? "A" : n > 1 ? "tool:" + (n - 1) : null;
            }
        };
        var use = entry("use", List.of(new CapturedRecipe.Candidate<>(
                new CapturedRecipe.Amount<>("tool:1", 1), null, false, false, wear)), "G", 1);
        var finish = entry("finish", List.of(candidate("A")), "G", 1);
        var forge = entry("forge", List.of(candidate("A")), "tool:64", 1);
        for (var entries : List.of(List.of(finish, forge, use), List.of(use, forge, finish))) {
            // No captured nominal return is A. The exact callback closure is
            // required to discover forge -> tool:64 -> ... -> tool:21 -> A.
            eq(false, verify(entries, proposal(true, 0), true), "unlisted material producer was treated as raw");
            eq(true, verify(entries, proposal(true, 1), true), "actual material startup was refused");
            eq(true, verify(entries, proposal(false, 0), true), "ordinary hidden material proposal refused");
        }
        eq(true, verify(List.of(finish, use), proposal(true, 0), true), "unreachable hypothetical state became a producer");
    }
    private static void eq(Object wanted, Object actual, String message) {
        checks++; if (!Objects.equals(wanted, actual)) throw new AssertionError(message + ": " + wanted + " != " + actual);
    }
}
