// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded explicit coverability with persistent sets, visibility and the DFS cycle proviso. */
final class ForwardCoverability<K> implements AutoCloseable {

    private static final class Stop extends RuntimeException {
        final boolean workLimit;

        Stop(boolean workLimit) {
            super(null, null, false, false);
            this.workLimit = workLimit;
        }
    }

    private static final class Frame {

        final List<BigInteger> stock;
        final PlanStep edge;
        BitSet todo;
        BitSet enabled = new BitSet();
        int action, coordinate;
        int goalCoordinate;
        boolean belowGoal;
        List<BigInteger> next;

        Frame(List<BigInteger> stock, PlanStep edge) {
            this.stock = stock;
            this.edge = edge;
        }
    }

    private final List<K> keys;
    private final List<GraphRecipe<K>> recipes;
    private final List<BackwardCoverability.Action<K>> actions;
    private final List<BigInteger> initial, goal;
    private final Deque<Frame> stack = new ArrayDeque<>();
    private final Set<List<BigInteger>> visited = new LinkedHashSet<>(), active = new HashSet<>();
    private final PlanningBudget budget;
    private final PartialOrder<K> order;
    private long allowance;
    private long work, memory, omitted, cycleExpansions;
    private long sliceStarted;
    private boolean retained, paused, closed;
    private ExecutionProof.Certificate certificate;
    private ExecutionProof.ForwardCheck checking;
    private BackwardCoverability.Result result;
    private PlanStep witness;

    ForwardCoverability(List<GraphRecipe<K>> recipes, List<K> keys, List<BigInteger> initial, List<BigInteger> goal,
                        List<BackwardCoverability.Action<K>> actions, PlanningBudget budget, long maximumWork) {
        this(recipes, keys, initial, goal, actions, budget, maximumWork, true);
    }

