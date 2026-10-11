// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Inventory/demand and supply-boundary changes, with an independent exact derivation replay. */
public final class CountRowTemplatesTest {
    public static void main(String[] args) throws Exception {
        var recipes = List.of(recipe("a", "ore", 2, "out", 2), recipe("b", "ore", 4, "out", 2));
        var compiler = new GraphCompiler<>(recipes);
        var cache = compiler.rowTemplates;
        var initialBudget = budget();
        try (var model = RecipeCountModel.create(compiler, "out", 3, Map.of("ore", 5L), Map.of(), Set.of(), Set.of(), false, initialBudget)) {
            check(model != null, "Missing count model");
            var low = new BigInteger[model.recipes.size()];
            var high = new BigInteger[low.length];
            Arrays.fill(low, BigInteger.ZERO);
            Arrays.fill(high, BigInteger.TEN);
            try (var views = CountModelViews.create(model.constraints, low, high, initialBudget)) {
                views.retainTemplates(model);
                views.publishCuts(views.available().get(0), List.of(divide(model, "ore", 2), divide(model, "out", 2)), 0, new Object());
            }
            check(cache.cacheMetrics().entries() == 2, "Original material cuts were not retained through the portfolio");
            var wrong = new CountLpLearning.Cut(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), BigInteger.valueOf(-100)),
                    Map.of(model.rowKeys.indexOf("ore"), BigInteger.ONE), BigInteger.TWO);
            cache.remember(model, model.constraints, wrong, initialBudget);
            var branch = new ArrayList<>(model.constraints);
            branch.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), BigInteger.ZERO));
            cache.remember(model, branch, new CountLpLearning.Cut(branch.get(branch.size() - 1), Map.of(branch.size() - 1, BigInteger.ONE), BigInteger.ONE), initialBudget);
            check(cache.cacheMetrics().entries() == 2, "Invalid or branch-local derivation was cached");
            long occupied = initialBudget.availableBytes() - 32768;
            check(initialBudget.tryReserve(occupied), "Unable to arrange bounded cache admission");
            try { check(cache.reuse(model, initialBudget).isEmpty(), "Template scratch ignored available memory"); }
            finally { initialBudget.release(occupied); }
            check(cache.cacheMetrics().entries() == 2, "Memory refusal destroyed reusable templates");
        }
        check(initialBudget.reservedBytes() == 0, "Template capture workspace leak");

        var journal = new CountProof.Journal(16L << 20);
        var random = new Random(172912);
        for (int sample = 0; sample < 120; sample++) {
            long ore = sample % 2 == 0 ? 11 : random.nextInt(50), demand = 1 + random.nextInt(15), storedOut = random.nextInt(9);
            var shuffled = new ArrayList<>(recipes);
            Collections.shuffle(shuffled, random);
            var budget = budget();
            budget.proofJournal(journal);
            try (var model = RecipeCountModel.forShell(shuffled, Map.of("out", BigInteger.valueOf(demand)),
                    Map.of("ore", ore, "out", storedOut), Set.of(), budget)) {
                var cuts = cache.reuse(model, budget);
                check(cuts.size() == 2, "Reordered recipes or changed RHS lost a reusable derivation");
                check(cuts.stream().anyMatch(cut -> cut.row().equals(divide(model, "ore", 2).row())) &&
                        cuts.stream().anyMatch(cut -> cut.row().equals(divide(model, "out", 2).row())), "A stale RHS or ordinal was reused");
                // Independent enumeration checks both relaxed and strengthened requests.
                for (int a = 0; a <= 12; a++) for (int b = 0; b <= 12; b++) {
                    var values = new BigInteger[] {BigInteger.valueOf(a), BigInteger.valueOf(b)};
                    if (model.constraints.stream().allMatch(row -> holds(row, values)))
                        for (var cut : cuts) check(holds(cut.row(), values), "Template excluded a current witness");
                }
            }
            check(budget.reservedBytes() == 0, "Replay workspace leak");
        }

        for (int variant = 0; variant < 5; variant++) {
            var changed = new ArrayList<>(recipes);
            Set<String> external = Set.of();
            if (variant == 0) changed.add(recipe("new_supplier", "fuel", 1, "ore", 2));
            if (variant == 1) changed.set(0, recipe("a", "ore", 3, "out", 2));
            if (variant == 2) external = Set.of("ore");
            if (variant == 3) changed.remove(0);
            if (variant == 4) changed.add(recipe("new_out_supplier", "fuel", 1, "out", 1));
            var budget = budget();
            try (var model = RecipeCountModel.forShell(changed, Map.of("out", BigInteger.valueOf(3)), Map.of("ore", 1L, "fuel", 5L), external, budget)) {
                var cuts = cache.reuse(model, budget);
                check(cuts.size() == (variant == 3 ? 0 : 1), "Changed supply boundary was ignored: " + variant);
                String surviving = variant == 4 ? "ore" : "out";
                if (variant != 3) check(cuts.get(0).row().equals(divide(model, surviving, 2).row()), "Wrong surviving template");
            }
            check(budget.reservedBytes() == 0, "Boundary replay leak");
        }

        // A large unrelated catalog must not invalidate a small, complete proof support.
        var large = new ArrayList<>(recipes);
        for (int i = 0; i < 1500; i++) large.add(recipe("noise_" + i, "input_" + i, 1, "output_" + i, 1));
        Collections.shuffle(large, random);
        var largeBudget = budget();
        try (var model = RecipeCountModel.forShell(large, Map.of("out", BigInteger.valueOf(3)), Map.of("ore", 11L), Set.of(), largeBudget)) {
            check(cache.reuse(model, largeBudget).size() == 2, "Whole-model size blocked sparse template replay");
        }
        check(largeBudget.reservedBytes() == 0, "Large replay leak");

        // Production imports reconstructed constraints into the normal count-stage cut pool.
        var reusedBudget = budget();
        try (var search = new IntegerCountSearch<>(compiler, "out", 4, Map.of("ore", 8L), Map.of(), Set.of(), Set.of(),
                false, false, reusedBudget, System.nanoTime())) {
            check(reusedBudget.diagnostics().contains("replayed=2"), "Production request did not import templates");
        }
        check(reusedBudget.reservedBytes() == 0, "Count-stage template import leaked");

        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                int stock = i;
                futures.add(pool.submit(() -> {
                    var budget = budget();
                    try (var model = RecipeCountModel.forShell(recipes, Map.of("out", BigInteger.TEN), Map.of("ore", (long) stock), Set.of(), budget)) {
                        var cuts = cache.reuse(model, budget);
                        check(cuts.size() == 2 && cuts.stream().anyMatch(cut -> cut.row().equals(divide(model, "ore", 2).row())), "Concurrent requests exchanged RHS");
                    }
                    check(budget.reservedBytes() == 0, "Concurrent replay leak");
                }));
            }
            for (var future : futures) future.get();
        } finally { pool.shutdownNow(); }

        var archive = Files.createTempFile("cgse-row-template-", ".bin");
        try {
            journal.write(archive);
            var replay = CountProof.read(archive);
            check(replay.derivations().size() == 240, "Missing current-RHS proofs");
            for (var proof : replay.derivations()) check(CountProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "Replayed proof does not derive current row");
        } finally { Files.delete(archive); }
        check(compiler.cacheMetrics().caches().get("count_row_template").estimatedBytes() > 0, "Template cache residency is invisible");
        System.out.println("Row templates: 120 inventory/demand permutations, changed/missing/new producers, 1502-variable support, 32 concurrent requests, production import and 240 proof replays passed");
    }

    private static CountLpLearning.Cut divide(RecipeCountModel<String> model, String key, long divisor) {
        int id = model.rowKeys.indexOf(key);
        var source = model.constraints.get(id);
        var d = BigInteger.valueOf(divisor);
        Map<Integer, BigInteger> terms = new TreeMap<>();
        source.terms().forEach((i, a) -> terms.put(i, a.divide(d)));
        var qr = source.upper().divideAndRemainder(d);
        var bound = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
        return new CountLpLearning.Cut(new ExactLinearProgram.Constraint(terms, bound), Map.of(id, BigInteger.ONE), d);
    }
    private static boolean holds(ExactLinearProgram.Constraint row, BigInteger[] values) {
        var sum = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
        return sum.compareTo(row.upper()) <= 0;
    }
    private static GraphRecipe<String> recipe(String id, String input, long amount, String output, long produced) {
        return new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>(input, amount)), Map.of(output, produced));
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 128L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
