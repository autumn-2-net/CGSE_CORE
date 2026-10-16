// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact state restoration must preserve compact witnesses and physical ownership. */
public final class CapturedStateRestoreTest {
    private static int checks;

    public static void main(String[] args) {
        fixedPoints();
        serialResources();
        entityDemand();
        transitionIdentity();
        periodicStates();
        outcomes();
        System.out.println("CapturedStateRestoreTest: " + checks + " assertions passed");
    }

    private static void fixedPoints() {
        long work = -1;
        for (long n : new long[]{8, 128, 1024, 1_000_000_000L}) {
            var family = new Stable();
            var source = entry("use", List.of(tool(family, 1), ordinary("raw")), "product");
            var solved = solve(List.of(source), n, Map.of("tool", 1L, "raw", n), false, Map.of());
            var plan = require(solved);
            check(plan.recipes().size() == 1, "Equal exact transitions got distinct IDs");
            check(nodes(plan.steps()) <= 3, "Fixed state expanded with order size");
            check(plan.patternTimesExact().values().stream().reduce(BigInteger.ZERO, BigInteger::add).equals(BigInteger.valueOf(n)), "Lost selected firings");
            check(family.calls == 1, "Fixed-point restoration invoked every transition");
            check(solved.budget.peakBytes() < 1_000_000, "Fixed-point restoration retained per-use objects");
            if (work >= 0) check(solved.budget.searchWork() == work, "Fixed-point restore work scaled with repetitions");
            work = solved.budget.searchWork();
            var stage = solved.budget.pipelineMetrics().costs().get(new PlanningCostTrace.Context(
                    PlanningCostTrace.Origin.ALLOCATION, PlanningCostTrace.Stage.RESTORE));
            check(stage != null && stage.searchTicks() > 0, "Restoration cost lost in solver work");
            // This is a production-capture plan, rather than a hand-built DAG.
            var dag = DagScheduler.create(plan, Map.of());
            check(dag != null && dag.poll(0).runs() == n, "Stable tool did not retain a compact DAG count");
            if (n == 128) runtime(plan);
        }
        var family = new Stable();
        var source = entry("forced", List.of(tool(family, 1), ordinary("raw")), "product");
        var forced = require(solve(List.of(source), 64, Map.of("tool", 1L, "raw", 64L, "product", 64L), true, Map.of("tool", 1L)));
        check(forced.patternTimesExact().values().stream().reduce(BigInteger.ZERO, BigInteger::add).equals(BigInteger.valueOf(64)), "Stock target replaced force-craft production");
    }

