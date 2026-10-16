// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/** Primitive replay of witnessed scan words, independent of the scheduling summaries. */
public final class CountScheduleWordTest {
    private static int jumps, assertions, schedules;

    public static void main(String[] args) {
        randomWords();
        for (int length : new int[]{3, 7, 31}) for (int order = 0; order < 6; order++)
            for (BigInteger n : List.of(BigInteger.valueOf(96), BigInteger.valueOf(1_000_000_000L), BigInteger.TEN.pow(24).add(BigInteger.ONE)))
                ring(length, order, n);
        rejectedWitness(); finiteResources(); limits();
        System.out.println("Count scan words: random=4000; jumps=" + jumps + "; schedules=" + schedules + "; assertions=" + assertions);
    }

    private static void randomWords() {
        Random random = new Random(9016);
        for (int sample = 0; sample < 4000; sample++) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            for (int r = 0; r < 4; r++) {
                var in = new LinkedHashMap<String, Long>(); var out = new LinkedHashMap<String, Long>();
                for (int key = 0; key < 3; key++) {
                    long a = random.nextInt(4), b = random.nextInt(5);
                    if (a > 0) in.put("k" + key, a); if (b > 0) out.put("k" + key, b);
                }
                out.put("product", 1L); recipes.add(recipe("r" + r, in, out));
            }
            var actions = recipes.stream().map(SequenceSummary::recipe).toList();
            var byId = index(recipes); var stock = new LinkedHashMap<String, BigInteger>();
            for (int key = 0; key < 3; key++) stock.put("k" + key, BigInteger.valueOf(12 + random.nextInt(30)));
            Set<String> external = sample % 3 == 0 ? Set.of("k0") : Set.of();
            BigInteger[] remaining = new BigInteger[4];
            for (int i = 0; i < 4; i++) remaining[i] = BigInteger.valueOf(5 + random.nextInt(30));
            var budget = budget(100_000);
            try (var word = new CountScheduleWord<>(actions, remaining, budget)) {
                for (int pass = 0; pass < 15; pass++) {
                    var steps = new ArrayList<PlanStep>(); var used = zeros(4);
                    for (int i = 0; i < 1 + random.nextInt(6); i++) {
                        int r = random.nextInt(4);
                        if (remaining[r].signum() == 0 || !fire(recipes.get(r), stock, external)) continue;
                        remaining[r] = remaining[r].subtract(BigInteger.ONE); used[r] = used[r].add(BigInteger.ONE);
                        steps.add(new PlanStep.Batch("r" + r, 1));
                    }
                    if (steps.isEmpty()) continue;
                    var body = new PlanStep.Sequence(steps);
                    var jump = word.observe(body, SequenceSummary.of(body, byId), used, remaining, stock, external);
                    if (jump == null) continue;
                    var ids = flatten(jump.body()); var actual = zeros(4);
                    for (String id : ids) actual[Integer.parseInt(id.substring(1))] = actual[Integer.parseInt(id.substring(1))].add(BigInteger.ONE);
                    check(Arrays.equals(actual, jump.counts()), "Word counts differ from primitive program");
                    var after = new LinkedHashMap<>(stock); var counts = remaining.clone(); long copies = 0;
                    while (true) {
                        boolean enough = true;
                        for (int i = 0; i < 4; i++) enough &= counts[i].compareTo(actual[i]) >= 0;
                        if (!enough) break;
                        var trial = new LinkedHashMap<>(after); boolean funded = true;
                        for (String id : ids) if (!fire(byId.get(id), trial, external)) { funded = false; break; }
                        if (!funded) break;
                        after = trial; copies++;
                        for (int i = 0; i < 4; i++) counts[i] = counts[i].subtract(actual[i]);
                    }
                    check(jump.copies().equals(BigInteger.valueOf(copies)) && copies > 0, "Incorrect maximum safe copies");
                    stock = after; remaining = counts; jumps++; word.reset();
                }
            }
            check(budget.reservedBytes() == 0, "Random word leaked memory");
        }
        check(jumps > 3000, "Random oracle failed to exercise enough accepted words");
    }

    private static void ring(int length, int order, BigInteger n) {
        var recipes = ringRecipes(length);
        if (order == 0) Collections.reverse(recipes);
        else Collections.shuffle(recipes, new Random(215L + order));
        Map<String, Long> stock = Map.of("p0", 1L, "tool-a", 1L, "tool-b", 1L);
        Set<String> external = Set.of("raw");
        BigInteger[] counts = recipes.stream().map(r -> r.id().startsWith("r") ? n : BigInteger.ONE).toArray(BigInteger[]::new);
        var budget = budget(1_000_000);
        try (var model = RecipeCountModel.forShell(recipes, Map.of("product", n), stock, external, budget);
             var schedule = new CountSchedule<>(model, counts, budget)) {
            while (!schedule.step()) {}
            check(schedule.result() == CountSchedule.Result.WITNESS, "Ring was not scheduled: " + length + "/" + order);
            verify(schedule.witness(), recipes, stock, external, counts);
            check(budget.searchWork() < 100_000, "Ring cost depends on requested firing count"); schedules++;
        }
        check(budget.reservedBytes() == 0, "Ring schedule leaked");
    }

    private static ArrayList<GraphRecipe<String>> ringRecipes(int length) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < length; i++) {
            var slots = new ArrayList<GraphRecipe.Slot<String>>();
            slots.add(new GraphRecipe.Slot<>("p" + i, 1));
            slots.add(new GraphRecipe.Slot<>("tools", 2, 0, true, true));
            if (i == 0) slots.add(new GraphRecipe.Slot<>("raw", 1));
            var out = new LinkedHashMap<String, Long>(); out.put("p" + (i + 1 == length ? 0 : i + 1), 1L); out.put("tools", 2L);
            if (i + 1 == length) out.put("product", 1L);
            recipes.add(new GraphRecipe<>("r" + i, "r" + i, slots, out));
        }
        recipes.add(recipe("activate-a", Map.of("tool-a", 1L), Map.of("tools", 1L)));
        recipes.add(recipe("activate-b", Map.of("tool-b", 1L), Map.of("tools", 1L)));
        return recipes;
    }

    private static void rejectedWitness() {
        var recipes = ringRecipes(3); Collections.reverse(recipes);
        BigInteger[] counts = recipes.stream().map(r -> BigInteger.valueOf(r.id().startsWith("r") ? 12 : 1)).toArray(BigInteger[]::new);
        Map<String, Long> stock = Map.of("p0", 1L, "tool-a", 1L, "tool-b", 1L, "raw", 12L);
        var budget = budget(1_000_000);
        try (var model = RecipeCountModel.forShell(recipes, Map.of("product", BigInteger.valueOf(12)), stock, Set.of(), budget);
             var schedule = new CountSchedule<>(model, counts, budget).retainWitnessForAssembly()) {
            while (!schedule.step()) {}
            check(schedule.result() == CountSchedule.Result.WITNESS && budget.diagnostics().contains("count_schedule_word"), "No accelerated candidate for rejection");
            check(schedule.retryAfterRejectedWitness(), "Rejected acceleration lost ordinary continuation");
            while (!schedule.step()) {}
            check(schedule.result() == CountSchedule.Result.WITNESS, "Original scan no longer succeeds after rejection");
            var held = big(stock); var left = counts.clone(); var expected = new ArrayList<String>();
            while (Arrays.stream(left).anyMatch(c -> c.signum() > 0)) {
                int before = expected.size();
                for (int i = 0; i < left.length; i++) while (left[i].signum() > 0 && fire(recipes.get(i), held, Set.of())) {
                    expected.add(recipes.get(i).id()); left[i] = left[i].subtract(BigInteger.ONE);
                }
                check(expected.size() > before, "Independent original scan stalled");
            }
            check(flatten(schedule.witness()).equals(expected), "Accelerated rejection skipped the original scan order");
            verify(schedule.witness(), recipes, stock, Set.of(), counts);
        }
        check(budget.reservedBytes() == 0, "Rejected word leaked");
    }

    private static void finiteResources() {
        for (boolean seeded : new boolean[]{false, true}) {
            var recipes = ringRecipes(3); Collections.reverse(recipes);
            Map<String, Long> stock = Map.of("p0", seeded ? 1L : 0L, "tool-a", 1L, "tool-b", 1L, "raw", 11L);
            BigInteger[] counts = recipes.stream().map(r -> BigInteger.valueOf(r.id().startsWith("r") ? 12 : 1)).toArray(BigInteger[]::new);
            var budget = budget(1_000_000);
            try (var model = RecipeCountModel.forShell(recipes, Map.of("product", BigInteger.valueOf(12)), stock, Set.of(), budget);
                 var schedule = new CountSchedule<>(model, counts, budget)) {
                try { while (!schedule.step()) {} }
                catch (PlanningBudget.Exhausted limit) { /* An incomplete search is not a feasibility verdict. */ }
                check(schedule.witness() == null && schedule.result() != CountSchedule.Result.WITNESS, "A prefix or finite supply was invented");
            }
            check(budget.reservedBytes() == 0, "Finite-resource failure leaked");
        }
    }

    private static void limits() {
        var recipes = List.of(recipe("ab", Map.of("a", 1L), Map.of("b", 1L)), recipe("ba", Map.of("b", 1L), Map.of("a", 1L)));
        var actions = recipes.stream().map(SequenceSummary::recipe).toList();
        BigInteger[] counts = {BigInteger.valueOf(100_000), BigInteger.valueOf(100_000)};
        var budget = budget(40_000); long denied = budget.availableBytes(); budget.reserve(denied);
        try (var word = new CountScheduleWord<>(actions, counts, budget)) {
            check(word.observe(new PlanStep.Batch("ab", 1), actions.get(0), new BigInteger[]{BigInteger.ONE, BigInteger.ZERO}, counts, Map.of("b", BigInteger.ONE), Set.of()) == null,
                    "Optional word ignored memory refusal");
        } finally { budget.release(denied); }
        try (var word = new CountScheduleWord<>(actions, counts, budget)) {
            for (int i = 0; i < 10_000; i++) {
                word.observe(new PlanStep.Batch("ab", 1), actions.get(0), new BigInteger[]{BigInteger.ONE, BigInteger.ZERO}, counts, Map.of("b", BigInteger.ONE), Set.of());
                word.observe(new PlanStep.Batch("ba", 1), actions.get(1), new BigInteger[]{BigInteger.ZERO, BigInteger.ONE}, counts, Map.of("a", BigInteger.ONE), Set.of());
                word.reset();
            }
            check(budget.searchWork() <= 2500, "Reset renewed the auxiliary work allowance");
        }
        check(budget.reservedBytes() == 0, "Local cutoff leaked");
        for (long offset : new long[]{0, 1, 4, 7, 12}) {
            var stop = new AtomicLong(Long.MAX_VALUE); var holder = new PlanningBudget[1];
            var cancelling = new PlanningBudget(0, 40_000, 64L << 20, () -> holder[0].searchWork() >= stop.get(), System::nanoTime); holder[0] = cancelling;
            try (var word = new CountScheduleWord<>(actions, counts, cancelling)) {
                stop.set(offset);
                try {
                    word.observe(new PlanStep.Batch("ab", 1), actions.get(0), new BigInteger[]{BigInteger.ONE, BigInteger.ZERO}, counts, Map.of("b", BigInteger.ONE), Set.of());
                    word.observe(new PlanStep.Batch("ba", 1), actions.get(1), new BigInteger[]{BigInteger.ZERO, BigInteger.ONE}, counts, Map.of("a", BigInteger.ONE), Set.of());
                } catch (CancellationException expected) { /* Owner close releases the partial word. */ }
            }
            check(cancelling.reservedBytes() == 0, "Cancellation leaked a partial word");
        }
    }

    private static void verify(PlanStep program, List<GraphRecipe<String>> recipes, Map<String, Long> stock, Set<String> external, BigInteger[] expected) {
        var counts = new LinkedHashMap<String, BigInteger>(); count(program, BigInteger.ONE, counts);
        for (int i = 0; i < recipes.size(); i++) check(expected[i].equals(counts.getOrDefault(recipes.get(i).id(), BigInteger.ZERO)), "Compressed count mismatch");
        var summary = SequenceSummary.of(program, index(recipes));
        for (var need : summary.required().entrySet()) check(external.contains(need.getKey()) || need.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(need.getKey(), 0L))) <= 0, "Unfunded compressed prefix");
        if (Arrays.stream(expected).reduce(BigInteger.ZERO, BigInteger::add).compareTo(BigInteger.valueOf(10_000)) <= 0) {
            var held = big(stock); var byId = index(recipes);
            for (String id : flatten(program)) check(fire(byId.get(id), held, external), "Primitive execution needs missing stock");
        }
    }

    private static boolean fire(GraphRecipe<String> recipe, Map<String, BigInteger> held, Set<String> external) {
        for (var in : recipe.inputs().entrySet()) if (!external.contains(in.getKey()) && held.getOrDefault(in.getKey(), BigInteger.ZERO).compareTo(BigInteger.valueOf(in.getValue())) < 0) return false;
        recipe.inputs().forEach((key, n) -> { if (!external.contains(key)) held.merge(key, BigInteger.valueOf(n).negate(), BigInteger::add); });
        recipe.outputs().forEach((key, n) -> { if (!external.contains(key)) held.merge(key, BigInteger.valueOf(n), BigInteger::add); });
        return true;
    }

    private static List<String> flatten(PlanStep program) { var result = new ArrayList<String>(); flatten(program, result); return result; }
    private static void flatten(PlanStep program, List<String> out) {
        if (program instanceof PlanStep.Batch b) { for (long i = 0; i < b.runs(); i++) out.add(b.recipe()); }
        else if (program instanceof PlanStep.Sequence s) s.children().forEach(c -> flatten(c, out));
        else { var r = (PlanStep.Repeat) program; for (long i = 0; i < r.times(); i++) flatten(r.body(), out); }
    }
    private static void count(PlanStep program, BigInteger times, Map<String, BigInteger> out) {
        if (program instanceof PlanStep.Batch b) out.merge(b.recipe(), times.multiply(BigInteger.valueOf(b.runs())), BigInteger::add);
        else if (program instanceof PlanStep.Sequence s) s.children().forEach(c -> count(c, times, out));
        else { var r = (PlanStep.Repeat) program; count(r.body(), times.multiply(BigInteger.valueOf(r.times())), out); }
    }
    private static Map<String, GraphRecipe<String>> index(List<GraphRecipe<String>> recipes) { var r = new LinkedHashMap<String, GraphRecipe<String>>(); recipes.forEach(x -> r.put(x.id(), x)); return r; }
    private static Map<String, BigInteger> big(Map<String, Long> input) { var r = new LinkedHashMap<String, BigInteger>(); input.forEach((k, v) -> r.put(k, BigInteger.valueOf(v))); return r; }
    private static BigInteger[] zeros(int n) { var result = new BigInteger[n]; Arrays.fill(result, BigInteger.ZERO); return result; }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) { return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out); }
    private static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
