package org.cgse.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;

/** Pure captured-value expansion: ordering, material returns, truncation and budget boundaries. */
public final class CapturedRecipeTest {

    public static void main(String[] args) {
        dependencyOrder();
        mixedCandidates();
        materialReturns();
        truncation();
        budgets();
        immutableValues();
        System.out.println("CapturedRecipeTest: PASS");
    }

    private static void dependencyOrder() {
        var recipe = recipe(List.of(input(1, "a", "b", "c"), input(1, "x", "y")), true, false);
        check(list(recipe.dependencies()).equals(List.of("a", "x", "y", "b", "c")), "dependency visitation must retain Cartesian first-use order");
        var variants = all(recipe, budget());
        check(variants.stream().map(value -> value.slots().stream().map(GraphRecipe.Slot::key).toList()).toList().equals(
                List.of(List.of("a", "x"), List.of("a", "y"), List.of("b", "x"), List.of("b", "y"), List.of("c", "x"), List.of("c", "y"))),
                "last input slot must advance first");
        for (int first = 1; first <= 5; first++) for (int second = 1; second <= 5; second++) {
            for (int multiplier = 1; multiplier <= 3; multiplier++) {
                var captured = recipe(List.of(numbered("a", first, multiplier), numbered("b", second, multiplier)), false, false);
                var firstSeen = new LinkedHashSet<String>();
                for (var resolved : all(captured, budget())) resolved.slots().forEach(slot -> firstSeen.add(slot.key()));
                check(list(captured.dependencies()).equals(List.copyOf(firstSeen)), "lazy dependency traversal differs from materialized expansion");
            }
        }
        var empty = recipe(List.of(new CapturedRecipe.Input<String>(1, List.of())), false, false);
        check(empty.size() == 0 && !empty.dependencies().hasNext() && !empty.expand(budget()).hasNext(), "empty alternatives must produce no variants");
        expect(NoSuchElementException.class, () -> empty.expand(budget()).next());
        var noInputs = recipe(List.of(), false, false);
        check(noInputs.size() == 1 && !noInputs.dependencies().hasNext(), "zero-input pattern still has one output variant");
        check(all(noInputs, budget()).get(0).outputs().equals(Map.of("out", 1L)), "zero-input output lost");
    }

