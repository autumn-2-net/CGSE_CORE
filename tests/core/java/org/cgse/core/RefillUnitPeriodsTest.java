// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;

/** Independent dispatch traces and symbolic multiplicities for exact periods. */
public final class RefillUnitPeriodsTest {
    private static int checks;
    public static void main(String[] args) {
        generated(); boundaries(); large(); cutoffs();
        System.out.println("RefillUnitPeriodsTest: assertions=" + checks);
    }
    private static PlanStep repeated(int length, int copies, int wrappers) {
        var atoms = new ArrayList<PlanStep>();
        for (int copy = 0; copy < copies; copy++) {
            var segment = new ArrayList<PlanStep>();
            for (int i = 0; i < length; i++) {
                PlanStep p = new PlanStep.Batch("r" + i, 1 + i % 3);
                for (int depth = 0; depth < (copy + i) % 3; depth++) p = new PlanStep.Sequence(List.of(p));
                segment.add(p);
            }
            atoms.add(new PlanStep.Repeat(new PlanStep.Sequence(segment), 1));
        }
        PlanStep unit = new PlanStep.Sequence(atoms);
        for (int i = 0; i < wrappers; i++) unit = new PlanStep.Repeat(new PlanStep.Sequence(List.of(unit)), 1);
        return unit;
    }
    private static void generated() {
        for (int length : new int[]{2, 3, 7, 16, 33}) for (int copies = 2; copies <= 5; copies++)
        for (int wraps : new int[]{0, 1, 5}) for (long outer : new long[]{0, 1, 3}) {
            var selected = new PlanStep.Repeat(repeated(length, copies, wraps), outer);
            var reverse = List.<PlanStep>of(new PlanStep.Batch("after", 4), selected, new PlanStep.Batch("before", 2));
            var result = run(reverse, selected, 100000, 64L << 20);
            check(result != null, "Missed exact compound period"); sameTrace(reverse, result);
        }
    }
    private static void sameTrace(List<PlanStep> reverse, PlanStep result) {
        var after = new ArrayList<String>(); RefillWordNormalFormsTest.trace(result, after);
        check(after.equals(RefillWordNormalFormsTest.traceWord(reverse)), "Changed a dispatch batch/order/count");
    }
    private static void boundaries() {
        var a = new PlanStep.Batch("a", 1); var b = new PlanStep.Batch("b", 2);
        for (var children : List.of(List.<PlanStep>of(a, b, a), List.<PlanStep>of(a, b, a, new PlanStep.Batch("b", 1)),
                List.<PlanStep>of(new PlanStep.Batch("FB", 1), b, new PlanStep.Batch("Ea", 1), b), List.<PlanStep>of(a, a, a, a))) {
            var atom = new PlanStep.Sequence(children);
            check(run(List.of(atom), atom, 100000, 64L << 20) == null, "Invented period / fused a one-event period");
        }
        var unit = repeated(4, 3, 0);
        check(run(List.of(a, b), unit, 100000, 64L << 20) == null, "Replaced an absent unit");
        var inner = new PlanStep.Repeat(new PlanStep.Sequence(List.of(a, b)), 3);
        var distinct = new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Sequence(List.of(a)), b)), 3);
        unit = new PlanStep.Sequence(List.of(inner, a, distinct, a));
        var result = run(List.of(unit), unit, 100000, 64L << 20);
        check(result != null, "Opaque normalized atoms not recognized"); sameTrace(List.of(unit), result);
        // Long equal prefixes force many KMP fallback links. No one turn may
        // contain an uncharged loop over all those links.
        var prefix = new ArrayList<PlanStep>(); for (int i = 0; i < 180; i++) prefix.add(a); prefix.add(b);
        var word = new ArrayList<PlanStep>(prefix); word.addAll(prefix);
        unit = new PlanStep.Sequence(word); result = run(List.of(unit), unit, 100000, 64L << 20);
        check(result != null, "Long primitive period"); sameTrace(List.of(unit), result);
    }
    private static void large() {
        PlanStep unit = new PlanStep.Repeat(new PlanStep.Repeat(repeated(3, 5, 2), Long.MAX_VALUE), Long.MAX_VALUE);
        var result = run(List.of(unit), unit, 10000, 64L << 20);
        check(result != null, "Unfolded enormous repeat counts");
        check(counts(unit).equals(counts(result)), "Overflow in symbolic dispatch multiplicities");
        for (int i = 0; i < 20000; i++) unit = new PlanStep.Repeat(unit, 1);
        check(run(List.of(unit), unit, 100000, 64L << 20) == null, "Unbounded wrapper workspace");
    }
    private static Map<String, BigInteger> counts(PlanStep p) {
        if (p instanceof PlanStep.Batch b) return Map.of(b.recipe() + "=" + b.runs(), BigInteger.ONE);
        var out = new TreeMap<String, BigInteger>();
        if (p instanceof PlanStep.Repeat r) counts(r.body()).forEach((k, n) -> out.put(k, n.multiply(BigInteger.valueOf(r.times()))));
        else for (var child : ((PlanStep.Sequence) p).children()) counts(child).forEach((k, n) -> out.merge(k, n, BigInteger::add));
        return out;
    }
    private static void cutoffs() {
        var atom = repeated(32, 4, 20); var reverse = List.of(atom);
        for (long cap : new long[]{0, 1, 31, 63, 64, 65, 128, 512}) check(run(reverse, atom, cap, 64L << 20) == null, "Published partial factorization");
        for (long bytes : new long[]{1, 4096, 8192, 16384}) check(run(reverse, atom, 100000, bytes) == null, "Ignored optional memory share");
        for (String mode : List.of("work", "time", "memory", "cancel")) {
            var clock = new AtomicLong(); var cancelled = new AtomicBoolean();
            var b = new PlanningBudget(1, 100000, 64L << 20, cancelled::get, clock::get); long held = 0; b.failureDetail("proof");
            try (var work = new RefillUnitPeriods(reverse, atom, b, 200000)) {
                check(!work.step() && work.result() == null, "Early factorization");
                switch (mode) {
                    case "work" -> b.charge(b.remainingWork());
                    case "time" -> clock.set(2000000);
                    case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                    case "cancel" -> cancelled.set(true);
                }
                try { while (!work.step()) {} check(mode.equals("memory"), "Ignored resource boundary"); }
                catch (PlanningBudget.Exhausted expected) { check(mode.equals("work") || mode.equals("time"), "Unexpected exhaustion"); }
                catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                check(work.result() == null, "Published interrupted factorization");
                if (mode.equals("memory")) check(b.failureDetail().equals("proof"), "Optional refusal replaced proof");
            } finally { b.release(held); }
            check(b.reservedBytes() == 0, "Interrupted lease leak");
        }
    }
    private static PlanStep run(List<PlanStep> reverse, PlanStep selected, long cap, long bytes) {
        var b = new PlanningBudget(0, 1000000, bytes, () -> false, System::nanoTime); PlanStep result;
        var work = new RefillUnitPeriods(reverse, selected, b, cap);
        try {
            while (true) { long before = b.nodes(); boolean done = work.step(); check(b.nodes() - before <= 64, "Unbounded period turn"); if (done) break; }
            result = work.result(); check(b.nodes() <= cap, "Exceeded local allowance");
        } finally { work.close(); work.close(); }
        check(b.reservedBytes() == 0 && b.cachePreparationWork() == 0, "Period workspace/account leak"); return result;
    }
    private static void check(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
}
