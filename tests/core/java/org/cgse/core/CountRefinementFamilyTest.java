// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Constructive words and finite-state exploration check families of count/startup refinements. */
public final class CountRefinementFamilyTest {
    private static final String TARGET = "goal";
    private static final long WORK = 4_000_000, MEMORY = 96L << 20;
    private static final int STATE_LIMIT = 20_000, REFINEMENTS = 24;
    private static int checks, cases, witnesses, closedOracles, openOracles, limited;

    private record Family(String name, List<GraphRecipe<String>> recipes, PlanStep word, long amount,
                          String startup, String feed, long bypassYield, Map<String, Long> seeds, String brokenEdge) {}
    private record Case(String id, List<GraphRecipe<String>> recipes, Map<String, Long> stock,
                        long amount, Map<String, Long> seeds, PlanStep known) {}
    private record Fold(Map<String, BigInteger> need, Map<String, BigInteger> delta) {}
    private enum Reachable { YES, NO, UNKNOWN }
    private record Oracle(Reachable status, int states) {}

    public static void main(String[] args) {
        var filter = Set.of(args);
        for (var family : families()) {
            if (!filter.isEmpty() && !filter.contains(family.name)) continue;
            runFamily(family);
        }
        if (filter.isEmpty() || filter.contains("partial-startup")) partialStartupRefinement();
        if (filter.isEmpty() || filter.contains("exact-large")) exactLarge();
        if (filter.isEmpty() || filter.contains("lifecycle")) cancellationAndLimitedBudgets();
        System.out.println("CountRefinementFamilyTest PASS: cases=" + cases + "; witnesses=" + witnesses +
                "; bfs_closed=" + closedOracles + "; bfs_unknown=" + openOracles + "; limited=" + limited + "; checks=" + checks);
    }

