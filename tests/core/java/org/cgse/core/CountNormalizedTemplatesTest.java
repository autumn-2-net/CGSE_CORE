// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Multi-stage current-RHS proofs; independent arithmetic, complete supply changes and cancellation. */
public final class CountNormalizedTemplatesTest {
    private static final List<GraphRecipe<String>> RECIPES = List.of(
            recipe("a", Map.of("ore", 2L, "dust", 9L), Map.of("out", 2L)),
            recipe("b", Map.of("ore", 6L, "dust", 3L), Map.of("out", 2L)));

    public static void main(String[] args) throws Exception {
        var compiler = new GraphCompiler<>(RECIPES);
        var cache = compiler.rowTemplates;
        var budget = budget();
        try (var model = RecipeCountModel.create(compiler, "out", 3, Map.of("ore", 11L, "dust", 17L),
                Map.of(), Set.of(), Set.of(), false, budget)) {
            try (var views = CountModelViews.create(model.constraints, filled(2, 0), filled(2, 100), budget)) {
                views.retainTemplates(model);
                views.compileLight();
                var view = views.available().stream().filter(v -> v.name().equals("normalized")).findFirst().orElseThrow();
                var ore = normalized(model, "ore", 2);
                var dust = normalized(model, "dust", 3);
                var out = normalized(model, "out", 2);
                int oreId = view.rows().indexOf(ore), dustId = view.rows().indexOf(dust), outId = view.rows().indexOf(out);
                check(oreId >= 0 && dustId >= 0 && outId >= 0, "Missing normalized material rows");
                views.publishCuts(view, List.of(combine(view.rows(), oreId, dustId), combine(view.rows(), oreId, outId)), 0, new Object());
                check(cache.cacheMetrics().entries() == 2, "Normalized portfolio cuts were discarded");
                var forged = new ExactLinearProgram.Constraint(ore.terms(), ore.upper().subtract(BigInteger.ONE));
                cache.remember(model, List.of(forged), new CountLpLearning.Cut(forged, Map.of(0, BigInteger.ONE), BigInteger.ONE), budget);
                var branch = new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), BigInteger.ZERO);
                cache.remember(model, List.of(branch), new CountLpLearning.Cut(branch, Map.of(0, BigInteger.ONE), BigInteger.ONE), budget);
                var capped = new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE, 1, BigInteger.ONE), BigInteger.ONE);
                cache.remember(model, List.of(capped), new CountLpLearning.Cut(capped, Map.of(0, BigInteger.ONE), BigInteger.ONE), budget);
                check(cache.cacheMetrics().entries() == 2, "Conditional or forged premise became a catalog rule");
            }
        }
        check(budget.reservedBytes() == 0, "Normalized capture leak");

        var journal = new CountProof.Journal(16L << 20);
        var random = new Random(2026101108L);
        for (int sample = 0; sample < 180; sample++) {
            long ore = sample == 0 ? 5 : random.nextInt(50), dust = sample == 0 ? 5 : random.nextInt(50);
            long demand = 1 + random.nextInt(20), stored = random.nextInt(8);
            var recipes = new ArrayList<>(RECIPES);
            Collections.shuffle(recipes, random);
            var request = budget();
            request.proofJournal(journal);
            try (var model = RecipeCountModel.forShell(recipes, Map.of("out", BigInteger.valueOf(demand)),
                    Map.of("ore", ore, "dust", dust, "out", stored), Set.of(), request)) {
                var cuts = cache.reuse(model, request);
                check(cuts.size() == 2, "Changing inventory, demand or column order lost a derivation");
                var capacity = new ExactLinearProgram.Constraint(Map.of(0, BigInteger.TWO, 1, BigInteger.TWO),
                        BigInteger.valueOf(Math.floorDiv(Math.floorDiv(ore, 2) + Math.floorDiv(dust, 3), 2)));
                int b = model.recipes.get(0).id().equals("b") ? 0 : 1;
                var demandCut = new ExactLinearProgram.Constraint(Map.of(b, BigInteger.ONE),
                        BigInteger.valueOf(Math.floorDiv(Math.floorDiv(ore, 2) + Math.floorDiv(stored - demand, 2), 2)));
                check(cuts.stream().anyMatch(c -> c.row().equals(capacity)) && cuts.stream().anyMatch(c -> c.row().equals(demandCut)),
                        "A nested floor, negative RHS, or material identity was lost");
                if (sample == 0) {
                    check(capacity.upper().equals(BigInteger.ONE), "Test no longer distinguishes nested floors");
                    check(Math.floorDiv(3 * ore + 2 * dust, 12) == 2, "Flattened derivation comparison changed");
                }
                for (int x = 0; x <= 12; x++) for (int y = 0; y <= 12; y++) {
                    var point = new BigInteger[] {BigInteger.valueOf(x), BigInteger.valueOf(y)};
                    if (model.constraints.stream().allMatch(row -> holds(row, point)))
                        for (var cut : cuts) check(holds(cut.row(), point), "Template excluded a current witness");
                }
            }
            check(request.reservedBytes() == 0, "Normalized replay leak");
        }

        for (int variant = 0; variant < 6; variant++) {
            var recipes = new ArrayList<>(RECIPES);
            Set<String> external = Set.of();
            if (variant == 0) recipes.add(recipe("new_dust", Map.of("fuel", 1L), Map.of("dust", 1L)));
            if (variant == 1) recipes.add(recipe("new_ore", Map.of("fuel", 1L), Map.of("ore", 2L)));
            if (variant == 2) recipes.set(0, recipe("a", Map.of("ore", 2L, "dust", 9L), Map.of("out", 3L)));
            if (variant == 3) external = Set.of("dust");
            if (variant == 4) recipes.remove(0);
            if (variant == 5) for (int i = 0; i < 1500; i++) recipes.add(recipe("noise_" + i, Map.of("i_" + i, 1L), Map.of("o_" + i, 1L)));
            var request = budget();
            try (var model = RecipeCountModel.forShell(recipes, Map.of("out", BigInteger.valueOf(3)), Map.of("ore", 17L, "dust", 29L), external, request)) {
                check(cache.reuse(model, request).size() == (variant == 5 ? 2 : variant == 1 || variant == 4 ? 0 : 1),
                        "Supply-boundary invalidation failed: " + variant);
            }
            check(request.reservedBytes() == 0, "Supply-boundary leak");
        }

        for (int stop = 1; stop <= 160; stop++) {
            var checks = new AtomicInteger();
            int limit = stop;
            var owner = budget();
            var cancelled = new PlanningBudget(0, 4_000_000, 128L << 20, () -> checks.incrementAndGet() > limit, System::nanoTime);
            try (var model = RecipeCountModel.forShell(RECIPES, Map.of("out", BigInteger.ONE), Map.of("ore", 5L, "dust", 5L), Set.of(), owner)) {
                try { cache.reuse(model, cancelled); } catch (CancellationException ignored) {}
            }
            check(cancelled.reservedBytes() == 0 && owner.reservedBytes() == 0, "Cancellation leaked replay memory");
        }
        check(cache.cacheMetrics().entries() == 2, "Cancellation changed catalog templates");
        boundedCache();
        var path = Files.createTempFile("cgse-normalized-template-", ".bin");
        try {
            journal.write(path);
            var archive = CountProof.read(path);
            check(archive.derivations().size() == 360, "Missing current-request normalized proofs");
            for (var proof : archive.derivations()) {
                check(proof.steps().size() == 3, "Intermediate floor operations were flattened");
                check(CountProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "Independent normalized replay failed");
            }
        } finally { Files.delete(path); }
        System.out.println("Normalized templates: 180 inventory/demand/order permutations, 360 portable multi-step proofs, full supply changes, 1502 columns, cancellation passed");
    }

    private static void boundedCache() {
        var cache = new CountRowTemplates();
        var owner = budget();
        try (var model = RecipeCountModel.forShell(RECIPES, Map.of("out", BigInteger.ONE), Map.of("ore", 13L, "dust", 17L), Set.of(), owner)) {
            var source = normalized(model, "ore", 2);
            for (int weight = 1; weight <= 48; weight++) {
                BigInteger factor = BigInteger.valueOf(weight);
                Map<Integer, BigInteger> terms = new TreeMap<>();
                source.terms().forEach((id, a) -> terms.put(id, a.multiply(factor)));
                var row = new ExactLinearProgram.Constraint(terms, source.upper().multiply(factor));
                cache.remember(model, List.of(source), new CountLpLearning.Cut(row, Map.of(0, factor), BigInteger.ONE), owner);
            }
            check(cache.cacheMetrics().entries() == 32 && cache.cacheMetrics().evictions() == 16, "Template residency is not bounded");
            for (long memory : new long[] {131072, 262144, 2L << 20, 8L << 20, 128L << 20}) {
                var limited = new PlanningBudget(0, 4_000_000, memory, () -> false, System::nanoTime);
                var rows = cache.reuse(model, limited);
                check(rows.size() <= 16, "Replay returned an unbounded retained batch");
                if (memory >= (8L << 20)) check(rows.size() == 16, "Small template batch lost all budget opportunities");
                check(limited.reservedBytes() == 0, "Limited-memory replay leaked");
            }
            for (long work : new long[] {1, 32, 1024, 32768, 65536}) {
                var limited = new PlanningBudget(0, work, 128L << 20, () -> false, System::nanoTime);
                try { cache.reuse(model, limited); } catch (PlanningBudget.Exhausted ignored) {}
                check(limited.reservedBytes() == 0, "Limited-work replay leaked");
            }
        }
        check(owner.reservedBytes() == 0, "Catalog capacity test leaked");
    }

    private static ExactLinearProgram.Constraint normalized(RecipeCountModel<?> model, String material, long divisor) {
        var row = model.constraints.get(model.rowKeys.indexOf(material));
        Map<Integer, BigInteger> terms = new TreeMap<>();
        row.terms().forEach((id, a) -> terms.put(id, a.divide(BigInteger.valueOf(divisor))));
        return new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(Math.floorDiv(row.upper().longValueExact(), divisor)));
    }
    private static CountLpLearning.Cut combine(List<ExactLinearProgram.Constraint> rows, int a, int b) {
        Map<Integer, BigInteger> terms = new TreeMap<>(rows.get(a).terms());
        rows.get(b).terms().forEach((id, value) -> terms.merge(id, value, BigInteger::add));
        terms.values().removeIf(value -> value.signum() == 0);
        terms.replaceAll((id, value) -> value.divide(BigInteger.TWO));
        var bound = BigInteger.valueOf(Math.floorDiv(rows.get(a).upper().add(rows.get(b).upper()).longValueExact(), 2));
        return new CountLpLearning.Cut(new ExactLinearProgram.Constraint(terms, bound), Map.of(a, BigInteger.ONE, b, BigInteger.ONE), BigInteger.TWO);
    }
    private static boolean holds(ExactLinearProgram.Constraint row, BigInteger[] point) {
        var total = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) total = total.add(term.getValue().multiply(point[term.getKey()]));
        return total.compareTo(row.upper()) <= 0;
    }
    private static BigInteger[] filled(int n, long value) { var result = new BigInteger[n]; Arrays.fill(result, BigInteger.valueOf(value)); return result; }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 128L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
