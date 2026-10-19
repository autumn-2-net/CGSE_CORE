// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;

/** Nonadjacent recognition must preserve every dispatch event, including gaps. */
public final class RefillWordSpansTest {
    private static int checks;
    public static void main(String[] args) {
        var random = new Random(2026101909L);
        for (int n = 0; n < 240; n++) {
            var word = new ArrayList<PlanStep>(); var repeated = new ArrayList<PlanStep>();
            for (int i = 0; i < 2 + n % 23; i++) repeated.add(new PlanStep.Batch("r" + i, 1 + n % 3));
            for (int k = 0; k < 2 + n % 5; k++) {
                word.addAll(repeated);
                for (int j = 0, gaps = random.nextInt(5); j < gaps; j++) word.add(new PlanStep.Batch("gap" + k + ":" + j, j + 1));
            }
            var result = run(word, 100000, 64L << 20);
            check(result != null, "Missed distinct repeated span"); sameTrace(word, result);
        }
        var collision = List.<PlanStep>of(new PlanStep.Batch("FB", 1), new PlanStep.Batch("a", 1), new PlanStep.Batch("gap", 1),
                new PlanStep.Batch("Ea", 1), new PlanStep.Batch("a", 1));
        check(run(collision, 100000, 64L << 20) == null, "Hash collision formed a span");
        var body = new PlanStep.Repeat(new PlanStep.Batch("a", 2), Long.MAX_VALUE);
        var opaque = List.<PlanStep>of(body, new PlanStep.Batch("b", 1), new PlanStep.Batch("gap", 1), body, new PlanStep.Batch("b", 1));
        check(run(opaque, 100000, 64L << 20) != null, "Unfolded opaque repeated body");
        cutoffs();
        System.out.println("RefillWordSpansTest: assertions=" + checks);
    }
    private static void sameTrace(List<PlanStep> reverse, PlanStep result) {
        var after = new ArrayList<String>(); RefillWordNormalFormsTest.trace(result, after);
        check(RefillWordNormalFormsTest.traceWord(reverse).equals(after), "Moved gap/changed dispatch");
    }
    private static List<PlanStep> sample() {
        var word = new ArrayList<PlanStep>();
        for (int k = 0; k < 30; k++) {
            for (int j = 0; j < 20; j++) word.add(new PlanStep.Batch("r" + j, 1));
            word.add(new PlanStep.Batch("gap" + k, 1));
        }
        return word;
    }
    private static void cutoffs() {
        for (long cap : new long[]{0, 1, 31, 63, 64, 65, 128, 512}) check(run(sample(), cap, 64L << 20) == null, "Published partial spans");
        for (String mode : List.of("work", "time", "memory", "cancel")) {
            var clock = new AtomicLong(); var cancelled = new AtomicBoolean();
            var b = new PlanningBudget(1, 100000, 64L << 20, cancelled::get, clock::get); long held = 0;
            try (var work = new RefillWordSpans(sample(), b, 200000)) {
                check(!work.step() && work.result() == null, "Early span result");
                switch (mode) {
                    case "work" -> b.charge(b.remainingWork());
                    case "time" -> clock.set(2000000);
                    case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                    case "cancel" -> cancelled.set(true);
                }
                try { while (!work.step()) {} throw new AssertionError("Ignored boundary: " + mode); }
                catch (PlanningBudget.Exhausted expected) { check(!mode.equals("cancel"), "Unexpected exhaustion"); }
                catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                check(work.result() == null, "Published interrupted spans");
            } finally { b.release(held); }
            check(b.reservedBytes() == 0, "Span workspace leak");
        }
    }
    private static PlanStep run(List<PlanStep> reverse, long cap, long bytes) {
        var b = new PlanningBudget(0, 1000000, bytes, () -> false, System::nanoTime); PlanStep result;
        var work = new RefillWordSpans(reverse, b, cap);
        try {
            while (true) { long before = b.nodes(); boolean done = work.step(); check(b.nodes() - before <= 64, "Unbounded span turn"); if (done) break; }
            result = work.result(); check(b.nodes() <= cap, "Exceeded scan allowance");
        } finally { work.close(); work.close(); }
        check(b.reservedBytes() == 0 && b.cachePreparationWork() == 0, "Span account leak"); return result;
    }
    private static void check(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
}
