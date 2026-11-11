// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Unavailable read arcs cannot win count proposals or contaminate a later request. */
public final class CountCyclicReachabilityTest {
    private static final long WORK = 8_000_000, MEMORY = 64L << 20;
    private static int checks;

    public static void main(String[] args) {
        unavailableReadArc();
        suppliedReadArc();
        reachableProducer();
        consumedConfiguration();
        changedStockAndCancellation();
        System.out.println("CountCyclicReachabilityTest PASS: checks=" + checks);
    }

    private static void unavailableReadArc() {
        for (boolean reusable : List.of(false, true)) for (boolean preserve : List.of(false, true))
            for (boolean reversed : List.of(false, true)) {
                var recipes = new ArrayList<>(List.of(gated(reusable), recipe("alternate", Map.of("raw", 1L), Map.of("goal", 1L))));
                if (reversed) Collections.reverse(recipes);
                var compiler = new GraphCompiler<>(recipes);
                var stock = Map.of("raw", 16L);
                check(unreachable(compiler, stock, Set.of(), Set.of()).contains("gated"),
                        "Absent self-returning credential became initially reachable");
                var plan = solve(compiler, stock, Set.of(), Map.of(), Set.of(), preserve);
                check(plan != null, "An unfunded cheap route suppressed the available alternative");
                check(!used(plan.steps(), "gated") && used(plan.steps(), "alternate"),
                        "Count proposal used an unavailable self-returning input");
                verify(plan, stock, Set.of());
                check(solve(compiler, stock, Set.of(), Map.of(), Set.of("alternate"), preserve) == null,
                        "Removing the funded alternative invented a startup credential");
            }
    }

    private static void suppliedReadArc() {
        for (boolean reusable : List.of(false, true)) for (boolean preserve : List.of(false, true))
            for (boolean external : List.of(false, true)) {
                var recipes = List.of(gated(reusable));
                var compiler = new GraphCompiler<>(recipes);
                var stock = external ? Map.of("raw", 4L) : Map.of("raw", 4L, "permit", 1L);
                var supplied = external ? Set.of("permit") : Set.<String>of();
                check(!unreachable(compiler, stock, supplied, Set.of()).contains("gated"),
                        "Funded/external credential was removed from reachability");
                var plan = solve(compiler, stock, supplied, external ? Map.of() : Map.of("permit", 1L), Set.of(), preserve);
                check(plan != null && used(plan.steps(), "gated"), "Reachability removed the only funded route");
                var terminal = verify(plan, stock, supplied);
                if (!external) check(terminal.getOrDefault("permit", BigInteger.ZERO).equals(BigInteger.ONE),
                        "A logical self-returning input was consumed");
            }
    }

    private static void reachableProducer() {
        // Reaching a credential through another allowed source is distinct
        // from fabricating the credential with its own zero-delta read arc.
        var recipes = List.of(gated(true), recipe("issue-permit", Map.of("blank", 1L), Map.of("permit", 1L)));
        var compiler = new GraphCompiler<>(recipes);
        var stock = Map.of("raw", 4L, "blank", 1L);
        check(!unreachable(compiler, stock, Set.of(), Set.of()).contains("gated"),
                "An independently startable credential producer was ignored");
        var plan = solve(compiler, stock, Set.of(), Map.of("permit", 1L), Set.of(), true);
        check(plan != null && used(plan.steps(), "issue-permit") && used(plan.steps(), "gated"),
                "A reachable credential and explicit final seed contract lost their route");
        check(verify(plan, stock, Set.of()).getOrDefault("permit", BigInteger.ZERO).equals(BigInteger.ONE),
                "Produced credential was not retained after delivery");
        check(unreachable(compiler, stock, Set.of(), Set.of("issue-permit")).contains("gated"),
                "An excluded credential producer still unlocked its consumer");
        check(solve(compiler, stock, Set.of(), Map.of("permit", 1L), Set.of("issue-permit"), true) == null,
                "Excluded credential production entered the witness");
    }

