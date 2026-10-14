// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Current-stock oracles, complete-basis ownership, scope changes and concurrent reuse. */
public final class ConservationKernelTest {
    private record Result(boolean blocked, long work, long peak, List<BigInteger[]> basis, boolean eliminated) {}

    public static void main(String[] args) throws Exception {
        reuse();
        ownershipAndCaps();
        limits();
        randomAndConcurrent();
        System.out.println("Conservation kernels: exact warm basis, cross-target probes/fallback, stock/seeds/external/producer scope, ownership, caps, interruptions and 512 oracle requests passed");
    }

    private static List<GraphRecipe<String>> recipes(int n) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < n; i++) recipes.add(new GraphRecipe<>("ring" + i, "ring" + i,
                List.of(new GraphRecipe.Slot<>("K" + i, 1)), Map.of("K" + ((i + 1) % n), 1L)));
        var random = new Random(2026101410L + n);
        for (int i = 0; i < n; i++) {
            var inputs = new ArrayList<GraphRecipe.Slot<String>>();
            var outputs = new LinkedHashMap<String, Long>();
            for (int j = 0; j < 4; j++) {
                inputs.add(new GraphRecipe.Slot<>("K" + random.nextInt(n), 1));
                outputs.merge("K" + random.nextInt(n), 1L, Long::sum);
            }
            recipes.add(new GraphRecipe<>("mix" + i, "mix" + i, inputs, outputs));
        }
        return recipes;
    }

    private static void reuse() throws Exception {
        var recipes = recipes(32);
        var compiler = new GraphCompiler<>(recipes);
        var stock = Map.of("K0", 5L);
        var first = run(compiler, "K0", 1, stock, Set.of(), Map.of(), Set.of(), budget());
        check(!first.blocked && first.eliminated, "Missing feasible cold basis");
        var capture = run(compiler, "K0", 1, stock, Set.of(), Map.of(), Set.of(), budget());
        check(!capture.blocked && capture.eliminated, "Repeated request did not complete original analysis");
        check(compiler.quantityCertificates(Set.of()).isEmpty(), "Feasible warmup unexpectedly published a deficit");
        var hot = run(compiler, "K0", 1, stock, Set.of(), Map.of(), Set.of(), budget());
        check(!hot.blocked && !hot.eliminated && hot.work < first.work / 2 && hot.peak < first.peak / 2,
                "Complete basis did not reduce work/workspace: " + first.work + " -> " + hot.work);
        equalBasis(first.basis, hot.basis);
        var other = run(compiler, "K1", 6, stock, Set.of(), Map.of(), Set.of(), budget());
        var coldOther = run(new GraphCompiler<>(recipes), "K1", 6, stock, Set.of(), Map.of(), Set.of(), budget());
        check(other.blocked && coldOther.blocked && !other.eliminated && other.work < coldOther.work / 2,
                "Different target failed to use current-stock ray");
        check(compiler.cacheMetrics().caches().get("conservation_overlap").hits() > 0, "No donor hit");
        var feasibleOther = run(compiler, "K2", 4, stock, Set.of(), Map.of(), Set.of(), budget());
        var coldFeasible = run(new GraphCompiler<>(recipes), "K2", 4, stock, Set.of(), Map.of(), Set.of(), budget());
        check(!feasibleOther.blocked && feasibleOther.eliminated, "Unsuccessful donor replaced original search");
        equalBasis(feasibleOther.basis, coldFeasible.basis);
        for (int variant = 0; variant < 5; variant++) {
            var currentStock = variant == 0 ? Map.of("K0", 8L) : stock;
            var external = variant == 1 ? Set.of("K3") : Set.<String>of();
            var seeds = variant == 2 ? Map.of("K0", 2L) : Map.<String, Long>of();
            var excluded = variant == 3 ? Set.of("mix0") : Set.<String>of();
            long amount = variant == 4 ? 0 : 4;
            var a = run(compiler, "K0", amount, currentStock, external, seeds, excluded, budget());
            var b = run(new GraphCompiler<>(recipes), "K0", amount, currentStock, external, seeds, excluded, budget());
            check(a.blocked == b.blocked && a.blocked == (variant == 2), "Stale stock/seed/external/exclusion conclusion");
        }
        var wide = run(compiler, "K0", Long.MAX_VALUE, Map.of("K0", Long.MAX_VALUE), Set.of(),
                Map.of("K0", Long.MAX_VALUE), Set.of(), budget());
        check(wide.blocked, "Wide target plus seed lost its deficit");
        for (int variant = 0; variant < 3; variant++) {
            var changed = new ArrayList<>(recipes);
            if (variant == 0) changed.add(new GraphRecipe<>("supply", "supply", List.of(), Map.of("K1", 1L)));
            else changed.set(0, new GraphRecipe<>("ring0", "ring0",
                    List.of(new GraphRecipe.Slot<>("K0", 1, 0, variant == 2, false)), Map.of("K1", 2L)));
            var replacement = new GraphCompiler<>(changed);
            check(replacement.cacheMetrics().caches().get("conservation_basis").entries() == 0, "New catalog inherited structure");
            for (int repeat = 0; repeat < 3; repeat++)
                check(!run(replacement, "K0", 20, stock, Set.of(), Map.of(), Set.of(), budget()).blocked,
                        "New producer or updated semantics inherited old conservation proof");
        }
        // A mixed-sign nullspace has no globally nonnegative donor ray. It is
        // still reusable for the exact target, including an empty deficit search.
        var growth = new GraphCompiler<>(List.of(new GraphRecipe<>("pair", "pair", List.of(), Map.of("A", 1L, "B", 1L))));
        for (int i = 0; i < 3; i++) check(!run(growth, "A", 7, Map.of(), Set.of(), Map.of(), Set.of(), budget()).blocked, "Growth blocked");
        check(!run(growth, "B", 7, Map.of(), Set.of(), Map.of(), Set.of(), budget()).blocked, "Mixed ray escaped as a positive proof");
    }

    private static void ownershipAndCaps() {
        var keys = new ArrayList<>(List.of("A", "B"));
        var vector = new BigInteger[]{BigInteger.ONE, BigInteger.TWO};
        var source = new ArrayList<BigInteger[]>(); source.add(vector);
        var b = budget(); var kernel = ConservationKernel.capture(keys, source, b);
        check(kernel != null && b.reservedBytes() == 0, "Kernel preparation leaked");
        vector[0] = BigInteger.TEN; keys.clear(); source.clear();
        var restored = new ArrayList<BigInteger[]>(); kernel.restore(restored, Map.of("A", 0, "B", 1), false, b);
        check(restored.get(0)[0].equals(BigInteger.ONE), "Capture borrowed mutable vector");
        restored.get(0)[0] = BigInteger.ZERO; restored.clear();
        kernel.restore(restored, Map.of("A", 1, "B", 0), true, b);
        check(Arrays.equals(restored.get(0), new BigInteger[]{BigInteger.TWO, BigInteger.ONE}), "Restore exposed cache or lost coordinates");
        var manyKeys = new ArrayList<String>(); for (int i = 0; i < 192; i++) manyKeys.add("K" + i);
        var vectors = new ArrayList<BigInteger[]>();
        for (int i = 0; i < 12; i++) {
            var row = new BigInteger[192]; Arrays.fill(row, BigInteger.ONE.shiftLeft(200 + i)); vectors.add(row);
        }
        var compiler = new GraphCompiler<String>(List.of());
        for (int i = 0; i < 32; i++) {
            compiler.conservationBasis("K" + i, Set.of()); compiler.conservationBasis("K" + i, Set.of());
            compiler.rememberConservationBasis("K" + i, Set.of(), manyKeys, vectors, b);
            var stats = compiler.cacheMetrics().caches().get("conservation_basis");
            check(stats.entries() <= 8 && stats.estimatedBytes() <= (1L << 20), "Unbounded structural residency");
        }
        var stats = compiler.cacheMetrics();
        check(stats.caches().get("conservation_basis").evictions() > 0, "No weight eviction exercised");
        check(stats.equals(compiler.cacheMetrics()), "Metrics mutated retention");
        for (var row : vectors) Arrays.fill(row, BigInteger.ONE.shiftLeft(4095));
        check(ConservationKernel.capture(manyKeys, vectors, b) == null, "Oversized basis admitted");
        check(b.reservedBytes() == 0, "Admission cap leaked request memory");
    }

    private static void limits() throws Exception {
        var compiler = new GraphCompiler<>(recipes(32));
        for (int i = 0; i < 2; i++) {
            var b = new PlanningBudget(0, 2000, 64L << 20, () -> false, System::nanoTime);
            try { run(compiler, "K0", 1, Map.of(), Set.of(), Map.of(), Set.of(), b); }
            catch (PlanningBudget.Exhausted expected) { check(b.reservedBytes() == 0, "Exhausted request leaked"); }
        }
        check(compiler.conservationBasis("K0", Set.of()) == null, "Partial elimination was retained as complete");
        check(run(compiler, "K0", 1, Map.of(), Set.of(), Map.of(), Set.of(), budget()).blocked, "Fresh complete analysis lost proof");
        check(compiler.conservationBasis("K0", Set.of()) != null, "Complete retry did not replace missing cache");
        var lowMemory = new PlanningBudget(0, 4_000_000, 128L << 10, () -> false, System::nanoTime);
        var result = run(compiler, "K0", 1, Map.of(), Set.of(), Map.of(), Set.of(), lowMemory);
        check(result.blocked && !result.eliminated, "Warm basis needed the old dense workspace");
        var cancelled = new AtomicBoolean();
        var b = new PlanningBudget(0, 4_000_000, 64L << 20, cancelled::get, System::nanoTime);
        boolean caught = false;
        try (var analysis = new ConservationAnalysis<>(compiler, "K0", 1, Map.of(), Set.of(), Map.of(), Set.of(), b)) {
            while ((int) field(analysis, "phase") != 5) check(!analysis.step(), "Finished before cached validation");
            cancelled.set(true);
            try { analysis.step(); } catch (CancellationException expected) { caught = true; }
        }
        check(caught && b.reservedBytes() == 0, "Cached analysis cancellation leaked");
        check(run(compiler, "K0", 1, Map.of(), Set.of(), Map.of(), Set.of(), budget()).blocked, "Cancelled borrower corrupted cache");
    }

    private static void randomAndConcurrent() throws Exception {
        var recipes = recipes(24); var compiler = new GraphCompiler<>(recipes);
        for (int i = 0; i < 256; i++) oracle(compiler, recipes, i, true);
        var pool = Executors.newFixedThreadPool(4); var start = new CountDownLatch(1);
        var jobs = new ArrayList<Future<?>>();
        try {
            for (int t = 0; t < 4; t++) {
                final int thread = t;
                jobs.add(pool.submit(() -> { start.await(); for (int i = 0; i < 64; i++) oracle(compiler, recipes, 256 + thread * 64 + i, false); return null; }));
            }
            start.countDown();
            for (var job : jobs) job.get(60, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }

    private static void oracle(GraphCompiler<String> compiler, List<GraphRecipe<String>> recipes, int sample, boolean compareCold) throws Exception {
        String target = "K" + (sample % 24);
        long amount = 1 + sample % 11, stored = sample % 7;
        var stock = Map.of("K0", stored, "K1", 2L);
        var seeds = sample % 3 == 0 ? Map.of("K2", 2L) : Map.<String, Long>of();
        var external = sample % 11 == 0 ? Set.of("K3") : Set.<String>of();
        var excluded = sample % 5 == 0 ? Set.of("mix0") : Set.<String>of();
        // Ring edges can move every token anywhere. Every extra recipe preserves
        // the same total, so this is a complete feasibility oracle for these nets.
        boolean deficit = external.isEmpty() && stored + 2 < amount + (seeds.isEmpty() ? 0 : 2);
        var a = run(compiler, target, amount, stock, external, seeds, excluded, budget());
        check(a.blocked == deficit, "Current-stock token oracle failed at " + sample);
        if (compareCold) check(a.blocked == run(new GraphCompiler<>(recipes), target, amount, stock, external, seeds, excluded, budget()).blocked,
                "Warm/cold conclusions differ");
    }

    private static Result run(GraphCompiler<String> compiler, String target, long amount, Map<String, Long> stock, Set<String> external,
                              Map<String, Long> seeds, Set<String> excluded, PlanningBudget budget) throws Exception {
        try (var analysis = new ConservationAnalysis<>(compiler, target, amount, stock, external, seeds, excluded, budget)) {
            while (!analysis.step()) {}
            @SuppressWarnings("unchecked") var vectors = (List<BigInteger[]>) field(analysis, "basis");
            var copy = new ArrayList<BigInteger[]>(); for (var v : vectors) copy.add(v.clone());
            return new Result(analysis.blocked(), budget.nodes(), budget.peakBytes(), copy, field(analysis, "matrix") != null);
        } finally { check(budget.reservedBytes() == 0, "Analysis retained request memory"); }
    }

    private static Object field(Object object, String name) throws Exception {
        var field = ConservationAnalysis.class.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void equalBasis(List<BigInteger[]> a, List<BigInteger[]> b) {
        check(a.size() == b.size(), "Basis dimension changed");
        for (int i = 0; i < a.size(); i++) check(Arrays.equals(a.get(i), b.get(i)), "Free-column basis changed");
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
