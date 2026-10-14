// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Complete supply permits proof sharing; only identical columns permit replacing an arm. */
public final class CountSearchOwnershipTest {
    private static final long STARTED = 1234;

    public static void main(String[] args) throws Exception {
        for (int order = 0; order < 6; order++) for (int fuel : new int[]{8, 9, 12}) {
            handoff(order, fuel, false);
            handoff(order, fuel, true);
        }
        scopeGuards();
        soleOwner();
        cancellation();
        System.out.println("Count search ownership: 36 ordered/full-scope controls, independent firing, scope guards, sole ownership and cancellation passed");
    }

    private static List<GraphRecipe<String>> recipes(int order) {
        var result = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 9; i++) for (int fuel = 1; fuel <= 2; fuel++)
            result.add(new GraphRecipe<>("r" + i + "/" + fuel, "binding/" + i + "/" + fuel,
                    List.of(new GraphRecipe.Slot<>("F", fuel), new GraphRecipe.Slot<>("A" + i, 1)),
                    Map.of(i == 8 ? "G" : "A" + (i + 1), 1L)));
        Collections.shuffle(result, new Random(order));
        return result;
    }

    private static PlanningBudget budget(AtomicBoolean cancelled) {
        return new PlanningBudget(0, 40_000_000, 256L << 20, cancelled::get, System::nanoTime);
    }

    private static IntegerCountSearch<String> scout(GraphCompiler<String> compiler, Map<String, Long> stock,
                                                     PlanningBudget budget) {
        var search = new IntegerCountSearch<>(compiler, "G", 1, stock, Map.of(), Set.of(), Set.of(),
                false, true, budget, STARTED);
        search.scout(1);
        while (!search.step()) {}
        check(search.paused(), "Fixture did not retain a live frontier");
        return search;
    }

    private static void handoff(int order, int fuel, boolean differentOrder) throws Exception {
        var recipes = recipes(order);
        var full = new GraphCompiler<>(recipes);
        var localRecipes = new ArrayList<>(recipes);
        if (differentOrder) Collections.reverse(localRecipes);
        var stock = Map.of("F", (long) fuel, "A0", 1L);
        var budget = budget(new AtomicBoolean());
        try (var search = scout(new GraphCompiler<>(localRecipes), stock, budget);
             var proofs = new OrderProofs<>(RecipeCountModel.forProofs(full, "G", 1, stock, Map.of(),
                     Set.of(), Set.of(), true, budget), budget)) {
            var model = model(search);
            var beforePending = new ArrayList<>((Collection<?>) get(search, "pending"));
            long work = number(search, "work"), global = budget.searchWork();
            // Every witness fires one first and one last transition. Publish
            // independently indexed clauses from both orders before joining.
            var localClause = requiredTransition(model, 0);
            ((CountConflictPool) get(search, "choiceConflicts")).add(List.of(localClause));
            proofs.publish(proofs.model, List.of(requiredTransition(proofs.model, 8)));
            var joined = search.joinFullSearch(full, "G", 1, stock, Map.of(), Set.of(), Set.of(),
                    false, true, budget, STARTED, proofs);
            check(joined == (differentOrder ? IntegerCountSearch.Handoff.SHARED : IntegerCountSearch.Handoff.TRANSFERRED),
                    "Different recipe order lost its independent search: " + joined);
            check(model(search) == model && number(search, "work") == work && budget.searchWork() >= global,
                    "Handoff rebuilt columns or refunded completed work");
            check(new ArrayList<>((Collection<?>) get(search, "pending")).equals(beforePending), "Handoff replaced live branches");
            check(proofs.forModel(model).contains(localClause), "Local clause lost its recipe-coordinate translation");
            check(((CountConflictPool) get(search, "choiceConflicts")).snapshot().contains(requiredTransition(model, 8)),
                    "Incoming order clause was not translated into the retained columns");
            long ceiling = number(search, "allowance");
            check(search.joinFullSearch(full, "G", 1, stock, Map.of(), Set.of(), Set.of(), false, true,
                    budget, STARTED, proofs) == IntegerCountSearch.Handoff.DECLINED && number(search, "allowance") == ceiling,
                    "Repeated handoff renewed a granted turn");
            finish(search, recipes, stock, fuel >= 9);
        }
        check(budget.reservedBytes() == 0, "Joined search leaked a model or branch");
    }

    private static CountConflict requiredTransition(RecipeCountModel<String> model, int step) {
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        for (int i = 0; i < model.recipes.size(); i++)
            if (model.recipes.get(i).id().startsWith("r" + step + "/")) terms.put(i, BigInteger.ONE);
        check(terms.size() == 2, "Wrong transition support");
        return new CountConflict(List.of(new ExactLinearProgram.Constraint(terms, BigInteger.ZERO)));
    }

    private static void scopeGuards() throws Exception {
        var recipes = recipes(0);
        var full = new GraphCompiler<>(recipes);
        var stock = Map.of("F", 9L, "A0", 1L);
        var budget = budget(new AtomicBoolean());
        try (var search = scout(full, stock, budget); var proofs = new OrderProofs<String>(null, budget)) {
            var otherBudget = budget(new AtomicBoolean());
            check(search.joinFullSearch(full, "A8", 1, stock, Map.of(), Set.of(), Set.of(), false, true, budget, STARTED, proofs) == IntegerCountSearch.Handoff.DECLINED, "Changed goal reused");
            check(search.joinFullSearch(full, "G", 2, stock, Map.of(), Set.of(), Set.of(), false, true, budget, STARTED, proofs) == IntegerCountSearch.Handoff.DECLINED, "Changed amount reused");
            check(search.joinFullSearch(full, "G", 1, Map.of("F", 10L, "A0", 1L), Map.of(), Set.of(), Set.of(), false, true, budget, STARTED, proofs) == IntegerCountSearch.Handoff.DECLINED, "Changed stock reused");
            check(search.joinFullSearch(full, "G", 1, stock, Map.of("A0", 1L), Set.of(), Set.of(), false, true, budget, STARTED, proofs) == IntegerCountSearch.Handoff.DECLINED, "Changed seed reused");
            check(search.joinFullSearch(full, "G", 1, stock, Map.of(), Set.of("F"), Set.of(), false, true, budget, STARTED, proofs) == IntegerCountSearch.Handoff.DECLINED, "Changed external supply reused");
            check(search.joinFullSearch(full, "G", 1, stock, Map.of(), Set.of(), Set.of(), true, true, budget, STARTED, proofs) == IntegerCountSearch.Handoff.DECLINED, "Changed preservation reused");
            check(search.joinFullSearch(full, "G", 1, stock, Map.of(), Set.of(), Set.of(), false, false, budget, STARTED, proofs) == IntegerCountSearch.Handoff.DECLINED, "Changed production semantics reused");
            check(search.joinFullSearch(full, "G", 1, stock, Map.of(), Set.of(), Set.of(), false, true, otherBudget, STARTED, proofs) == IntegerCountSearch.Handoff.DECLINED, "Foreign budget accepted");
            check(search.joinFullSearch(full, "G", 1, stock, Map.of(), Set.of(), Set.of(), false, true, budget, STARTED + 1, proofs) == IntegerCountSearch.Handoff.DECLINED, "Foreign request accepted");
            check(search.joinFullSearch(full, "G", 1, stock, Map.of(), Set.of(), Set.of(recipes.get(0).id()), false, true, budget, STARTED, proofs) == IntegerCountSearch.Handoff.DECLINED, "Changed allowed supply reused");
            var expanded = new ArrayList<>(recipes);
            expanded.add(new GraphRecipe<>("new", "new", List.of(new GraphRecipe.Slot<>("A0", 1)), Map.of("G", 1L)));
            check(join(search, new GraphCompiler<>(expanded), stock, budget, proofs) == IntegerCountSearch.Handoff.DECLINED, "New producer omitted from supply scope");
            for (int change = 0; change < 3; change++) {
                var replacement = new ArrayList<>(recipes);
                var first = replacement.get(0);
                var slots = new ArrayList<>(first.slots());
                if (change == 1) slots.set(0, new GraphRecipe.Slot<>("F", 1, 7, true));
                replacement.set(0, new GraphRecipe<>(first.id(), change == 0 ? "new/binding" : first.binding(), slots,
                        change == 2 ? Map.of(first.outputs().keySet().iterator().next(), 2L) : first.outputs()));
                check(join(search, new GraphCompiler<>(replacement), stock, budget, proofs) == IntegerCountSearch.Handoff.DECLINED,
                        "Recipe id hid a semantic change");
            }
            check(get(search, "proofs") == null && proofs.model == null, "Declined scope attached proofs");
            long held = budget.availableBytes() - 1;
            budget.reserve(held);
            try { check(join(search, full, stock, budget, proofs) == IntegerCountSearch.Handoff.DECLINED, "Memory admission bypassed"); }
            finally { budget.release(held); }
            check(get(search, "proofs") == null && search.paused(), "Declined memory mutated live ownership");
            // An unrelated recipe outside the full goal closure changes no premise.
            var unrelated = new ArrayList<>(recipes);
            unrelated.add(new GraphRecipe<>("irrelevant", "irrelevant", List.of(new GraphRecipe.Slot<>("U", 1)), Map.of("V", 1L)));
            check(join(search, new GraphCompiler<>(unrelated), stock, budget, proofs) == IntegerCountSearch.Handoff.TRANSFERRED,
                    "Unrelated catalog entry disabled exact reuse");
            finish(search, recipes, stock, true);
        }
        check(budget.reservedBytes() == 0, "Scope rejection leaked memory");
    }

    private static void soleOwner() throws Exception {
        var recipes = recipes(1);
        var compiler = new GraphCompiler<>(recipes);
        var stock = Map.of("F", 9L, "A0", 1L);
        var budget = budget(new AtomicBoolean());
        try (var search = scout(compiler, stock, budget); var proofs = new OrderProofs<String>(null, budget);
             var neighborhood = new GraphSupportNeighborhood<>(compiler, "G", 1, stock, Set.of(), Map.of(), Set.of(),
                     false, true, budget, STARTED)) {
            set(neighborhood, "search", search);
            var taken = neighborhood.takeFullSearch(compiler, "G", 1, stock, Map.of(), Set.of(), Set.of(),
                    false, true, budget, STARTED, proofs);
            check(taken == search && get(neighborhood, "search") == null && !neighborhood.retained(), "Ownership was copied instead of moved");
            check(!neighborhood.beginTurn(262144), "Former owner restarted the transferred search");
            neighborhood.close();
            neighborhood.close();
            check(!(boolean) get(search, "complete") && !((AtomicBoolean) get(search, "released")).get(),
                    "Former owner released the primary frontier");
            finish(search, recipes, stock, true);
        }
        check(budget.reservedBytes() == 0, "Transfer cleanup leaked memory");
    }

    private static void cancellation() throws Exception {
        var compiler = new GraphCompiler<>(recipes(0));
        var stock = Map.of("F", 9L, "A0", 1L);
        var cancelled = new AtomicBoolean();
        var budget = budget(cancelled);
        try (var search = scout(compiler, stock, budget); var proofs = new OrderProofs<String>(null, budget)) {
            cancelled.set(true);
            try { join(search, compiler, stock, budget, proofs); throw new AssertionError("Cancelled handoff completed"); }
            catch (CancellationException expected) { /* Original owner still releases the frontier. */ }
        }
        check(budget.reservedBytes() == 0, "Cancelled scope check leaked memory");
    }

    private static IntegerCountSearch.Handoff join(IntegerCountSearch<String> search, GraphCompiler<String> compiler,
                                                    Map<String, Long> stock, PlanningBudget budget, OrderProofs<String> proofs) {
        return search.joinFullSearch(compiler, "G", 1, stock, Map.of(), Set.of(), Set.of(), false, true, budget, STARTED, proofs);
    }

    private static void finish(IntegerCountSearch<String> search, List<GraphRecipe<String>> recipes,
                               Map<String, Long> stock, boolean expected) {
        do {
            if (search.paused()) search.resume();
            while (!search.step()) {}
        } while (search.paused());
        var plan = search.result();
        check(expected ? plan != null : plan == null && search.infeasible(), "Changed conclusion after handoff");
        if (plan != null) {
            var inventory = new HashMap<>(stock);
            var catalog = new HashMap<String, GraphRecipe<String>>();
            recipes.forEach(r -> catalog.put(r.id(), r));
            replay(plan.steps(), catalog, inventory);
            check(inventory.getOrDefault("G", 0L) >= 1, "Primitive replay missed the goal");
        }
    }

    private static void replay(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, Long> stock) {
        if (step instanceof PlanStep.Batch batch) {
            check(batch.runs() < 100, "Unexpected unbounded fixture witness");
            var recipe = recipes.get(batch.recipe());
            check(recipe != null, "Unknown primitive");
            for (long i = 0; i < batch.runs(); i++) {
                recipe.inputs().forEach((k, v) -> { check(stock.getOrDefault(k, 0L) >= v, "Unfunded primitive"); stock.merge(k, -v, Long::sum); });
                recipe.outputs().forEach((k, v) -> stock.merge(k, v, Long::sum));
            }
        } else if (step instanceof PlanStep.Sequence sequence) sequence.children().forEach(s -> replay(s, recipes, stock));
        else if (step instanceof PlanStep.Repeat repeat) {
            check(repeat.times() < 100, "Unexpected unbounded fixture repeat");
            for (long i = 0; i < repeat.times(); i++) replay(repeat.body(), recipes, stock);
        }
    }

    @SuppressWarnings("unchecked")
    private static RecipeCountModel<String> model(Object owner) throws Exception { return (RecipeCountModel<String>) get(owner, "model"); }
    private static long number(Object owner, String field) throws Exception { return (Long) get(owner, field); }
    private static Object get(Object owner, String field) throws Exception {
        var f = owner.getClass().getDeclaredField(field); f.setAccessible(true); return f.get(owner);
    }
    private static void set(Object owner, String field, Object value) throws Exception {
        var f = owner.getClass().getDeclaredField(field); f.setAccessible(true); f.set(owner, value);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
