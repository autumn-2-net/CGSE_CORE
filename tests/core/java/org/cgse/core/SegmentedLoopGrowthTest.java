// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.math.BigInteger;

/** Execution must not depend on whether an equivalent cycle was serialized as Repeat or Sequence. */
public final class SegmentedLoopGrowthTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static PlanStep word(String shape, int amount) {
        PlanStep body = new PlanStep.Sequence(List.of(new PlanStep.Batch("a", 1), new PlanStep.Batch("b", 1)));
        if (shape.equals("repeat")) return new PlanStep.Repeat(body, amount);
        var children = new ArrayList<PlanStep>();
        for (int i = 0; i < amount; i++) {
            if (shape.equals("flat")) {
                children.add(new PlanStep.Batch("a", 1)); children.add(new PlanStep.Batch("b", 1));
            } else children.add(new PlanStep.Repeat(new PlanStep.Sequence(List.of(
                    new PlanStep.Batch("a", 1), new PlanStep.Batch("b", 1))), 1));
        }
        return new PlanStep.Sequence(children);
    }
    private static GraphPlan<String> plan(String shape, int amount, boolean tail) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.put("a", recipe("a", Map.of("A", 1L, "raw", 1L), Map.of("B", 1L)));
        recipes.put("b", recipe("b", Map.of("B", 1L), Map.of("A", 2L, "P", 1L)));
        PlanStep steps = word(shape, amount);
        var initial = new LinkedHashMap<>(Map.of("A", 1L, "raw", (long) amount));
        if (tail) {
            recipes.put("independent", recipe("independent", Map.of("U", 1L), Map.of("Q", 1L)));
            recipes.put("finish", recipe("finish", Map.of("P", (long) amount, "Q", 1L), Map.of("T", 1L)));
            initial.put("U", 1L);
            var children = new ArrayList<PlanStep>();
            if (steps instanceof PlanStep.Sequence sequence) children.addAll(sequence.children()); else children.add(steps);
            children.add(new PlanStep.Batch("independent", 1)); children.add(new PlanStep.Batch("finish", 1));
            steps = new PlanStep.Sequence(children);
        }
        return new GraphPlan<>(tail ? "T" : "P", tail ? 1 : amount, true, steps, recipes, initial,
                Map.of("A", 1L), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }
    private static final class Host implements GraphJobRuntime.Adapter<String> {
        final GraphPlan<String> plan;
        GraphJobRuntime<String> runtime;
        final Map<String, Long> stock, refunds = new LinkedHashMap<>(), accepted = new LinkedHashMap<>();
        final Map<Integer, List<Map<String, Long>>> returns = new TreeMap<>();
        final Map<String, Integer> busy = new HashMap<>();
        final boolean capped;
        final int delay;
        long delivered, peak;
        int tick, independentTick = -1;
        Host(GraphPlan<String> plan, boolean capped, int delay) {
            this.plan = plan; this.capped = capped; this.delay = delay;
            runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of()); stock = new LinkedHashMap<>(plan.initial());
        }
        public long capacity(GraphRecipe<String> r, long n) {
            if (busy.getOrDefault(r.id(), -1) > tick) return 0;
            return capped && tick < 30 ? Math.min(n, 3) : n;
        }
        public GraphJobRuntime.Outcome push(GraphRecipe<String> r, long n, Map<String, Long> in) {
            check(in.equals(r.dispatchInputs(n)), "Wrong dispatch inputs");
            in.forEach((k, v) -> { long left = stock.getOrDefault(k, 0L) - v; check(left >= 0, "Spent future return"); stock.put(k, left); });
            var out = new LinkedHashMap<String, Long>(); r.executionOutputs().forEach((k, v) -> out.put(k, Math.multiplyExact(v, n)));
            returns.computeIfAbsent(tick + delay, ignored -> new ArrayList<>()).add(out);
            busy.put(r.id(), tick + delay); accepted.merge(r.id(), n, Math::addExact); peak = Math.max(peak, n);
            if (r.id().equals("independent")) independentTick = tick;
            return GraphJobRuntime.Outcome.ACCEPTED;
        }
        private void take(String key, long n) { long left = stock.getOrDefault(key, 0L) - n; check(left >= 0, "Unfunded transfer"); stock.put(key, left); }
        public long deliver(String key, long n) { check(key.equals(plan.target()), "Wrong delivery"); take(key, n); delivered += n; return n; }
        public long refund(String key, long n) { take(key, n); refunds.merge(key, n, Math::addExact); return n; }
        void receive() {
            var due = returns.remove(tick);
            if (due != null) for (var out : due) out.forEach((k, n) -> {
                stock.merge(k, n, Math::addExact); check(runtime.accept(k, n, false) == n, "Lost physical return");
            });
        }
    }
    private static int run(String shape, int amount, boolean tail, boolean capped, int delay, int restore, int cancel) {
        var plan = plan(shape, amount, tail); var host = new Host(plan, capped, delay);
        for (host.tick = 0; host.tick < 20000 && !host.runtime.finished(); host.tick++) {
            host.receive();
            if (host.tick == restore) host.runtime = new GraphJobRuntime<>(host.runtime.snapshot());
            if (host.tick == cancel) host.runtime.cancel();
            host.runtime.tick(host, host.tick, 64, () -> 0L);
        }
        check(host.runtime.finished(), "Execution did not finish: " + shape);
        check(host.stock.values().stream().allMatch(n -> n == 0), "Owned ledger was lost");
        check(host.returns.isEmpty(), "Ignored in-flight return");
        var balance = new LinkedHashMap<>(plan.initial());
        host.accepted.forEach((id, n) -> {
            var r = plan.recipes().get(id); r.inputs().forEach((k, v) -> balance.merge(k, -Math.multiplyExact(v, n), Math::addExact));
            r.executionOutputs().forEach((k, v) -> balance.merge(k, Math.multiplyExact(v, n), Math::addExact));
        });
        balance.merge(plan.target(), -host.delivered, Math::addExact); host.refunds.forEach((k, n) -> balance.merge(k, -n, Math::addExact));
        check(balance.values().stream().allMatch(n -> n == 0), "Independent conservation failed");
        if (cancel < 0) {
            check(host.delivered == plan.amount(), "Wrong final quantity");
            check(host.refunds.getOrDefault("A", 0L) == amount + 1L, "Lost final seed");
            check(host.accepted.getOrDefault("a", 0L) == amount && host.accepted.getOrDefault("b", 0L) == amount, "Recipe counts changed");
            check(host.peak >= 256, "Fixed segmented batch ceiling: " + shape + "/" + amount + "/" + tail
                    + "/" + capped + "/" + delay + "/" + restore + " peak=" + host.peak + " ticks=" + host.tick);
            check(host.tick < 250, "Segmented loop did not grow: " + shape + ": " + host.tick);
            if (tail) check(host.independentTick < 100 && host.independentTick < host.tick - 1,
                    "Independent branch delayed until loop completion");
        }
        return host.tick;
    }
    public static void main(String[] args) {
        int baseline = run("repeat", 8192, false, false, 1, -1, -1);
        int flattened = run("flat", 8192, false, false, 1, -1, -1);
        check(flattened <= baseline + 64, "Sliced compilation did not remove the flat batch ceiling");
        System.out.println("Equivalent 8192-round loop ticks: Repeat=" + baseline + ", sliced Sequence=" + flattened);
        for (String shape : List.of("repeat", "flat", "wrapped")) for (boolean tail : List.of(false, true)) {
            run(shape, 2048, tail, false, 1, -1, -1);
            run(shape, 2048, tail, true, 3, 18, -1);
            run(shape, 2048, tail, true, 3, 18, 24);
        }
        // Exhausting optional structure work must retain the original exact cursor.
        var huge = word("wrapped", 20000); var cursor = new PlanCursor(huge); cursor.prepareBatching(SequenceLoopIndex.MAX_WORK);
        check(cursor.remainingCountsExact().equals(Map.of("a", BigInteger.valueOf(20000), "b", BigInteger.valueOf(20000))), "Index cutoff changed work");
        var shared = new PlanStep.Sequence(List.of(new PlanStep.Batch("a", 1), new PlanStep.Batch("b", 1)));
        var mixed = new PlanStep.Sequence(List.of(shared, shared, new PlanStep.Sequence(List.of(new PlanStep.Batch("a", 2), new PlanStep.Batch("b", 1)))));
        var c = new PlanCursor(mixed); c.prepareBatching(SequenceLoopIndex.MAX_WORK);
        check(c.remainingCountsExact().equals(Map.of("a", BigInteger.valueOf(4), "b", BigInteger.valueOf(3))), "False structural alias");
        slicedIndex();
        checkpointAlignment();
        sharedBudget();
        enormousNestedCount();
        System.out.println("SegmentedLoopGrowthTest " + checks + " checks");
    }

    private static void slicedIndex() {
        PlanStep root = word("flat", 8192);
        var index = new SequenceLoopIndex(root);
        while (!index.complete()) {
            int before = index.work(); index.advance(64);
            check(index.work() - before <= 64, "Structural slice exceeded its allowance");
        }
        check(index.work() <= SequenceLoopIndex.MAX_WORK, "Unbounded structural compilation");
        var loop = index.get(root, 0);
        check(loop != null && loop.width() == 2 && loop.extent() == 16384,
                "Flat loop was not recognized within the structure budget");
        check(loop.counts().equals(Map.of("a", BigInteger.ONE, "b", BigInteger.ONE)), "Wrong period counts");

        var adversarial = new ArrayList<PlanStep>();
        for (int i = 0; i < 6000; i++) adversarial.add(new PlanStep.Batch("FB", 1));
        adversarial.add(new PlanStep.Batch("Ea", 1)); // Deliberate String.hashCode collision.
        PlanStep mismatch = new PlanStep.Sequence(adversarial);
        var cut = new SequenceLoopIndex(mismatch);
        while (!cut.complete()) {
            int before = cut.work(); cut.advance(64);
            check(cut.work() - before <= 64, "KMP fallback escaped its slice");
        }
        check(cut.get(mismatch, 0) == null, "Homogeneous prefix or hash collision invented a mixed loop");
        var empty = new SequenceLoopIndex(new PlanStep.Sequence(List.of(
                new PlanStep.Sequence(List.of()), new PlanStep.Sequence(List.of()))));
        empty.advance(1024);
        check(empty.complete() && empty.get(mismatch, 0) == null, "Empty normalized shape did not terminate");
    }

    private static void checkpointAlignment() {
        var p = plan("flat", 8, false);
        var c = new PlanCursor(p.steps());
        c.prepareBatching(1); // Saving must work even before optional compilation finishes.
        check(c.current().recipe().equals("a"), "Unexpected first recipe");
        c.dispatched(1);
        var saved = c.snapshot();
        c = new PlanCursor(p.steps(), saved);
        c.prepareBatching(SequenceLoopIndex.MAX_WORK);
        var group = c.takeLoopBatch(p.recipes(), 32, () -> key -> BigInteger.valueOf(100));
        check(group == null && c.current().recipe().equals("b"), "Mid-period restore skipped the unmatched suffix");
        c.dispatched(1);
        group = c.takeLoopBatch(p.recipes(), 32, () -> key -> BigInteger.valueOf(100));
        check(group != null && group.equals(List.of(new PlanStep.Batch("a", 7), new PlanStep.Batch("b", 7))),
                "Aligned suffix failed to batch after restore");
        var restored = new PlanCursor(p.steps(), c.snapshot());
        check(restored.current() == null && restored.remainingCountsExact().isEmpty(),
                "Compiled cursor introduced non-original node IDs");
    }

    private static void sharedBudget() {
        var cursors = List.of(new PlanCursor(word("flat", 8192)), new PlanCursor(word("flat", 8192)),
                new PlanCursor(word("flat", 8192)));
        var budget = new PipelineScheduler.CompilationBudget();
        for (int tick = 0; tick < 100; tick++) {
            int before = budget.work();
            // Include repeated provider probes in the same tick and different lanes.
            for (int probe = 0; probe < 4; probe++) for (var cursor : cursors) budget.advance(cursor, tick);
            check(budget.work() - before <= 1024, "Lanes multiplied per-tick compilation work");
            check(budget.work() <= SequenceLoopIndex.MAX_WORK, "Lanes multiplied total compilation work");
        }
        for (var cursor : cursors) check(cursor.remainingCountsExact().equals(
                Map.of("a", BigInteger.valueOf(8192), "b", BigInteger.valueOf(8192))),
                "Shared budget cutoff changed the original witness");
    }

    private static void enormousNestedCount() {
        PlanStep huge = new PlanStep.Sequence(List.of(new PlanStep.Batch("a", 1), new PlanStep.Batch("b", 1)));
        for (int i = 0; i < 500; i++) huge = new PlanStep.Repeat(huge, Long.MAX_VALUE);
        PlanStep root = new PlanStep.Sequence(List.of(huge, huge));
        var index = new SequenceLoopIndex(root);
        while (!index.complete()) index.advance(64);
        check(index.get(root, 0) == null, "Huge nested counts entered the runtime regrouping candidate");
        check(index.work() < 2048, "Huge nested counts were expanded instead of saturated");
    }
}
