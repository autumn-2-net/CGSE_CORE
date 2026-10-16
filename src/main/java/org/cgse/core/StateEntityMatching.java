// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.ToIntBiFunction;

/** Owned physical entities shared by slots with different eligibility rules.
 * Whole-key slots branch over keys; mixed and unit slots use integral residual
 * flow. Both the assignments and augmentations survive step boundaries. This
 * is an exact one-firing allocation, not a theorem about future tool choices. */
final class StateEntityMatching<K> implements AutoCloseable {
    private final PlanningBudget budget;
    private final List<StateEntityAllocation.Demand> demands;
    private final List<StateEntityAllocation.Supply<K>> supplies;
    private final List<StateEntityAllocation.Pick<K>> result = new ArrayList<>();
    private final boolean[] allowed;
    private final long[] flow, left;
    private final BigInteger[] remaining;
    private final int[] chosen, next, parentDemand, parentSupply, queue;
    private final int[] priority;
    private final int fixed, width;
    private int depth, unmet, failedSlot;
    private long memory;
    private boolean done, feasible, flowing;

    static <K> StateEntityMatching<K> create(List<StateEntityAllocation.Demand> demands,
            List<StateEntityAllocation.Supply<K>> supplies,
            BiPredicate<StateEntityAllocation.Demand, K> eligible, PlanningBudget budget) {
        return create(demands, supplies, eligible, null, budget);
    }

    static <K> StateEntityMatching<K> create(List<StateEntityAllocation.Demand> demands,
            List<StateEntityAllocation.Supply<K>> supplies, BiPredicate<StateEntityAllocation.Demand, K> eligible,
            ToIntBiFunction<StateEntityAllocation.Demand, K> preference, PlanningBudget budget) {
        long edges = (long) demands.size() * supplies.size();
        if (edges > Integer.MAX_VALUE - 8) return null;
        // Includes the eligibility/flow matrix and worst-case materialized picks.
        long bytes = 768L + 192L * demands.size() + (preference == null ? 224L : 256L) * supplies.size() +
                (preference == null ? 96L : 104L) * edges;
        if (!budget.tryReserve(bytes)) return preference == null ? null : create(demands, supplies, eligible, budget);
        try { return new StateEntityMatching<>(demands, supplies, eligible, preference, budget, bytes); }
        catch (RuntimeException | Error failure) { budget.release(bytes); throw failure; }
    }

    private StateEntityMatching(List<StateEntityAllocation.Demand> demands,
            List<StateEntityAllocation.Supply<K>> supplies,
            BiPredicate<StateEntityAllocation.Demand, K> eligible,
            ToIntBiFunction<StateEntityAllocation.Demand, K> preference, PlanningBudget budget, long bytes) {
        this.budget = budget; memory = bytes;
        this.demands = new ArrayList<>(demands); this.supplies = new ArrayList<>(supplies);
        this.demands.sort((a, b) -> {
            budget.check();
            int kind = Boolean.compare(!whole(a), !whole(b));
            return kind != 0 ? kind : Long.compare(b.amount(), a.amount());
        });
        this.supplies.sort((a, b) -> { budget.check(); return Long.compare(b.capacity(), a.capacity()); });
        width = supplies.size(); int count = demands.size(), indivisible = 0;
        allowed = new boolean[count * width]; flow = new long[allowed.length];
        priority = preference == null ? null : new int[allowed.length];
        int[] scores = preference == null ? null : new int[width];
        Integer[] order = preference == null ? null : new Integer[width];
        remaining = new BigInteger[width]; left = new long[count];
        BigInteger available = BigInteger.ZERO, needed = BigInteger.ZERO;
        for (int s = 0; s < width; s++) {
            budget.check(); remaining[s] = this.supplies.get(s).count(); available = available.add(remaining[s]);
        }
        for (int d = 0; d < count; d++) {
            budget.check(); var demand = this.demands.get(d);
            if (whole(demand)) indivisible++;
            needed = needed.add(BigInteger.valueOf(demand.amount()));
            left[d] = demand.amount();
            for (int s = 0; s < width; s++) {
                budget.check(); allowed[d * width + s] = eligible.test(demand, this.supplies.get(s).state());
                if (scores != null) {
                    scores[s] = allowed[d * width + s] ? preference.applyAsInt(demand, this.supplies.get(s).state()) : Integer.MAX_VALUE;
                    order[s] = s;
                }
            }
            if (scores != null) {
                // Stable per-slot edge order is only a search preference. All
                // allowed edges remain available to branches and augmentations.
                Arrays.sort(order, (a, b) -> { budget.check(); return Integer.compare(scores[a], scores[b]); });
                for (int s = 0; s < width; s++) { budget.check(); priority[d * width + s] = order[s]; }
            }
        }
        fixed = indivisible; unmet = fixed;
        chosen = new int[fixed]; next = new int[fixed]; Arrays.fill(chosen, -1);
        parentDemand = new int[count]; parentSupply = new int[width]; queue = new int[count];
        failedSlot = count == 0 ? -1 : this.demands.get(0).slot();
        if (needed.compareTo(available) > 0) done = true;
    }

