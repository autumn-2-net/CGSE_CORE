// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Literal firing oracles for repeated recipe ports in the refill word pass. */
public final class RefillUnitTraversalTest {
    private static int checks;
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;

    public static void main(String[] args) {
        boundedWideWords(); preservedWideWord(); repeatedWords(); exactPorts(); mixedUnits(); interruptions();
        System.out.println("RefillUnitTraversalTest: assertions=" + checks);
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }

    private static GraphPlan<String> wide(int width, int copies, boolean reverse) {
        var keys = new ArrayList<String>(List.of("A", "tool"));
        for (int i = 0; i < width; i++) { keys.add("raw" + i); keys.add("return" + i); }
        if (reverse) Collections.reverse(keys);
        var in = new LinkedHashMap<String, Long>(); var out = new LinkedHashMap<String, Long>();
        for (var k : keys) {
            in.put(k, k.equals("A") ? 10L : 1L);
            if (!k.startsWith("raw")) out.put(k, k.equals("A") ? 11L : 1L);
        }
        out.put("joint", 1L);
        var recipes = new ArrayList<>(List.of(recipe("grow", in, out),
                recipe("finish", Map.of("A", 11L, "joint", 1L), Map.of("G", 1L))));
        if (reverse) Collections.reverse(recipes);
        var word = new ArrayList<PlanStep>();
        for (int i = 0; i < copies; i++) word.add(new PlanStep.Batch("grow", 1));
        word.add(new PlanStep.Batch("finish", 1));
        // This port-traversal oracle consumes A and retains only the explicit
        // tool. Automatic growth preservation is tested separately below.
        return RefillWordPruningTest.preservation(RefillWordPruningTest.literalPlan(recipes,
                new PlanStep.Sequence(word), Map.of("tool", 1L), Map.of(), Set.of()), false);
    }
    private static void preservedWideWord() {
        for (boolean reverse : List.of(false, true)) for (int copies : new int[] {12, 32}) {
            var ordinary = wide(32, copies, reverse);
            var original = RefillWordPruningTest.literalPlan(new ArrayList<>(ordinary.recipes().values()),
                    ordinary.steps(), Map.of("tool", 1L, "A", 10L), Map.of(), Set.of());
            var b = budget(100_000, 8L << 20);
            var result = reduce(original, b, 8192, SequenceSummary.of(original.steps(), original.recipes()), true);
            // Twelve wide firings reach the exact preserving bound within
            // the existing allowance. Both the old and new pruning pass can
            // exhaust that allowance on 32 firings; its safe incumbent must
            // survive without charging more work or consuming the seed.
            if (copies == 12) check(result.patternTimesExact().equals(Map.of("grow", BigInteger.valueOf(11), "finish", ONE)),
                    "Wide PRESERVE word missed its exact funded bound");
            var summary = SequenceSummary.of(result.steps(), result.recipes());
            var finalA = result.initialExact().get("A").add(summary.delta("A"));
            check(finalA.compareTo(BigInteger.TEN) >= 0 && (copies != 12 || finalA.equals(BigInteger.TEN)),
                    "Wide PRESERVE word lost its actual growth startup");
            check(b.nodes() <= 8192 + 64, "Wide PRESERVE exceeded its unchanged allowance");
            check(b.reservedBytes() == 0, "Wide PRESERVE workspace leaked");
        }
    }

