// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

public final class CapturedRecipeDomainTest {

    public static void main(String[] args) {
        prefixCompatibility();
        independentMixedOracle();
        largeDomains();
        boundsAndOwnership();
        System.out.println("CapturedRecipeDomainTest passed");
    }

    private static void prefixCompatibility() {
        int cases = 0;
        for (boolean external : List.of(false, true)) {
            for (int n : new int[]{1, 2, 4, 16, 20, 255, 256, 257, 300}) {
                for (int copies : new int[]{1, 2, 3, 9, 10}) {
                    var captured = new CapturedRecipe<>(List.of(input("a", n, copies), input("b", 3, 1)),
                            List.of(new CapturedRecipe.Amount<>("out", 1)), external, false);
                    var budget = budget();
                    var prefix = captured.expand(budget());
                    try (var domain = captured.domain(budget)) {
                        for (int i = 0; i < captured.size(); i++) {
                            var first = prefix.next();
                            var second = domain.next();
                            check(first.equals(second), "prefix changed: " + external + "/" + n + "/" + copies + "/" + i);
                        }
                        check(domain.coveredPrefix().equals(BigInteger.valueOf(captured.size())), "coverage cursor");
                    }
                    check(budget.reservedBytes() == 0, "prefix domain reservation leak");
                    cases++;
                }
            }
        }
        check(cases == 90, "case count");
    }

    private static void independentMixedOracle() {
        for (int n = 2; n <= 6; n++) for (int copies = 2; copies <= 5; copies++) {
            // Independent descending vector enumeration, then move all singles first.
            List<List<GraphRecipe.Slot<String>>> expected = new ArrayList<>();
            for (int i = 0; i < n; i++) expected.add(List.of(new GraphRecipe.Slot<>("v" + i, copies, 0)));
            oracle(new int[n], 0, copies, expected);
            var captured = new CapturedRecipe<>(List.of(input("v", n, copies)),
                    List.of(new CapturedRecipe.Amount<>("out", 1)), false, false);
            var budget = budget();
            try (var domain = captured.domain(budget)) {
                check(domain.size().equals(BigInteger.valueOf(expected.size())), "exact compositions");
                for (int i = 0; i < expected.size(); i++) {
                    check(domain.next().slots().equals(expected.get(i)), "mixed DFS order: " + n + "/" + copies + "/" + i);
                    var allocation = new java.util.LinkedHashMap<Integer, Long>();
                    for (var value : expected.get(i)) allocation.put(Integer.parseInt(value.key().substring(1)), value.amount());
                    check(domain.ordinal(List.of(allocation)).intValueExact() == i, "rank/unrank mismatch");
                }
                check(!domain.hasNext(), "mixed domain exhaustion");
            }
            check(budget.reservedBytes() == 0, "mixed reservation leak");
        }
    }

    private static void oracle(int[] counts, int at, int left, List<List<GraphRecipe.Slot<String>>> result) {
        if (at == counts.length) {
            if (left != 0) return;
            var slots = new ArrayList<GraphRecipe.Slot<String>>();
            for (int i = 0; i < counts.length; i++) if (counts[i] != 0) slots.add(new GraphRecipe.Slot<>("v" + i, counts[i], 0));
            if (slots.size() > 1) result.add(List.copyOf(slots));
            return;
        }
        for (int value = left; value >= 0; value--) {
            counts[at] = value;
            oracle(counts, at + 1, left - value, result);
        }
    }

    private static void largeDomains() {
        var inputs = new ArrayList<CapturedRecipe.Input<String>>();
        for (int i = 0; i < 16; i++) inputs.add(input("s" + i + "-", 300, 1));
        var captured = new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        var budget = budget();
        try (var domain = captured.domain(budget)) {
            check(domain.size().equals(BigInteger.valueOf(300).pow(16)), "Cartesian count overflow");
            var last = domain.resolve(domain.size().subtract(BigInteger.ONE));
            for (int i = 0; i < inputs.size(); i++) check(last.slots().get(i).key().equals("s" + i + "-299"), "random access");
            check(domain.coveredPrefix().signum() == 0, "random access falsely claimed full prefix");
            check(budget.peakBytes() < 65536 && budget.compilationWork() < 1024, "Cartesian product was enumerated");
        }
        check(budget.reservedBytes() == 0, "large domain ownership");
        var mixed = new CapturedRecipe<>(List.of(input("m", 1000, 9)), List.of(new CapturedRecipe.Amount<>("out", 1)), false, false);
        try (var domain = mixed.domain(budget)) {
            check(domain.size().compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0, "mixed count must exceed long");
            var last = domain.resolve(domain.size().subtract(BigInteger.ONE));
            check(last.slots().equals(List.of(new GraphRecipe.Slot<>("m998", 1, 0), new GraphRecipe.Slot<>("m999", 8, 0))), "last mixed rank");
        }
    }

    private static void boundsAndOwnership() {
        var captured = new CapturedRecipe<>(List.of(input("a", 2, 1)), List.of(new CapturedRecipe.Amount<>("out", 1)), true, true);
        var budget = budget();
        try (var domain = captured.domain(budget)) {
            check(!domain.captureComplete(), "host truncation lost");
            expect(IndexOutOfBoundsException.class, () -> domain.resolve(BigInteger.valueOf(2)));
            domain.next();
            domain.next();
            check(!domain.hasNext() && !domain.captureComplete(), "enumeration cannot complete a truncated capture");
        }
        check(budget.reservedBytes() == 0, "domain cleanup");
        var noInputs = new CapturedRecipe<String>(List.of(), List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        try (var domain = noInputs.domain(budget)) {
            check(domain.size().equals(BigInteger.ONE) && domain.next().outputs().equals(Map.of("out", 1L)), "zero inputs");
        }
        var empty = new CapturedRecipe<>(List.of(input("a", 0, 1)), List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        try (var domain = empty.domain(budget)) { check(!domain.hasNext(), "empty slot"); }
        var small = new PlanningBudget(0, 100, 1, () -> false, System::nanoTime);
        expect(PlanningBudget.Exhausted.class, () -> captured.domain(small));
        check(small.reservedBytes() == 0, "constructor refusal leak");
        var cancelled = budget();
        cancelled.cancel();
        expect(CancellationException.class, () -> captured.domain(cancelled));
        check(cancelled.reservedBytes() == 0, "cancelled constructor leak");
        var overflow = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(2,
                List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("x", Long.MAX_VALUE), null, false)))),
                List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        try (var domain = overflow.domain(budget)) {
            long before = budget.reservedBytes();
            expect(ArithmeticException.class, domain::next);
            check(domain.coveredPrefix().signum() == 0 && budget.reservedBytes() == before, "failed ordinal advanced or leaked");
        }
    }

    static CapturedRecipe.Input<String> input(String prefix, int size, long copies) {
        var candidates = new ArrayList<CapturedRecipe.Candidate<String>>();
        for (int i = 0; i < size; i++) candidates.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(prefix + i, 1), null, false));
        return new CapturedRecipe.Input<>(copies, candidates);
    }

    static PlanningBudget budget() { return new PlanningBudget(0, 20_000_000, 512L << 20, () -> false, System::nanoTime); }

    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName(), failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
