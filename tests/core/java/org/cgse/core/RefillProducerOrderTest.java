// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Material semantics, linear shared support, and real scheduler fairness. */
public final class RefillProducerOrderTest {
    private static int checks;
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static PlanningBudget budget(long work, long bytes, AtomicBoolean cancelled) {
        return new PlanningBudget(0, work, bytes, cancelled::get, System::nanoTime);
    }
    private static void finish(RefillProducerOrder<String> order, PlanningBudget budget) {
        boolean done;
        do {
            long before = budget.nodes(); done = order.step();
            check(budget.nodes() - before <= 64, "Unbounded producer/rank/SCC step");
        } while (!done);
    }
    public static void main(String[] args) throws Exception {
        semantics(); sharedSupport(); stableReadiness(); projectedComponents(); lifecycle(); scheduler(false); scheduler(true);
        System.out.println("RefillProducerOrderTest: assertions=" + checks);
    }
    private static void semantics() {
        var recipes = List.of(r("feed", Map.of("raw", 1L), Map.of("fuel", 1L)),
                r("a", Map.of("B", 1L, "fuel", 1L), Map.of("A", 2L)),
                r("b", Map.of("A", 1L), Map.of("B", 1L)),
                r("finish", Map.of("A", 1L), Map.of("G", 1L)),
                r("escape", Map.of("raw", 1L), Map.of("B", 1L)));
        for (boolean escape : List.of(false, true)) {
            var b = budget(1_000_000, 1L << 20, new AtomicBoolean());
            try (var order = new RefillProducerOrder<>(new GraphCompiler<>(recipes), List.of("G"), Set.of(), escape ? Set.of() : Set.of("escape"), b)) {
                finish(order, b);
                check(order.rank("raw") == 0 && order.rank("fuel") == 1 && order.rank("A") == 2 && order.rank("G") == 3, "Recipe nodes added material depth");
                check(order.rank("B") == (escape ? 1 : 2), "Lost raw escape or shared SCC rank");
                check(order.get("A").cycle != escape && order.get("B").cycle != escape && !order.get("G").cycle, "Wrong cycle scope");
            }
            check(b.reservedBytes() == 0, "Semantic fixture leaked");
        }
        var growth = r("growth", Map.of("A", 1L, "tool", 1L, "raw", 1L), Map.of("A", 2L, "tool", 1L, "unused", 1L));
        var b = budget(1_000_000, 1L << 20, new AtomicBoolean());
        try (var order = new RefillProducerOrder<>(new GraphCompiler<>(List.of(growth)), List.of("A", "tool"), Set.of(), Set.of(), b)) {
            finish(order, b);
            check(order.rank("A") == 1 && order.get("A").cycle, "Growth lost its self-input enablement");
            check(order.get("tool").sources.isEmpty() && order.rank("tool") == 0, "Returned tool became a net producer");
            check(order.get("unused") == null, "Unreached coproduct was activated");
        }
        check(b.reservedBytes() == 0, "Growth fixture leaked");
        b = budget(1_000_000, 1L << 20, new AtomicBoolean());
        try (var order = new RefillProducerOrder<>(new GraphCompiler<>(List.of(growth)), List.of("A"), Set.of("A"), Set.of(), b)) {
            finish(order, b);
            check(order.rank("A") == 0 && order.get("raw") == null, "External supply was expanded");
        }
        check(b.reservedBytes() == 0, "External fixture leaked");
    }
    private static void sharedSupport() {
        for (int n : new int[] {16, 64, 128, 1024}) {
            var in = new LinkedHashMap<String, Long>(); var out = new LinkedHashMap<String, Long>();
            for (int i = 0; i < n; i++) { in.put("raw" + i, 1L); out.put("part" + i, 1L); }
            var b = budget(32L * n + 128, 1024L * (2 * n + 1), new AtomicBoolean());
            try (var order = new RefillProducerOrder<>(new GraphCompiler<>(List.of(r("joint", in, out))), out.keySet(), Set.of(), Set.of(), b)) {
                finish(order, b);
                for (int i = 0; i < n; i++) check(order.rank("raw" + i) == 0 && order.rank("part" + i) == 1, "Joint producer rank");
            }
            check(b.reservedBytes() == 0, "Joint support leaked");
        }
    }
    private record Fixture(GraphCompiler<String> compiler, List<String> roots, Set<String> external, Set<String> excluded) {}
    private static Fixture random(int trial) {
        var random = new Random(3129831 + trial); var recipes = new ArrayList<GraphRecipe<String>>();
        for (int j = 0; j < 24; j++) {
            var in = new LinkedHashMap<String, Long>(); var out = new LinkedHashMap<String, Long>();
            for (int i = 0, n = random.nextInt(4); i < n; i++) in.put("k" + random.nextInt(12), 1L + random.nextInt(3));
            for (int i = 0, n = 1 + random.nextInt(4); i < n; i++) out.put("k" + random.nextInt(12), 1L + random.nextInt(4));
            recipes.add(r("r" + j, in, out));
        }
        Collections.shuffle(recipes, random); var base = new GraphCompiler<>(recipes);
        var producers = new LinkedHashMap<String, List<GraphRecipe<String>>>();
        for (int i = 0; i < 12; i++) { var list = new ArrayList<>(base.producers("k" + i)); Collections.shuffle(list, random); producers.put("k" + i, list); }
        var roots = List.of("k" + random.nextInt(12), "k" + random.nextInt(12), "k" + random.nextInt(12));
        Set<String> external = trial % 3 == 0 ? Set.of("k" + random.nextInt(12)) : Set.of();
        Set<String> excluded = trial % 2 == 0 ? Set.of("r" + random.nextInt(24)) : Set.of();
        return new Fixture(new GraphCompiler<>(recipes, producers), roots, external, excluded);
    }
    private static void stableReadiness() {
        // Observed original incidence-order results. Grouping coproduct outputs
        // ahead of intervening sources changes these ranks and preview choices.
        int[][] examples = {{54, 0, 2}, {456, 4, 2}, {630, 0, 2}, {730, 4, 2}, {730, 5, 1},
                {1188, 5, 3}, {1551, 8, 3}, {1643, 0, 1}, {1803, 7, 1}, {1848, 3, 1}};
        for (var example : examples) {
            var f = random(example[0]); var b = budget(1_000_000, 1L << 20, new AtomicBoolean());
            try (var order = new RefillProducerOrder<>(f.compiler, f.roots, f.external, f.excluded, b)) {
                finish(order, b); check(order.rank("k" + example[1]) == example[2], "Shared source changed readiness order");
            }
            check(b.reservedBytes() == 0, "Readiness fixture leaked");
        }
    }
    private static void projectedComponents() {
        // Independent small material graph oracle: boolean derivability followed
        // by transitive closure, with no shared recipe vertices or Tarjan stack.
        for (int trial = 0; trial < 240; trial++) {
            var f = random(trial); var b = budget(1_000_000, 1L << 20, new AtomicBoolean());
            try (var order = new RefillProducerOrder<>(f.compiler, f.roots, f.external, f.excluded, b)) {
                finish(order, b);
                var reachable = new LinkedHashSet<>(f.roots); var todo = new ArrayList<>(reachable);
                var sources = new HashMap<String, List<GraphRecipe<String>>>();
                for (int at = 0; at < todo.size(); at++) {
                    String key = todo.get(at); var list = new ArrayList<GraphRecipe<String>>();
                    if (!f.external.contains(key)) for (var r : f.compiler.producers(key)) {
                        if (f.excluded.contains(r.id()) || r.outputs().getOrDefault(key, 0L) <= r.inputs().getOrDefault(key, 0L)) continue;
                        list.add(r); for (String input : r.inputs().keySet()) if (reachable.add(input)) todo.add(input);
                    }
                    sources.put(key, list);
                }
                var finite = new HashSet<String>();
                boolean changed;
                do {
                    changed = false;
                    for (String key : todo) if (!finite.contains(key) && (sources.get(key).isEmpty() ||
                            sources.get(key).stream().anyMatch(r -> finite.containsAll(r.inputs().keySet())))) changed |= finite.add(key);
                } while (changed);
                boolean[][] paths = new boolean[12][12];
                for (String key : todo) if (!finite.contains(key)) for (var r : sources.get(key)) for (String input : r.inputs().keySet())
                    if (!finite.contains(input)) paths[Integer.parseInt(key.substring(1))][Integer.parseInt(input.substring(1))] = true;
                for (int k = 0; k < 12; k++) for (int i = 0; i < 12; i++) for (int j = 0; j < 12; j++) paths[i][j] |= paths[i][k] && paths[k][j];
                for (int i = 0; i < 12; i++) {
                    String key = "k" + i; var node = order.get(key);
                    check((node != null) == reachable.contains(key), "Changed material reachability");
                    if (node == null) continue;
                    check(node.sources.stream().map(s -> s.recipe.id()).toList().equals(sources.get(key).stream().map(GraphRecipe::id).toList()), "Changed per-key source priority");
                    check(node.cycle == paths[i][i], "Cycle flag disagrees with projected material closure");
                    if (!finite.contains(key)) for (var r : sources.get(key)) for (String input : r.inputs().keySet()) {
                        int j = Integer.parseInt(input.substring(1));
                        check(paths[i][j] && paths[j][i] ? order.rank(key) == order.rank(input) : order.rank(key) > order.rank(input), "SCC outside-feedstock boundary changed");
                    }
                }
            }
            check(b.reservedBytes() == 0, "Projected graph leaked");
        }
    }
    private static List<GraphRecipe<String>> ring(int size) {
        var result = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < size; i++) result.add(r("r" + i, Map.of("k" + ((i + 1) % size), 1L, "raw", 1L), Map.of("k" + i, 2L)));
        return result;
    }
    private static void lifecycle() {
        var compiler = new GraphCompiler<>(ring(512));
        for (int stop = 0; stop < 240; stop++) {
            var cancel = new AtomicBoolean(); var b = budget(1_000_000, 1L << 20, cancel);
            try (var order = new RefillProducerOrder<>(compiler, List.of("k0"), Set.of(), Set.of(), b)) {
                try { for (int i = 0; ; i++) { if (i == stop) cancel.set(true); if (order.step()) break; } }
                catch (CancellationException expected) { check(cancel.get(), "Unexpected cancellation"); }
            }
            check(b.reservedBytes() == 0, "Cancelled cursor or component leaked");
        }
        for (long bytes : new long[] {1, 256, 1024, 8192, 65536, 1L << 20}) {
            var b = budget(1_000_000, bytes, new AtomicBoolean());
            try (var order = new RefillProducerOrder<>(compiler, List.of("k0"), Set.of(), Set.of(), b)) {
                try { finish(order, b); }
                catch (PlanningBudget.Exhausted exhausted) { check(exhausted.limit() == PlanningBudget.Limit.MEMORY_LIMIT, "Unexpected budget exit"); }
                order.close();
            }
            check(b.reservedBytes() == 0 && b.peakBytes() <= bytes, "Source memory escaped its account");
        }
    }
    private static void scheduler(boolean cancel) throws Exception {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 12000; i++) recipes.add(r("r" + i, Map.of("raw", 1L), Map.of("G", 1L)));
        var cancelled = new AtomicBoolean(); var b = budget(1_000_000, 16L << 20, cancelled);
        var entered = new CountDownLatch(1); var released = new CountDownLatch(1);
        try (var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
            var large = new PlanningScheduler.Work<Boolean>() {
                final RefillProducerOrder<String> order = new RefillProducerOrder<>(new GraphCompiler<>(recipes), List.of("G"), Set.of(), Set.of(), b);
                boolean started;
                public boolean advance(PlanningScheduler.Slice slice) {
                    if (!started) {
                        started = true; entered.countDown();
                        try { if (!released.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test handoff timed out"); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    }
                    while (slice.nextUncharged()) if (order.step()) return true;
                    return false;
                }
                public Boolean result() { return true; }
                public void close() { order.close(); }
            };
            var future = scheduler.submit(large, b);
            check(entered.await(5, TimeUnit.SECONDS), "Heavy work did not start");
            var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                long observed;
                public boolean advance(PlanningScheduler.Slice slice) { observed = b.nodes(); if (cancel) cancelled.set(true); return true; }
                public Long result() { return observed; }
            }, budget(1000, 1L << 20, new AtomicBoolean()));
            released.countDown();
            check(marker.get(10, TimeUnit.SECONDS) <= 64, "Large source list monopolized the actual scheduler");
            try { check(future.get(10, TimeUnit.SECONDS) && !cancel, "Cancelled rank work returned success"); }
            catch (CancellationException expected) { check(cancel, "Unexpected scheduler cancellation"); }
            catch (ExecutionException failure) { check(cancel && failure.getCause() instanceof CancellationException, "Wrong scheduler failure: " + failure); }
        } finally { released.countDown(); }
        check(b.reservedBytes() == 0, "Scheduled rank work leaked");
    }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