    private static List<Family> families() {
        var result = new ArrayList<Family>();
        for (int length : new int[]{2, 5}) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            var turn = new ArrayList<PlanStep>();
            for (int i = 0; i + 1 < length; i++) {
                String id = "edge" + i;
                recipes.add(recipe(id, Map.of("s" + i, 1L), Map.of("s" + (i + 1), 1L)));
                turn.add(new PlanStep.Batch(id, 1));
            }
            recipes.add(recipe("grow", Map.of("s" + (length - 1), 1L, "feed", 1L), Map.of("s0", 2L)));
            recipes.add(recipe("deliver", Map.of("s0", 1L), Map.of(TARGET, 1L)));
            turn.add(new PlanStep.Batch("grow", 1)); turn.add(new PlanStep.Batch("deliver", 1));
            result.add(new Family("ring-" + length, recipes, new PlanStep.Repeat(new PlanStep.Sequence(turn), 3), 3,
                    "s0", "feed", 1, Map.of("s0", 1L), "edge0"));
        }
        var shared = List.of(recipe("left", Map.of("a", 1L), Map.of("b", 1L)),
                recipe("right", Map.of("a", 1L), Map.of("c", 1L)),
                recipe("return", Map.of("b", 1L, "c", 1L, "feed", 1L), Map.of("a", 3L)),
                recipe("deliver", Map.of("a", 1L), Map.of(TARGET, 1L)));
        result.add(new Family("shared-startup", shared, repeated(3, "left", "right", "return", "deliver"), 3,
                "a", "feed", 1, Map.of("a", 2L), "right"));
        var coproduct = List.of(recipe("co-produce", Map.of("a", 1L, "feed", 1L), ordered("b", 1L, TARGET, 1L, "waste", 2L)),
                recipe("return", Map.of("b", 1L), Map.of("a", 1L)));
        result.add(new Family("coproduct-return", coproduct, repeated(3, "co-produce", "return"), 3,
                "a", "feed", 1, Map.of("a", 1L), "return"));
        var gated = new GraphRecipe<>("gated", "gated", List.of(new GraphRecipe.Slot<>("feed", 1),
                new GraphRecipe.Slot<>("permit", 1, 0, true, true)), ordered(TARGET, 2L, "permit", 1L));
        result.add(new Family("self-returning-permit", List.of(gated), new PlanStep.Batch("gated", 2), 4,
                "permit", "feed", 2, Map.of("permit", 1L), "gated"));
        var neutral = List.of(recipe("forward", Map.of("a", 1L), Map.of("b", 1L)),
                recipe("return", Map.of("b", 1L), Map.of("a", 1L)),
                recipe("supply", Map.of("feed", 1L), Map.of("a", 1L)),
                recipe("deliver", Map.of("b", 1L), Map.of(TARGET, 1L)));
        result.add(new Family("neutral-with-supply", neutral, repeated(3, "supply", "forward", "deliver"), 3,
                "a", "feed", 1, Map.of("a", 1L), "supply"));
        var partial = List.of(recipe("supply", Map.of("ore", 1L), Map.of("a", 1L)),
                recipe("gated", Map.of("a", 2L), ordered("a", 2L, TARGET, 1L)));
        result.add(new Family("partial-startup", partial, repeated(1, "supply", "gated"), 1,
                "a", "ore", 1, Map.of(), "supply"));
        return result;
    }

    private static void runFamily(Family family) {
        var base = initial(family.word, index(family.recipes), family.amount, family.seeds);
        var variants = new ArrayList<Case>();
        BigInteger threshold = base.getOrDefault(family.startup, BigInteger.ZERO);
        var amounts = new LinkedHashSet<>(List.of(BigInteger.ZERO, threshold.subtract(BigInteger.ONE).max(BigInteger.ZERO),
                threshold, threshold.add(BigInteger.valueOf(3))));
        for (var available : amounts) {
            var stock = new LinkedHashMap<>(base); stock.put(family.startup, available);
            variants.add(new Case(family.name + "/startup=" + available, family.recipes, PlanningInventory.exactSnapshot(stock),
                    family.amount, family.seeds, available.compareTo(threshold) >= 0 ? family.word : null));
        }
        for (String removed : new LinkedHashSet<>(List.of(family.brokenEdge, targetProducer(family.recipes)))) {
            var broken = family.recipes.stream().filter(recipe -> !recipe.id().equals(removed)).toList();
            variants.add(new Case(family.name + "/removed=" + removed, broken, PlanningInventory.exactSnapshot(base),
                    family.amount, family.seeds, null));
            var alternative = new ArrayList<>(broken);
            alternative.add(recipe("bypass", Map.of(family.feed, 1L),
                    ordered(TARGET, family.bypassYield, family.startup, 2L)));
            var withoutStartup = new LinkedHashMap<>(base); withoutStartup.put(family.startup, BigInteger.ZERO);
            var bypassWord = new PlanStep.Batch("bypass", family.amount / family.bypassYield);
            check(funded(bypassWord, index(alternative), withoutStartup, family.amount, family.seeds),
                    family.name + " malformed alternate witness");
            variants.add(new Case(family.name + "/removed=" + removed + "/alternate", alternative,
                    PlanningInventory.exactSnapshot(withoutStartup), family.amount, family.seeds, bypassWord));
        }
        // A repeated funded request follows all deficient mutations on the
        // same compiler, so stock-dependent exclusions cannot survive reuse.
        variants.add(new Case(family.name + "/funded-retry", family.recipes, PlanningInventory.exactSnapshot(base),
                family.amount, family.seeds, family.word));
        for (int order = 0; order < 3; order++) {
            var compilers = new HashMap<List<GraphRecipe<String>>, GraphCompiler<String>>();
            GraphCompiler<String> originalCompiler = null;
            for (var source : variants) {
                var recipes = reorder(source.recipes, order);
                var test = new Case(source.id + "/order=" + order, recipes, source.stock, source.amount, source.seeds, source.known);
                var oracle = explore(test);
                if (oracle.status == Reachable.NO) closedOracles++;
                if (oracle.status == Reachable.UNKNOWN) openOracles++;
                if (test.known != null) {
                    check(funded(test.known, index(recipes), PlanningInventory.exactAmounts(test.stock), test.amount, test.seeds),
                            test.id + " constructive word lost its primitive funding");
                    check(oracle.status != Reachable.NO, test.id + " independent BFS contradicted its constructive witness");
                }
                boolean expected = test.known != null || oracle.status == Reachable.YES;
                var compiler = compilers.computeIfAbsent(recipes, GraphCompiler::new);
                if (source.recipes == family.recipes) {
                    if (originalCompiler == null) originalCompiler = compiler;
                    else check(compiler == originalCompiler, test.id + " changed-stock requests did not reuse the same compiler instance");
                }
                for (boolean preserve : List.of(false, true)) {
                    cases++;
                    var plan = mainline(test, compiler, preserve, WORK);
                    checkResult(test, plan, expected, oracle, "mainline/preserve=" + preserve);
                }
                // Run the new refinement helper separately: ordinary source
                // search cannot hide a missing count/startup capability here.
                cases++;
                var direct = refined(test, compiler, WORK);
                checkResult(test, direct, expected, oracle, "refined-counts");
                if (Boolean.getBoolean("cgse.family.trace")) System.out.println(test.id + " oracle=" + oracle.status +
                        " states=" + oracle.states + " expected=" + expected);
            }
        }
    }

    private static GraphPlan<String> mainline(Case test, GraphCompiler<String> compiler, boolean preserve, long workLimit) {
        var budget = budget(workLimit, new AtomicBoolean());
        GraphPlan<String> result = null;
        var work = new GraphPlanningWork<>(compiler, TARGET, test.amount, test.stock, Set.of(), test.seeds,
                preserve, true, budget).catalysts(CatalystPolicy.MINIMAL);
        try {
            while (!work.step()) {}
            result = work.result();
        } catch (PlanningBudget.Exhausted limit) {
            if (workLimit >= WORK) throw new AssertionError(test.id + " unexpected full-budget limit " + limit + ": " + budget.diagnostics(), limit);
            limited++;
        } finally { work.close(); }
        check(budget.reservedBytes() == 0, test.id + " mainline retained request memory");
        return result;
    }

    private static GraphPlan<String> refined(Case test, GraphCompiler<String> compiler, long workLimit) {
        var budget = budget(workLimit, new AtomicBoolean());
        GraphPlan<String> result = null;
        try (var model = RecipeCountModel.create(compiler, TARGET, test.amount, test.stock, test.seeds,
                Set.of(), Set.of(), true, budget)) {
            if (model != null) {
                var originals = List.copyOf(model.constraints);
                try (var counts = new CountRefinedProjection<>(model, budget, workLimit / 2, 512, true)) {
                    for (int attempt = 0; attempt < REFINEMENTS; attempt++) {
                        while (!counts.step()) {}
                        var proposal = counts.result();
                        if (proposal == null) break; // Optional exhaustion is UNKNOWN, not UNSAT.
                        check(validRows(originals, proposal), test.id + " refined counts violate original integer rows");
                        PlanStep word;
                        try (var scheduling = new CountSchedule<>(model, proposal, budget, true)) {
                            while (!scheduling.step()) {}
                            word = scheduling.witness();
                        }
                        if (word != null) {
                            try (var candidate = new AllocationSearch.Candidate<>(word, index(model.recipes), TARGET, test.amount,
                                    test.stock, test.seeds, Set.of(), true, true, false, budget, System.nanoTime()).productionContract(test.seeds)) {
                                while (!candidate.step()) {}
                                if (candidate.plan != null && candidate.plan.feasible()) {
                                    result = candidate.plan;
                                    break;
                                }
                            }
                        }
                        if (!counts.reject()) break;
                    }
                }
                check(model.constraints.equals(originals), test.id + " refinement mutated the original model rows");
            }
        } catch (PlanningBudget.Exhausted limit) {
            if (workLimit >= WORK) throw new AssertionError(test.id + " unexpected refinement limit " + limit + ": " + budget.diagnostics(), limit);
            limited++;
        }
        check(budget.reservedBytes() == 0, test.id + " refinement retained request memory");
        return result;
    }

    private static void checkResult(Case test, GraphPlan<String> plan, boolean expected, Oracle oracle, String path) {
        boolean found = plan != null && plan.feasible();
        if (expected) check(found, test.id + " " + path + " lost an independently witnessed feasible input; result=" +
                (plan == null ? "UNKNOWN" : plan.result()));
        if (oracle.status == Reachable.NO) check(!found, test.id + " " + path + " contradicted complete finite-state exploration");
        if (found) { verify(test, plan); witnesses++; }
        // MISSING_* and UNKNOWN are not independent infeasibility evidence.
        // An incomplete BFS likewise cannot impose a negative expectation.
    }

    private static void exactLarge() {
        var split = new GraphRecipe<>("split", "split", List.of(new GraphRecipe.Slot<>("ore", 1),
                new GraphRecipe.Slot<>("permit", 1, 0, true, true)),
                ordered("intermediate", Long.MAX_VALUE, "waste", 1L, "permit", 1L));
        var recipes = List.of(split, recipe("deliver", Map.of("intermediate", 3L), Map.of(TARGET, 1L)));
        var known = new PlanStep.Sequence(List.of(new PlanStep.Batch("split", 3), new PlanStep.Batch("deliver", Long.MAX_VALUE)));
        for (int order = 0; order < 3; order++) {
            var reordered = reorder(recipes, order);
            var test = new Case("exact-large/order=" + order, reordered, Map.of("ore", 3L, "permit", 1L),
                    Long.MAX_VALUE, Map.of("permit", 1L), known);
            var primitive = fold(known, index(recipes));
            check(primitive.delta.get(TARGET).equals(BigInteger.valueOf(Long.MAX_VALUE)), "Large constructive target changed");
            check(funded(known, index(recipes), PlanningInventory.exactAmounts(test.stock), test.amount, test.seeds),
                    "Large primitive witness is not funded");
            // Three output/consumption batches cross long internally while
            // the simultaneously reserved physical input remains only 3+1.
            var total = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(3));
            check(total.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0, "Large fixture did not cross long");
            var compiler = new GraphCompiler<>(reordered);
            for (boolean preserve : List.of(false, true)) {
                cases++;
                checkResult(test, mainline(test, compiler, preserve, WORK), true, new Oracle(Reachable.UNKNOWN, 0), "large-mainline");
            }
            cases++;
            checkResult(test, refined(test, compiler, WORK), true, new Oracle(Reachable.UNKNOWN, 0), "large-refinement");
        }
    }

    private static void partialStartupRefinement() {
        // Presence alone is not sufficient: one existing catalyst cannot
        // start a two-catalyst read. Net balance still accepts supply=0,
        // gated=1, whereas the physical first firing requires supply>=1.
        var family = families().stream().filter(value -> value.name.equals("partial-startup")).findFirst().orElseThrow();
        for (int order = 0; order < 3; order++) {
            var recipes = reorder(family.recipes, order);
            var test = new Case("partial-startup/explicit-feedback/order=" + order, recipes, Map.of("a", 1L, "ore", 1L),
                    1, Map.of(), family.word);
            var compiler = new GraphCompiler<>(recipes); var budget = budget(WORK, new AtomicBoolean());
            try (var model = RecipeCountModel.create(compiler, TARGET, 1, test.stock, Map.of(), Set.of(), Set.of(), true, budget)) {
                var proposed = new BigInteger[model.recipes.size()];
                for (int i = 0; i < proposed.length; i++) proposed[i] = model.recipes.get(i).id().equals("gated") ? BigInteger.ONE : BigInteger.ZERO;
                check(validRows(model.constraints, proposed), test.id + " initial counterexample no longer satisfies net balance");
                try (var work = new CountRefinedProjection<>(model, budget, WORK / 2, 512, true, proposed)) {
                    while (!work.step()) {}
                    check(work.result() != null && Arrays.equals(work.result(), proposed), test.id + " initial candidate changed before physical validation");
                    try (var first = new CountSchedule<>(model, work.result(), budget, true)) {
                        while (!first.step()) {}
                        check(first.witness() == null, test.id + " unfunded two-key read became executable");
                    }
                    check(work.reject(), test.id + " failed execution discarded the necessary refinement opportunity");
                    GraphPlan<String> plan = null;
                    for (int attempt = 0; attempt < REFINEMENTS; attempt++) {
                        while (!work.step()) {}
                        if (work.result() == null) break;
                        check(validRows(model.constraints, work.result()), test.id + " refined counts changed original quantity obligations");
                        PlanStep word;
                        try (var next = new CountSchedule<>(model, work.result(), budget, true)) {
                            while (!next.step()) {}
                            word = next.witness();
                        }
                        if (word != null) {
                            try (var candidate = new AllocationSearch.Candidate<>(word, index(model.recipes), TARGET, 1,
                                    test.stock, Map.of(), Set.of(), true, true, false, budget, System.nanoTime())) {
                                while (!candidate.step()) {}
                                if (candidate.plan != null && candidate.plan.feasible()) { plan = candidate.plan; break; }
                            }
                        }
                        if (!work.reject()) break;
                    }
                    check(work.refined() && plan != null, test.id + " necessary startup condition did not fund the missing catalyst");
                    verify(test, plan); witnesses++; cases++;
                    check(plan.patternTimesExact().getOrDefault("supply", BigInteger.ZERO).compareTo(BigInteger.ONE) >= 0,
                            test.id + " refined witness omitted required catalyst production");
                }
            }
            check(budget.reservedBytes() == 0, test.id + " explicit feedback retained request memory");
        }
    }

    private static void cancellationAndLimitedBudgets() {
        var family = families().get(0);
        var stock = PlanningInventory.exactSnapshot(initial(family.word, index(family.recipes), family.amount, family.seeds));
        var test = new Case("lifecycle", family.recipes, stock, family.amount, family.seeds, family.word);
        var compiler = new GraphCompiler<>(family.recipes);
        for (int step : new int[]{0, 1, 5}) {
            var stop = new AtomicBoolean(); var budget = budget(WORK, stop);
            try (var model = RecipeCountModel.create(compiler, TARGET, test.amount, stock, test.seeds, Set.of(), Set.of(), true, budget);
                 var work = new CountRefinedProjection<>(model, budget, WORK / 2, 512, true)) {
                boolean yielded = false;
                for (int i = 0; i < step && !yielded; i++) yielded = work.step();
                if (!yielded) {
                    stop.set(true);
                    try { work.step(); throw new AssertionError("Refinement swallowed cancellation"); }
                    catch (CancellationException expected) { checks++; }
                    check(work.result() == null, "Cancelled refinement published an incomplete proposal");
                }
            }
            check(budget.reservedBytes() == 0, "Cancelled family refinement retained memory");
            checkResult(test, refined(test, compiler, WORK), true, new Oracle(Reachable.YES, 0), "after-cancellation");
        }
        for (long cap : new long[]{1, 16, 512}) {
            var direct = refined(test, compiler, cap);
            var main = mainline(test, compiler, true, cap);
            check(main == null || main.result() != GraphPlan.Result.INFEASIBLE,
                    "A tiny budget promoted a known feasible input to INFEASIBLE");
            if (direct != null && direct.feasible()) verify(test, direct);
            if (main != null && main.feasible()) verify(test, main);
            // A tiny budget can decline this known feasible input. No such
            // decline is counted as a closed oracle or infeasibility proof.
        }
    }

    /** Exhaust every reachable primitive inventory, with no artificial resource cap. */
    private static Oracle explore(Case test) {
        var keys = new TreeSet<String>(test.stock.keySet()); keys.add(TARGET); keys.addAll(test.seeds.keySet());
        for (var recipe : test.recipes) { keys.addAll(recipe.inputs().keySet()); keys.addAll(recipe.outputs().keySet()); }
        var ordered = List.copyOf(keys);
        var initial = ordered.stream().map(key -> PlanningInventory.exactAmount(test.stock, key)).toList();
        var seen = new HashSet<List<BigInteger>>(); var queue = new ArrayDeque<List<BigInteger>>();
        seen.add(initial); queue.add(initial);
        while (!queue.isEmpty()) {
            var current = queue.removeFirst(); boolean delivered = true;
            for (int i = 0; i < ordered.size(); i++) {
                String key = ordered.get(i);
                BigInteger goal = BigInteger.valueOf(test.seeds.getOrDefault(key, 0L));
                if (key.equals(TARGET)) goal = goal.add(BigInteger.valueOf(test.amount));
                if (current.get(i).compareTo(goal) < 0) delivered = false;
            }
            if (delivered) return new Oracle(Reachable.YES, seen.size());
            for (var recipe : test.recipes) {
                check(!recipe.batchSensitiveInputs(), test.id + " finite-state oracle does not admit per-push configurations");
                var next = new ArrayList<BigInteger>(ordered.size()); boolean enabled = true;
                for (int i = 0; i < ordered.size(); i++) {
                    String key = ordered.get(i);
                    BigInteger input = BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L));
                    if (current.get(i).compareTo(input) < 0) { enabled = false; break; }
                    next.add(current.get(i).subtract(input).add(BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L))));
                }
                if (enabled && seen.add(next)) {
                    if (seen.size() > STATE_LIMIT) return new Oracle(Reachable.UNKNOWN, seen.size());
                    queue.addLast(next);
                }
            }
        }
        return new Oracle(Reachable.NO, seen.size());
    }

    private static void verify(Case test, GraphPlan<String> plan) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        check(plan.target().equals(TARGET) && plan.amount() == test.amount && plan.missingExact().isEmpty(), test.id + " changed request contract");
        var original = index(test.recipes);
        for (var entry : plan.recipes().entrySet()) check(entry.getValue().equals(original.get(entry.getKey())), test.id + " witness changed a primitive recipe");
        check(funded(plan.steps(), original, PlanningInventory.exactAmounts(test.stock), test.amount, plan.seeds()),
                test.id + " primitive replay rejected prefix or delivery funding");
        for (var seed : test.seeds.entrySet()) check(plan.seeds().getOrDefault(seed.getKey(), 0L) >= seed.getValue(), test.id + " caller final seed dropped");
        var required = initial(plan.steps(), original, test.amount, plan.seeds());
        required.forEach((key, value) -> check(plan.initialExact().getOrDefault(key, BigInteger.ZERO).compareTo(value) >= 0,
                test.id + " published reservation omitted primitive funding for " + key));
        plan.initialExact().forEach((key, value) -> check(value.compareTo(PlanningInventory.exactAmount(test.stock, key)) <= 0,
                test.id + " reservation exceeded current stock: " + key));
    }

    /** Independent exact primitive prefix composition; no solver summaries or count rows are consulted. */
    private static Fold fold(PlanStep step, Map<String, GraphRecipe<String>> recipes) {
        if (step instanceof PlanStep.Batch batch) {
            var recipe = recipes.get(batch.recipe());
            check(recipe != null && !recipe.batchSensitiveInputs(), "Unknown primitive or unsupported per-push fixture");
            var need = new LinkedHashMap<String, BigInteger>(); var delta = new LinkedHashMap<String, BigInteger>();
            recipe.inputs().forEach((key, value) -> { need.put(key, BigInteger.valueOf(value)); delta.put(key, BigInteger.valueOf(value).negate()); });
            recipe.outputs().forEach((key, value) -> delta.merge(key, BigInteger.valueOf(value), BigInteger::add));
            return repeat(new Fold(need, delta), batch.runs());
        }
        if (step instanceof PlanStep.Repeat repeated) return repeat(fold(repeated.body(), recipes), repeated.times());
        var result = new Fold(new LinkedHashMap<>(), new LinkedHashMap<>());
        for (var child : ((PlanStep.Sequence) step).children()) {
            var suffix = fold(child, recipes);
            suffix.need.forEach((key, value) -> result.need.merge(key,
                    value.subtract(result.delta.getOrDefault(key, BigInteger.ZERO)).max(BigInteger.ZERO), BigInteger::max));
            suffix.delta.forEach((key, value) -> result.delta.merge(key, value, BigInteger::add));
        }
        return result;
    }

    private static Fold repeat(Fold word, long count) {
        if (count == 0) return new Fold(Map.of(), Map.of());
        var need = new LinkedHashMap<>(word.need); var delta = new LinkedHashMap<String, BigInteger>();
        word.delta.forEach((key, value) -> {
            if (value.signum() < 0) need.merge(key, value.negate().multiply(BigInteger.valueOf(count - 1)), BigInteger::add);
            delta.put(key, value.multiply(BigInteger.valueOf(count)));
        });
        return new Fold(need, delta);
    }

    private static Map<String, BigInteger> initial(PlanStep word, Map<String, GraphRecipe<String>> recipes,
                                                   long amount, Map<String, Long> seeds) {
        var summary = fold(word, recipes); var keys = new HashSet<>(summary.need.keySet());
        keys.addAll(summary.delta.keySet()); keys.addAll(seeds.keySet()); keys.add(TARGET);
        var stock = new LinkedHashMap<String, BigInteger>();
        for (String key : keys) {
            BigInteger goal = BigInteger.valueOf(seeds.getOrDefault(key, 0L));
            if (key.equals(TARGET)) goal = goal.add(BigInteger.valueOf(amount));
            BigInteger required = summary.need.getOrDefault(key, BigInteger.ZERO)
                    .max(goal.subtract(summary.delta.getOrDefault(key, BigInteger.ZERO))).max(BigInteger.ZERO);
            if (required.signum() > 0) stock.put(key, required);
        }
        return stock;
    }

    private static boolean funded(PlanStep word, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> stock,
                                    long amount, Map<String, Long> seeds) {
        for (var entry : initial(word, recipes, amount, seeds).entrySet())
            if (stock.getOrDefault(entry.getKey(), BigInteger.ZERO).compareTo(entry.getValue()) < 0) return false;
        return fold(word, recipes).delta.getOrDefault(TARGET, BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0;
    }

    private static boolean validRows(List<ExactLinearProgram.Constraint> rows, BigInteger[] counts) {
        for (var count : counts) if (count == null || count.signum() < 0) return false;
        for (var row : rows) {
            BigInteger total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) total = total.add(term.getValue().multiply(counts[term.getKey()]));
            if (total.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static List<GraphRecipe<String>> reorder(List<GraphRecipe<String>> original, int order) {
        var result = new ArrayList<GraphRecipe<String>>();
        for (var recipe : original) {
            var outputs = new ArrayList<>(recipe.outputs().entrySet());
            if (order != 0) Collections.reverse(outputs); // Swap which coproduct is encountered first.
            var reordered = new LinkedHashMap<String, Long>(); outputs.forEach(entry -> reordered.put(entry.getKey(), entry.getValue()));
            result.add(new GraphRecipe<>(recipe.id(), recipe.binding(), recipe.slots(), reordered));
        }
        if (order == 1) Collections.reverse(result);
        if (order == 2) Collections.shuffle(result, new Random(20261027));
        return List.copyOf(result);
    }

    private static PlanStep repeated(long times, String... ids) {
        return new PlanStep.Repeat(new PlanStep.Sequence(Arrays.stream(ids).map(id -> (PlanStep) new PlanStep.Batch(id, 1)).toList()), times);
    }
    private static String targetProducer(List<GraphRecipe<String>> recipes) {
        return recipes.stream().filter(recipe -> recipe.outputs().containsKey(TARGET)).findFirst().orElseThrow().id();
    }
    private static Map<String, GraphRecipe<String>> index(List<GraphRecipe<String>> recipes) {
        var result = new LinkedHashMap<String, GraphRecipe<String>>(); recipes.forEach(recipe -> result.put(recipe.id(), recipe)); return result;
    }
    private static Map<String, Long> ordered(Object... entries) {
        var result = new LinkedHashMap<String, Long>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], (Long) entries[i + 1]);
        return result;
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> input, Map<String, Long> output) {
        return new GraphRecipe<>(id, id, input.entrySet().stream().map(entry -> new GraphRecipe.Slot<>(entry.getKey(), entry.getValue())).toList(), output);
    }
    private static PlanningBudget budget(long work, AtomicBoolean cancelled) {
        return new PlanningBudget(0, work, MEMORY, cancelled::get, System::nanoTime);
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