    private static void serialResources() {
        var family = new Stable();
        var returning = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                new CapturedRecipe.Amount<>("raw", 1), "raw", false)), CapturedRecipe.InputMode.INDIVISIBLE);
        var source = entry("returning", List.of(tool(family, 1), returning), "product");
        require(solve(List.of(source), 100_000, Map.of("tool", 1L, "raw", 1L), false, Map.of()));
        for (boolean reusable : new boolean[]{false, true}) {
            var configuration = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                    new CapturedRecipe.Amount<>("config", 1), null, true, reusable)), CapturedRecipe.InputMode.INDIVISIBLE);
            source = entry("configured", List.of(tool(family, 1), ordinary("raw"), configuration), "product");
            require(solve(List.of(source), 8, Map.of("tool", 1L, "raw", 8L, "config", reusable ? 1L : 8L), false, Map.of()));
            if (!reusable) check(solve(List.of(source), 8, Map.of("tool", 1L, "raw", 8L, "config", 1L), false, Map.of()).plan == null,
                    "Serial repeats paid a consumed configuration only once");
        }
        // The same physical state cannot pay an ordinary input and a tool slot.
        source = entry("overlap", List.of(tool(family, 1), ordinary("tool")), "product");
        check(solve(List.of(source), 1, Map.of("tool", 1L), false, Map.of()).plan == null, "Activated entity was spent as ordinary stock");
    }

    private static void entityDemand() {
        for (var mode : List.of(CapturedRecipe.InputMode.INDIVISIBLE, CapturedRecipe.InputMode.MIXED))
                for (boolean separate : new boolean[]{false, true}) for (boolean reverse : new boolean[]{false, true}) {
            var family = new Stable();
            var inputs = new ArrayList<CapturedRecipe.Input<String>>();
            var first = tool(family, separate ? 1 : 2);
            inputs.add(new CapturedRecipe.Input<>(first.multiplier(), first.candidates(), mode));
            if (separate) inputs.add(tool(family, 1));
            inputs.add(ordinary("raw"));
            if (reverse) Collections.reverse(inputs);
            var use = entry("pair", inputs, "product");
            var plan = require(solve(List.of(use), 32, Map.of("tool", 2L, "raw", 32L), false, Map.of()));
            check(plan.initialExact().get("tool").equals(BigInteger.TWO), "Two simultaneous inputs were funded by one entity");
            check(solve(List.of(use), 1, Map.of("tool", 1L, "raw", 1L), false, Map.of()).plan == null, "Capacity replaced physical multiplicity");
            var build = entry("build", List.of(ordinary("metal")), "tool");
            var entries = reverse ? List.of(build, use) : List.of(use, build);
            plan = require(solve(entries, 32, Map.of("tool", 1L, "raw", 32L, "metal", 1L), false, Map.of()));
            BigInteger made = BigInteger.ZERO;
            for (var count : plan.patternTimesExact().entrySet())
                if (plan.recipes().get(count.getKey()).binding().equals("build")) made = made.add(count.getValue());
            check(made.equals(BigInteger.ONE), "Entity demand did not manufacture exactly the missing tool");
        }
        // A slot with a family OR an ordinary choice must not gain an
        // unconditional entity requirement from its family marginal.
        var family = new Stable();
        var mixed = new CapturedRecipe.Input<>(1, List.of(tool(family, 1).candidates().get(0),
                ordinary("token").candidates().get(0)), CapturedRecipe.InputMode.INDIVISIBLE);
        require(solve(List.of(entry("alternative", List.of(mixed), "product")), 1, Map.of("token", 1L), false, Map.of()));
    }

    private static void transitionIdentity() {
        var family = new Stable();
        var first = entry("hostA", List.of(tool(family, 1), ordinary("raw")), "middle");
        var second = entry("hostB", List.of(tool(family, 1), ordinary("middle")), "product");
        var plan = require(solve(List.of(first, second), 16, Map.of("tool", 1L, "raw", 16L), false, Map.of()));
        check(plan.recipes().values().stream().map(GraphRecipe::binding).collect(java.util.stream.Collectors.toSet()).equals(Set.of("hostA", "hostB")),
                "Transition interning crossed host bindings");
        check(plan.recipes().size() == 2, "Repeated transitions were not interned per source");
    }

    private static void periodicStates() {
        StateFamily<String> periodic = new StateFamily<>() {
            public String id() { return "periodic"; }
            public boolean accepts(String k) { return k.equals("tool") || k.equals("next"); }
            public long capacity(String k) { return Long.MAX_VALUE; }
            public String nominalAfterOne(String k) { return k.equals("tool") ? "next" : "tool"; }
        };
        var plan = require(solve(List.of(entry("toggle", List.of(tool(periodic, 1), ordinary("raw")), "product")),
                128, Map.of("tool", 1L, "raw", 128L), false, Map.of()));
        check(plan.recipes().size() == 2, "Repeated state word assigned per-firing IDs");
        check(nodes(plan.steps()) < 20, "Existing word compression could not see stable transition IDs");
        var cursor = new PlanCursor(plan.steps());
        var inventory = new LinkedHashMap<>(plan.initial());
        for (int i = 0; i < 128; i++) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            String expected = i % 2 == 0 ? "tool" : "next";
            check(recipe.inputs().containsKey(expected), "State word changed order");
            recipe.inputs().forEach((key, count) -> {
                check(inventory.getOrDefault(key, 0L) >= count, "Unfunded primitive replay");
                inventory.merge(key, -count, Long::sum);
            });
            recipe.executionOutputs().forEach((key, count) -> inventory.merge(key, count, Long::sum));
            cursor.dispatched(1);
        }
        check(cursor.current() == null && inventory.get("product") == 128 && inventory.get("tool") == 1, "Primitive replay lost inventory");
        var source = entry("toggle", List.of(tool(periodic, 1), ordinary("raw")), "product");
        check(solve(List.of(source), 1, Map.of("tool", 1L, "raw", 1L), false, Map.of("tool", 1L)).plan == null,
                "Relaxed auxiliary seed requirement escaped exact final validation");
        require(solve(List.of(source), 2, Map.of("tool", 1L, "raw", 2L), false, Map.of("tool", 1L)));
    }

    private static void outcomes() {
        var family = new Stable();
        var entry = entry("pair", List.of(tool(family, 2), ordinary("raw")), "product");
        var failed = solve(List.of(entry), 1, Map.of("tool", 1L, "raw", 1L), false, Map.of());
        check(failed.outcome.status() == CapturedStatePlanning.Status.DECLINED, "Local negative reported as completed witness");
        var budget = new PlanningBudget(0, 512, 1 << 20, () -> false, System::nanoTime);
        try (var work = new CapturedStatePlanning<>(List.of(entry), "product", 1, Map.of("tool", 2L, "raw", 1L),
                Set.of(), Map.of(), false, false, budget)) {
            check(work.step() && work.outcome().status() == CapturedStatePlanning.Status.LIMITED &&
                    work.outcome().reason() == CapturedStatePlanning.Reason.LOCAL_WORK_LIMIT, "Budget refusal lost typed cause");
        }
        check(budget.searchWork() == 0 && budget.reservedBytes() == 0, "Refusal charged the parent");
    }

    private static void runtime(GraphPlan<String> plan) {
        var host = new Host(plan);
        for (int i = 0; i < 128; i++) {
            host.runtime.tick(host, i * 20L, 64, () -> 0L);
            check(host.pending.size() == 1, "One physical tool allowed parallel dispatch");
            host.runtime.tick(host, i * 20L + 1, 64, () -> 0L);
            check(host.pending.size() == 1, "Missing return was synthesized");
            if (i % 17 == 0) host.runtime = new GraphJobRuntime<>(host.runtime.snapshot());
            var returned = host.pending.remove(0);
            returned.forEach((key, count) -> check(host.runtime.accept(key, count, false) == count, "Lost real return"));
        }
        host.runtime.tick(host, 10_000, 256, () -> 0L);
        check(host.delivered == 128 && host.refunded.getOrDefault("tool", 0L) == 1, "Compressed program did not finish/refund");
    }

    private static final class Host implements GraphJobRuntime.Adapter<String> {
        GraphJobRuntime<String> runtime;
        final List<Map<String, Long>> pending = new ArrayList<>();
        final Map<String, Long> refunded = new LinkedHashMap<>();
        long delivered;
        Host(GraphPlan<String> plan) { runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of()); }
        public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
            check(runs == 1 && inputs.get("tool") == 1, "Order count multiplied simultaneous tool ownership");
            pending.add(recipe.executionOutputs()); return GraphJobRuntime.Outcome.ACCEPTED;
        }
        public long deliver(String key, long count) { delivered += count; return count; }
        public long refund(String key, long count) { refunded.merge(key, count, Long::sum); return count; }
    }

    private record Solved(GraphPlan<String> plan, PlanningBudget budget, CapturedStatePlanning.Outcome outcome) {}
    private static Solved solve(List<CapturedCatalog.Entry<String>> entries, long n, Map<String, Long> stock,
                                boolean force, Map<String, Long> seeds) {
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime); budget.enableMetrics();
        Solved solved;
        try (var work = new CapturedStatePlanning<>(entries, "product", n, stock, Set.of(), seeds, false, force, budget)) {
            while (!work.step()) {}
            solved = new Solved(work.result(), budget, work.outcome());
        }
        check(budget.reservedBytes() == 0, "State restoration leaked memory accounting");
        PlanningCostTraceTest.conserved(budget);
        if (solved.plan != null) {
            PlanVerifier.verify(solved.plan); PlanVerifier.verifyRuntimeInventory(solved.plan);
            solved.plan.initialExact().forEach((key, count) -> check(count.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0, "Invented initial stock"));
        }
        return solved;
    }
    private static GraphPlan<String> require(Solved solved) {
        check(solved.plan != null && solved.outcome.status() == CapturedStatePlanning.Status.VERIFIED, "Expected exact witness: " + solved.outcome);
        return solved.plan;
    }
    private static final class Stable implements StateFamily<String> {
        long calls;
        public String id() { return "stable"; }
        public boolean accepts(String key) { return key.equals("tool"); }
        public long capacity(String key) { return Long.MAX_VALUE; }
        public String nominalAfterOne(String key) { calls++; return key; }
    }
    private static CapturedRecipe.Input<String> tool(StateFamily<String> family, long copies) {
        return new CapturedRecipe.Input<>(copies, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool", 1),
                family.id().equals("periodic") ? "next" : "tool", false, false, family)), CapturedRecipe.InputMode.MIXED);
    }
    private static CapturedRecipe.Input<String> ordinary(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static int nodes(PlanStep step) {
        if (step instanceof PlanStep.Sequence s) return 1 + s.children().stream().mapToInt(CapturedStateRestoreTest::nodes).sum();
        return step instanceof PlanStep.Repeat r ? 1 + nodes(r.body()) : 1;
    }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
