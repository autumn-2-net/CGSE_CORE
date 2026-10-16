// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent primitive-prefix oracle for observed state words. */
public final class StateRestorationCycleTest {
    private record Firing(int[] needed, int[] spent, int[] produced) {}
    private static int checks, cases;

    public static void main(String[] args) {
        randomPrefixes();
        identities();
        wideAmounts();
        limits();
        System.out.println("StateRestorationCycleTest: cases=" + cases + "; assertions=" + checks);
    }

    private static void randomPrefixes() {
        var random = new Random(0x513c1eL);
        for (int sample = 0; sample < 4000; sample++) {
            int size = 1 + random.nextInt(7);
            var firings = new ArrayList<Firing>();
            for (int i = 0; i < size; i++) {
                int[] need = new int[3], spent = new int[3], produced = new int[3];
                for (int k = 0; k < 3; k++) {
                    need[k] = random.nextInt(6); spent[k] = random.nextInt(need[k] + 1); produced[k] = random.nextInt(6);
                }
                firings.add(new Firing(need, spent, produced));
            }
            var budget = budget();
            try (var cycle = StateRestorationCycle.start("last", pools(), budget)) {
                for (int i = 0; i < size; i++) {
                    var f = firings.get(i);
                    check(cycle.record(i == size - 1 ? "last" : "r" + i, amounts(f.needed), amounts(f.spent), amounts(f.produced)), "Unexpected workspace decline");
                }
                check(cycle.matches("last", pools()), "Identical physical boundary was missed");
                for (int trial = 0; trial < 4; trial++) {
                    int[] initial = new int[]{random.nextInt(21), random.nextInt(21), random.nextInt(21)};
                    int max = random.nextInt(41), extra = random.nextInt(size);
                    Set<String> external = trial == 3 ? Set.of("k1") : Set.of();
                    long[] inventory = new long[]{initial[0], initial[1], initial[2]};
                    int completed = 0;
                    outer: for (; completed < max; completed++) {
                        long[] next = inventory.clone();
                        for (var firing : firings) {
                            for (int k = 0; k < 3; k++) if (!external.contains("k" + k) && next[k] < firing.needed[k]) break outer;
                            for (int k = 0; k < 3; k++) next[k] = Math.max(0, next[k] - firing.spent[k]) + firing.produced[k];
                        }
                        inventory = next;
                    }
                    var free = amounts(initial);
                    long copies = cycle.copies((long) max * size + extra, free, external);
                    check(copies == completed, "Prefix feasibility differed from primitive execution");
                    cycle.apply(copies, free);
                    for (int k = 0; k < 3; k++) if (!external.contains("k" + k))
                        check(free.getOrDefault("k" + k, BigInteger.ZERO).equals(BigInteger.valueOf(inventory[k])), "Ordinary material delta changed");
                    cases++;
                }
            }
            check(budget.reservedBytes() == 0, "Cycle summary leaked");
        }
    }

    private static void identities() {
        var budget = budget(); var owned = pools(); PlanStep detached;
        try (var cycle = StateRestorationCycle.start("last", owned, budget)) {
            check(cycle.record("last", Map.of("raw", BigInteger.TWO), Map.of("raw", BigInteger.TWO), Map.of("raw", BigInteger.TWO)), "Record failed");
            check(cycle.copies(100, Map.of("raw", BigInteger.ONE), Set.of()) == 0, "Zero net change erased startup demand");
            check(!cycle.matches("other-binding", owned), "Crossed exact transition identity");
            var reversed = new LinkedHashMap<String, BigInteger>(); reversed.put("BB", BigInteger.ONE); reversed.put("Aa", BigInteger.ONE);
            check(!cycle.matches("last", Map.of("family", reversed)), "Pool order changed under an allocation tie");
            check("Aa".hashCode() == "BB".hashCode(), "Expected a key-hash collision fixture");
            var changed = new LinkedHashMap<>(owned.get("family")); changed.put("BB", BigInteger.TWO);
            check(!cycle.matches("last", Map.of("family", changed)), "Entity quantity was ignored");
            check(!cycle.matches("last", Map.of("other-family", owned.get("family"))), "Family identity was ignored");
            detached = cycle.word();
        }
        check(((PlanStep.Sequence) detached).children().size() == 1, "Released detector mutated retained execution word");
        check(budget.reservedBytes() == 0, "Detached word bookkeeping leaked");
    }

    private static void wideAmounts() {
        var budget = budget(); BigInteger huge = BigInteger.ONE.shiftLeft(90), loss = huge.subtract(BigInteger.ONE);
        try (var cycle = StateRestorationCycle.start("last", pools(), budget)) {
            check(cycle.record("last", Map.of("raw", huge), Map.of("raw", huge), Map.of("raw", BigInteger.ONE)), "Wide record failed");
            var free = new LinkedHashMap<String, BigInteger>();
            free.put("raw", huge.add(loss.multiply(BigInteger.valueOf(Long.MAX_VALUE - 1))));
            check(cycle.copies(Long.MAX_VALUE, free, Set.of()) == Long.MAX_VALUE, "Copy bound overflowed");
            cycle.apply(Long.MAX_VALUE, free);
            check(free.get("raw").equals(BigInteger.ONE), "Wide accumulated delta overflowed");
        }
        check(budget.reservedBytes() == 0, "Wide cycle leaked");
    }

    private static void limits() {
        var tiny = new PlanningBudget(0, 1000, 1, () -> false, System::nanoTime);
        check(StateRestorationCycle.start("last", pools(), tiny) == null && tiny.reservedBytes() == 0, "Failed admission leaked");
        var budget = budget();
        try (var cycle = StateRestorationCycle.start("last", pools(), budget)) {
            long held = budget.availableBytes(); budget.reserve(held);
            check(!cycle.record("last", Map.of(), Map.of(), Map.of()), "Word bypassed memory admission");
            budget.release(held);
        }
        check(budget.reservedBytes() == 0, "Declined word retained memory");
        var cancel = new AtomicBoolean();
        var cancellable = new PlanningBudget(0, 1000, 1 << 20, cancel::get, System::nanoTime);
        try (var cycle = StateRestorationCycle.start("last", pools(), cancellable)) {
            cancel.set(true);
            try { cycle.record("last", Map.of(), Map.of(), Map.of()); throw new AssertionError("Ignored cancellation"); }
            catch (CancellationException expected) { checks++; }
        }
        check(cancellable.reservedBytes() == 0, "Cancelled word leaked");
        var shortBudget = new PlanningBudget(0, 1, 1 << 20, () -> false, System::nanoTime);
        try { StateRestorationCycle.start("last", pools(), shortBudget); throw new AssertionError("Ignored work cap"); }
        catch (PlanningBudget.Exhausted expected) { checks++; }
        check(shortBudget.reservedBytes() == 0, "Interrupted snapshot leaked");
    }

    private static Map<String, Map<String, BigInteger>> pools() {
        var states = new LinkedHashMap<String, BigInteger>(); states.put("Aa", BigInteger.ONE); states.put("BB", BigInteger.ONE);
        return Map.of("family", states);
    }
    private static Map<String, BigInteger> amounts(int[] values) {
        var map = new LinkedHashMap<String, BigInteger>();
        for (int k = 0; k < values.length; k++) if (values[k] != 0) map.put("k" + k, BigInteger.valueOf(values[k]));
        return map;
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 100_000, 4L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