    ForwardCoverability(List<GraphRecipe<K>> recipes, List<K> keys, List<BigInteger> initial, List<BigInteger> goal,
                        List<BackwardCoverability.Action<K>> actions, PlanningBudget budget, long maximumWork,
                        boolean reduce) {
        this.recipes = recipes;
        this.keys = keys;
        this.initial = initial;
        this.goal = goal;
        this.actions = List.copyOf(actions);
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork() / 32);
        order = reduce ? new PartialOrder<>(actions.stream().map(BackwardCoverability.Action::summary).toList(), budget) : null;
        if (keys.size() > 32 || actions.size() > 64 || allowance < 1024 || recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs)) {
            result = BackwardCoverability.Result.UNKNOWN;
            return;
        }
        try {
            push(initial, null);
        } catch (Stop stopped) {
            result = BackwardCoverability.Result.UNKNOWN;
        }
    }

    boolean step() {
        if (result != null) return true;
        long before = budget.threadWork();
        sliceStarted = before;
        try {
            charge();
            if (stack.isEmpty()) return closeReachability();
            Frame frame = stack.peek();
            while (!frame.belowGoal && frame.goalCoordinate < keys.size()) {
                charge();
                if (frame.stock.get(frame.goalCoordinate).compareTo(goal.get(frame.goalCoordinate)) < 0) frame.belowGoal = true;
                frame.goalCoordinate++;
            }
            if (!frame.belowGoal) {
                var path = new ArrayList<PlanStep>();
                var frames = stack.descendingIterator();
                while (frames.hasNext()) {
                    var next = frames.next();
                    if (next.edge != null) path.add(next.edge);
                }
                witness = new PlanStep.Sequence(path);
                return finish(BackwardCoverability.Result.WITNESS);
            }
            if (frame.todo == null) frame.todo = order == null ? enabled(frame) : persistent(frame.stock);
            int id = frame.todo.nextSetBit(0);
            if (id < 0) {
                stack.pop();
                active.remove(frame.stock);
                return false;
            }
            if (frame.next == null) frame.next = new ArrayList<>(keys.size());
            while (frame.next.size() < keys.size()) {
                charge();
                int k = frame.next.size();
                frame.next.add(frame.stock.get(k).add(actions.get(id).summary().delta(keys.get(k))));
            }
            var next = List.copyOf(frame.next);
            if (!visited.contains(next)) push(next, actions.get(id).program());
            // Do not consume an edge until its successor has been retained.
            // A local stop midway through construction must not omit a firing.
            frame.todo.clear(id);
            frame.next = null;
            return false;
        } catch (Stop stopped) {
            paused = retained && stopped.workLimit;
            return finish(BackwardCoverability.Result.UNKNOWN);
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private BitSet enabled(Frame frame) {
        while (frame.action < actions.size()) {
            if (frame.coordinate == keys.size()) {
                frame.enabled.set(frame.action++);
                frame.coordinate = 0;
                continue;
            }
            charge();
            if (frame.stock.get(frame.coordinate).compareTo(actions.get(frame.action).summary().required(keys.get(frame.coordinate))) < 0) {
                frame.action++;
                frame.coordinate = 0;
            } else frame.coordinate++;
        }
        return frame.enabled;
    }

    private BitSet persistent(List<BigInteger> stock) {
        var enabled = new BitSet();
        for (int a = 0; a < actions.size(); a++) {
            boolean can = true;
            for (int k = 0; k < keys.size(); k++) {
                charge();
                if (stock.get(k).compareTo(actions.get(a).summary().required(keys.get(k))) < 0) {
                    can = false;
                    break;
                }
            }
            if (can) enabled.set(a);
        }
        // Full traversal is useful when looking for an independently replayable
        // closed boundary. A persistent-set traversal need not visit that boundary.
        if (order == null) return enabled;
        var all = new BitSet();
        all.set(0, actions.size());
        var held = new LinkedHashMap<K, BigInteger>();
        for (int k = 0; k < keys.size(); k++) held.put(keys.get(k), stock.get(k));
        BitSet reduced = order.persistent(all, enabled, held, Set.of());
        if (reduced.isEmpty() || reduced.equals(enabled)) return enabled;
        for (int a = reduced.nextSetBit(0); a >= 0; a = reduced.nextSetBit(a + 1)) {
            // Invisible transitions preserve the coverability predicate.
            for (int k = 0; k < keys.size(); k++) {
                charge();
                if (goal.get(k).signum() > 0 && actions.get(a).summary().delta(keys.get(k)).signum() != 0) return enabled;
            }
            if (active.contains(successor(stock, actions.get(a).summary()))) {
                cycleExpansions++;
                return enabled;
            }
        }
        omitted += enabled.cardinality() - reduced.cardinality();
        return reduced;
    }

    private List<BigInteger> successor(List<BigInteger> stock, SequenceSummary<K> action) {
        var next = new ArrayList<BigInteger>();
        for (int k = 0; k < keys.size(); k++) {
            charge();
            next.add(stock.get(k).add(action.delta(keys.get(k))));
        }
        return List.copyOf(next);
    }

    private void push(List<BigInteger> stock, PlanStep edge) {
        if (visited.size() >= 4096) throw new Stop(false);
        long bytes = 384L + keys.size() * 128L;
        if (!budget.tryReserve(bytes)) throw new Stop(false);
        memory += bytes;
        visited.add(stock);
        active.add(stock);
        stack.push(new Frame(stock, edge));
    }

    private boolean closeReachability() {
        if (checking == null) {
            var in = new ArrayList<List<BigInteger>>();
            var out = new ArrayList<List<BigInteger>>();
            for (var recipe : recipes) {
                in.add(keys.stream().map(key -> BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L))).toList());
                out.add(keys.stream().map(key -> BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L))).toList());
            }
            certificate = new ExecutionProof.Certificate(order == null ? "forward_original_closure" : "forward_por_original_closure",
                    ExecutionProof.Kind.FORWARD_BOUNDARY, initial, goal, in, out, List.copyOf(visited), Set.of());
            checking = new ExecutionProof.ForwardCheck(certificate);
        }
        // A reduced traversal alone is never an absence certificate. Require
        // explicit closure under all original transitions before exporting DEAD.
        long remaining = allowance - work - (budget.threadWork() - sliceStarted);
        if (remaining <= 0) throw new Stop(true);
        if (!checking.step(Math.min(1024, remaining), budget::charge)) return false;
        if (checking.result() != CountProof.Verdict.VERIFIED)
            return finish(BackwardCoverability.Result.UNKNOWN);
        if (budget.proofJournal() != null) budget.proofJournal().add(certificate);
        return finish(BackwardCoverability.Result.CLOSED);
    }

    private void charge() {
        if (work + budget.threadWork() - sliceStarted >= allowance) throw new Stop(true);
        budget.check();
    }

    private boolean finish(BackwardCoverability.Result value) {
        result = value;
        budget.note(order == null ? "forward_original" : "forward_por", "result=" + value + "; states=" + visited.size() + "; omitted=" + omitted + "; cycle_expansions=" + cycleExpansions + "; work=" + work + "; retained=" + paused + "; checking=" + (checking != null));
        return true;
    }

    BackwardCoverability.Result result() {
        return result;
    }

    PlanStep witness() {
        return witness;
    }

    ForwardCoverability<K> retained() {
        if (order != null) throw new IllegalStateException("Only full original-state traversal retains its frontier");
        retained = true;
        return this;
    }

    boolean paused() { return paused && !closed; }

    void resume(long additionalWork) {
        if (!paused() || additionalWork <= 0) throw new IllegalStateException("No retained forward frontier");
        long extra = Math.min(additionalWork, budget.remainingWork());
        allowance = work + Math.min(extra, Long.MAX_VALUE - work);
        paused = false;
        result = null;
        budget.note("forward_resume", "states=" + visited.size() + "; work=" + work + "; allowance=" + allowance + "; checking=" + (checking != null));
    }

    @Override
    public void close() {
        closed = true;
        certificate = null;
        checking = null;
        stack.clear();
        visited.clear();
        active.clear();
        budget.release(memory);
        memory = 0;
    }
}
