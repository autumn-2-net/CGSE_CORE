// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent physical-reachability oracle, ordered baseline digest and cold worker paths. */
public final class GraphEdgeCompactionTest {
    private static final HexFormat HEX = HexFormat.of();

    public static void main(String[] args) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (int seed = 0; seed < 600; seed++) random(seed, digest);
        for (boolean cyclic : new boolean[] {false, true}) dense(cyclic, digest);
        collidingKeys();
        interrupted();
        System.out.println("ORDERED_GRAPH_SHA256=" + HEX.formatHex(digest.digest()));
        System.out.println("Graph edge compaction: random physical SCCs, cold/packed/parallel paths, stock boundaries and interruption passed");
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 40_000_000, 256L << 20, () -> false, System::nanoTime);
    }

    private static <K> GraphCompiler.Compiled<K> compile(GraphCompiler<K> compiler, K target,
            Set<K> additional, Map<K, Integer> choices, Set<String> excluded, Set<K> leaves,
            PlanningBudget budget) {
        try (var work = compiler.beginStockView(target, additional, choices, excluded, leaves, budget)) {
            while (!work.step()) {}
            return work.result();
        }
    }

    private static void random(int seed, MessageDigest digest) {
        var rng = new Random(seed + 612_831L);
        var recipes = new ArrayList<GraphRecipe<String>>();
        int keys = 3 + rng.nextInt(24), count = 2 + rng.nextInt(28);
        for (int r = 0; r < count; r++) {
            var slots = new ArrayList<GraphRecipe.Slot<String>>();
            for (int i = rng.nextInt(12); i > 0; i--)
                slots.add(new GraphRecipe.Slot<>("k" + rng.nextInt(keys), 1 + rng.nextInt(3)));
            var outputs = new LinkedHashMap<String, Long>();
            outputs.put("k" + rng.nextInt(keys), 1L);
            for (int i = rng.nextInt(8); i > 0; i--) outputs.put("k" + rng.nextInt(keys), 2L);
            // Logical self-returns must not become physical producers; consumed
            // configuration returns, in contrast, are ordinary physical edges.
            if (r % 3 == 0) {
                boolean reusable = r % 2 == 0;
                slots.add(new GraphRecipe.Slot<>("config", 1, 0, true, reusable));
                outputs.put("config", 1L);
            }
            recipes.add(new GraphRecipe<>("r" + r, "r" + r, slots, outputs));
        }
        Collections.shuffle(recipes, rng);
        var compiler = new GraphCompiler<>(recipes);
        var choices = new LinkedHashMap<String, Integer>();
        var additional = new LinkedHashSet<String>();
        var excluded = new HashSet<String>();
        var leaves = new HashSet<String>();
        for (int i = 0; i < keys; i++) {
            if (rng.nextBoolean()) choices.put("k" + i, rng.nextInt(4));
            if (rng.nextInt(8) == 0) additional.add("k" + i);
            if (rng.nextInt(8) == 0) leaves.add("k" + i);
        }
        if (seed % 3 == 0) excluded.add(recipes.get(0).id());
        var b = budget();
        var graph = compile(compiler, "k0", additional, choices, excluded, leaves, b);
        oracle(graph);
        record(digest, graph);
        check(b.reservedBytes() == 0, "Random graph leaked");
        // A new compiler and changed leaves cannot borrow the earlier boundary.
        var full = compile(compiler, "k0", additional, choices, excluded, Set.of(), b);
        var fresh = compile(new GraphCompiler<>(recipes), "k0", additional, choices, excluded, Set.of(), b);
        check(full.equals(fresh), "Warm graph changed ordered structure at seed " + seed);
        oracle(full);
        record(digest, fresh);
        check(b.reservedBytes() == 0, "Warm graph leaked");
    }

    private static List<GraphRecipe<String>> denseRecipes(boolean cyclic) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int r = 0; r < 600; r++) {
            var slots = new ArrayList<GraphRecipe.Slot<String>>();
            var outputs = new LinkedHashMap<String, Long>();
            for (int k = 0; k < 12; k++) {
                slots.add(new GraphRecipe.Slot<>(r == 0 ? (cyclic ? "p599_" + k : "raw" + k) : "p" + (r - 1) + "_" + k, 1));
                outputs.put("p" + r + "_" + k, 1L);
            }
            recipes.add(new GraphRecipe<>("r" + r, "r" + r, slots, outputs));
        }
        return recipes;
    }

    private static void dense(boolean cyclic, MessageDigest digest) throws Exception {
        var recipes = denseRecipes(cyclic);
        var compiler = new GraphCompiler<>(recipes);
        var b = budget();
        var cold = compile(compiler, "p599_0", Set.of(), Map.of(), Set.of(), Set.of(), b);
        check(cold.regions().size() == (cyclic ? 1 : 600), "Dense partition incorrect");
        check(cold.regions().stream().allMatch(r -> r.cyclic() == cyclic), "Dense cyclic flag incorrect");
        oracle(cold);
        record(digest, cold);
        System.out.println("dense cyclic=" + cyclic + " work=" + b.compilationWork() + " peak=" + b.peakBytes());
        var limited = new PlanningBudget(0, 40_000_000, 1_100_000, () -> false, System::nanoTime);
        check(cold.equals(compile(new GraphCompiler<>(recipes), "p599_0", Set.of(), Map.of(), Set.of(), Set.of(), limited)),
                "Duplicate edges exhausted a budget sufficient for the compact graph");
        check(limited.reservedBytes() == 0, "Memory-bounded graph leaked");
        for (int workers : new int[] {1, 4}) {
            // A fresh compiler is essential: otherwise the SCC cache bypasses
            // the worker edge builder this test is supposed to exercise.
            var fresh = new GraphCompiler<>(recipes);
            var workBudget = budget();
            try (var scheduler = new PlanningScheduler(workers, 4, 17, 1_000_000_000);
                 var work = fresh.begin("p599_0", Map.of(), Set.of(), workBudget)) {
                var parallel = scheduler.submit(work, workBudget).get(30, TimeUnit.SECONDS);
                check(cold.equals(parallel), "Worker assembly changed ordered graph");
                record(digest, parallel);
            }
            check(workBudget.reservedBytes() == 0, "Worker compilation leaked");
        }
        // Explicitly prepare the optional packed catalog, avoiding an accidental
        // topology cache hit on an otherwise identical second compilation.
        var packed = new GraphCompiler<>(recipes);
        installPacked(packed);
        var packedBudget = budget();
        var packedGraph = compile(packed, "p599_0", Set.of(), Map.of(), Set.of(), Set.of(), packedBudget);
        check(cold.equals(packedGraph), "Packed ports changed ordered graph");
        record(digest, packedGraph);
        check(packedBudget.reservedBytes() == 0 && b.reservedBytes() == 0, "Dense graph leaked");
        // Bypass the completed topology cache while preserving the same packed
        // index owner, so four workers really process packed dependencies.
        // The test-only source map still needs the complete physical provider
        // lists; retaining catalog identity does not mean retaining a boundary.
        var sources = new LinkedHashMap<String, List<GraphRecipe<String>>>();
        for (var recipe : recipes) for (String key : recipe.executionOutputs().keySet())
            sources.computeIfAbsent(key, ignored -> new ArrayList<>()).add(recipe);
        var packedWorkers = new GraphCompiler<>(packed.catalog(), sources);
        packedWorkers.rememberCatalogIndex(packed.catalogIndex());
        var parallelBudget = budget();
        try (var scheduler = new PlanningScheduler(4, 4, 17, 1_000_000_000);
             var work = packedWorkers.begin("p599_0", Map.of(), Set.of(), parallelBudget)) {
            check(cold.equals(scheduler.submit(work, parallelBudget).get(30, TimeUnit.SECONDS)), "Packed worker graph changed");
        }
        check(parallelBudget.reservedBytes() == 0, "Packed worker graph leaked");
    }

    private static <K> void installPacked(GraphCompiler<K> compiler) {
        var b = budget();
        try (var builder = new GraphCatalogIndex.Builder<>(compiler.catalog(), b)) {
            for (int i = 0; i < compiler.catalog().size(); i++) builder.add(compiler.catalog().get(i), i);
            while (!builder.step()) {}
            check(builder.result() != null, "Packed test declined");
            compiler.rememberCatalogIndex(builder.result());
        }
        check(b.reservedBytes() == 0, "Packed catalog preparation leaked");
    }

    private record CollidingKey(int id) {
        @Override public int hashCode() { return 1; }
    }

    private static void collidingKeys() {
        for (int sample = 0; sample < 12; sample++) {
            var recipes = new ArrayList<GraphRecipe<CollidingKey>>();
            var additional = new LinkedHashSet<CollidingKey>();
            for (int i = 0; i < 100; i++) {
                var slots = new ArrayList<GraphRecipe.Slot<CollidingKey>>();
                for (int k = 0; k < 3; k++) slots.add(new GraphRecipe.Slot<>(new CollidingKey((i + k + 1) % 100), 1));
                var outputs = new LinkedHashMap<CollidingKey, Long>();
                outputs.put(new CollidingKey(i), 1L);
                outputs.put(new CollidingKey((i + 1) % 100), 1L);
                recipes.add(new GraphRecipe<>("r" + i, "r" + i, slots, outputs));
                additional.add(new CollidingKey(i));
            }
            // Equal resource keys deliberately have different identities in
            // input slots and output maps. HashMap tree-bin ordering must use
            // the same representatives in cold and packed compilations.
            var b = budget();
            var cold = compile(new GraphCompiler<>(recipes), new CollidingKey(0), additional, Map.of(), Set.of(), Set.of(), b);
            var compiler = new GraphCompiler<>(recipes);
            installPacked(compiler);
            var packed = compile(compiler, new CollidingKey(0), additional, Map.of(), Set.of(), Set.of(), b);
            check(cold.equals(packed), "Canonical key identity changed SCC priority");
            oracle(packed);
            check(b.reservedBytes() == 0, "Colliding resource compilation leaked");
        }
    }

    private static void interrupted() {
        var recipes = denseRecipes(true);
        for (long work : new long[] {1, 5_000, 60_000, 110_000, 160_000}) {
            var b = new PlanningBudget(0, work, 256L << 20, () -> false, System::nanoTime);
            try {
                compile(new GraphCompiler<>(recipes), "p599_0", Set.of(), Map.of(), Set.of(), Set.of(), b);
            } catch (PlanningBudget.Exhausted e) {
                check(e.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "Wrong work refusal");
            }
            check(b.reservedBytes() == 0, "Interrupted compilation leaked");
        }
        var cancel = new AtomicBoolean();
        var b = new PlanningBudget(0, 40_000_000, 256L << 20, cancel::get, System::nanoTime);
        try (var work = new GraphCompiler<>(recipes).begin("p599_0", Map.of(), Set.of(), b)) {
            for (int i = 0; i < 1000; i++) check(!work.step(), "Premature completion");
            cancel.set(true);
            try {
                while (!work.step()) {}
                throw new AssertionError("Cancellation ignored");
            } catch (CancellationException expected) { /* No partial graph is published. */ }
        }
        check(b.reservedBytes() == 0, "Cancelled graph leaked");
        var tiny = new PlanningBudget(0, 40_000_000, 4096, () -> false, System::nanoTime);
        var limitedCompiler = new GraphCompiler<>(recipes);
        try {
            compile(limitedCompiler, "p599_0", Set.of(), Map.of(), Set.of(), Set.of(), tiny);
            throw new AssertionError("Memory limit ignored");
        } catch (PlanningBudget.Exhausted expected) {
            check(expected.limit() == PlanningBudget.Limit.MEMORY_LIMIT, "Wrong memory result");
        }
        check(tiny.reservedBytes() == 0 && limitedCompiler.cacheMetrics().caches().get("topology").entries() == 0,
                "Partial graph survived memory failure");
    }

    private static <K> void oracle(GraphCompiler.Compiled<K> graph) {
        var recipes = new ArrayList<>(graph.recipes().values());
        int n = recipes.size();
        var reach = new BitSet[n];
        for (int i = 0; i < n; i++) {
            reach[i] = new BitSet(n);
            for (int j = 0; j < n; j++)
                if (!Collections.disjoint(recipes.get(i).inputs().keySet(), recipes.get(j).executionOutputs().keySet())) reach[i].set(j);
        }
        // Recipe-only transitive closure does not use compiler hubs, DFS or its
        // compact adjacency representation.
        for (int k = 0; k < n; k++) for (int i = 0; i < n; i++) if (reach[i].get(k)) reach[i].or(reach[k]);
        var region = new HashMap<String, Integer>();
        for (int r = 0; r < graph.regions().size(); r++) for (var recipe : graph.regions().get(r).recipes())
            check(region.put(recipe.id(), r) == null, "Repeated region member");
        check(region.size() == n, "Recipe absent from partition");
        for (int i = 0; i < n; i++) {
            int ri = region.get(recipes.get(i).id());
            check(graph.regions().get(ri).cyclic() == reach[i].get(i), "Physical cycle mismatch");
            for (int j = 0; j < n; j++) {
                int rj = region.get(recipes.get(j).id());
                check((ri == rj) == (i == j || reach[i].get(j) && reach[j].get(i)), "SCC reachability mismatch");
                check(!reach[i].get(j) || ri <= rj, "Consumer-first order violated");
            }
        }
    }

    private static void record(MessageDigest digest, GraphCompiler.Compiled<String> graph) {
        var text = new StringBuilder(graph.recipes().keySet().toString());
        graph.selected().forEach((key, recipe) -> text.append('|').append(key).append('=').append(recipe.id()));
        for (var region : graph.regions()) text.append('|').append(region.cyclic()).append(':')
                .append(region.recipes().stream().map(GraphRecipe::id).toList());
        digest.update((text + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