    private static void boundedWideWords() {
        for (boolean reverse : List.of(false, true)) for (int width : new int[] {32, 128}) {
            var original = wide(width, width == 32 ? 32 : 8, reverse);
            // This is the existing optional allowance, with the exact fold
            // already available from the enclosing refill verifier.
            var b = budget(1_000_000, width == 32 ? 30_000 : 120_000);
            try (var v = PlanVerification.refill(funded(original), b)) {
                while (!v.step()) {}
                long before = b.nodes(), held = b.reservedBytes();
                var p = reduce(original, b, 8192, v.summary(), true);
                check(p.patternTimesExact().equals(Map.of("grow", ONE, "finish", ONE)), "Repeated wide word exceeded unchanged allowance");
                // One grow firing is necessary to supply joint; it yields the
                // required A and returns every tool. All other raw uses vanish.
                for (int i = 0; i < width; i++) {
                    check(p.missingExact().get("raw" + i).equals(ONE), "Overstated consumed feed");
                    check(p.initialExact().get("return" + i).equals(ONE), "Lost zero-net startup token");
                }
                check(p.initialExact().get("A").equals(BigInteger.TEN), "Changed startup bound");
                check(b.nodes() - before <= 8192, "Increased optional allowance");
                check(b.reservedBytes() == held, "Borrowed fold ownership changed");
            }
            check(b.reservedBytes() == 0, "Wide-word workspace leaked");
            check(b.cachePreparationWork() == 0, "Recipe traversal spent retention budget");
        }
    }

