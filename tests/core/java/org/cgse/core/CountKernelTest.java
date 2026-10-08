// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Independent finite-box oracle for exact affine restoration and optional kernel repair. */
public final class CountKernelTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}

    public static void main(String[] args) {
        int witnessed = 0, kernels = 0, impossible = 0;
        for (int seed = 0; seed < 480; seed++) {
            Model model = model(seed);
            boolean feasible = enumerate(model, model.low.clone(), 0);
            if (!feasible) impossible++;
            var budget = budget(64L << 20);
            try (var search = new CountAffineLattice(model.rows, model.low, model.high, budget)) {
                while (!search.step()) {}
                if (search.counts() != null) {
                    check(feasible && valid(model, search.counts()), "Invalid original-coordinate witness: " + seed);
                    witnessed++;
                }
            }
            if (budget.diagnostics().contains("count_kernel@")) kernels++;
            check(budget.reservedBytes() == 0, "Affine workspace leaked");
            // A missed heuristic point must still reach the retained proving
            // backend. Check both directions against the exhaustive oracle.
            budget = budget(64L << 20);
            try (var search = new CountConditionalSearch(model.rows, model.low, model.high, budget, 16384)) {
                do {
                    if (search.paused()) search.resume(16384);
                    while (!search.step()) {}
                } while (search.paused());
                if (search.infeasible()) check(!feasible, "Kernel restriction became a negative local proof: " + seed);
                check((search.counts() != null) == feasible, "Conditional backend disagrees with finite oracle: " + seed);
                if (search.counts() != null) check(valid(model, search.counts()), "Conditional restoration changed coordinates");
            }
            check(budget.reservedBytes() == 0, "Conditional oracle leaked");
        }
        check(kernels > 0 && witnessed > 100 && impossible > 50, "Oracle did not exercise repair and infeasible boxes");
        limitsAndCancellation();
        exhaustedResume();
        System.out.println("Kernel oracle: 480 signed/large-offset finite boxes; witnesses=" + witnessed +
                "; kernel searches=" + kernels + "; independently infeasible=" + impossible +
                "; all conditional conclusions matched; limits/cancellation passed");
    }

    private static PlanningBudget budget(long memory) {
        return new PlanningBudget(0, 4_000_000, memory, () -> false, System::nanoTime);
    }

    private static void exhaustedResume() {
        var low = new BigInteger[128];
        var high = low.clone();
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.ONE);
        var terms = new LinkedHashMap<Integer, BigInteger>();
        var reverse = new LinkedHashMap<Integer, BigInteger>();
        for (int i = 0; i < low.length; i++) { terms.put(i, BigInteger.ONE); reverse.put(i, BigInteger.ONE.negate()); }
        var rows = List.of(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(64)),
                new ExactLinearProgram.Constraint(reverse, BigInteger.valueOf(-64)));
        var budget = budget(64L << 20);
        try (var search = new CountConditionalSearch(rows, low, high, budget, 1024)) {
            while (!search.step()) {}
            check(search.paused(), "No frontier at exhausted-resume boundary");
            budget.charge(budget.remainingWork());
            try { search.resume(1024); throw new AssertionError("Empty budget created an endless pause/resume loop"); }
            catch (PlanningBudget.Exhausted expected) { check(expected.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "Wrong exhausted-resume outcome"); }
        }
        check(budget.reservedBytes() == 0, "Exhausted resume leaked");
    }

    private static Model model(int seed) {
        Random random = new Random(94013 + seed);
        int n = 4 + random.nextInt(3);
        var low = new BigInteger[n];
        var high = low.clone();
        var witness = low.clone();
        for (int i = 0; i < n; i++) {
            low[i] = BigInteger.valueOf(random.nextInt(7) - 3);
            if (seed % 5 == 0) low[i] = low[i].add(BigInteger.ONE.shiftLeft(70).multiply(BigInteger.valueOf(i % 2 == 0 ? 1 : -1)));
            int width = 1 + random.nextInt(3);
            high[i] = low[i].add(BigInteger.valueOf(width));
            witness[i] = low[i].add(BigInteger.valueOf(random.nextInt(width + 1)));
        }
        List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            BigInteger rhs = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                var a = BigInteger.valueOf(random.nextInt(19) - 9);
                if (seed % 7 == 0) a = a.shiftLeft(65);
                if (a.signum() != 0) terms.put(i, a);
                rhs = rhs.add(a.multiply(witness[i]));
            }
            if (r >= 2 && seed % 3 == 0) rhs = rhs.subtract(BigInteger.ONE);
            rows.add(new ExactLinearProgram.Constraint(terms, rhs));
            if (r < 2) {
                var reverse = new LinkedHashMap<Integer, BigInteger>();
                terms.forEach((i, a) -> reverse.put(i, a.negate()));
                rows.add(new ExactLinearProgram.Constraint(reverse, rhs.negate()));
            }
        }
        Collections.shuffle(rows, random);
        return new Model(rows, low, high);
    }

    private static void limitsAndCancellation() {
        boolean cancelled = false;
        for (int seed = 0; seed < 100 && !cancelled; seed++) {
            Model model = model(seed);
            var budget = budget(64L << 20);
            var search = new CountAffineLattice(model.rows, model.low, model.high, budget);
            try {
                while (!search.step()) if (budget.diagnostics().contains("count_kernel@")) {
                    budget.cancel();
                    try { search.step(); throw new AssertionError("Cancellation swallowed"); }
                    catch (CancellationException expected) { cancelled = true; }
                    break;
                }
            } finally { search.close(); search.close(); }
            check(budget.reservedBytes() == 0, "Kernel cancellation leaked");
        }
        check(cancelled, "No live kernel reached cancellation check");
        for (long memory : new long[]{512, 16384, 65536, 131072, 262144, 1048576}) for (int seed = 0; seed < 16; seed++) {
            Model model = model(seed);
            var budget = budget(memory);
            try (var search = new CountAffineLattice(model.rows, model.low, model.high, budget, 4096)) {
                while (!search.step()) {}
                if (search.counts() != null) check(valid(model, search.counts()), "Memory refusal changed witness scope");
            }
            check(budget.reservedBytes() == 0, "Kernel limit leaked: " + memory);
        }
    }

    private static boolean enumerate(Model model, BigInteger[] values, int id) {
        if (id == values.length) return valid(model, values);
        for (values[id] = model.low[id]; values[id].compareTo(model.high[id]) <= 0; values[id] = values[id].add(BigInteger.ONE))
            if (enumerate(model, values, id + 1)) return true;
        return false;
    }

    private static boolean valid(Model model, BigInteger[] values) {
        for (int i = 0; i < values.length; i++) if (values[i].compareTo(model.low[i]) < 0 || values[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
