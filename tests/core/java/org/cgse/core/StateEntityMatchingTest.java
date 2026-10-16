// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent small-integer allocation oracle, including nonuniform eligibility. */
public final class StateEntityMatchingTest {
    private static long checks;
    private static int cases;

    public static void main(String[] args) {
        exhaustive(2, 3, 2, 3);
        exhaustive(3, 2, 3, 2);
        var random = new Random(0x51a7eL);
        for (int i = 0; i < 1500; i++) {
            int[] stock = new int[3], need = new int[3];
            for (int j = 0; j < 3; j++) { stock[j] = 1 + random.nextInt(4); need[j] = 1 + random.nextInt(4); }
            compare(stock, need, random.nextInt(8), random.nextInt(512), (i & 1) == 0);
        }
        boundaries(); subsetPruning(); retainedRelaxation();
        System.out.println("StateEntityMatchingTest: cases=" + cases + "; assertions=" + checks);
    }

    private static void exhaustive(int demands, int supplies, int maxStock, int maxNeed) {
        for (int stockCode = 0; stockCode < pow(maxStock, supplies); stockCode++) {
            int[] stock = decode(stockCode, maxStock, supplies);
            for (int needCode = 0; needCode < pow(maxNeed, demands); needCode++) {
                int[] need = decode(needCode, maxNeed, demands);
                for (int mixed = 0; mixed < (1 << demands); mixed++)
                    for (int edges = 0; edges < (1 << (demands * supplies)); edges++)
                        compare(stock, need, mixed, edges, (edges & 1) == 0);
            }
        }
    }