    private static void consumedConfiguration() {
        var configured = new GraphRecipe<>("configured", "configured",
                List.of(new GraphRecipe.Slot<>("raw", 1), new GraphRecipe.Slot<>("config", 1, 0, true, false)),
                Map.of("goal", 1L));
        var compiler = new GraphCompiler<>(List.of(configured,
                recipe("alternate", Map.of("raw", 1L), Map.of("goal", 1L))));
        var absent = Map.of("raw", 16L);
        check(unreachable(compiler, absent, Set.of(), Set.of()).contains("configured"),
                "Absent consumed configuration was treated as a virtual supply");
        var alternate = solve(compiler, absent, Set.of(), Map.of(), Set.of(), false);
        check(alternate != null && !used(alternate.steps(), "configured"),
                "Missing per-push configuration suppressed the funded alternative");
        verify(alternate, absent, Set.of());

        // One configuration can fund one push containing sixteen runs. This
        // optimistic reachability screen cannot multiply that charge by the
        // count or prove failure from the finite configuration stock.
        var finite = Map.of("raw", 16L, "config", 1L);
        check(!unreachable(compiler, finite, Set.of(), Set.of()).contains("configured"),
                "Per-push configuration was charged per firing during reachability");
        check(configured.dispatchInputs(16).get("config") == 1L,
                "Configuration fixture no longer has per-push semantics");
        var supplied = solve(compiler, absent, Set.of("config"), Map.of(), Set.of("alternate"), false);
        check(supplied != null && used(supplied.steps(), "configured"),
                "Unlimited configuration was incorrectly excluded");
        verify(supplied, absent, Set.of("config"));

        // Returning a consumed configuration has positive gain when runs are
        // grouped into one push: one key in, two keys out. A per-firing zero
        // delta must not mark the two-key consumer unreachable.
        var batchReturn = new GraphRecipe<>("batch-return", "batch-return",
                List.of(new GraphRecipe.Slot<>("raw", 1), new GraphRecipe.Slot<>("config", 1, 0, true, false)),
                Map.of("part", 1L, "config", 1L));
        var consumer = new GraphRecipe<>("two-key-consumer", "two-key-consumer",
                List.of(new GraphRecipe.Slot<>("part", 2), new GraphRecipe.Slot<>("config", 2, 0, true, true)),
                Map.of("goal", 16L, "config", 2L));
        var batching = new GraphCompiler<>(List.of(batchReturn, consumer));
        var startup = Map.of("raw", 2L, "config", 1L);
        check(!unreachable(batching, startup, Set.of(), Set.of()).contains("two-key-consumer"),
                "Reachability lost per-push configuration amplification");
        var physical = new LinkedHashMap<String, BigInteger>();
        startup.forEach((key, value) -> physical.put(key, BigInteger.valueOf(value)));
        execute(new PlanStep.Sequence(List.of(new PlanStep.Batch("batch-return", 2),
                new PlanStep.Batch("two-key-consumer", 1))),
                Map.of("batch-return", batchReturn, "two-key-consumer", consumer), physical, Set.of());
        check(physical.get("goal").equals(BigInteger.valueOf(16)), "Independent batched configuration oracle is invalid");
        // The current counted topology may decline finite per-push inputs.
        // That optional scope limit is allowed; every returned word is checked.
        var optional = solve(batching, startup, Set.of(), Map.of(), Set.of(), false);
        if (optional != null) verify(optional, startup, Set.of());
    }

    private static void changedStockAndCancellation() {
        var recipes = List.of(gated(true));
        var compiler = new GraphCompiler<>(recipes);
        var empty = Map.of("raw", 4L);
        var funded = Map.of("raw", 4L, "permit", 1L);
        // First finish a missing request, then cancel two fresh requests
        // during preparation. None may retain stock-dependent exclusions in
        // the shared immutable compiler used by the following funded order.
        check(solve(compiler, empty, Set.of(), Map.of(), Set.of(), true) == null,
                "Absent startup token unexpectedly produced a witness");
        for (int stopAfter : new int[]{0, 1, 2}) {
            var cancelled = new AtomicBoolean(); var budget = budget(cancelled);
            try (var search = new CountCyclicStockSearch<>(compiler, "goal", 16, empty, Set.of(), Map.of(), Set.of(),
                    true, true, budget, System.nanoTime())) {
                for (int i = 0; i < stopAfter; i++) check(!search.step(), "Cancellation fixture ended before its handoff");
                cancelled.set(true);
                try { search.step(); throw new AssertionError("Reachability proposal swallowed cancellation"); }
                catch (CancellationException expected) { checks++; }
                check(search.result() == null, "Cancelled reachability search published an unfinished witness");
            }
            check(budget.reservedBytes() == 0, "Cancelled reachability search retained workspace");
            for (int repeat = 0; repeat < 2; repeat++) {
                var plan = solve(compiler, funded, Set.of(), Map.of("permit", 1L), Set.of(), true);
                check(plan != null && used(plan.steps(), "gated"), "Unreachable recipe cache poisoned changed inventory");
                verify(plan, funded, Set.of());
                check(solve(compiler, empty, Set.of(), Map.of(), Set.of(), true) == null,
                        "A previous funded order lent its credential to a later request");
            }
        }
    }

    private static Set<String> unreachable(GraphCompiler<String> compiler, Map<String, Long> stock,
                                            Set<String> external, Set<String> excluded) {
        var budget = budget(new AtomicBoolean());
        Set<String> result;
        try (var analysis = new MissingStockAnalysis<>(compiler, "goal", stock, external, Set.of(), excluded, true, budget)) {
            while (!analysis.step()) {}
            result = analysis.unreachableRecipes();
        }
        check(budget.reservedBytes() == 0, "Reachability screen retained request workspace");
        return result;
    }

