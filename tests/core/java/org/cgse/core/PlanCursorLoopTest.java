// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Whole-loop skips compared with independently expanded small programs. */
public final class PlanCursorLoopTest {
    private static int checks, cases;

    public static void main(String[] args) {
        randomPrograms(); sharedOccurrences(); nestedBoundaries(); wideCounts(); limits();
        System.out.println("PlanCursorLoopTest: cases=" + cases + "; assertions=" + checks);
    }

    private static void randomPrograms() {
        var random = new Random(0x1ee7c0deL);
        for (int sample = 0; sample < 3000; sample++) {
            var root = generate(random, 4, new ArrayList<>());
            var flat = flatten(root); var indexed = new ArrayList<PlanStep>();
            index(root, indexed, new IdentityHashMap<>());
            var cursor = new PlanCursor(root); var budget = budget(); int offset = 0;
            while (true) {
                var boundary = cursor.loopBoundary(budget);
                check(cursor.remainingCountsExact().equals(counts(flat.subList(offset, flat.size()))), "Boundary peek accepted work");
                var reloaded = new PlanCursor(root, cursor.snapshot());
                check(reloaded.remainingCountsExact().equals(cursor.remainingCountsExact()), "Boundary snapshot changed suffix");
                if (boundary != null && random.nextBoolean()) {
                    var path = cursor.snapshot();
                    var repeat = (PlanStep.Repeat) indexed.get(path.get(path.size() - 1).node());
                    var body = flatten(repeat.body());
                    long iterations = 1 + random.nextInt((int) boundary.remaining());
                    int length = Math.toIntExact(iterations * body.size());
                    for (int i = 0; i < length; i++) check(flat.get(offset + i).equals(body.get(i % body.size())), "Skip crossed a loop occurrence");
                    cursor.skipLoop(boundary, iterations); offset += length;
                    reject(() -> cursor.skipLoop(boundary, 1));
                    cases++;
                } else {
                    var batch = cursor.current();
                    if (batch == null) break;
                    int accepted = 1 + random.nextInt((int) batch.runs());
                    for (int i = 0; i < accepted; i++) check(flat.get(offset++).equals(batch.recipe()), "Cursor order changed");
                    cursor.dispatched(accepted);
                }
            }
            check(offset == flat.size(), "Skipped an unvisited suffix");
            check(cursor.snapshot().isEmpty(), "Exhausted cursor retained live frames");
        }
    }

    private static PlanStep generate(Random r, int depth, List<PlanStep> shared) {
        if (depth == 0 || r.nextInt(4) == 0) return new PlanStep.Batch("r" + r.nextInt(4), r.nextInt(4));
        if (!shared.isEmpty() && r.nextInt(5) == 0) return shared.get(r.nextInt(shared.size()));
        PlanStep result;
        if (r.nextBoolean()) result = new PlanStep.Repeat(generate(r, depth - 1, shared), r.nextInt(4));
        else {
            var children = new ArrayList<PlanStep>();
            for (int i = 0, n = r.nextInt(4); i < n; i++) children.add(generate(r, depth - 1, shared));
            result = new PlanStep.Sequence(children);
        }
        shared.add(result); return result;
    }

