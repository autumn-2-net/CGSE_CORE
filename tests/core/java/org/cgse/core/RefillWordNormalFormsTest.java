// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;

/** Independent dispatch-event replay, not just per-recipe execution totals. */
public final class RefillWordNormalFormsTest {
    private static int checks;
    public static void main(String[] args) {
        generated(); identities(); large(); cutoffs();
        System.out.println("RefillWordNormalFormsTest: assertions=" + checks);
    }
    private static void generated() {
        var random = new Random(2026101908L);
        for (int test = 0; test < 160; test++) {
            var reverse = new ArrayList<PlanStep>();
            for (int i = 0; i < 20; i++) reverse.add(tree(random, 3));
            var result = run(reverse, 100000, 64L << 20);
            check(result != null, "Small normalization incomplete");
            check(traceWord(reverse).equals(traceWord(result)), "Changed dispatch trace");
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
    static List<String> traceWord(List<PlanStep> reverse) {
        var events = new ArrayList<String>();
        for (int i = reverse.size() - 1; i >= 0; i--) trace(reverse.get(i), events);
        return events;
    }
    static void trace(PlanStep step, List<String> events) {
        // Batch size remains part of an indivisible dispatch event, even zero.
        if (step instanceof PlanStep.Batch b) events.add(b.recipe().length() + ":" + b.recipe() + "=" + b.runs());
        else if (step instanceof PlanStep.Repeat r) for (long i = 0; i < r.times(); i++) trace(r.body(), events);
        else for (var child : ((PlanStep.Sequence) step).children()) trace(child, events);
    }
    private static void identities() {
        var a = new PlanStep.Batch("a", 1); var b = new PlanStep.Batch("b", 2); var c = new PlanStep.Batch("c", 3);
        var left = new PlanStep.Sequence(List.of(a, new PlanStep.Sequence(List.of(b, c))));
        var right = new PlanStep.Sequence(List.of(new PlanStep.Sequence(List.of(a, b)), c));
        var word = List.<PlanStep>of(new PlanStep.Repeat(left, 6), new PlanStep.Repeat(right, 6),
                new PlanStep.Repeat(new PlanStep.Repeat(right, 2), 3),
                new PlanStep.Repeat(new PlanStep.Sequence(List.of(left)), 6));
        var result = run(word, 100000, 64L << 20);
        check(result.size() == 4, "Unfolded a repeat");
        for (var p : result) check(p == result.get(0), "Missed associativity/repeat identity");
        check(traceWord(word).equals(traceWord(result)), "Changed equivalent traces");
        word = List.of(new PlanStep.Batch("a", 2), new PlanStep.Repeat(a, 2),
                new PlanStep.Batch("FB", 1), new PlanStep.Batch("Ea", 1), a,
                new PlanStep.Sequence(List.of(a)), new PlanStep.Repeat(a, 1), new PlanStep.Batch("a", 0));
        result = run(word, 100000, 64L << 20);
        check(result.get(0) != result.get(1), "Merged dispatch batch with repeat");
        check(result.get(2) != result.get(3), "Hash collision confused recipe IDs");
        check(result.get(4) == result.get(5) && result.get(5) == result.get(6), "Singleton identity");
        check(result.get(7) != result.get(4), "Changed zero batch");
        check(traceWord(word).equals(traceWord(result)), "Changed boundary trace");
        word = List.of(new PlanStep.Sequence(List.of()), new PlanStep.Repeat(a, 0),
                new PlanStep.Sequence(List.of(a, new PlanStep.Sequence(List.of()), b)));
        check(traceWord(word).equals(traceWord(run(word, 100000, 64L << 20))), "Empty/zero sequence");
    }
    private static void large() {
        PlanStep a = new PlanStep.Batch("a", 1), b = new PlanStep.Batch("a", 1);
        for (int i = 0; i < 256; i++) { a = new PlanStep.Repeat(a, Long.MAX_VALUE); b = new PlanStep.Repeat(b, Long.MAX_VALUE); }
        var result = run(List.of(a, b), 100000, 64L << 20);
        check(result != null && result.get(0) == result.get(1), "Overflow repeat expanded/changed");
        PlanStep flat = new PlanStep.Batch("deep", 1), deep = flat;
        for (int i = 0; i < 10000; i++) deep = new PlanStep.Repeat(new PlanStep.Sequence(List.of(deep)), 1);
        result = run(List.of(deep, flat), 100000, 64L << 20);
        check(result != null && result.get(0) == result.get(1), "Deep identity used recursive stack");
        var children = new ArrayList<PlanStep>(); for (int i = 0; i < 16384; i++) children.add(flat);
        check(run(List.of(new PlanStep.Sequence(children)), 100000, 64L << 20) == null, "Unbounded wide workspace");
    }
    static List<PlanStep> sample() {
        var word = new ArrayList<PlanStep>();
        for (int i = 0; i < 100; i++) word.add(new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("a", 1), new PlanStep.Batch("b", 2))), 3));
        return word;
    }
    private static void cutoffs() {
        for (long cap : new long[]{0, 1, 31, 63, 64, 65, 128, 512}) check(run(sample(), cap, 64L << 20) == null, "Published partial normalization");
        for (long bytes : new long[]{1, 8191, 8192, 16384, 65536}) check(run(sample(), 100000, bytes) == null, "Exceeded private memory share");
        for (String mode : List.of("work", "time", "memory", "cancel")) {
            var clock = new AtomicLong(); var cancelled = new AtomicBoolean();
            var b = new PlanningBudget(1, 100000, 64L << 20, cancelled::get, clock::get); long held = 0; b.failureDetail("proof");
            try (var work = new RefillWordNormalForms(sample(), b, 200000)) {
                check(!work.step() && work.result() == null, "Unexpected early result");
                switch (mode) {
                    case "work" -> b.charge(b.remainingWork());
                    case "time" -> clock.set(2000000);
                    case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                    case "cancel" -> cancelled.set(true);
                }
                try { check(work.step() && mode.equals("memory"), "Ignored boundary"); }
                catch (PlanningBudget.Exhausted expected) { check(mode.equals("work") || mode.equals("time"), "Unexpected exhaustion"); }
                catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                check(work.result() == null, "Published incomplete normalization");
                if (mode.equals("memory")) check(b.failureDetail().equals("proof"), "Refusal replaced proof");
            } finally { b.release(held); }
            check(b.reservedBytes() == 0, "Interrupted workspace leak");
        }
    }
    private static List<PlanStep> run(List<PlanStep> input, long cap, long bytes) {
        var b = new PlanningBudget(0, 1000000, bytes, () -> false, System::nanoTime); List<PlanStep> result;
        var work = new RefillWordNormalForms(input, b, cap);
        try {
            while (true) { long before = b.nodes(); boolean done = work.step(); check(b.nodes() - before <= 64, "Unbounded turn"); if (done) break; }
            result = work.result();
            check(b.nodes() <= cap && b.peakBytes() <= Math.min(262144, bytes / 16), "Exceeded optional allowance");
            check(b.reservedBytes() == (work.changed() && result != null ? 32L * result.size() : 0), "Retained traversal scratch");
        } finally { work.close(); work.close(); }
        check(b.reservedBytes() == 0 && b.cachePreparationWork() == 0, "Workspace/account leak"); return result;
    }
    private static void check(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
}
