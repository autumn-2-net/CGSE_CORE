// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Negative structural certificates must never hide an exact short tandem. */
public final class RefillShortBlockHintTest {
    private static int checks;
    public static void main(String[] args) {
        boundaries(); opaque(); generated(); accounting();
        System.out.println("RefillShortBlockHintTest: assertions=" + checks);
    }
    private static PlanStep b(String id, long count) { return new PlanStep.Batch(id, count); }
    private static void boundaries() {
        for (int distance : new int[] {1, 2, 15, 16, 17, 32, 64}) {
            var word = new ArrayList<PlanStep>();
            for (int copy = 0; copy < 3; copy++) for (int i = 0; i < distance; i++) word.add(b("r" + i, i % 4));
            check(hint(word) == (distance <= 16), "Window boundary " + distance);
            certify(word);
        }
        check(!hint(List.of(b("FB", 1), b("Ea", 1), b("FB", 2), b("Ea", 2))), "Hash/count collision");
        check(hint(List.of(b("FB", 0), b("Ea", 1), b("FB", 0))), "Zero-count atom equality");
        check(!hint(List.of()), "Empty stream");
        check(!hint(List.of(b("single", 1))), "One atom");
    }
    private static void opaque() {
        PlanStep a = b("a", 1), c = b("a", 1);
        for (int i = 0; i < 10_000; i++) { a = new PlanStep.Repeat(a, Long.MAX_VALUE); c = new PlanStep.Repeat(c, Long.MAX_VALUE); }
        check(!hint(List.of(a, c)), "Recursed through structurally equal opaque atoms");
        check(hint(List.of(a, b("x", 1), a)), "Lost identical composite");
        certify(List.of(a, c, a, c));
    }
    private static void generated() {
        var random = new Random(2026101904L);
        for (int test = 0; test < 600; test++) {
            var word = new ArrayList<PlanStep>();
            for (int i = 0, n = random.nextInt(80); i < n; i++) word.add(b("r" + random.nextInt(40), random.nextInt(4)));
            if (test % 2 == 0) {
                var body = new ArrayList<PlanStep>();
                for (int i = 0, n = 2 + random.nextInt(15); i < n; i++) body.add(b("p" + i, random.nextInt(4)));
                word.addAll(body); word.addAll(body);
                check(hint(word), "Missed planted short tandem");
            }
            certify(word);
            Collections.reverse(word); certify(word);
        }
    }
    private static void certify(List<PlanStep> word) {
        var budget = MissingMaterialExpansionTest.budget(100_000);
        boolean possible = hint(word);
        try (var scan = new RefillWordBlocks(word, budget, 100_000)) {
            while (!scan.step()) {}
            check(possible || scan.result() == null, "Negative hint suppressed exact grouping");
        }
        check(budget.reservedBytes() == 0, "Block scan leaked");
    }
    private static boolean hint(List<PlanStep> word) {
        var budget = MissingMaterialExpansionTest.budget(100_000);
        boolean possible;
        try (var hint = RefillShortBlockHint.tryCreate(budget)) {
            check(hint != null, "Unexpected reservation refusal");
            for (var step : word) {
                hint.offer(step);
                if (hint.possible()) check(budget.reservedBytes() == 0, "Positive hint retained index");
            }
            possible = hint.possible();
        }
        check(budget.reservedBytes() == 0 && budget.nodes() == 0, "Index allocated a new scan/budget");
        return possible;
    }
    private static void accounting() {
        for (long memory : new long[] {1, 2304, 36_863}) {
            var budget = new PlanningBudget(0, 1000, memory, () -> false, System::nanoTime);
            budget.failureDetail("original proof");
            check(RefillShortBlockHint.tryCreate(budget) == null, "Optional hint displaced primary memory");
            check(budget.reservedBytes() == 0 && budget.failureDetail().equals("original proof"), "Refusal damaged proof");
        }
        var budget = new PlanningBudget(0, 1000, 36_864, () -> false, System::nanoTime);
        var hint = RefillShortBlockHint.tryCreate(budget); check(hint != null, "Exact memory admission boundary");
        for (int i = 0; i < 100_000; i++) hint.offer(b("r" + i, i));
        check(!hint.possible() && budget.reservedBytes() == 2304, "Index grew with stream length");
        hint.close(); hint.close(); check(budget.reservedBytes() == 0, "Double close/accounting");
    }
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
}
