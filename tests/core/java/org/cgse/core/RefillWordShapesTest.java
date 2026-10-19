// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;

/** Exact AST identity, bounded traversal and private workspace ownership. */
public final class RefillWordShapesTest {
    private static int checks;
    public static void main(String[] args) {
        generated(); boundaries(); large(); cutoffs();
        System.out.println("RefillWordShapesTest: assertions=" + checks);
    }
    private static void generated() {
        var random = new Random(2026101907L);
        for (int test = 0; test < 160; test++) {
            var word = new ArrayList<PlanStep>();
            for (int i = 0; i < 12; i++) {
                var tree = tree(random, 4); word.add(tree); word.add(copy(tree));
                if (random.nextBoolean()) word.add(tree);
            }
            Collections.shuffle(word, random);
            var result = run(word, 100000, 64L << 20);
            check(result != null, "Small exact shapes incomplete");
            for (int i = 0; i < word.size(); i++) {
                check(signature(word.get(i)).equals(signature(result.get(i))), "Changed AST");
                for (int j = 0; j < word.size(); j++)
                    check((result.get(i) == result.get(j)) == signature(word.get(i)).equals(signature(word.get(j))), "Incorrect identity class");
            }
        }
    }
    private static PlanStep tree(Random r, int depth) {
        int kind = depth == 0 ? 0 : r.nextInt(3);
        if (kind == 0) return new PlanStep.Batch(List.of("a", "b", "FB", "Ea").get(r.nextInt(4)), r.nextInt(4));
        if (kind == 1) return new PlanStep.Repeat(tree(r, depth - 1), r.nextInt(4));
        var children = new ArrayList<PlanStep>();
        for (int i = 0, n = r.nextInt(4); i < n; i++) children.add(tree(r, depth - 1));
        return new PlanStep.Sequence(children);
    }
    private static PlanStep copy(PlanStep p) {
        if (p instanceof PlanStep.Batch b) return new PlanStep.Batch(b.recipe(), b.runs());
        if (p instanceof PlanStep.Repeat r) return new PlanStep.Repeat(copy(r.body()), r.times());
        var children = new ArrayList<PlanStep>();
        for (var child : ((PlanStep.Sequence) p).children()) children.add(copy(child));
        return new PlanStep.Sequence(children);
    }
    private static String signature(PlanStep p) {
        if (p instanceof PlanStep.Batch b) return "B(" + b.recipe().length() + ":" + b.recipe() + "," + b.runs() + ")";
        if (p instanceof PlanStep.Repeat r) return "R(" + r.times() + "," + signature(r.body()) + ")";
        var s = new StringBuilder("S[");
        for (var child : ((PlanStep.Sequence) p).children()) s.append(signature(child)).append(';');
        return s.append(']').toString();
    }
    private static void boundaries() {
        var a = new PlanStep.Batch("a", 1); var b = new PlanStep.Batch("b", 1);
        var word = List.<PlanStep>of(new PlanStep.Batch("FB", 1), new PlanStep.Batch("Ea", 1),
                new PlanStep.Sequence(List.of(a, b)), new PlanStep.Sequence(List.of(b, a)),
                new PlanStep.Repeat(a, 2), new PlanStep.Batch("a", 2), new PlanStep.Sequence(List.of(a, a)),
                a, new PlanStep.Repeat(a, 1), new PlanStep.Sequence(List.of(a)), new PlanStep.Repeat(b, 0),
                new PlanStep.Batch("a", 0), new PlanStep.Sequence(List.of()), new PlanStep.Repeat(a, Long.MAX_VALUE));
        var duplicates = new ArrayList<>(word); word.forEach(p -> duplicates.add(copy(p)));
        var result = run(duplicates, 100000, 64L << 20);
        for (int i = 0; i < word.size(); i++) {
            check(result.get(i) == result.get(i + word.size()), "Equal shapes not interned");
            for (int j = 0; j < word.size(); j++) check((result.get(i) == result.get(j)) == (i == j), "Collapsed dispatch/order/zero boundary");
        }
        check(run(List.of(a, a), 100000, 64L << 20).get(0) == a, "Replaced existing representative");
    }
    private static void large() {
        PlanStep a = new PlanStep.Batch("a", Long.MAX_VALUE), b = new PlanStep.Batch("a", Long.MAX_VALUE);
        for (int i = 0; i < 256; i++) { a = new PlanStep.Repeat(a, Long.MAX_VALUE); b = new PlanStep.Repeat(b, Long.MAX_VALUE); }
        var result = run(List.of(a, b), 100000, 64L << 20);
        check(result != null && result.get(0) == result.get(1), "Expanded enormous execution count");
        for (int i = 0; i < 10000; i++) { a = new PlanStep.Repeat(a, 1); b = new PlanStep.Repeat(b, 1); }
        check(run(List.of(a, b), 100000, 64L << 20) == null, "Unbounded deep workspace");
        var children = new ArrayList<PlanStep>(); var leaf = new PlanStep.Batch("a", 1);
        for (int i = 0; i < 512; i++) children.add(leaf);
        result = run(List.of(new PlanStep.Sequence(children), new PlanStep.Sequence(children)), 100000, 64L << 20);
        check(result != null && result.get(0) == result.get(1), "Wide sequence identity");
        for (int i = 0; i < 4096; i++) children.add(leaf);
        check(run(List.of(new PlanStep.Sequence(children)), 100000, 64L << 20) == null, "Unbounded wide workspace");
    }
    private static List<PlanStep> sample() {
        var word = new ArrayList<PlanStep>();
        for (int i = 0; i < 100; i++) word.add(new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("a", 1), new PlanStep.Batch("b", 2))), 3));
        return word;
    }
    private static void cutoffs() {
        for (long cap : new long[]{0, 1, 31, 32, 63, 64, 65, 128, 512}) check(run(sample(), cap, 64L << 20) == null, "Published partial shapes");
        for (long bytes : new long[]{1, 8191, 8192, 16384, 65536}) check(run(sample(), 100000, bytes) == null, "Exceeded optional memory share");
        for (String mode : List.of("work", "time", "memory", "cancel")) {
            var clock = new AtomicLong(); var cancelled = new AtomicBoolean();
            var b = new PlanningBudget(1, 100000, 64L << 20, cancelled::get, clock::get); long held = 0;
            b.failureDetail("proof");
            try (var work = new RefillWordShapes(sample(), b, 200000)) {
                check(!work.step() && work.result() == null, "Unexpected early result");
                switch (mode) {
                    case "work" -> b.charge(b.remainingWork());
                    case "time" -> clock.set(2000000);
                    case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                    case "cancel" -> cancelled.set(true);
                }
                try { check(work.step() && mode.equals("memory"), "Ignored resource boundary"); }
                catch (PlanningBudget.Exhausted expected) { check(mode.equals("work") || mode.equals("time"), "Unexpected exhaustion"); }
                catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                check(work.result() == null, "Published an incomplete identity map");
                if (mode.equals("memory")) check(b.failureDetail().equals("proof"), "Optional refusal replaced proof");
            } finally { b.release(held); }
            check(b.reservedBytes() == 0, "Interrupted workspace leak");
        }
    }
    private static List<PlanStep> run(List<PlanStep> input, long cap, long bytes) {
        var b = new PlanningBudget(0, 1000000, bytes, () -> false, System::nanoTime); List<PlanStep> result;
        var work = new RefillWordShapes(input, b, cap);
        try {
            while (true) { long before = b.nodes(); boolean done = work.step(); check(b.nodes() - before <= 64, "Unbounded traversal turn"); if (done) break; }
            result = work.result();
            check(b.nodes() <= cap && b.peakBytes() <= Math.min(262144, bytes / 16), "Exceeded private allowance");
            check(b.reservedBytes() == (work.changed() && result != null ? 32L * input.size() : 0), "Retained traversal scratch");
        } finally { work.close(); work.close(); }
        check(b.reservedBytes() == 0 && b.cachePreparationWork() == 0, "Shape workspace/account leak");
        return result;
    }
    private static void check(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
}
