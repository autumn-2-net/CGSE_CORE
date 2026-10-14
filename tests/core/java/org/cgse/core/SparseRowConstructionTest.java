// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Independent exact material equations, and ownership of incrementally built rows. */
public final class SparseRowConstructionTest {
    public static void main(String[] args) {
        rows();
        materials();
        System.out.println("Sparse construction: immutable ordered rows and 4096 full-range material equations passed");
    }

    private static void rows() {
        var random = new Random(2026101404L);
        for (int size : new int[]{0, 1, 2, 3, 16, 127, 128, 257, 1024}) {
            var builder = new ExactLinearProgram.RowBuilder();
            var reference = new LinkedHashMap<Integer, BigInteger>();
            for (int i = 0; i < size; i++) {
                var coefficient = new BigInteger(300, random).subtract(BigInteger.ONE.shiftLeft(299));
                if (i % 7 == 0) coefficient = BigInteger.ZERO;
                builder.add(3 * i, coefficient);
                reference.put(3 * i, coefficient);
            }
            var row = builder.build(BigInteger.valueOf(-size));
            check(row.equals(new ExactLinearProgram.Constraint(reference, row.upper())), "Built value differs");
            check(row.terms().hashCode() == reference.hashCode(), "Wrong map hash");
            check(new ArrayList<>(row.terms().keySet()).equals(new ArrayList<>(reference.keySet())), "Wrong iteration order");
            check(new ExactLinearProgram.Constraint(row.terms(), BigInteger.ONE).terms().equals(reference), "RHS overlay changed coefficients");
            try { builder.add(3 * size, BigInteger.ONE); throw new AssertionError("Builder reopened after transfer"); }
            catch (IllegalStateException expected) {}
            try { builder.build(BigInteger.ZERO); throw new AssertionError("Builder transferred twice"); }
            catch (IllegalStateException expected) {}
            try { row.terms().put(-1, BigInteger.ONE); throw new AssertionError("Mutable row"); }
            catch (UnsupportedOperationException expected) {}
            if (size > 0) {
                try { row.terms().entrySet().iterator().next().setValue(BigInteger.ONE); throw new AssertionError("Mutable entry"); }
                catch (UnsupportedOperationException expected) {}
                try { row.terms().values().clear(); throw new AssertionError("Mutable values view"); }
                catch (UnsupportedOperationException expected) {}
            }
        }
        var builder = new ExactLinearProgram.RowBuilder();
        builder.add(7, BigInteger.ONE);
        builder.add(3, BigInteger.TWO);
        builder.add(7, BigInteger.TEN);
        try { builder.add(-1, BigInteger.TWO); throw new AssertionError("Negative coordinate accepted"); }
        catch (IllegalArgumentException expected) {}
        builder.add(Integer.MAX_VALUE, BigInteger.ONE);
        var reordered = builder.build(BigInteger.ZERO).terms();
        check(new ArrayList<>(reordered.keySet()).equals(List.of(3, 7, Integer.MAX_VALUE)) && reordered.get(7).equals(BigInteger.TEN),
                "Remapped or replaced coordinate changed");
    }

    private static void materials() {
        var random = new Random(2026101405L);
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 4096; i++) {
            long total = switch (i % 8) {
                case 0 -> 0;
                case 1 -> 1;
                case 2 -> Long.MAX_VALUE;
                case 3 -> Long.MAX_VALUE - 1;
                default -> random.nextLong() >>> 1;
            };
            long configured = Math.min(total, random.nextLong() >>> 1);
            if (i % 4 == 0) configured = total;
            long reusable = i % 3 == 0 ? configured : configured / 2;
            long output = Math.max(reusable, i % 5 == 0 ? Long.MAX_VALUE : i % 7 == 0 ? 0 : random.nextLong() >>> 1);
            var slots = new ArrayList<GraphRecipe.Slot<String>>();
            if (total > configured) slots.add(new GraphRecipe.Slot<>("material", total - configured, 0, false, false));
            if (configured > reusable) slots.add(new GraphRecipe.Slot<>("material", configured - reusable, 1, true, false));
            if (reusable > 0) slots.add(new GraphRecipe.Slot<>("material", reusable, 2, true, true));
            var outputs = new LinkedHashMap<String, Long>();
            outputs.put("material", output);
            outputs.put("product", 1L);
            var recipe = new GraphRecipe<>("r" + i, "r" + i, slots, outputs);
            for (String key : List.of("material", "product", "absent")) {
                var exact = reference(recipe, key);
                check(RecipeCountModel.delta(recipe, key).equals(exact), "Net amount overflow or wrong configuration");
                check(RecipeCountModel.coefficient(recipe, key).equals(exact.negate()), "Wrong constraint sign");
            }
            recipes.add(recipe);
        }
        // The full sparse pass, including absent/external/zero rows, is compared
        // with equations reconstructed from the original slots using BigInteger.
        for (var external : List.of(Set.<String>of(), Set.of("material"), Set.of("product"))) {
            var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
            var goals = Map.of("product", BigInteger.ONE.shiftLeft(65), "absent", BigInteger.ONE);
            var stock = Map.of("material", Long.MAX_VALUE, "product", Long.MAX_VALUE);
            try (var model = RecipeCountModel.forShell(recipes, goals, stock, external, budget)) {
                check(model != null, "Model unexpectedly declined");
                for (int r = 0; r < model.rowKeys.size(); r++) {
                    String key = model.rowKeys.get(r);
                    var terms = new LinkedHashMap<Integer, BigInteger>();
                    for (int c = 0; c < recipes.size(); c++) {
                        var value = reference(recipes.get(c), key).negate();
                        if (value.signum() != 0) terms.put(c, value);
                    }
                    var upper = BigInteger.valueOf(stock.getOrDefault(key, 0L)).subtract(goals.getOrDefault(key, BigInteger.ZERO));
                    check(model.constraints.get(r).equals(new ExactLinearProgram.Constraint(terms, upper)), "Complete equation differs");
                }
            }
            check(budget.reservedBytes() == 0, "Model memory not released");
        }
    }

    private static BigInteger reference(GraphRecipe<String> recipe, String key) {
        BigInteger amount = BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L));
        for (var slot : recipe.slots()) if (slot.key().equals(key) && (!slot.configuration() || slot.reusable()))
            amount = amount.subtract(BigInteger.valueOf(slot.amount()));
        return amount;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
