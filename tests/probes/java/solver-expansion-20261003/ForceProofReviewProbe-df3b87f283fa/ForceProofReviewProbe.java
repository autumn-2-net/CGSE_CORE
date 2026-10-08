package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Local independent one-sided oracle for the final force-crafting guard. */
public final class ForceProofReviewProbe {
    static int assertions, random, proved, rejected;
    static BigInteger b(long x) { return BigInteger.valueOf(x); }
    static void ck(boolean yes, String why) { assertions++; if (!yes) throw new AssertionError(why); }
    static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    static PlanningBudget budget() { return new PlanningBudget(0, 2_000_000, 64L << 20, () -> false, System::nanoTime); }
    static GraphPlan<String> plan(List<GraphRecipe<String>> rs, long[] ns, Map<String, BigInteger> initial, Map<String, Long> seeds, long amount) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>(); var steps = new ArrayList<PlanStep>();
        for (int i = 0; i < rs.size(); i++) { recipes.put(rs.get(i).id(), rs.get(i)); steps.add(new PlanStep.Batch(rs.get(i).id(), ns[i])); }
        return new GraphPlan<>("C", amount, !seeds.isEmpty(), new PlanStep.Sequence(steps), recipes, initial, seeds, Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }
    static PlanVerification<String> verify(GraphPlan<String> p) {
        var v = new PlanVerification<>(p, budget()); while (!v.step()) {} return v;
    }
    static ForceCraftProof<String> proof(GraphPlan<String> p, PlanVerification<String> v, Map<String, Long> mandatory, PlanningBudget budget) {
        // Retain compatibility while the root applies the independently reviewed mandatory-seed fix.
        try {
            var constructor = ForceCraftProof.class.getDeclaredConstructor(GraphPlan.class, PlanVerification.class, Map.class, PlanningBudget.class);
            return (ForceCraftProof<String>) constructor.newInstance(p, v, mandatory, budget);
        } catch (NoSuchMethodException old) {
            try {
                var constructor = ForceCraftProof.class.getDeclaredConstructor(GraphPlan.class, PlanVerification.class, PlanningBudget.class);
                return (ForceCraftProof<String>) constructor.newInstance(p, v, budget);
            } catch (ReflectiveOperationException failure) { throw unbox(failure); }
        } catch (ReflectiveOperationException failure) { throw unbox(failure); }
    }
    static RuntimeException unbox(ReflectiveOperationException failure) {
        var cause = failure.getCause(); if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error; return new RuntimeException(failure);
    }
    static boolean run(GraphPlan<String> p, Map<String, Long> mandatory) {
        var budget = budget(); boolean result;
        try (var v = verify(p); var guard = proof(p, v, mandatory, budget)) { while (!guard.step()) {} result = guard.proved(); }
        ck(budget.reservedBytes() == 0, "proof leak " + budget.reservedBytes()); return result;
    }
    static boolean counterexample(List<GraphRecipe<String>> recipes, long[] upper, int[] choice, int at, GraphPlan<String> p, Map<String, Long> mandatory) {
        if (at < choice.length) {
            for (int i = 0; i <= upper[at]; i++) { choice[at] = i; if (counterexample(recipes, upper, choice, at + 1, p, mandatory)) return true; }
            return false;
        }
        var finalAmounts = new HashMap<>(p.initialExact()); long gross = 0;
        for (int i = 0; i < recipes.size(); i++) {
            final int n = choice[i]; var recipe = recipes.get(i);
            gross += n * recipe.executionOutputs().getOrDefault("C", 0L);
            recipe.inputs().forEach((key, value) -> finalAmounts.merge(key, b(-n * value), BigInteger::add));
            recipe.outputs().forEach((key, value) -> finalAmounts.merge(key, b(n * value), BigInteger::add));
        }
        if (gross >= p.amount()) return false;
        for (var entry : finalAmounts.entrySet()) {
            long goal = mandatory.getOrDefault(entry.getKey(), 0L) + (entry.getKey().equals("C") ? p.amount() : 0);
            if (entry.getValue().compareTo(b(goal)) < 0) return false;
        }
        return true;
    }
    static boolean executableCounterexample(List<GraphRecipe<String>> recipes, long[] upper, int[] used, Map<String, BigInteger> stock, long gross, GraphPlan<String> p, Map<String, Long> mandatory, Set<String> visited) {
        if (gross >= p.amount() || !visited.add(Arrays.toString(used))) return false;
        BigInteger targetGoal = p.initialExact().getOrDefault("C", BigInteger.ZERO);
        for (int i = 0; i < recipes.size(); i++) targetGoal = targetGoal.add(b(upper[i]).multiply(b(recipes.get(i).outputs().getOrDefault("C", 0L) - recipes.get(i).inputs().getOrDefault("C", 0L))));
        targetGoal = targetGoal.max(b(p.amount()).add(b(mandatory.getOrDefault("C", 0L))));
        boolean delivery = stock.getOrDefault("C", BigInteger.ZERO).compareTo(targetGoal) >= 0;
        for (var seed : mandatory.entrySet()) if (stock.getOrDefault(seed.getKey(), BigInteger.ZERO).compareTo(b(seed.getValue())) < 0) delivery = false;
        if (delivery) return true;
        for (int i = 0; i < recipes.size(); i++) {
            if (used[i] >= upper[i]) continue; var recipe = recipes.get(i); boolean enabled = true;
            for (var input : recipe.inputs().entrySet()) if (stock.getOrDefault(input.getKey(), BigInteger.ZERO).compareTo(b(input.getValue())) < 0) enabled = false;
            if (!enabled) continue;
            var next = new HashMap<>(stock);
            recipe.inputs().forEach((key, value) -> next.merge(key, b(-value), BigInteger::add));
            recipe.outputs().forEach((key, value) -> next.merge(key, b(value), BigInteger::add));
            used[i]++;
            boolean found = executableCounterexample(recipes, upper, used, next, gross + recipe.executionOutputs().getOrDefault("C", 0L), p, mandatory, visited);
            used[i]--; if (found) return true;
        }
        return false;
    }
    static GraphPlan<String> original() {
        return plan(List.of(r("out", Map.of("C", 1L), Map.of("A", 1L)), r("main", Map.of("A", 2L, "B", 3L), Map.of("A", 1L, "C", 3L))),
                new long[]{1, 1}, Map.of("A", b(1), "B", b(3), "C", b(1)), Map.of(), 3);
    }
    static void directed() {
        ck(run(original(), Map.of()), "original C3 bootstrap rejected");
        var rs = List.of(r("make", Map.of("A", 1L, "raw", 1L), Map.of("A", 1L, "C", 1L)), r("out", Map.of("C", 1L), Map.of("A", 1L, "P", 1L)), r("turn", Map.of("A", 1L), Map.of("C", 1L)));
        var initial = Map.of("A", b(1), "C", b(1), "raw", b(1));
        var plain = plan(rs, new long[]{1, 1, 1}, initial, Map.of(), 2);
        ck(!run(plain, Map.of()), "gratuitous coproduct turnover accepted");
        var autoSeed = plan(rs, new long[]{1, 1, 1}, initial, Map.of("P", 1L), 2);
        System.out.println("automatic-seed-candidate accepted=" + run(autoSeed, Map.of()) + "; mandated-seed accepted=" + run(autoSeed, Map.of("P", 1L)));
        ck(!run(autoSeed, Map.of()), "automatic candidate seed makes gratuitous bonus falsely necessary");
        ck(run(autoSeed, Map.of("P", 1L)), "explicit seed request should retain mandatory P");
        ck(!run(plan(rs, new long[]{1, 1, 1}, Map.of("A", b(1), "C", b(2), "raw", b(1)), Map.of(), 3), Map.of()), "gross below force amount accepted");
    }
    static void oracle() {
        var rng = new Random(9638127); String[] keys = {"A", "B", "C"};
        for (int test = 0; test < 800; test++) {
            var rs = new ArrayList<GraphRecipe<String>>(); int count = 1 + rng.nextInt(4); var ns = new long[count];
            for (int i = 0; i < count; i++) {
                var in = new LinkedHashMap<String, Long>(); var out = new LinkedHashMap<String, Long>();
                for (String key : keys) { long used = rng.nextInt(3), made = rng.nextInt(4); if (used > 0) in.put(key, used); if (made > 0) out.put(key, made); }
                if (out.isEmpty()) out.put("C", 1L); rs.add(r("r" + i, in, out)); ns[i] = 1 + rng.nextInt(2);
            }
            var p = plan(rs, ns, Map.of(), Map.of(), 1); var summary = SequenceSummary.of(p.steps(), p.recipes());
            long amount = 1 + rng.nextInt(8); var initial = new LinkedHashMap<String, BigInteger>();
            for (String key : keys) initial.put(key, summary.required(key).max(b(key.equals("C") ? amount : 0).subtract(summary.delta(key))).add(b(rng.nextInt(2))));
            p = plan(rs, ns, initial, Map.of(), amount);
            try (var verified = verify(p)) {
                BigInteger net = verified.summary().delta("C"), gross = verified.physicalProduced("C");
                boolean accepted = run(p, Map.of());
                if (gross.compareTo(b(amount)) < 0 || net.signum() <= 0) ck(!accepted, "random trivial bad candidate=" + test);
                else if (net.compareTo(b(amount)) >= 0) ck(accepted, "random sufficiently productive candidate=" + test);
                else {
                    random++; boolean alternative = executableCounterexample(rs, ns, new int[count], p.initialExact(), 0, p, Map.of(), new HashSet<>());
                    if (accepted) { proved++; ck(!alternative, "false necessary-production proof=" + test + " recipes=" + rs + " counts=" + Arrays.toString(ns) + " initial=" + initial + " amount=" + amount); } else rejected++;
                }
            }
        }
        System.out.println("random-models=800 bootstrap-proof-cases=" + random + " proved=" + proved + " rejected-or-unresolved=" + rejected);
    }
    static void cleanup() {
        var p = original();
        try (var verified = verify(p)) {
            for (int cutoff : new int[]{1, 2, 4, 8, 16, 32, 64, 128}) {
                var calls = new AtomicInteger(); var budget = new PlanningBudget(0, 2_000_000, 64L << 20, () -> calls.incrementAndGet() > cutoff, System::nanoTime);
                try (var guard = proof(p, verified, Map.of(), budget)) { while (!guard.step()) {} } catch (CancellationException expected) {}
                ck(budget.reservedBytes() == 0, "cancel proof leak cutoff=" + cutoff + " bytes=" + budget.reservedBytes());
            }
            for (long memory : new long[]{1, 1024, 2048, 4096, 8192, 16384, 65536}) {
                var budget = new PlanningBudget(0, 2_000_000, memory, () -> false, System::nanoTime);
                try (var guard = proof(p, verified, Map.of(), budget)) { while (!guard.step()) {} } catch (PlanningBudget.Exhausted expected) {}
                ck(budget.reservedBytes() == 0, "low-memory proof leak cap=" + memory + " bytes=" + budget.reservedBytes());
            }
            var budget = budget();
            try (var guard = proof(p, verified, Map.of(), budget)) {
                Thread.currentThread().interrupt();
                try { guard.step(); throw new AssertionError("proof ignored interrupt"); }
                catch (CancellationException expected) {} finally { Thread.interrupted(); }
            }
            ck(budget.reservedBytes() == 0, "interrupted proof leak=" + budget.reservedBytes());
        }
    }
    public static void main(String[] args) { directed(); oracle(); cleanup(); System.out.println("PASS independent final-force proof assertions=" + assertions); }
}
