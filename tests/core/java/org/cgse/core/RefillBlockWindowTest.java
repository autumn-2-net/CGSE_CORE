// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** The optional first-atom index must preserve the original exact grouping. */
public final class RefillBlockWindowTest {
    private static int checks;
    private record Run(PlanStep word, long work, long peak) {}

    public static void main(String[] args) throws Exception {
        exactGrouping(); opaque(); admission(); cutoffs();
        System.out.println("RefillBlockWindowTest: assertions=" + checks);
    }
    private static void exactGrouping() {
        var random = new Random(2026101907L);
        for (int test = 0; test < 800; test++) {
            var word = new ArrayList<PlanStep>();
            int size = random.nextInt(350);
            for (int i = 0; i < size; i++) word.add(new PlanStep.Batch(
                    i % 7 == 0 ? "FB" : i % 7 == 1 ? "Ea" : "r" + random.nextInt(35), random.nextInt(4)));
            if ((test & 1) == 0) {
                int period = 2 + random.nextInt(20), copies = 2 + random.nextInt(8);
                var body = new ArrayList<PlanStep>();
                for (int i = 0; i < period; i++) body.add(new PlanStep.Batch("p" + random.nextInt(9), random.nextInt(4)));
                for (int i = 0; i < copies; i++) word.addAll(body);
            }
            if ((test & 2) != 0) Collections.reverse(word);
            compare(word);
        }
        for (int prefix = 0; prefix < 65; prefix++) for (int period = 2; period <= 17; period++) {
            var word = new ArrayList<PlanStep>();
            for (int i = 0; i < prefix; i++) word.add(new PlanStep.Batch("prefix" + i, 1));
            for (int c = 0; c < 4; c++) for (int i = 0; i < period; i++)
                word.add(new PlanStep.Batch(i == 0 ? "FB" : i == 1 ? "Ea" : "body" + i, i == 2 ? Long.MAX_VALUE : i & 1));
            compare(word);
        }
        var longWord = new ArrayList<PlanStep>();
        for (int round = 0; round < 8; round++) {
            for (int i = 0; i < 32; i++) longWord.add(new PlanStep.Batch("r" + i, 1));
            for (int i = 0; i < 6; i++) longWord.add(new PlanStep.Batch("inner" + i % 2, 1));
        }
        Run slow = run(longWord, false, 64L << 20, 100000), fast = run(longWord, true, 64L << 20, 100000);
        check(Objects.equals(slow.word, fast.word), "Indexed grouping changed selection");
        check(fast.work * 2 < slow.work, "No reduction of unequal first-atom scans");
        check(run(longWord, false, 64L << 20, 2048).word == null, "Expected bounded legacy scan");
        check(run(longWord, true, 64L << 20, 2048).word != null, "Index failed to complete bounded scan");
    }
    private static void compare(List<PlanStep> word) {
        Run slow = run(word, false, 64L << 20, 100000), fast = run(word, true, 64L << 20, 100000);
        check(Objects.equals(slow.word, fast.word), "Changed exact period/copy/position selection");
        if (fast.word != null) {
            var expected = new ArrayList<>(word); Collections.reverse(expected);
            check(trace(fast.word, Set.of()).equals(expected), "Changed original batch trace");
        }
    }
    private static void opaque() {
        PlanStep shared = new PlanStep.Batch("deep", 1);
        for (int i = 0; i < 20000; i++) shared = new PlanStep.Repeat(shared, Long.MAX_VALUE);
        var distinct = new PlanStep.Repeat(shared, 1);
        var atoms = Collections.newSetFromMap(new IdentityHashMap<PlanStep, Boolean>());
        atoms.add(shared); atoms.add(distinct);
        var word = new ArrayList<PlanStep>();
        for (int i = 0; i < 20; i++) word.addAll(List.of(shared, new PlanStep.Batch("a", i & 1), distinct, new PlanStep.Batch("b", 0)));
        var result = run(word, true, 64L << 20, 100000).word;
        check(result != null, "Lost opaque-identity repetition");
        Collections.reverse(word);
        var actual = trace(result, atoms);
        check(actual.size() == word.size(), "Lost opaque occurrences");
        for (int i = 0; i < word.size(); i++) {
            var a = actual.get(i); var b = word.get(i);
            check(a == b || a instanceof PlanStep.Batch && a.equals(b), "Changed opaque identity/count");
        }
    }
    private static List<PlanStep> trace(PlanStep root, Set<PlanStep> opaque) {
        var out = new ArrayList<PlanStep>(); var todo = new ArrayDeque<PlanStep>(); todo.push(root);
        while (!todo.isEmpty()) {
            var step = todo.pop();
            if (step instanceof PlanStep.Batch || opaque.contains(step)) out.add(step);
            else if (step instanceof PlanStep.Sequence s) for (int i = s.children().size() - 1; i >= 0; i--) todo.push(s.children().get(i));
            else {
                var repeat = (PlanStep.Repeat) step; check(repeat.times() < 1000, "Expanded opaque repeat");
                for (int i = 0; i < repeat.times(); i++) todo.push(repeat.body());
            }
        }
        return out;
    }
    private static List<PlanStep> sample() {
        var word = new ArrayList<PlanStep>();
        for (int i = 0; i < 300; i++) word.add(new PlanStep.Batch("r" + i % 11, i % 3));
        return word;
    }
    private static void admission() throws Exception {
        var word = sample();
        Run slow = run(word, false, 65535, 100000), denied = run(word, true, 65535, 100000);
        check(slow.work == denied.work && slow.peak == denied.peak && Objects.equals(slow.word, denied.word), "Refused index changed original path");
        var b = budget(65536);
        try (var work = new RefillWordBlocks(word, b, 100000, true)) {
            check(window(work) != null && b.reservedBytes() == 4096, "Incorrect admission boundary");
            long held = b.availableBytes() - 256; b.reserve(held);
            try {
                for (int i = 0; window(work) != null; i++) check(i < 1000 && !work.step(), "No allocation-pressure fallback");
            } finally { b.release(held); }
            while (!work.step()) {}
            check(Objects.equals(work.result(), slow.word), "Pressure fallback changed exact grouping");
        }
        check(b.reservedBytes() == 0, "Pressure workspace leak");
    }
    private static Object window(RefillWordBlocks work) throws Exception {
        var field = RefillWordBlocks.class.getDeclaredField("window"); field.setAccessible(true); return field.get(work);
    }
    private static void cutoffs() {
        for (long cap : new long[]{0, 1, 15, 16, 31, 32, 64, 128, 512}) {
            Run result = run(sample(), true, 64L << 20, cap);
            check(result.work <= cap, "Exceeded exact scan cap");
        }
        for (String mode : List.of("work", "time", "cancel")) {
            var clock = new AtomicLong(); var cancelled = new AtomicBoolean();
            var b = new PlanningBudget(1, 100000, 64L << 20, cancelled::get, clock::get);
            try (var work = new RefillWordBlocks(sample(), b, 100000, true)) {
                check(!work.step(), "Unexpected early completion");
                switch (mode) { case "work" -> b.charge(b.remainingWork()); case "time" -> clock.set(2000000); case "cancel" -> cancelled.set(true); }
                try {
                    boolean done = work.step();
                    check(mode.equals("work") && done, "Ignored cutoff");
                }
                catch (PlanningBudget.Exhausted expected) { check(!mode.equals("cancel"), "Swallowed cancellation"); }
                catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                check(work.result() == null, "Published partial regrouping");
            }
            check(b.reservedBytes() == 0, "Interrupted index leak");
        }
    }
    private static Run run(List<PlanStep> word, boolean indexed, long bytes, long allowance) {
        var b = budget(bytes); PlanStep result;
        try (var work = new RefillWordBlocks(word, b, allowance, indexed)) {
            while (true) { long before = b.nodes(); boolean done = work.step(); check(b.nodes() - before <= 64, "Unbounded index turn"); if (done) break; }
            result = work.result();
        }
        check(b.reservedBytes() == 0 && b.cachePreparationWork() == 0, "Index budget/lease leak");
        return new Run(result, b.nodes(), b.peakBytes());
    }
    private static PlanningBudget budget(long bytes) { return new PlanningBudget(0, 1000000, bytes, () -> false, System::nanoTime); }
    private static void check(boolean condition, String reason) { checks++; if (!condition) throw new AssertionError(reason); }
}