    private static boolean whole(StateEntityAllocation.Demand demand) { return !demand.mixed() && demand.amount() > 1; }

    /** One branch/undo or integral augmentation; quantities never expand into entities. */
    boolean step() {
        budget.checkpoint();
        if (done) return true;
        budget.check();
        if (!flowing) {
            if (depth == fixed) { flowing = true; unmet = fixed; return false; }
            var demand = demands.get(depth);
            if (next[depth] == width) {
                next[depth] = 0; failedSlot = demand.slot();
                if (depth == 0) { done = true; return true; }
                undoFixed(); return false;
            }
            int ordinal = next[depth]++;
            int s = priority == null ? ordinal : priority[depth * width + ordinal];
            BigInteger use = BigInteger.valueOf(demand.amount());
            if (!allowed[depth * width + s] || remaining[s].compareTo(use) < 0) return false;
            remaining[s] = remaining[s].subtract(use); chosen[depth++] = s;
            return false;
        }
        while (unmet < demands.size() && left[unmet] == 0) { budget.check(); unmet++; }
        if (unmet == demands.size()) {
            for (int d = 0; d < demands.size(); d++) {
                budget.check(); var demand = demands.get(d);
                if (d < fixed) result.add(new StateEntityAllocation.Pick<>(demand.slot(), supplies.get(chosen[d]).state(), demand.amount()));
                else for (int s = 0; s < width; s++) {
                    budget.check(); long use = flow[d * width + s];
                    if (use > 0) result.add(new StateEntityAllocation.Pick<>(demand.slot(), supplies.get(s).state(), use));
                }
            }
            feasible = done = true; return true;
        }
        if (augment(unmet)) return false;
        failedSlot = demands.get(unmet).slot();
        if (fixed == 0) { done = true; return true; }
        // A failed flow only rejects this whole-key assignment. Restore its
        // residual inventory and continue the retained indivisible search.
        for (int d = fixed; d < demands.size(); d++) {
            budget.check(); left[d] = demands.get(d).amount();
            for (int s = 0; s < width; s++) {
                budget.check(); int edge = d * width + s;
                if (flow[edge] > 0) remaining[s] = remaining[s].add(BigInteger.valueOf(flow[edge]));
                flow[edge] = 0;
            }
        }
        flowing = false; undoFixed(); return false;
    }

    private void undoFixed() {
        depth--;
        int s = chosen[depth]; remaining[s] = remaining[s].add(BigInteger.valueOf(demands.get(depth).amount()));
        chosen[depth] = -1;
    }

    private boolean augment(int root) {
        Arrays.fill(parentDemand, -1); Arrays.fill(parentSupply, -1);
        int head = 0, tail = 0; queue[tail++] = root; parentDemand[root] = -2;
        while (head < tail) {
            int d = queue[head++];
            for (int ordinal = 0; ordinal < width; ordinal++) {
                int s = priority == null ? ordinal : priority[d * width + ordinal];
                budget.check();
                if (!allowed[d * width + s] || parentSupply[s] != -1) continue;
                parentSupply[s] = d;
                if (remaining[s].signum() > 0) {
                    long use = remaining[s].min(BigInteger.valueOf(left[root])).longValueExact();
                    for (int v = d; v != root; ) {
                        budget.check(); int previous = parentDemand[v];
                        use = Math.min(use, flow[v * width + previous]); v = parentSupply[previous];
                    }
                    remaining[s] = remaining[s].subtract(BigInteger.valueOf(use));
                    int to = s;
                    for (int v = d; ; ) {
                        budget.check(); flow[v * width + to] = Math.addExact(flow[v * width + to], use);
                        if (v == root) break;
                        to = parentDemand[v]; flow[v * width + to] -= use; v = parentSupply[to];
                    }
                    left[root] -= use; return true;
                }
                for (int other = fixed; other < demands.size(); other++) {
                    budget.check();
                    if (parentDemand[other] != -1 || flow[other * width + s] == 0) continue;
                    parentDemand[other] = s; queue[tail++] = other;
                }
            }
        }
        return false;
    }

    boolean feasible() { return done && feasible; }
    int failedSlot() { return failedSlot; }
    List<StateEntityAllocation.Pick<K>> result() { if (!feasible()) throw new IllegalStateException("No matching witness"); return result; }

    @Override public void close() {
        demands.clear(); supplies.clear(); result.clear(); Arrays.fill(remaining, null);
        budget.release(memory); memory = 0; done = true;
    }
}