    private static void repeatedWords() {
        var random = new Random(202610191L);
        for (int test = 0; test < 300; test++) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            recipes.add(recipe("grow", Map.of("A", 2L, "raw", 1L), Map.of("A", 3L)));
            for (int r = 0; r < 4; r++) {
                var in = new LinkedHashMap<String, Long>(); var out = new LinkedHashMap<String, Long>();
                var keys = new ArrayList<>(List.of("A", "B", "C", "tool"));
                Collections.shuffle(keys, random);
                for (var k : keys) {
                    if (random.nextBoolean()) in.put(k, 1L + random.nextInt(4));
                    if (random.nextBoolean()) out.put(k, 1L + random.nextInt(4));
                }
                if (out.isEmpty()) out.put("B", 1L);
                recipes.add(recipe("r" + r, in, out));
            }
            recipes.add(recipe("finish", Map.of("A", 3L, "B", 2L), Map.of("G", 1L)));
            var word = new ArrayList<PlanStep>(); word.add(new PlanStep.Batch("grow", 4));
            for (int i = 0; i < 16; i++) word.add(new PlanStep.Batch("r" + random.nextInt(4), random.nextInt(4)));
            word.add(new PlanStep.Batch("finish", 1));
            if (test % 2 == 0) Collections.reverse(recipes);
            var p = RefillWordPruningTest.literalPlan(recipes, new PlanStep.Sequence(word), Map.of("tool", 1L), Map.of(), Set.of());
            var b = budget(100_000, 8L << 20); reduce(p, b, 8192, null, test % 3 != 0);
            check(b.reservedBytes() == 0, "Repeated word retained private maps");
        }
    }

    private static void exactPorts() {
        long max = Long.MAX_VALUE;
        var recipes = List.of(recipe("grow", Map.of("A", max - 1, "raw", max, "tool", max), Map.of("A", max, "tool", max)),
                recipe("finish", Map.of("A", max), Map.of("G", 1L)));
        var word = new PlanStep.Sequence(List.of(new PlanStep.Batch("grow", 3), new PlanStep.Batch("finish", 1)));
        var p = RefillWordPruningTest.preservation(
                RefillWordPruningTest.literalPlan(recipes, word, Map.of("tool", max), Map.of(), Set.of()), false);
        check(p.missingExact().get("raw").compareTo(BigInteger.valueOf(max)) > 0, "Not an exact oversized fixture");
        var b = budget(100_000, 8L << 20); p = reduce(p, b, 8192, null, true);
        check(p.patternTimesExact().equals(Map.of("grow", ONE, "finish", ONE)), "Exact port subtraction or counts overflowed");
        check(p.missingExact().get("raw").equals(BigInteger.valueOf(max)), "Large negative port delta changed");
        check(p.initialExact().get("tool").equals(BigInteger.valueOf(max)), "Large zero-net token lost");
        check(b.reservedBytes() == 0, "Exact workspace leaked");
    }

    private static void mixedUnits() {
        var recipes = List.of(recipe("grow", Map.of("A", 10L, "raw", 1L), Map.of("A", 11L)),
                recipe("side", Map.of("feed", 1L), Map.of("joint", 1L)),
                recipe("finish", Map.of("A", 11L, "joint", 1L), Map.of("G", 1L)));
        var body = new PlanStep.Sequence(List.of(new PlanStep.Batch("grow", 1), new PlanStep.Batch("side", 1)));
        var word = new PlanStep.Sequence(List.of(new PlanStep.Batch("grow", 10), new PlanStep.Repeat(body, 3),
                new PlanStep.Batch("grow", 0), new PlanStep.Batch("finish", 1)));
        var p = RefillWordPruningTest.literalPlan(recipes, word, Map.of(), Map.of(), Set.of());
        var b = budget(100_000, 8L << 20); p = reduce(p, b, 8192, null, true);
        check(p.patternTimesExact().equals(Map.of("grow", ONE, "side", ONE, "finish", ONE)), "Mixed direct/composite unit data crossed boundaries");
        check(b.reservedBytes() == 0, "Mixed unit workspace leaked");
    }

    private static void interruptions() {
        var p = wide(32, 32, false);
        for (long allowance : new long[] {0, 1, 64, 256, 1024, 4096, 8192}) {
            var b = budget(100_000, 8L << 20); b.failureDetail("retained proof");
            reduce(p, b, allowance, null, true);
            check(b.nodes() <= allowance + 64, "Cursor overshot one cooperative slice");
            check(b.failureDetail().equals("retained proof") && b.reservedBytes() == 0, "Optional cutoff damaged completed result");
        }
        for (long memory : new long[] {1, 256, 1024, 16_384}) {
            var b = budget(100_000, memory); b.failureDetail("retained proof");
            reduce(p, b, 50_000, null, true);
            check(b.failureDetail().equals("retained proof") && b.reservedBytes() == 0, "Memory cutoff leaked or replaced proof");
        }
        for (int stop : new int[] {0, 1, 20, 200, 1000}) {
            var cancelled = new AtomicBoolean();
            var b = new PlanningBudget(0, 100_000, 8L << 20, cancelled::get, System::nanoTime);
            try (var w = new RefillWordPruning<>(p, Map.of(), Set.of(), true, false, b, 50_000)) {
                for (int i = 0; i < stop; i++) if (w.step()) break;
                cancelled.set(true);
                try { w.step(); throw new AssertionError("Cancellation ignored"); }
                catch (CancellationException expected) { checks++; }
            }
            check(b.reservedBytes() == 0, "Cancelled cursor retained workspace");
        }
    }

    private static GraphPlan<String> reduce(GraphPlan<String> original, PlanningBudget b, long allowance,
                                             SequenceSummary<String> known, boolean force) {
        GraphPlan<String> p;
        try (var w = new RefillWordPruning<>(original, Map.of(), Set.of(), force, false, b, allowance, known)) {
            while (true) { long before = b.nodes(); boolean done = w.step(); check(b.nodes() - before <= 64, "Unbounded port walk"); if (done) break; }
            p = w.result();
        }
        RefillWordPruningTest.literalVerify(p, Map.of(), Set.of());
        for (var e : p.initialExact().entrySet()) check(e.getValue().compareTo(original.initialExact().getOrDefault(e.getKey(), ZERO)) <= 0, "Increased initial supply");
        for (var e : p.missingExact().entrySet()) check(e.getValue().compareTo(original.missingExact().getOrDefault(e.getKey(), ZERO)) <= 0, "Increased missing supply");
        check(p.recipes().equals(original.recipes()) && p.seeds().equals(original.seeds()), "Changed binding or final seed");
        return p;
    }

    private static GraphPlan<String> funded(GraphPlan<String> p) {
        return GraphPlan.ownedRefill(p, p.steps(), p.recipes(), p.initialExact(), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, 0);
    }
    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
}
