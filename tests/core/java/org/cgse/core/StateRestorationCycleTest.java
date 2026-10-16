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
        repeatedDispatches();
        composedWords();
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

    private static void repeatedDispatches() {
        var random = new Random(0x51e71a1L);
        for (int sample = 0; sample < 4000; sample++) {
            var budget = budget(); var primitive = new ArrayList<Firing>();
            BigInteger firings = BigInteger.ZERO;
            try (var word = StateRestorationCycle.start("loop", pools(), budget)) {
                for (int j = 0, n = 1 + random.nextInt(5); j < n; j++) {
                    int[] need = new int[3], spent = new int[3], produced = new int[3];
                    for (int k = 0; k < 3; k++) {
                        need[k] = random.nextInt(8); spent[k] = random.nextInt(need[k] + 1); produced[k] = random.nextInt(8);
                    }
                    int runs = 1 + random.nextInt(6), times = 1 + random.nextInt(5);
                    check(word.record("r" + j, runs, times, amounts(need), amounts(spent), amounts(produced)), "Repeated dispatch declined");
                    firings = firings.add(BigInteger.valueOf((long) runs * times));
                    for (int i = 0; i < times; i++) primitive.add(new Firing(need, spent, produced));
                }
                check(word.exactFirings().equals(firings), "Repeated dispatch lost exact firings");
                int[] initial = {random.nextInt(51), random.nextInt(51), random.nextInt(51)};
                long[] inventory = {initial[0], initial[1], initial[2]};
                int limit = random.nextInt(30), completed = 0;
                outer: for (; completed < limit; completed++) {
                    var next = inventory.clone();
                    for (var f : primitive) {
                        for (int k = 0; k < 3; k++) if (next[k] < f.needed[k]) break outer;
                        for (int k = 0; k < 3; k++) next[k] += f.produced[k] - f.spent[k];
                    }
                    inventory = next;
                }
                var free = amounts(initial);
                long copies = word.copiesOfWord(limit, free, Set.of());
                check(copies == completed, "Serial configuration/prefix requirement changed under repetition");
                word.apply(copies, free);
                for (int k = 0; k < 3; k++) check(free.getOrDefault("k" + k, BigInteger.ZERO).equals(BigInteger.valueOf(inventory[k])), "Repeated delta differs from primitive replay");
                cases++;
            }
            check(budget.reservedBytes() == 0, "Repeated dispatch workspace leaked");
        }
        var budget = budget();
        try (var word = StateRestorationCycle.start("wide-loop", pools(), budget)) {
            check(word.record("wide", Long.MAX_VALUE, Long.MAX_VALUE, Map.of(), Map.of(), Map.of()), "Wide word declined");
            BigInteger expected = BigInteger.valueOf(Long.MAX_VALUE).pow(2);
            check(word.exactFirings().equals(expected), "Wide word overflowed");
            check(PlanCountComputation.of(word.word()).equals(Map.of("wide", expected)), "Wide serial word was flattened");
        }
        check(budget.reservedBytes() == 0, "Wide word retained memory");
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

    private static void composedWords() {
        var random = new Random(0x71eeacceL);
        for (int sample = 0; sample < 2000; sample++) {
            var budget = budget(); var base = new ArrayList<Firing>();
            for (int i = 0; i < 4; i++) {
                int[] need = new int[3], spent = new int[3], produced = new int[3];
                for (int k = 0; k < 3; k++) { need[k] = random.nextInt(6); spent[k] = random.nextInt(need[k] + 1); produced[k] = random.nextInt(6); }
                base.add(new Firing(need, spent, produced));
            }
            int repetitions = 1 + random.nextInt(5);
            var primitive = new ArrayList<Firing>(); primitive.add(base.get(0));
            for (int i = 0; i < repetitions; i++) primitive.addAll(base.subList(1, 3));
            primitive.add(base.get(3));
            try (var outer = StateRestorationCycle.start("outer", pools(), budget)) {
                var first = base.get(0);
                check(outer.record("prefix", amounts(first.needed), amounts(first.spent), amounts(first.produced)), "Prefix declined");
                try (var inner = StateRestorationCycle.start("inner", pools(), budget)) {
                    for (int i = 1; i < 3; i++) {
                        var f = base.get(i); check(inner.record("inner" + i, amounts(f.needed), amounts(f.spent), amounts(f.produced)), "Inner word declined");
                    }
                    check(outer.record(inner, inner.word(), repetitions), "Nested composition declined");
                }
                var last = base.get(3);
                check(outer.record("suffix", amounts(last.needed), amounts(last.spent), amounts(last.produced)), "Suffix declined");
                check(outer.exactFirings().equals(BigInteger.valueOf(2L + 2L * repetitions)), "Composed exact count changed");
                var counts = PlanCountComputation.of(outer.word());
                check(counts.equals(Map.of("prefix", BigInteger.ONE, "inner1", BigInteger.valueOf(repetitions), "inner2", BigInteger.valueOf(repetitions), "suffix", BigInteger.ONE)), "Closed inner workspace mutated outer word");
                int[] initial = {random.nextInt(41), random.nextInt(41), random.nextInt(41)};
                long[] inventory = {initial[0], initial[1], initial[2]}; int max = random.nextInt(21), completed = 0;
                outerLoop: for (; completed < max; completed++) {
                    var next = inventory.clone();
                    for (var f : primitive) {
                        for (int k = 0; k < 3; k++) if (next[k] < f.needed[k]) break outerLoop;
                        for (int k = 0; k < 3; k++) next[k] += f.produced[k] - f.spent[k];
                    }
                    inventory = next;
                }
                var free = amounts(initial); long copies = outer.copiesOfWord(max, free, Set.of());
                check(copies == completed, "Nested prefix differed from primitive execution"); outer.apply(copies, free);
                for (int k = 0; k < 3; k++) check(free.getOrDefault("k" + k, BigInteger.ZERO).equals(BigInteger.valueOf(inventory[k])), "Nested material delta changed");
                cases++;
            }
            check(budget.reservedBytes() == 0, "Composed word leaked");
        }
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
