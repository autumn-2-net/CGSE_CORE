// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Every imported clause must survive a full current supply row, independent of recipe coordinates. */
public final class CountSessionReuseTest {
    public static void main(String[] args) throws Exception {
        var cache = new CountSessions(); var b = budget();
        try (var model = CountSessionBudgetTest.model(b, 2)) {
            cache.remember(model, List.of(CountSessionBudgetTest.conflict(model)), b);
            check(cache.cacheMetrics().entries() == 1, "Missing support fixture");
            long first = b.cachePreparationWork();
            var next = budget(); cache.remember(model, List.of(CountSessionBudgetTest.conflict(model)), next);
            check(next.cachePreparationWork() > 0 && next.cachePreparationWork() < first, "Duplicate repeated proof construction");
            check(next.searchWork() == 0, "Duplicate preparation used solver work");
        }
        check(b.reservedBytes() == 0, "Fixture leaked");
        coordinatesAndTargets(cache);
        changedSupply(cache);
        chainedSupport();
        exhaustive(cache);
        journal(cache);
        readerEviction();
        System.out.println("Count sessions: checked sparse supports, reordered/superset/subset catalogs, changed targets/stocks/producers, exhaustive assignments and proof replay passed");
    }

    private static void coordinatesAndTargets(CountSessions cache) {
        for (int seed = 0; seed < 32; seed++) {
            var recipes = new ArrayList<>(CountSessionBudgetTest.RECIPES);
            // These recipes belong to other targets and can change freely.
            for (int i = 0; i < 12; i++) recipes.add(recipe("side" + i, Map.of("raw" + i, 1L), Map.of("other" + i, 1L)));
            Collections.shuffle(recipes, new Random(seed));
            var b = budget();
            try (var model = RecipeCountModel.forShell(recipes, Map.of("other0", BigInteger.ONE),
                    Map.of("ore", 2L, "dust", 999L, "raw0", 1L), Set.of(), b)) {
                var result = cache.reuse(model, b);
                check(result.equals(List.of(CountSessionBudgetTest.conflict(model))), "Coordinate/goal change lost a supported fact");
                CountSessionBudgetTest.verified(model, result);
            }
            check(b.reservedBytes() == 0, "Reordered reuse leaked");
        }
        var large = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 1000; i++) large.add(recipe("unrelated" + i, Map.of("raw" + i, 1L), Map.of("side" + i, 1L)));
        large.add(CountSessionBudgetTest.RECIPES.get(0));
        var b = budget();
        try (var model = RecipeCountModel.forShell(large, Map.of("goal", BigInteger.ONE), Map.of("ore", 2L), Set.of(), b)) {
            var result = cache.reuse(model, b);
            check(!result.isEmpty(), "Small proof rejected because surrounding model grew");
            CountSessionBudgetTest.verified(model, result);
        }
        check(b.reservedBytes() == 0, "Large-model reuse leaked");
    }

    private static void changedSupply(CountSessions cache) {
        var a = CountSessionBudgetTest.RECIPES.get(0);
        var variants = List.of(
                List.of(a),
                List.of(a, recipe("supply", Map.of("raw", 1L), Map.of("ore", 2L))),
                List.of(recipe("a", Map.of("ore", 1L), Map.of("goal", 1L))),
                List.of(a));
        for (int version = 0; version < variants.size(); version++) {
            var b = budget();
            try (var model = RecipeCountModel.forShell(variants.get(version), Map.of("goal", BigInteger.ONE),
                    Map.of("ore", version == 0 ? 4L : 2L, "raw", 1L), version == 3 ? Set.of("ore") : Set.of(), b)) {
                check(cache.reuse(model, b).isEmpty(), "Stale bound survived changed complete supply semantics " + version);
                var witness = new BigInteger[model.recipes.size()]; Arrays.fill(witness, BigInteger.ONE); witness[0] = BigInteger.TWO;
                check(satisfies(model, witness), "Changed-supply oracle is not feasible");
            }
            check(b.reservedBytes() == 0, "Changed-supply revalidation leaked");
        }
    }

    private static void exhaustive(CountSessions cache) {
        int checked = 0;
        for (long ore = 0; ore <= 6; ore++) for (long dust = 0; dust <= 6; dust++) for (int amount = 1; amount <= 3; amount++) {
            var b = budget();
            try (var model = RecipeCountModel.forShell(CountSessionBudgetTest.RECIPES, Map.of("goal", BigInteger.valueOf(amount)),
                    Map.of("ore", ore, "dust", dust), Set.of(), b)) {
                var imported = cache.reuse(model, b);
                for (int a = 0; a <= 3; a++) for (int c = 0; c <= 3; c++) {
                    var assignment = new BigInteger[]{BigInteger.valueOf(a), BigInteger.valueOf(c)};
                    if (satisfies(model, assignment)) for (var conflict : imported)
                        check(!conflict.impliedBy(assignment, assignment, b), "Imported clause removed feasible assignment");
                    checked++;
                }
            }
            check(b.reservedBytes() == 0, "Exhaustive replay leaked");
        }
        check(checked == 2352, "Incomplete finite oracle");
    }

    private static void chainedSupport() {
        var recipes = List.of(recipe("a", Map.of("ore", 1L), Map.of("mid", 1L)),
                recipe("b", Map.of("mid", 1L), Map.of("goal", 1L)),
                recipe("side", Map.of("raw", 1L), Map.of("other", 1L)));
        var cache = new CountSessions(); var b = budget();
        try (var model = RecipeCountModel.forShell(recipes, Map.of("goal", BigInteger.ONE), Map.of("ore", 2L, "raw", 1L), Set.of(), b)) {
            // b <= a <= 2: the ore premise does not directly touch b. A
            // first-neighbor working set alone cannot establish this clause.
            var conflict = new CountConflict(List.of(new ExactLinearProgram.Constraint(Map.of(1, BigInteger.ONE.negate()), BigInteger.valueOf(-3))));
            cache.remember(model, List.of(conflict), b);
            check(cache.cacheMetrics().entries() == 1, "Transitive support not retained");
        }
        for (int seed = 0; seed < 12; seed++) for (long ore = 1; ore <= 4; ore++) {
            var order = new ArrayList<>(recipes); Collections.shuffle(order, new Random(seed));
            try (var model = RecipeCountModel.forShell(order, Map.of("goal", BigInteger.ONE), Map.of("ore", ore, "raw", 99L), Set.of(), b)) {
                var conflicts = cache.reuse(model, b);
                check((ore <= 2) == !conflicts.isEmpty(), "Transitive resource boundary was weakened");
                if (!conflicts.isEmpty()) CountSessionBudgetTest.verified(model, conflicts);
                for (int a = 0; a <= 4; a++) for (int c = 0; c <= 4; c++) {
                    var x = new BigInteger[order.size()];
                    for (int i = 0; i < order.size(); i++) x[i] = BigInteger.valueOf(order.get(i).id().equals("a") ? a : order.get(i).id().equals("b") ? c : 0);
                    if (satisfies(model, x)) for (var conflict : conflicts)
                        check(!conflict.impliedBy(x, x, b), "Transitive learned core removed a feasible assignment");
                }
            }
        }
        check(b.reservedBytes() == 0, "Transitive support leaked");
    }

    private static void journal(CountSessions cache) throws Exception {
        var b = budget(); var journal = new CountProof.Journal(1L << 20); b.proofJournal(journal);
        var recipes = List.of(CountSessionBudgetTest.RECIPES.get(1), CountSessionBudgetTest.RECIPES.get(0));
        try (var model = RecipeCountModel.forShell(recipes, Map.of("goal", BigInteger.TWO), Map.of("ore", 2L, "dust", 6L), Set.of(), b)) {
            check(!cache.reuse(model, b).isEmpty(), "Reordered journal reuse failed");
            check(!journal.entries().isEmpty(), "Imported certificate was not exported");
            for (var proof : journal.entries()) {
                check(proof.variables() == model.recipes.size(), "Proof retained old coordinates");
                check(CountProof.verify(proof, 100_000) == CountProof.Verdict.VERIFIED, "Independent mapped certificate replay failed");
            }
            var path = java.nio.file.Files.createTempFile("cgse-session-proof-", ".bin");
            try {
                journal.write(path);
                for (var proof : CountProof.read(path).entries())
                    check(CountProof.verify(proof, 100_000) == CountProof.Verdict.VERIFIED, "Serialized proof changed");
            } finally { java.nio.file.Files.delete(path); }
        }
        check(b.reservedBytes() == 0, "Proof replay leaked");
    }

    private static void readerEviction() throws Exception {
        var cache = new CountSessions(); var owner = budget();
        try (var original = CountSessionBudgetTest.model(owner, 2)) {
            cache.remember(original, List.of(CountSessionBudgetTest.conflict(original)), budget());
            var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
            var b = new PlanningBudget(0, 4_000_000, 128L << 20, () -> {
                entered.countDown();
                try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Reader gate timeout"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return false;
            }, System::nanoTime);
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var read = pool.submit(() -> cache.reuse(original, b));
                check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "Reader did not acquire snapshot");
                for (int i = 0; i < 12; i++) {
                    var next = budget(); var recipe = recipe("replacement" + i, Map.of("ore" + i, 2L), Map.of("goal", 1L));
                    try (var model = RecipeCountModel.forShell(List.of(recipe), Map.of("goal", BigInteger.ONE), Map.of("ore" + i, 2L), Set.of(), next)) {
                        var conflict = new CountConflict(List.of(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.negate()), BigInteger.valueOf(-2))));
                        cache.remember(model, List.of(conflict), next);
                    }
                }
                check(cache.cacheMetrics().entries() == 8 && cache.cacheMetrics().evictions() > 0, "Eviction fixture failed");
                release.countDown(); check(!read.get(5, java.util.concurrent.TimeUnit.SECONDS).isEmpty(), "Pinned proof was corrupted");
                check(cache.reuse(original, budget()).isEmpty(), "Old reader resurrected evicted proof");
            } finally { release.countDown(); pool.shutdownNow(); }
            check(b.reservedBytes() == 0, "Pinned reader leaked");
        }
        check(owner.reservedBytes() == 0, "Eviction fixture leaked");
    }

    private static boolean satisfies(RecipeCountModel<?> model, BigInteger[] x) {
        for (var row : model.constraints) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(x[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static PlanningBudget budget() { return CountSessionBudgetTest.budget(); }
    private static void check(boolean ok, String message) { CountSessionBudgetTest.check(ok, message); }
}