    private static void mixedCandidates() {
        var input = new CapturedRecipe.Input<>(3, List.of(
                new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("a", 2), "container-a", false),
                new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("b", 3), "container-b", false)));
        var recipe = recipe(List.of(input), false, false);
        var variants = all(recipe, budget());
        check(recipe.size() == 4 && !recipe.bounded(), "weak composition count changed");
        check(variants.get(0).slots().equals(List.of(new GraphRecipe.Slot<>("a", 6, 0))), "first single alternative changed");
        check(variants.get(1).slots().equals(List.of(new GraphRecipe.Slot<>("b", 9, 0))), "second single alternative changed");
        check(variants.get(2).slots().equals(List.of(new GraphRecipe.Slot<>("a", 4, 0), new GraphRecipe.Slot<>("b", 3, 0))), "descending-copy mixed candidate order changed");
        check(variants.get(3).slots().equals(List.of(new GraphRecipe.Slot<>("a", 2, 0), new GraphRecipe.Slot<>("b", 6, 0))), "last mixed candidate changed");
        check(variants.get(2).outputs().equals(Map.of("out", 1L, "container-a", 2L, "container-b", 1L)), "containers follow selected copies, not stack amounts");
        check(recipe(List.of(input), true, false).size() == 2, "external processing must not mix alternatives");
        check(recipe(List.of(input(10, "a", "b")), false, false).size() == 2, "large multipliers must keep single alternatives");
    }

    private static void materialReturns() {
        var reusable = new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool", 2), "container", true, true);
        var configuration = new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("circuit", 1), null, true, false);
        var inputs = List.of(new CapturedRecipe.Input<>(3, List.of(reusable)), new CapturedRecipe.Input<>(1, List.of(configuration)));
        var captured = new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>("tool", 5),
                new CapturedRecipe.Amount<>("product", 2), new CapturedRecipe.Amount<>("product", 3)), false, false);
        var resolved = captured.expand(budget()).next();
        check(resolved.slots().equals(List.of(new GraphRecipe.Slot<>("tool", 6, 0, true, true),
                new GraphRecipe.Slot<>("circuit", 1, 1, true, false))), "configuration/reusability/original slot indices must survive capture");
        check(resolved.outputs().equals(Map.of("tool", 11L, "container", 3L, "product", 5L)), "reusable material and duplicate outputs must aggregate exactly");
        check(!resolved.outputs().containsKey("circuit"), "configuration does not itself imply a physical return");
        var overflow = new CapturedRecipe<String>(List.of(), List.of(new CapturedRecipe.Amount<>("product", Long.MAX_VALUE),
                new CapturedRecipe.Amount<>("product", 1)), false, false);
        expect(ArithmeticException.class, () -> overflow.expand(budget()).next());
    }

    private static void truncation() {
        var exact = recipe(List.of(numbered("a", 16, 1), numbered("b", 16, 1)), true, false);
        check(exact.size() == 256 && !exact.bounded(), "exact Cartesian boundary is not a truncation");
        var truncated = recipe(List.of(numbered("a", 20, 1), numbered("b", 20, 1)), true, false);
        check(truncated.size() == 256 && truncated.bounded(), "Cartesian expansion must stop at 256");
        var variants = all(truncated, budget());
        check(variants.size() == 256, "bounded expansion emitted wrong number of variants");
        check(variants.get(255).slots().equals(List.of(new GraphRecipe.Slot<>("a12", 1, 0), new GraphRecipe.Slot<>("b15", 1, 1))), "truncation changed which alternatives remain visible");
        var dependencies = list(truncated.dependencies());
        check(dependencies.contains("a12") && !dependencies.contains("a13") && dependencies.contains("b19"), "excluded alternatives cannot discover providers or inventory");
        var firstSeen = new LinkedHashSet<String>();
        variants.forEach(variant -> variant.slots().forEach(slot -> firstSeen.add(slot.key())));
        check(dependencies.equals(List.copyOf(firstSeen)), "truncated dependency traversal must match actual variants");
        var mixed = recipe(List.of(numbered("m", 20, 9)), false, false);
        check(mixed.size() == 256 && mixed.bounded() && all(mixed, budget()).size() == 256, "mixed composition cap changed");
        check(recipe(List.of(input(1, "a")), false, true).bounded(), "capture-side truncation evidence must be preserved");
    }

    private static void budgets() {
        var simple = recipe(List.of(input(3, "a", "b")), false, false);
        var budget = budget();
        var expansion = simple.expand(budget);
        check(budget.reservedBytes() == 0 && budget.compilationWork() == 0, "creating an expansion must stay lazy");
        expansion.next();
        check(budget.compilationWork() == 10 && budget.reservedBytes() == 960, "first expansion work/reservation changed");
        while (expansion.hasNext()) expansion.next();
        check(budget.compilationWork() == 13 && budget.reservedBytes() == 2560, "shared expansion budget accounting changed");
        var smallWork = new PlanningBudget(0, 1, 1L << 20, () -> false, System::nanoTime);
        expectLimit(PlanningBudget.Limit.SEARCH_LIMIT, () -> simple.expand(smallWork).next());
        var smallMemory = new PlanningBudget(0, 1000, 1, () -> false, System::nanoTime);
        expectLimit(PlanningBudget.Limit.MEMORY_LIMIT, () -> simple.expand(smallMemory).next());
        var cancelled = budget();
        cancelled.cancel();
        expect(CancellationException.class, () -> simple.expand(cancelled).next());
        check(cancelled.reservedBytes() == 0, "cancelled expansion must not allocate its variant selections");
    }

    private static void immutableValues() {
        var candidates = new ArrayList<>(List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("a", 1), null, false)));
        var inputs = new ArrayList<>(List.of(new CapturedRecipe.Input<>(1, candidates)));
        var outputs = new ArrayList<>(List.of(new CapturedRecipe.Amount<>("out", 1)));
        var captured = new CapturedRecipe<>(inputs, outputs, false, false);
        candidates.clear();
        inputs.clear();
        outputs.clear();
        var result = captured.expand(budget()).next();
        check(result.slots().get(0).key().equals("a") && result.outputs().equals(Map.of("out", 1L)), "capture must own immutable list snapshots");
        expect(UnsupportedOperationException.class, () -> result.outputs().put("extra", 1L));
        expect(UnsupportedOperationException.class, () -> result.slots().clear());
    }

    private static CapturedRecipe<String> recipe(List<CapturedRecipe.Input<String>> inputs, boolean external, boolean bounded) {
        return new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>("out", 1)), external, bounded);
    }

    private static CapturedRecipe.Input<String> input(long multiplier, String... keys) {
        var candidates = new ArrayList<CapturedRecipe.Candidate<String>>();
        for (String key : keys) candidates.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false));
        return new CapturedRecipe.Input<>(multiplier, candidates);
    }

    private static CapturedRecipe.Input<String> numbered(String prefix, int count, long multiplier) {
        String[] keys = new String[count];
        for (int i = 0; i < count; i++) keys[i] = prefix + i;
        return input(multiplier, keys);
    }

    private static List<String> list(Iterator<String> iterator) {
        var result = new ArrayList<String>();
        iterator.forEachRemaining(result::add);
        return result;
    }

    private static List<CapturedRecipe.Resolved<String>> all(CapturedRecipe<String> recipe, PlanningBudget budget) {
        var result = new ArrayList<CapturedRecipe.Resolved<String>>();
        var expansion = recipe.expand(budget);
        while (expansion.hasNext()) result.add(expansion.next());
        return result;
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 1_000_000, () -> false);
    }

    private static void expectLimit(PlanningBudget.Limit limit, Runnable operation) {
        try {
            operation.run();
        } catch (PlanningBudget.Exhausted exhausted) {
            check(exhausted.limit() == limit, "wrong budget limit");
            return;
        }
        throw new AssertionError("Expected " + limit);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable operation) {
        try {
            operation.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