    private static void compare(int[] stock, int[] need, int mixed, int edges, boolean reverse) {
        boolean expected = assignSlot(0, stock.clone(), need, mixed, edges);
        var demands = new ArrayList<StateEntityAllocation.Demand>();
        var supplies = new ArrayList<StateEntityAllocation.Supply<Integer>>();
        for (int d = 0; d < need.length; d++) demands.add(new StateEntityAllocation.Demand(d, need[d], (mixed & (1 << d)) != 0));
        for (int s = 0; s < stock.length; s++) supplies.add(new StateEntityAllocation.Supply<>(s, BigInteger.valueOf(stock[s]), s + 1));
        if (reverse) { Collections.reverse(demands); Collections.reverse(supplies); }
        var budget = budget();
        try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> edge(edges, stock.length, d.slot(), s), budget)) {
            check(work != null, "Small matching rejected");
            int steps = 0;
            while (!work.step()) check(++steps < 20_000, "Nonterminating continuation");
            check(work.feasible() == expected, "Oracle mismatch: stock=" + Arrays.toString(stock) + "; need=" + Arrays.toString(need) +
                    "; mixed=" + mixed + "; edges=" + edges);
            if (expected) {
                check(work.deficit() == null, "Feasible allocation exported a deficit");
                long[] spent = new long[stock.length], filled = new long[need.length];
                int[] key = new int[need.length]; Arrays.fill(key, -1);
                for (var pick : work.result()) {
                    int d = pick.slot(), s = pick.state();
                    check(pick.amount() > 0 && edge(edges, stock.length, d, s), "Ineligible entity");
                    check((mixed & (1 << d)) != 0 || key[d] < 0 || key[d] == s, "Split an indivisible slot");
                    key[d] = s; spent[s] += pick.amount(); filled[d] += pick.amount();
                }
                for (int s = 0; s < stock.length; s++) check(spent[s] <= stock[s], "Shared a physical entity twice");
                for (int d = 0; d < need.length; d++) check(filled[d] == need[d], "Unfilled slot");
            } else {
                check(work.failedSlot() >= 0 && work.failedSlot() < need.length, "Lost allocation failure scope");
                var deficit = work.deficit();
                boolean relaxed = assignSlot(0, stock.clone(), need, (1 << need.length) - 1, edges);
                check((deficit == null) == relaxed, "Lost subset deficit or exported a whole-key branch failure");
                if (deficit != null) {
                    // Reconstruct the certificate using only original input
                    // eligibility and quantities, independently of residual flow.
                    long required = 0, available = 0;
                    var slots = new java.util.HashSet<>(deficit.slots());
                    check(!slots.isEmpty() && slots.size() == deficit.slots().size(), "Invalid deficit slots");
                    for (int d : slots) { check(d >= 0 && d < need.length, "Invented slot"); required += need[d]; }
                    for (int s = 0; s < stock.length; s++) {
                        boolean neighbor = false;
                        for (int d : slots) neighbor |= edge(edges, stock.length, d, s);
                        if (neighbor) available += stock[s];
                    }
                    check(required > available, "Certificate is not a deficient subset");
                    check(deficit.required().equals(BigInteger.valueOf(required)) && deficit.available().equals(BigInteger.valueOf(available)),
                            "Certificate changed original counts");
                }
            }
        }
        check(budget.reservedBytes() == 0, "Matching workspace leak"); cases++;
    }

    // Enumerate literal finite allocations, without residual flow or matching.
    private static boolean assignSlot(int slot, int[] left, int[] need, int mixed, int edges) {
        if (slot == need.length) return true;
        if ((mixed & (1 << slot)) != 0) return split(slot, 0, need[slot], left, need, mixed, edges);
        for (int s = 0; s < left.length; s++) if (edge(edges, left.length, slot, s) && left[s] >= need[slot]) {
            left[s] -= need[slot]; boolean ok = assignSlot(slot + 1, left, need, mixed, edges); left[s] += need[slot];
            if (ok) return true;
        }
        return false;
    }

    private static boolean split(int slot, int s, int remaining, int[] left, int[] need, int mixed, int edges) {
        if (remaining == 0) return assignSlot(slot + 1, left, need, mixed, edges);
        if (s == left.length) return false;
        int max = edge(edges, left.length, slot, s) ? Math.min(left[s], remaining) : 0;
        for (int use = 0; use <= max; use++) {
            left[s] -= use; boolean ok = split(slot, s + 1, remaining - use, left, need, mixed, edges); left[s] += use;
            if (ok) return true;
        }
        return false;
    }

    private static void boundaries() {
        var demands = List.of(new StateEntityAllocation.Demand(0, Long.MAX_VALUE, true),
                new StateEntityAllocation.Demand(1, Long.MAX_VALUE, true));
        var supplies = List.of(new StateEntityAllocation.Supply<>("a", BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), 1),
                new StateEntityAllocation.Supply<>("b", BigInteger.ONE, Long.MAX_VALUE));
        var budget = budget();
        try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> d.slot() == 0 || s.equals("a"), budget)) {
            int steps = 0; while (!work.step()) check(++steps < 20, "Expanded quantities into physical instances");
            check(work.feasible(), "BigInteger supply / long demand overflow");
        }
        check(budget.reservedBytes() == 0, "Wide matching leak");
        var deficient = List.of(new StateEntityAllocation.Supply<>("a", BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), 1),
                new StateEntityAllocation.Supply<>("b", BigInteger.valueOf(Long.MAX_VALUE), 1));
        for (boolean mixed : List.of(false, true)) {
            var wide = List.of(new StateEntityAllocation.Demand(0, Long.MAX_VALUE, mixed),
                    new StateEntityAllocation.Demand(1, Long.MAX_VALUE, mixed));
            try (var work = StateEntityMatching.create(wide, deficient, (d, s) -> s.equals("b"), budget)) {
                while (!work.step()) {}
                var deficit = work.deficit();
                check(deficit != null && deficit.required().compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
                        "Wide deficit overflow");
                check(deficit.available().equals(BigInteger.valueOf(Long.MAX_VALUE)), "Counted an ineligible large supply");
            }
        }
        var indivisible = List.of(new StateEntityAllocation.Demand(0, 2, false));
        var split = List.of(new StateEntityAllocation.Supply<>("a", BigInteger.ONE, 1),
                new StateEntityAllocation.Supply<>("b", BigInteger.ONE, 1));
        try (var work = StateEntityMatching.create(indivisible, split, (d, s) -> true, budget)) {
            while (!work.step()) {}
            check(!work.feasible() && work.deficit() == null, "Whole-key failure became a false Hall deficit");
        }
        check(budget.reservedBytes() == 0, "Deficit workspace leaked");
        // Uniform count is sufficient, but the subset of eligible keys is not.
        compare(new int[]{2, 3}, new int[]{2, 2}, 3, 5, false);
        // First whole-key choice must be undone to leave the only eligible key
        // for the mixed demand. This exercises flow rollback into the DFS.
        compare(new int[]{3, 3}, new int[]{2, 2}, 2, 11, false);
        var tiny = new PlanningBudget(0, 100_000, 1, () -> false, System::nanoTime);
        check(StateEntityMatching.create(demands, supplies, (d, s) -> true, tiny) == null, "Ignored memory cap");
        check(tiny.reservedBytes() == 0, "Rejected workspace leaked");
        var cancel = new AtomicBoolean();
        var cancellable = new PlanningBudget(0, 100_000, 1 << 20, cancel::get, System::nanoTime);
        try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> true, cancellable)) {
            work.step(); cancel.set(true);
            try { work.step(); throw new AssertionError("Ignored cancellation"); } catch (CancellationException expected) { checks++; }
        }
        check(cancellable.reservedBytes() == 0, "Cancelled continuation leak");
        var interrupted = budget();
        try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> true, interrupted)) { work.step(); }
        check(interrupted.reservedBytes() == 0, "Abandoned continuation leak");
    }

    private static void subsetPruning() {
        // Total supply 24 equals demand 24, but eleven whole-key slots need
        // 22 entities from only ten two-entity keys. The two surplus entities
        // accepted by the last slot cannot fill this deficient subset.
        for (boolean reverse : List.of(false, true)) for (boolean prefer : List.of(false, true)) {
            var demands = new ArrayList<StateEntityAllocation.Demand>();
            var supplies = new ArrayList<StateEntityAllocation.Supply<Integer>>();
            for (int d = 0; d < 12; d++) demands.add(new StateEntityAllocation.Demand(d, 2, false));
            for (int s = 0; s < 11; s++) supplies.add(new StateEntityAllocation.Supply<>(s, BigInteger.valueOf(s == 10 ? 4 : 2), 1));
            if (reverse) { Collections.reverse(demands); Collections.reverse(supplies); }
            var budget = new PlanningBudget(0, 20_000, 1 << 20, () -> false, System::nanoTime);
            try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> d.slot() == 11 ? s == 10 : s < 10,
                    prefer ? (d, s) -> s : null, budget)) {
                int steps = 0;
                while (!work.step()) check(++steps < 100, "Enumerated permutations of a deficient subset");
                var deficit = work.deficit();
                check(!work.feasible() && deficit != null, "Missed the eleven-slot shortage");
                check(new java.util.HashSet<>(deficit.slots()).equals(new java.util.HashSet<>(java.util.stream.IntStream.range(0, 11).boxed().toList())),
                        "Included the unrelated supply domain");
                check(deficit.required().equals(BigInteger.valueOf(22)) && deficit.available().equals(BigInteger.valueOf(20)),
                        "Invalid whole-key subset certificate");
            }
            check(budget.reservedBytes() == 0, "Relaxed subset workspace leak"); cases++;
        }
        // A splittable relaxation can succeed while the exact packing cannot.
        // Eligibility is deliberately nonuniform so the relaxation is tried.
        compare(new int[]{1, 1, 2}, new int[]{2, 2}, 0, 35, false);
    }

    private static void retainedRelaxation() {
        for (boolean mixed : List.of(false, true)) {
            var demands = List.of(new StateEntityAllocation.Demand(0, 2, false), new StateEntityAllocation.Demand(1, 2, mixed));
            var supplies = List.of(new StateEntityAllocation.Supply<>(0, BigInteger.TWO, 1), new StateEntityAllocation.Supply<>(1, BigInteger.TWO, 1));
            // First whole-key choice takes key 0, starving the second slot.
            // Relaxation succeeds after either a whole-key or mixed-flow dead
            // end. Exact search must resume and move the first slot to key 1.
            var budget = budget();
            try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> d.slot() == 0 || s == 0, (d, s) -> s, budget)) {
                while (!work.step()) check(work.deficit() == null, "Partial matching exported a fact");
                check(work.feasible() && work.deficit() == null, "Relaxation destroyed retained choices");
                for (var pick : work.result()) check(pick.state() == 1 - pick.slot() && pick.amount() == 2, "Used relaxed fragments as an exact witness");
            }
            check(budget.reservedBytes() == 0, "Resumed relaxation workspace leak");
            // Sweep work/cancellation boundaries through constructor, exact
            // branching, relaxed flow and restoration. Interrupted work must
            // not export a deficient subset or escape ownership accounting.
            for (int limit = 1; limit <= 160; limit++) {
                var capped = new PlanningBudget(0, limit, 1 << 20, () -> false, System::nanoTime);
                try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> d.slot() == 0 || s == 0, capped)) {
                    while (!work.step()) {}
                    check(work.feasible() && work.deficit() == null, "Budget created a negative result");
                } catch (PlanningBudget.Exhausted expected) { /* No finished proof was returned. */ }
                // The rejecting check itself is charged before Exhausted.
                check(capped.searchWork() <= limit + 1L && capped.reservedBytes() == 0,
                        "Relaxation renewed a cap or leaked: limit=" + limit + "; work=" + capped.searchWork() + "; bytes=" + capped.reservedBytes());
            }
            for (int at = 0; at < 16; at++) {
                var cancelled = new AtomicBoolean();
                var cancellable = new PlanningBudget(0, 100_000, 1 << 20, cancelled::get, System::nanoTime);
                try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> d.slot() == 0 || s == 0, cancellable)) {
                    for (int step = 0; step < at && !work.step(); step++) {}
                    cancelled.set(true);
                    try { work.step(); throw new AssertionError("Ignored relaxed-search cancellation"); } catch (CancellationException expected) {}
                }
                check(cancellable.reservedBytes() == 0, "Cancelled relaxation workspace leak");
            }
        }
    }

    private static boolean edge(int edges, int width, int d, int s) { return (edges & (1 << (d * width + s))) != 0; }
    private static int pow(int n, int p) { int result = 1; for (int i = 0; i < p; i++) result *= n; return result; }
    private static int[] decode(int code, int radix, int count) {
        int[] result = new int[count]; for (int i = 0; i < count; i++) { result[i] = 1 + code % radix; code /= radix; } return result;
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 16 << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
