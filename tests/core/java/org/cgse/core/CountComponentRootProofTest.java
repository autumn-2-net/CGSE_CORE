// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.lang.reflect.Field;
import java.util.*;

/** A complete component proof must survive earlier inconclusive sibling work. */
public final class CountComponentRootProofTest {
    private static GraphRecipe<String> recipe(String id, String input, long consume, String output, long produce) {
        return new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>(input, consume)), Map.of(output, produce));
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    @SuppressWarnings("unchecked")
    private static void check(boolean guarded) throws Exception {
        var compiler = new GraphCompiler<>(List.of(recipe("melt", "T", 1, "F", 7),
                recipe("cast", "F", 7, "I", 1), recipe("grind", "I", 1, "T", 1),
                new GraphRecipe<>("mold_cast", "mold_cast", List.of(new GraphRecipe.Slot<>("F", 7),
                        new GraphRecipe.Slot<>("mold", 1, 1, true, true)), Map.of("I", 1L, "mold", 1L))));
        // Conservation: 7*T + 7*I + F. Six fluid units cannot fund a new T.
        // With seven, the unrestricted model has the explicit cast/grind witness.
        var stock = Map.of("T", 1L << 55, "F", guarded ? 7L : 6L, "mold", Long.MAX_VALUE);
        var budget = new PlanningBudget(0, 4_000_000, 128L << 20, () -> false, System::nanoTime);
        try (var search = new IntegerCountSearch<>(compiler, "T", 1, stock, Map.of(), Set.of(), Set.of(), false, true, budget, System.nanoTime())) {
            var model = (RecipeCountModel<String>) get(search, "model");
            var execution = (CountExecution<String>) get(search, "execution");
            var pending = (Deque<IntegerCountBranch<String>>) get(search, "pending");
            pending.forEach(IntegerCountBranch::close);
            pending.clear();
            int cast = 0;
            while (!model.recipes.get(cast).id().equals("cast")) cast++;
            int alternate = 0;
            while (!model.recipes.get(alternate).id().equals("mold_cast")) alternate++;
            var restrictions = guarded ? List.of(new ExactLinearProgram.Constraint(Map.of(cast, BigInteger.ONE, alternate, BigInteger.ONE), BigInteger.ZERO)) : List.<ExactLinearProgram.Constraint>of();
            try (var branch = new IntegerCountBranch<>(model, execution, "T", 1, stock, Map.of(), Set.of(), false, true, budget, System.nanoTime(), restrictions)) {
                branch.initialized = branch.compiled = branch.partitioned = true;
                branch.linearConstraints = new ArrayList<>(model.constraints);
                branch.linearConstraints.addAll(restrictions);
                branch.lower = new BigInteger[model.recipes.size()];
                branch.upper = new BigInteger[model.recipes.size()];
                Arrays.fill(branch.lower, BigInteger.ZERO);
                branch.reduction = new CountReduction(branch.linearConstraints, branch.lower, branch.upper, budget);
                while (!branch.reduction.step()) {}
                branch.components = new CountComponents(branch.reduction.rows(), branch.reduction.lower(), branch.reduction.upper(), budget);
                for (int rounds = 0; rounds < 100000 && branch.state == IntegerCountBranch.State.OPEN; rounds++)
                    branch.run(4096, List.of(), List.of(), List.of(), null, () -> false);
                if (branch.state != IntegerCountBranch.State.DEAD) throw new AssertionError("Component did not close " + branch.state);
                if (branch.proofContradiction == guarded) throw new AssertionError("Wrong component proof scope guarded=" + guarded + " " + budget.diagnostics());
                // A sibling may have exhausted its local grant before this root closes.
                set(search, "unresolved", true);
                var merge = IntegerCountSearch.class.getDeclaredMethod("merge", List.class, boolean.class);
                merge.setAccessible(true);
                merge.invoke(search, List.of(branch), true);
                if (search.infeasible() == guarded) throw new AssertionError("Root proof lost or local proof escaped");
            }
        }
        if (budget.reservedBytes() != 0) throw new AssertionError("Reservation leak");
        if (guarded) {
            var b = new PlanningBudget(0, 4_000_000, 128L << 20, () -> false, System::nanoTime);
            var work = new GraphPlanningWork<>(compiler, "T", 1, stock, false, true, b);
            try {
                while (!work.step()) {}
                var plan = work.result();
                if (!plan.feasible()) throw new AssertionError("Guard contaminated feasible root: " + plan.result());
                PlanVerifier.verify(plan);
                PlanVerifier.verifyRuntimeInventory(plan);
            } finally { work.close(); }
            if (b.reservedBytes() != 0) throw new AssertionError("Control reservation leak");
        }
    }

    public static void main(String[] args) throws Exception {
        check(false);
        check(true);
        System.out.println("PASS component root closure, guarded scope, feasible control and cleanup");
    }
}
