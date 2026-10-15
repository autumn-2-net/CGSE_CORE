// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Port-equivalent topology reuse with current recipes and independent reachability. */
public final class GraphTopologyReuseTest {
    public static void main(String[] args) throws Exception {
        for (int seed = 0; seed < 240; seed++) random(seed);
        for (boolean ring : new boolean[]{false, true}) dense(ring);
        semantics();
        changedPorts();
        collisions();
        boundaries();
        concurrent();
        System.out.println("Topology reuse: ordered SCCs, current semantics, stock boundaries, collisions, cancellation and four workers passed");
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 40_000_000, 256L << 20, () -> false, System::nanoTime);
    }

    private static <K> GraphCompiler.Compiled<K> compile(GraphCompiler<K> compiler, K target, Set<K> additional,
            Map<K, Integer> choices, Set<String> excluded, Set<K> leaves, PlanningBudget budget) {
        try (var work = compiler.beginStockView(target, additional, choices, excluded, leaves, budget)) {
            while (!work.step()) {}
            return work.result();
        }
    }

    private static void random(int seed) {
        var random = new Random(168_219L + seed);
        int n = 2 + random.nextInt(32);
        var keys = new String[n + 4];
        for (int i = 0; i < keys.length; i++) keys[i] = "k" + i;
        var recipes = new ArrayList<GraphRecipe<String>>();
        var choices = new LinkedHashMap<String, Integer>();
        var additional = new LinkedHashSet<String>();
        for (int r = 0; r < n; r++) {
            var inputs = new LinkedHashMap<String, Long>();
            for (int i = random.nextInt(7); i > 0; i--) inputs.put(keys[random.nextInt(keys.length)], 1L);
            var outputs = new LinkedHashMap<String, Long>();
            outputs.put(keys[r], 1L);
            if (random.nextBoolean()) outputs.put(keys[n + 3], 1L);
            for (int variant = 0; variant < 2; variant++) {
                var slots = new ArrayList<GraphRecipe.Slot<String>>();
                for (var input : inputs.entrySet()) slots.add(new GraphRecipe.Slot<>(input.getKey(), 1 + variant + random.nextInt(3)));
                var amounts = new LinkedHashMap<String, Long>();
                for (String key : outputs.keySet()) amounts.put(key, 1L + variant + random.nextInt(5));
                String id = "r" + r + "/" + variant;
                recipes.add(new GraphRecipe<>(id, "binding/" + variant, slots, amounts));
            }
            additional.add(keys[r]);
            choices.put(keys[r], 1);
        }
        choices.put(keys[n + 3], 1);
        var compiler = new GraphCompiler<>(recipes);
        compile(compiler, keys[0], additional, Map.of(), Set.of(), Set.of(), budget());
        var b = budget();
        var rebound = compile(compiler, keys[0], additional, choices, Set.of(), Set.of(), b);
        var cold = compile(new GraphCompiler<>(recipes), keys[0], additional, choices, Set.of(), Set.of(), budget());
        check(rebound.equals(cold), "Changed ordered partition at seed " + seed);
        check(compiler.cacheMetrics().caches().get("topology_ports").hits() == 1, "Missing port reuse " + seed);
        oracle(rebound);
        check(b.searchWork() == 0 && b.cachePreparationWork() == 0 && b.reservedBytes() == 0,
                "Current graph compilation used search/retention work or leaked");
        // Selected-source and stock boundaries must be reconstructed, even if
        // their final physical partition happens to match a retained graph.
        var leaves = Set.of(keys[random.nextInt(n)]);
        var excluded = seed % 2 == 0 ? Set.of("r0/1") : Set.<String>of();
        var changed = compile(compiler, keys[0], additional, choices, excluded, leaves, budget());
        var fresh = compile(new GraphCompiler<>(recipes), keys[0], additional, choices, excluded, leaves, budget());
        check(changed.equals(fresh), "Reused an old boundary " + seed);
        oracle(changed);
    }

    private static List<GraphRecipe<String>> chain(int count, int width, boolean ring) {
        var keys = new String[count + 1][width];
        for (int i = 0; i <= count; i++) for (int j = 0; j < width; j++) keys[i][j] = "p" + i + "/" + j;
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 1; i <= count; i++) for (int variant = 0; variant < 2; variant++) {
            var slots = new ArrayList<GraphRecipe.Slot<String>>();
            var outputs = new LinkedHashMap<String, Long>();
            for (int j = 0; j < width; j++) {
                slots.add(new GraphRecipe.Slot<>(keys[i == 1 && ring ? count : i - 1][j], 1 + variant));
                outputs.put(keys[i][j], 1L + variant);
            }
            String id = "r" + i + "/" + variant;
            recipes.add(new GraphRecipe<>(id, id, slots, outputs));
        }
        return recipes;
    }

    private static Map<String, Integer> alternatives(List<GraphRecipe<String>> recipes) {
        var choices = new LinkedHashMap<String, Integer>();
        for (var recipe : recipes) for (String key : recipe.executionOutputs().keySet()) choices.put(key, 1);
        return choices;
    }

    private static void dense(boolean ring) {
        var recipes = chain(600, 12, ring);
        var compiler = new GraphCompiler<>(recipes);
        compile(compiler, "p600/0", Set.of(), Map.of(), Set.of(), Set.of(), budget());
        var warm = budget(); var cold = budget();
        var actual = compile(compiler, "p600/0", Set.of(), alternatives(recipes), Set.of(), Set.of(), warm);
        var expected = compile(new GraphCompiler<>(recipes), "p600/0", Set.of(), alternatives(recipes), Set.of(), Set.of(), cold);
        check(actual.equals(expected), "Dense ordered SCCs changed");
        check(compiler.cacheMetrics().caches().get("topology_ports").hits() == 1 &&
                warm.compilationWork() < cold.compilationWork(), "Port reuse did not avoid graph traversal");
        check(warm.reservedBytes() == 0 && cold.reservedBytes() == 0, "Dense reservation leaked");
        System.out.println("topology ring=" + ring + " cold_work=" + cold.compilationWork() +
                " rebound_work=" + warm.compilationWork() + " cold_peak=" + cold.peakBytes() + " rebound_peak=" + warm.peakBytes());
    }

    private static void semantics() {
        String raw = "raw", tool = "tool", mid = "mid", out = "out";
        var first = new GraphRecipe<>("old", "old-binding", List.of(new GraphRecipe.Slot<>(raw, 1)), Map.of(mid, 1L));
        var replacement = new GraphRecipe<>("new", "new-binding", List.of(new GraphRecipe.Slot<>(raw, 3)), Map.of(mid, 2L));
        var end = new GraphRecipe<>("end", "end", List.of(new GraphRecipe.Slot<>(mid, 2)), Map.of(out, 1L));
        var recipes = List.of(first, replacement, end); var compiler = new GraphCompiler<>(recipes);
        var original = compile(compiler, out, Set.of(), Map.of(), Set.of(), Set.of(), budget());
        compiler.demandProgram(original, budget()); var oldProgram = compiler.demandProgram(original, budget());
        var changed = compile(compiler, out, Set.of(), Map.of(mid, 1), Set.of(), Set.of(), budget());
        check(changed.recipes().get("new") == replacement && !changed.recipes().containsKey("old"), "Stale recipe bindings");
        compiler.demandProgram(changed, budget()); var next = compiler.demandProgram(changed, budget());
        check(next != null && next != oldProgram, "Old quantity program escaped topology scope");
        for (long stock : new long[]{2, 3, 30}) for (long amount : new long[]{1, 4}) {
            var b = budget(); var inventory = Map.of(raw, stock);
            try (var solve = new GraphSolve<>(changed, out, amount, inventory, Set.of(), Map.of(), false, true,
                    b, System.nanoTime(), CatalystPolicy.MINIMAL, inventory).program(next)) {
                while (!solve.step()) {}
                var plan = solve.result();
                check(plan.feasible() == (stock >= 3 * amount), "Old quantities or stock escaped: " + stock + "/" + amount);
                check(plan.patternTimesExact().get("new").longValueExact() == amount, "Restored old counts");
            }
        }
        // A virtual reusable return is not a physical output. Its presence in
        // outputs() alone must not make these partitions interchangeable.
        var virtual = new GraphRecipe<>("virtual", "virtual", List.of(new GraphRecipe.Slot<>(tool, 1, 0, true, true)),
                new LinkedHashMap<>(Map.of(mid, 1L, tool, 1L)));
        var physical = new GraphRecipe<>("physical", "physical", List.of(new GraphRecipe.Slot<>(tool, 1)),
                virtual.outputs());
        var toolSource = new GraphRecipe<>("source", "source", List.of(), Map.of(tool, 1L));
        var physicalRecipes = List.of(virtual, physical, toolSource, end);
        var physicalCompiler = new GraphCompiler<>(physicalRecipes);
        compile(physicalCompiler, out, Set.of(), Map.of(), Set.of(), Set.of(), budget());
        var physicalGraph = compile(physicalCompiler, out, Set.of(), Map.of(mid, 1), Set.of(), Set.of(), budget());
        check(physicalGraph.equals(compile(new GraphCompiler<>(physicalRecipes), out, Set.of(), Map.of(mid, 1), Set.of(), Set.of(), budget())),
                "Logical/physical returns confused");
        oracle(physicalGraph);
        check(physicalCompiler.cacheMetrics().caches().get("topology_ports").hits() == 0, "Different physical ports reused");
    }

    private record Collision(int id) { @Override public int hashCode() { return 7; } }

    private static void changedPorts() {
        String a = "a", b = "b", c = "c", raw = "raw", target = "target";
        for (int mutation = 0; mutation < 3; mutation++) {
            var outputs = new LinkedHashMap<String, Long>(); outputs.put(a, 1L); outputs.put(c, 1L);
            var original = new GraphRecipe<>("before", "b", List.of(new GraphRecipe.Slot<>(b, 1), new GraphRecipe.Slot<>(raw, 1)), outputs);
            var slots = mutation == 0 ? List.of(new GraphRecipe.Slot<>(raw, 1)) :
                    mutation == 1 ? List.of(new GraphRecipe.Slot<>(raw, 1), new GraphRecipe.Slot<>(b, 1)) : original.slots();
            var changedOutputs = new LinkedHashMap<String, Long>(); changedOutputs.put(c, 1L); changedOutputs.put(a, 1L);
            var changed = new GraphRecipe<>("after", "a", slots, mutation == 2 ? changedOutputs : outputs);
            var loop = new GraphRecipe<>("loop", "loop", List.of(new GraphRecipe.Slot<>(a, 1)), Map.of(b, 1L));
            var end = new GraphRecipe<>("end", "end", List.of(new GraphRecipe.Slot<>(a, 1)), Map.of(target, 1L));
            var catalog = List.of(original, changed, loop, end); var compiler = new GraphCompiler<>(catalog);
            compile(compiler, target, Set.of(b), Map.of(), Set.of(), Set.of(), budget());
            var choices = Map.of(a, 1);
            var result = compile(compiler, target, Set.of(b), choices, Set.of(), Set.of(), budget());
            check(result.equals(compile(new GraphCompiler<>(catalog), target, Set.of(b), choices, Set.of(), Set.of(), budget())), "Changed port reused old SCC");
            check(compiler.cacheMetrics().caches().get("topology_ports").hits() == 0, "Port order or physical dependency was ignored");
            oracle(result);
        }
    }

    private static void collisions() {
        for (boolean sameObjects : new boolean[]{false, true}) {
            var keys = new Collision[20]; for (int i = 0; i < keys.length; i++) keys[i] = new Collision(i);
            var recipes = new ArrayList<GraphRecipe<Collision>>();
            var choices = new LinkedHashMap<Collision, Integer>();
            var additional = new LinkedHashSet<Collision>();
            for (int i = 0; i < keys.length; i++) for (int variant = 0; variant < 2; variant++) {
                var key = variant == 0 || sameObjects ? keys[i] : new Collision(i);
                recipes.add(new GraphRecipe<>("r" + i + "/" + variant, "b" + variant,
                        List.of(new GraphRecipe.Slot<>(keys[(i + 1) % keys.length], 1)), Map.of(key, 1L)));
                choices.put(keys[i], 1); additional.add(keys[i]);
            }
            var compiler = new GraphCompiler<>(recipes);
            compile(compiler, keys[0], additional, Map.of(), Set.of(), Set.of(), budget());
            var actual = compile(compiler, keys[0], additional, choices, Set.of(), Set.of(), budget());
            check(actual.equals(compile(new GraphCompiler<>(recipes), keys[0], additional, choices, Set.of(), Set.of(), budget())), "Collision changed order");
            check((compiler.cacheMetrics().caches().get("topology_ports").hits() == 1) == sameObjects, "Unsafe collision tie-break reuse");
            oracle(actual);
        }
    }

    private static void boundaries() {
        var recipes = chain(32, 2, false); var choices = alternatives(recipes);
        for (int stop = 1; stop < 500; stop += 13) {
            var compiler = new GraphCompiler<>(recipes);
            compile(compiler, "p32/0", Set.of(), Map.of(), Set.of(), Set.of(), budget());
            var cancel = new AtomicBoolean(); var b = new PlanningBudget(0, 40_000_000, 256L << 20, cancel::get, System::nanoTime);
            try (var work = compiler.begin("p32/0", choices, Set.of(), b)) {
                for (int i = 0; i < stop && !work.step(); i++) {}
                cancel.set(true);
                try { work.step(); throw new AssertionError("Ignored cancellation"); }
                catch (CancellationException expected) {}
            }
            check(b.reservedBytes() == 0, "Cancelled graph leaked");
            var fresh = compile(new GraphCompiler<>(recipes), "p32/0", Set.of(), choices, Set.of(), Set.of(), budget());
            check(fresh.equals(compile(compiler, "p32/0", Set.of(), choices, Set.of(), Set.of(), budget())), "Partial cache poisoned retry");
        }
        // Enough workspace for ordinary compilation, but the optional donor
        // mapping cannot take an eighth of the remaining memory.
        var compiler = new GraphCompiler<>(recipes);
        compile(compiler, "p32/0", Set.of(), Map.of(), Set.of(), Set.of(), budget());
        var limited = new PlanningBudget(0, 40_000_000, 48_000, () -> false, System::nanoTime);
        var actual = compile(compiler, "p32/0", Set.of(), choices, Set.of(), Set.of(), limited);
        check(actual.equals(compile(new GraphCompiler<>(recipes), "p32/0", Set.of(), choices, Set.of(), Set.of(), budget())), "Optional refusal changed graph");
        check(compiler.cacheMetrics().caches().get("topology_ports").hits() == 0 && limited.reservedBytes() == 0, "Optional memory refusal failed");
    }

    private static void concurrent() throws Exception {
        var recipes = chain(96, 3, false); var choices = alternatives(recipes); var compiler = new GraphCompiler<>(recipes);
        compile(compiler, "p96/0", Set.of(), Map.of(), Set.of(), Set.of(), budget());
        var expected = compile(new GraphCompiler<>(recipes), "p96/0", Set.of(), choices, Set.of(), Set.of(), budget());
        var executor = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 32; i++) jobs.add(() -> {
                var b = budget();
                check(compile(compiler, "p96/0", Set.of(), choices, Set.of(), Set.of(), b).equals(expected), "Concurrent publication changed graph");
                check(b.reservedBytes() == 0, "Concurrent mapping leaked");
                return null;
            });
            for (var result : executor.invokeAll(jobs)) result.get(30, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        check(compiler.cacheMetrics().caches().get("topology").entries() == 2, "Rebinding grew a second unbounded cache");
        var fresh = new GraphCompiler<>(recipes);
        compile(fresh, "p96/0", Set.of(), Map.of(), Set.of(), Set.of(), budget());
        var scheduled = budget();
        try (var scheduler = new PlanningScheduler(4, 4, 17, 1_000_000_000);
             var work = fresh.begin("p96/0", choices, Set.of(), scheduled)) {
            check(scheduler.submit(work, scheduled).get(30, TimeUnit.SECONDS).equals(expected), "Retained matching cursor changed across worker slices");
        }
        check(scheduled.reservedBytes() == 0 && fresh.cacheMetrics().caches().get("topology_ports").hits() == 1,
                "Worker rebinding leaked or never ran");
    }

    /** Direct recipe-recipe edges, without compiler hubs or cached partitions. */
    private static <K> void oracle(GraphCompiler.Compiled<K> graph) {
        var recipes = List.copyOf(graph.recipes().values()); int n = recipes.size();
        boolean[][] reach = new boolean[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++)
            reach[i][j] = !Collections.disjoint(recipes.get(i).inputs().keySet(), recipes.get(j).executionOutputs().keySet());
        for (int k = 0; k < n; k++) for (int i = 0; i < n; i++) if (reach[i][k])
            for (int j = 0; j < n; j++) reach[i][j] |= reach[k][j];
        int[] component = new int[n]; Arrays.fill(component, -1);
        for (int r = 0; r < graph.regions().size(); r++) for (var recipe : graph.regions().get(r).recipes()) {
            int i = -1; for (int j = 0; j < n; j++) if (recipes.get(j) == recipe) i = j;
            check(i >= 0 && component[i] == -1, "Old or duplicate recipe in region"); component[i] = r;
            check(graph.regions().get(r).cyclic() == reach[i][i], "Wrong cyclic flag");
        }
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            check(component[i] >= 0 && (component[i] == component[j]) == (i == j || reach[i][j] && reach[j][i]), "Wrong SCC");
            check(!reach[i][j] || component[i] <= component[j], "Wrong dependency order");
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