    private static void sharedOccurrences() {
        var repeat = new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("a", 1), new PlanStep.Batch("b", 1))), 5);
        var cursor = new PlanCursor(new PlanStep.Sequence(List.of(repeat, repeat)));
        var first = cursor.loopBoundary(budget());
        reject(() -> cursor.skipLoop(first, 0));
        reject(() -> cursor.skipLoop(first, -1));
        reject(() -> cursor.skipLoop(first, 6));
        check(cursor.loopBoundary(budget()).remaining() == 5, "Rejected skip changed remaining iterations");
        cursor.skipLoop(first, 5);
        var second = cursor.loopBoundary(budget());
        check(!second.sameLoop(first), "Shared body identity authorized another occurrence");
        check(!cursor.insideLoop(first), "Retired occurrence remained live");
        reject(() -> cursor.skipLoop(first, 1));
        var other = new PlanCursor(repeat);
        reject(() -> other.skipLoop(second, 1));
        cursor.skipLoop(second, 5);
        check(cursor.current() == null, "Shared suffix remained after both skips");
    }

    private static void nestedBoundaries() {
        var inner = new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("b", 1), new PlanStep.Batch("c", 1))), 3);
        var root = new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("a", 2), inner)), 4);
        var cursor = new PlanCursor(root); var outer = cursor.loopBoundary(budget());
        cursor.dispatched(1);
        check(cursor.loopBoundary(budget()) == null && cursor.insideLoop(outer), "Partial batch exposed a loop boundary");
        reject(() -> cursor.skipLoop(outer, 1));
        cursor.dispatched(1);
        var child = cursor.loopBoundary(budget());
        check(!child.sameLoop(outer) && cursor.insideLoop(outer), "Nested boundary lost its parent");
        reject(() -> cursor.skipLoop(outer, 1));
        cursor.skipLoop(child, 3);
        var next = cursor.loopBoundary(budget());
        check(next.sameLoop(outer) && next.remaining() == 3, "Completed nested word lost parent identity");
        reject(() -> cursor.skipLoop(outer, 1)); // Same occurrence, stale remaining value.
        cursor.skipLoop(next, 3);
        check(cursor.current() == null, "Nested suffix remained");
    }

    private static void wideCounts() {
        var root = new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("a", Long.MAX_VALUE), new PlanStep.Batch("b", 2))), Long.MAX_VALUE);
        var cursor = new PlanCursor(root); var boundary = cursor.loopBoundary(budget());
        cursor.skipLoop(boundary, Long.MAX_VALUE - 1);
        check(cursor.remainingCountsExact().equals(Map.of("a", BigInteger.valueOf(Long.MAX_VALUE), "b", BigInteger.TWO)), "Wide skip truncated exact counts");
        var reload = new PlanCursor(root, cursor.snapshot());
        check(reload.current().runs() == Long.MAX_VALUE, "Wide saved boundary changed");
        var homogeneous = new PlanCursor(new PlanStep.Repeat(new PlanStep.Batch("a", Long.MAX_VALUE), Long.MAX_VALUE));
        check(!homogeneous.hasMixedLoops() && homogeneous.loopBoundary(budget()) == null, "Homogeneous fast path became mixed");
    }

    private static void limits() {
        var flag = new AtomicBoolean(true);
        var budget = new PlanningBudget(0, 100, 1 << 20, flag::get, System::nanoTime);
        var cursor = new PlanCursor(new PlanStep.Sequence(List.of(new PlanStep.Batch("a", 1), new PlanStep.Batch("b", 1))));
        try { cursor.loopBoundary(budget); throw new AssertionError("Ignored cancellation"); }
        catch (CancellationException expected) { checks++; }
        check(cursor.remainingCountsExact().equals(Map.of("a", BigInteger.ONE, "b", BigInteger.ONE)), "Cancelled peek accepted work");
    }

    private static List<String> flatten(PlanStep step) {
        var out = new ArrayList<String>();
        if (step instanceof PlanStep.Batch batch) for (int i = 0; i < batch.runs(); i++) out.add(batch.recipe());
        else if (step instanceof PlanStep.Repeat repeat) {
            var body = flatten(repeat.body()); for (int i = 0; i < repeat.times(); i++) out.addAll(body);
        } else for (var child : ((PlanStep.Sequence) step).children()) out.addAll(flatten(child));
        return out;
    }
    private static void index(PlanStep step, List<PlanStep> nodes, IdentityHashMap<PlanStep, Boolean> seen) {
        if (seen.put(step, true) != null) return;
        nodes.add(step);
        if (step instanceof PlanStep.Repeat repeat) index(repeat.body(), nodes, seen);
        else if (step instanceof PlanStep.Sequence sequence) for (var child : sequence.children()) index(child, nodes, seen);
    }
    private static Map<String, BigInteger> counts(List<String> flat) {
        var result = new LinkedHashMap<String, BigInteger>(); flat.forEach(id -> result.merge(id, BigInteger.ONE, BigInteger::add)); return result;
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 100_000, 1 << 20, () -> false, System::nanoTime); }
    private static void reject(Runnable action) {
        try { action.run(); throw new AssertionError("Accepted an invalid boundary"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