    private static GraphPlan<String> solve(GraphCompiler<String> compiler, Map<String, Long> stock,
                                            Set<String> external, Map<String, Long> seeds, Set<String> excluded,
                                            boolean preserve) {
        var budget = budget(new AtomicBoolean());
        budget.failureDetail("UNCHANGED_REQUEST");
        GraphPlan<String> plan;
        try (var search = new CountCyclicStockSearch<>(compiler, "goal", 16, stock, external, seeds, excluded,
                preserve, true, budget, System.nanoTime())) {
            while (!search.step()) {}
            plan = search.result();
        }
        check(budget.reservedBytes() == 0, "Cyclic reachability proposal retained workspace");
        check(!budget.failureDetail().startsWith("PROVEN_INFEASIBLE"), "Optional route filtering became a root infeasibility proof");
        if (plan != null) {
            PlanVerifier.verify(plan);
            PlanVerifier.verifyRuntimeInventory(plan);
            check(plan.feasible() && plan.missingExact().isEmpty(), "Positive proposal contained invented missing stock");
            for (var seed : seeds.entrySet()) check(plan.seeds().getOrDefault(seed.getKey(), 0L) >= seed.getValue(),
                    "Caller seed contract was dropped");
            for (String id : excluded) check(!used(plan.steps(), id), "Excluded source entered an execution word");
        }
        return plan;
    }

    private static Map<String, BigInteger> verify(GraphPlan<String> plan, Map<String, Long> stock, Set<String> external) {
        PlanVerifier.verify(plan);
        var held = new LinkedHashMap<String, BigInteger>();
        stock.forEach((key, value) -> held.put(key, BigInteger.valueOf(value)));
        plan.initialExact().forEach((key, value) -> check(external.contains(key) ||
                value.compareTo(held.getOrDefault(key, BigInteger.ZERO)) <= 0, "Plan invented initial stock: " + key));
        execute(plan.steps(), plan.recipes(), held, external);
        check(held.getOrDefault("goal", BigInteger.ZERO).compareTo(BigInteger.valueOf(plan.amount())) >= 0,
                "Primitive execution did not deliver the target");
        held.merge("goal", BigInteger.valueOf(plan.amount()).negate(), BigInteger::add);
        plan.seeds().forEach((key, value) -> check(external.contains(key) ||
                held.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(value)) >= 0,
                "Final delivery consumed a retained seed: " + key));
        return held;
    }

    /** Small exact physical replay: consumed configuration is per push, reusable input is a read arc. */
    private static void execute(PlanStep step, Map<String, GraphRecipe<String>> recipes,
                                Map<String, BigInteger> held, Set<String> external) {
        if (step instanceof PlanStep.Batch batch) {
            var recipe = recipes.get(batch.recipe());
            check(recipe != null && batch.runs() <= 64, "Unexpected primitive witness size/source");
            // Batch is a compressed sequence, not a demand that every
            // ordinary self-returning input exist for all runs at once.
            for (long run = 0; run < batch.runs(); run++) {
                var needed = new LinkedHashMap<String, BigInteger>();
                var consumed = new LinkedHashMap<String, BigInteger>();
                for (var slot : recipe.slots()) {
                    if (slot.configuration() && !slot.reusable() && run != 0) continue;
                    BigInteger amount = BigInteger.valueOf(slot.amount());
                    needed.merge(slot.key(), amount, BigInteger::add);
                    if (!slot.reusable()) consumed.merge(slot.key(), amount, BigInteger::add);
                }
                needed.forEach((key, amount) -> check(external.contains(key) ||
                        held.getOrDefault(key, BigInteger.ZERO).compareTo(amount) >= 0, "Unfunded physical input/read arc: " + key));
                consumed.forEach((key, amount) -> { if (!external.contains(key)) held.merge(key, amount.negate(), BigInteger::add); });
                recipe.executionOutputs().forEach((key, amount) -> {
                    if (!external.contains(key)) held.merge(key, BigInteger.valueOf(amount), BigInteger::add);
                });
            }
        } else if (step instanceof PlanStep.Repeat repeat) {
            check(repeat.times() <= 64, "Unexpected primitive repetition size");
            for (long i = 0; i < repeat.times(); i++) execute(repeat.body(), recipes, held, external);
        } else for (var child : ((PlanStep.Sequence) step).children()) execute(child, recipes, held, external);
    }

    private static boolean used(PlanStep step, String id) {
        if (step instanceof PlanStep.Batch batch) return batch.runs() > 0 && batch.recipe().equals(id);
        if (step instanceof PlanStep.Repeat repeat) return repeat.times() > 0 && used(repeat.body(), id);
        return ((PlanStep.Sequence) step).children().stream().anyMatch(child -> used(child, id));
    }

    private static GraphRecipe<String> gated(boolean reusable) {
        return new GraphRecipe<>("gated", "gated", List.of(new GraphRecipe.Slot<>("raw", 1),
                new GraphRecipe.Slot<>("permit", 1, 0, reusable, reusable)), Map.of("goal", 4L, "permit", 1L));
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> input, Map<String, Long> output) {
        return new GraphRecipe<>(id, id, input.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), output);
    }

    private static PlanningBudget budget(AtomicBoolean cancelled) {
        return new PlanningBudget(0, WORK, MEMORY, cancelled::get, System::nanoTime);
    }

    private static void check(boolean condition, String detail) {
        checks++; if (!condition) throw new AssertionError(detail);
    }
}
