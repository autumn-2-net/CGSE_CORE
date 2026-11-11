// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.lang.reflect.Field;
import java.util.*;

/** Completed pruning proofs survive the coordinator publication cutoff. */
public final class RefillPruningRetentionTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 32L << 20, () -> false, System::nanoTime); }
    private static Object get(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value);
    }
    private static GraphRecipe<String> recipe(String id, String input, String output) {
        return new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>(input, 1)), Map.of(output, 1L));
    }
    private static void retained(GraphMode mode) throws Exception {
        var grow = new GraphRecipe<String>("make", "make", List.of(new GraphRecipe.Slot<>("raw", 1), new GraphRecipe.Slot<>("A", 1)), Map.of("A", 2L));
        var finish = new GraphRecipe<String>("finish", "finish", List.of(new GraphRecipe.Slot<>("A", 2)), Map.of("T", 1L));
        var recipes = List.of(grow, finish);
        var bindings = new LinkedHashMap<String, GraphRecipe<String>>(); for (var r : recipes) bindings.put(r.id(), r);
        var old = new GraphPlan<>("T", 1, false,
                new PlanStep.Sequence(List.of(new PlanStep.Batch("make", 3), new PlanStep.Batch("finish", 1))),
                bindings, Map.of("raw", 3L, "A", 1L), Map.of(), Map.of("raw", 3L, "A", 1L), GraphPlan.Result.MISSING_INPUT, 0, 0);
        old.markNetProductionVerified();
        var compiler = new GraphCompiler<>(recipes, mode, new GraphCompiler<>(recipes).verificationCatalog());
        var b = budget();
        var parent = new GraphPlanningWork<>(compiler, "T", 1, Map.of(), Set.of(), Map.of(), false, true, b, mode);
        try (var expansion = new MissingMaterialExpansion<>(compiler, old, Map.of(), Set.of(), Set.of(), true, false, b)) {
            while (!expansion.step()) {}
            var improved = expansion.result();
            check(improved.patternTimes().equals(Map.of("make", 1L, "finish", 1L)), "pruning fixture did not improve");
            check(improved.missing().equals(Map.of("raw", 1L, "A", 1L)), "pruning bill");
            set(parent, "missingExpansion", expansion); set(parent, "verified", old); set(parent, "productionVerified", old);
            try { b.charge(b.remainingWork() + 1); } catch (PlanningBudget.Exhausted expected) { }
            check(parent.step(), "exhausted parent did not complete");
            var result = parent.result();
            check(result.missing().equals(Map.of("raw", 1L, "A", 1L)), "parent cutoff lost verified pruning improvement");
            check(result.patternTimes().equals(improved.patternTimes()), "parent changed improved manufacturing word");
            check(improved.netProductionVerified(), "completed pruning lost its production certificate");
        }
        finally { parent.close(); }
        check(b.reservedBytes() == 0, "pruning parent leaked memory");
    }
    private static void unverifiedAndLowNet() throws Exception {
        var r = new GraphRecipe<String>("make", "make", List.of(new GraphRecipe.Slot<>("raw", 1), new GraphRecipe.Slot<>("A", 1)), Map.of("A", 2L));
        var old = new GraphPlan<>("T", 1, false, new PlanStep.Batch("make", 3), Map.of("make", r),
                Map.of("raw", 3L, "A", 1L, "T", 1L), Map.of(), Map.of("raw", 3L, "A", 1L), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var b = budget();
        try (var pruning = new RefillWordPruning<>(old, Map.of("T", 1L), Set.of(), false, false, b, 8192)) {
            boolean sawUnverified = false;
            while (true) {
                if (get(pruning, "candidate") instanceof GraphPlan<?> candidate && get(pruning, "phase").toString().equals("VERIFY")) {
                    sawUnverified = true;
                    check(!candidate.netProductionVerified(), "unverified candidate was certified");
                }
                if (pruning.step()) break;
            }
            check(sawUnverified, "missing pending-verification boundary");
            check(!pruning.result().netProductionVerified(), "target-stock delivery with zero net production certified");
        }
        check(b.reservedBytes() == 0, "low-net pruning leaked memory");
    }
    public static void main(String[] args) throws Exception {
        retained(GraphMode.FULL); unverifiedAndLowNet();
        System.out.println("RefillPruningRetentionTest passed");
    }
}
