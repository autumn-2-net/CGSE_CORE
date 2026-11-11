// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exact lower proposals must preserve the old projection after row/physical rejection. */
public final class CountDemandExactLowerTest {
    private static void check(boolean okay, String reason) { if (!okay) throw new AssertionError(reason); }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 2_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static GraphPlan<String> run(GraphCompiler<String> compiler, long amount, Map<String, Long> stock,
                                          Map<String, Long> reserve, PlanningBudget budget) {
        try (var work = new CountDemandSearch<>(compiler, "T", amount, stock, Set.of(), reserve, Set.of(), false, true,
                budget, System.nanoTime()).structuralOnly(1_000_000)) {
            while (!work.step()) {}
            var plan = work.result();
            check(plan != null && plan.feasible(), "Structural candidate missing: " + budget.diagnostics());
            PlanVerifier.verify(plan);
            return plan;
        } finally { check(budget.reservedBytes() == 0, "Exact lower proposal workspace leaked"); }
    }
    private static void huge() {
        var recipes = List.of(r("a", Map.of(), Map.of("A", 1L, "shared", 1L)),
                r("b", Map.of(), Map.of("B", 1L, "shared", 1L)),
                r("finish", Map.of("A", 1L, "B", 100L, "shared", 101L), Map.of("T", 1L)));
        for (var mode : GraphMode.values()) for (boolean reserved : List.of(false, true)) {
            long q = Long.MAX_VALUE / 200; var reserve = reserved ? Map.of("A", 1L) : Map.<String, Long>of();
            var original = new GraphCompiler<>(recipes);
            var compiler = mode == GraphMode.FULL ? original : new GraphCompiler<>(recipes, mode, original.verificationCatalog());
            var budget = budget(); var plan = run(compiler, q, Map.of(), reserve, budget);
            check(plan.patternTimesExact().equals(Map.of("finish", BigInteger.valueOf(q),
                    "a", BigInteger.valueOf(q).add(reserved ? BigInteger.ONE : BigInteger.ZERO),
                    "b", BigInteger.valueOf(q).multiply(BigInteger.valueOf(100)))), "Exact lower proposal was replaced by rounded double counts");
            check(plan.seeds().equals(reserve), "Explicit final reserve changed");
            check(budget.diagnostics().contains("count_demand_lower") && !budget.diagnostics().contains("count_demand_projection"),
                    "Feasible exact lower vector still entered numerical projection");
        }
    }
    private static void invalidRows() {
        var recipes = List.of(r("a", Map.of(), Map.of("A", 1L)), r("b", Map.of(), Map.of("A", 1L)),
                r("finish", Map.of("A", 1L), Map.of("T", 1L)));
        var budget = budget(); var plan = run(new GraphCompiler<>(recipes), 1, Map.of(), Map.of(), budget);
        check(plan.patternTimesExact().getOrDefault("a", BigInteger.ZERO).add(plan.patternTimesExact().getOrDefault("b", BigInteger.ZERO)).equals(BigInteger.ONE),
                "Original projection could not recover after invalid lower rows");
        check(budget.diagnostics().contains("original_rows_not_satisfied") && budget.diagnostics().contains("count_demand_projection"),
                "Invalid lower vector suppressed the old projection");
    }
    private static void physicalRejection() {
        // Net rows permit turn=finish=1, seed=0; no recipe can start there.
        // The original projection/startup refinement must still get its turn.
        var recipes = List.of(r("seed", Map.of("raw", 1L), Map.of("A", 1L)),
                r("turn", Map.of("A", 1L), Map.of("B", 1L)),
                r("finish", Map.of("B", 1L), Map.of("A", 1L, "T", 1L)));
        var budget = budget(); var plan = run(new GraphCompiler<>(recipes), 1, Map.of("raw", 1L), Map.of(), budget);
        check(plan.patternTimesExact().equals(Map.of("seed", BigInteger.ONE, "turn", BigInteger.ONE, "finish", BigInteger.ONE)),
                "Rejected net-only lower vector erased the physical startup alternative");
        check(budget.diagnostics().contains("physical_candidate_rejected") && budget.diagnostics().contains("count_demand_projection"),
                "Physical rejection did not resume the existing projection");
    }
    private static void optionalMemoryDecline() {
        var recipes = List.of(r("source", Map.of(), Map.of("T", 1L)));
        var memory = new PlanningMemory(64L << 20);
        var refused = new java.util.concurrent.atomic.AtomicBoolean();
        var restored = new java.util.concurrent.atomic.AtomicBoolean();
        var budget = new PlanningBudget(0, 2_000_000, 64L << 20, () -> {
            // Simulate another order filling the shared cap exactly during
            // optional bounds admission, then releasing it before the next
            // coordinator continuation. Real count/physical code stays intact.
            boolean bounds = Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getClassName().equals(CountBounds.class.getName()));
            if (bounds && refused.compareAndSet(false, true)) memory.limit(Math.max(1, memory.used()));
            else if (!bounds && refused.get() && restored.compareAndSet(false, true)) memory.limit(64L << 20);
            return false;
        }, System::nanoTime).memory(memory);
        var plan = run(new GraphCompiler<>(recipes), 1, Map.of(), Map.of(), budget);
        check(refused.get() && restored.get(), "Optional bound memory refusal was not exercised");
        check(plan.patternTimesExact().equals(Map.of("source", BigInteger.ONE)), "Memory refusal erased the old projection witness");
        check(budget.diagnostics().contains("original_rows_not_satisfied") && budget.diagnostics().contains("count_demand_projection"),
                "Refused bounds suppressed the original numerical continuation");
        check(memory.used() == 0, "Optional bound refusal leaked shared memory");
        budget.closeMemory();
    }
    private static void blockedBounds() {
        var recipes = List.of(r("finish", Map.of("raw", 1L), Map.of("T", 1L)));
        var budget = budget(); budget.failureDetail("prior owner conclusion");
        try (var work = new CountDemandSearch<>(new GraphCompiler<>(recipes), "T", 1, Map.of(), Set.of(), Map.of(),
                Set.of(), false, true, budget, System.nanoTime()).structuralOnly(1_000_000)) {
            int steps = 0;
            while (!work.step()) check(++steps < 10_000, "Blocked bounds did not hand off to the old projection");
            check(work.result() == null, "Infeasible net model invented an executable word");
            check(budget.failureDetail().equals("prior owner conclusion"), "Optional blocked bounds replaced the owner's conclusion");
            check(work.step(), "Completed blocked proposal re-entered a stale lower cursor");
        }
        check(budget.reservedBytes() == 0, "Blocked lower/projection workspace leaked");
    }
    public static void main(String[] args) {
        huge(); invalidRows(); blockedBounds(); physicalRejection(); optionalMemoryDecline();
        System.out.println("CountDemandExactLowerTest: huge exact counts, invalid-row fallback and physical-rejection continuation and recoverable memory refusal/blocked-state handoff PASS");
    }
}
