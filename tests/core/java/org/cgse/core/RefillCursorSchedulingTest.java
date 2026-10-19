// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;

/** Exact prefix replay across fused cursor boundaries and optional hint cutoffs. */
public final class RefillCursorSchedulingTest {
    private static int checks;
    public static void main(String[] args) {
        families(); largeAndIrregular(); boundaries(); mixedPorts(); interruptions();
        System.out.println("RefillCursorSchedulingTest: assertions=" + checks);
    }
    private static void families() {
        for (int length : new int[] {8, 16, 17, 24, 32, 48, 64}) for (long goal : new long[] {1, 3})
        for (long seed : new long[] {0, 3}) for (long joint : new long[] {0, 5})
        for (boolean reverse : List.of(false, true)) for (int shape = 0; shape < 2; shape++) {
            var f = RefillWordAnchorsTest.fixture(length, goal, seed, joint, reverse, shape);
            var b = budget(64L << 20); GraphPlan<String> result;
            try (var owner = owner(f.plan(), b)) {
                while (!owner.step()) {} long held = b.reservedBytes(), start = b.nodes();
                result = reduce(f.plan(), f.stock(), b, 8192, owner.summary(), true);
                check(b.nodes() - start <= 8256, "Increased optional allowance");
                check(b.reservedBytes() == held, "Borrowed verifier lease changed");
            }
            check(b.reservedBytes() == 0 && b.cachePreparationWork() == 0, "Private or cache budget leaked");
            check(result.initialExact().get("feed").equals(BigInteger.valueOf(f.needed())), "Missed scoped feed bound " + length + "/" + shape);
            for (int i = 0; i < length; i++) check(result.patternTimesExact().get("r" + i).equals(BigInteger.valueOf(f.needed())), "Split loop execution");
        }
    }
    private static void largeAndIrregular() {
        // Beyond the measured bound family, retain legality and monotonically
        // improving supply; a finite optional pass need not reach a minimum.
        for (int length : new int[] {80, 96, 128, 256}) for (int shape = 0; shape < 2; shape++) {
            var f = RefillWordAnchorsTest.fixture(length, 3, 3, 5, false, shape);
            var b = budget(64L << 20);
            try (var owner = owner(f.plan(), b)) {
                while (!owner.step()) {} long start = b.nodes();
                var p = reduce(f.plan(), f.stock(), b, 8192, owner.summary(), true);
                check(p.initialExact().get("feed").compareTo(BigInteger.valueOf(f.needed())) >= 0, "Violated conservation");
                check(b.nodes() - start <= 8256, "Large-word cap changed");
            }
            check(b.reservedBytes() == 0, "Large-word owner leaked");
        }
        var random = new Random(2026101905L);
        for (int test = 0; test < 80; test++) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            recipes.add(MissingMaterialExpansionTest.recipe("grow", Map.of("A", 2L, "feed", 1L), Map.of("A", 3L)));
            for (int i = 0; i < 24; i++) {
                var in = new LinkedHashMap<String, Long>(); var out = new LinkedHashMap<String, Long>();
                for (String key : List.of("A", "B", "C", "tool")) {
                    if (random.nextBoolean()) in.put(key, 1L + random.nextInt(4));
                    if (random.nextBoolean()) out.put(key, 1L + random.nextInt(4));
                }
                if (out.isEmpty()) out.put("C", 1L);
                recipes.add(MissingMaterialExpansionTest.recipe("r" + i, in, out));
            }
            recipes.add(MissingMaterialExpansionTest.recipe("finish", Map.of("A", 3L), Map.of("G", 1L)));
            var word = new ArrayList<PlanStep>(); word.add(new PlanStep.Batch("grow", 4));
            for (int i = 0; i < 250; i++) word.add(new PlanStep.Batch("r" + random.nextInt(24), random.nextInt(4)));
            word.add(new PlanStep.Batch("finish", 1));
            if (test % 2 == 0) Collections.reverse(recipes);
            var stock = Map.of("A", 2L, "tool", 1L);
            var p = RefillWordPruningTest.literalPlan(recipes, new PlanStep.Sequence(word), Map.of("tool", 1L), stock, Set.of());
            var b = budget(64L << 20);
            reduce(p, stock, b, 8192, null, test % 3 != 0);
            check(b.reservedBytes() == 0, "Irregular word leaked");
        }
    }
    private static void boundaries() {
        var f = RefillWordAnchorsTest.fixture(48, 3, 3, 5, false, 0);
        for (long cap : new long[] {0, 1, 64, 1024, 1984, 2047, 2048, 2049, 2112, 3072, 4096, 8192}) {
            var b = budget(64L << 20);
            reduce(f.plan(), f.stock(), b, cap, SequenceSummary.of(f.plan().steps(), f.plan().recipes()), true);
            check(b.nodes() <= cap + 64 && b.reservedBytes() == 0, "Cursor threshold/cutoff leaked");
        }
        for (long bytes : new long[] {1, 256, 1024, 8192, 32_768, 36_864, 42_000, 49_152, 60_000, 80_000, 128_000}) {
            var b = budget(bytes); b.failureDetail("existing proof");
            reduce(f.plan(), f.stock(), b, 8192, SequenceSummary.of(f.plan().steps(), f.plan().recipes()), true);
            check(b.reservedBytes() == 0 && b.failureDetail().equals("existing proof"), "Memory admission changed incumbent/proof");
        }
    }
    private static void mixedPorts() {
        // The first (forward) unit is reached only after the 2048 handoff.
        // Empty inputs, overlapping returned outputs, negative/zero/huge deltas
        // and opaque repeat units must keep the same exact prefix contract.
        long max = Long.MAX_VALUE;
        for (int shape = 0; shape < 3; shape++) {
            var recipes = List.of(
                    MissingMaterialExpansionTest.recipe("source", Map.of(), Map.of("A", 1L)),
                    MissingMaterialExpansionTest.recipe("grow", Map.of("A", max - 1, "raw", max, "tool", 1L), Map.of("A", max, "tool", 1L)),
                    MissingMaterialExpansionTest.recipe("side", Map.of("raw", 1L), Map.of("B", 1L)),
                    MissingMaterialExpansionTest.recipe("finish", Map.of("A", max, "B", 1L), Map.of("G", 1L)));
            var word = new ArrayList<PlanStep>();
            PlanStep unit = new PlanStep.Sequence(List.of(new PlanStep.Batch("grow", 2), new PlanStep.Batch("side", 1)));
            word.add(shape == 0 ? new PlanStep.Batch("source", 5) : new PlanStep.Repeat(unit, shape));
            for (int i = 0; i < 240; i++) { word.add(new PlanStep.Batch("grow", i % 3)); word.add(new PlanStep.Batch("side", 1)); }
            word.add(new PlanStep.Batch("finish", 1));
            var p = RefillWordPruningTest.literalPlan(recipes, new PlanStep.Sequence(word), Map.of("tool", 1L), Map.of(), Set.of());
            var b = budget(64L << 20);
            reduce(p, Map.of(), b, 30_000, SequenceSummary.of(p.steps(), p.recipes()), true);
            check(b.reservedBytes() == 0, "Mixed exact unit leaked");
        }
    }
    private static void interruptions() {
        var f = RefillWordAnchorsTest.fixture(64, 3, 3, 5, false, 0);
        var summary = SequenceSummary.of(f.plan().steps(), f.plan().recipes());
        for (String mode : List.of("work", "time", "memory", "cancel")) for (int stop : new int[] {0, 1, 15, 31, 32, 33, 40, 50, 65, 80, 100}) {
            var clock = new AtomicLong(); var cancelled = new AtomicBoolean();
            var b = new PlanningBudget(1, 100_000, 64L << 20, cancelled::get, clock::get); b.failureDetail("original proof"); long held = 0;
            try (var p = new RefillWordPruning<>(f.plan(), f.stock(), Set.of(), true, false, b, 8192, summary)) {
                for (int i = 0; i < stop; i++) if (p.step()) break;
                switch (mode) {
                    case "work" -> b.charge(b.remainingWork());
                    case "time" -> clock.set(2_000_000);
                    case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                    case "cancel" -> cancelled.set(true);
                }
                try { while (!p.step()) {} check(!mode.equals("cancel"), "Ignored cancellation"); verify(f.plan(), p.result(), f.stock()); }
                catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
            } finally { b.release(held); }
            check(b.reservedBytes() == 0 && b.failureDetail().equals("original proof"), "Interrupted index/cursor ownership");
        }
    }
    private static GraphPlan<String> reduce(GraphPlan<String> original, Map<String, Long> stock, PlanningBudget b,
                                           long cap, SequenceSummary<String> known, boolean force) {
        GraphPlan<String> result;
        try (var p = new RefillWordPruning<>(original, stock, Set.of(), force, false, b, cap, known)) {
            while (true) { long before = b.nodes(); boolean done = p.step(); check(b.nodes() - before <= 64, "Unbounded cursor slice"); if (done) break; }
            result = p.result();
        }
        verify(original, result, stock);
        return result;
    }
    private static void verify(GraphPlan<String> original, GraphPlan<String> p, Map<String, Long> stock) {
        RefillWordPruningTest.literalVerify(p, stock, Set.of());
        p.initialExact().forEach((k,n) -> check(n.compareTo(original.initialExact().getOrDefault(k, BigInteger.ZERO)) <= 0, "Increased withdrawal"));
        p.missingExact().forEach((k,n) -> check(n.compareTo(original.missingExact().getOrDefault(k, BigInteger.ZERO)) <= 0, "Increased shortage"));
        check(p.recipes().equals(original.recipes()) && p.seeds().equals(original.seeds()), "Changed binding/final seeds");
    }
    private static PlanVerification<String> owner(GraphPlan<String> p, PlanningBudget b) {
        return PlanVerification.refill(GraphPlan.ownedRefill(p, p.steps(), p.recipes(), p.initialExact(), Map.of(), GraphPlan.Result.FEASIBLE, 0), b);
    }
    private static PlanningBudget budget(long memory) { return new PlanningBudget(0, 1_000_000, memory, () -> false, System::nanoTime); }
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
}
