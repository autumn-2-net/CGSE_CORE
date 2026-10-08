package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import static org.cgse.core.GeneralSearchReview.*;

public final class MacroConflictReview {
    public static void main(String[] ignored) {
        Random rng = new Random(2609261947L);
        int checked = 0;
        for (int trial = 0; trial < 300; trial++) {
            int stock = 4 + rng.nextInt(25);
            var recipes = new ArrayList<GraphRecipe<String>>();
            var weights = new int[3];
            var terms = new LinkedHashMap<Integer, BigInteger>();
            var block = new LinkedHashMap<String, BigInteger>();
            for (int i = 0; i < 3; i++) {
                weights[i] = 1 + rng.nextInt(5);
                recipes.add(r("r" + i, Map.of("raw", (long) weights[i]), Map.of("p" + i, 1L)));
                terms.put(i, z(-weights[i]));
                block.put("r" + i, z(rng.nextInt(4)));
            }
            var budget = budget();
            var model = RecipeCountModel.region(recipes, Map.of(), Map.of("raw", (long) stock), Set.of(), budget);
            try (var proofs = new OrderProofs<>(model, budget)) {
                proofs.publish(model, List.of(new CountConflict(List.of(new ExactLinearProgram.Constraint(terms, z(-stock - 1))))));
                for (int a = 0; a < 5; a++) for (int b = 0; b < 5; b++) for (int c = 0; c < 5; c++) {
                    var prefix = Map.of("r0", z(a), "r1", z(b), "r2", z(c));
                    BigInteger actual = proofs.maximumAdditional(prefix, block, z(50));
                    int used = a * weights[0] + b * weights[1] + c * weights[2];
                    int cost = 0;
                    for (int i = 0; i < 3; i++) cost += weights[i] * block.get("r" + i).intValueExact();
                    int maximum = used > stock ? 0 : cost == 0 ? 50 : Math.min(50, (stock - used) / cost);
                    check(actual.equals(z(maximum)), "macro cap mismatch: " + actual + " expected=" + maximum);
                    checked++;
                }
            }
            check(budget.reservedBytes() == 0, "macro proof memory");
        }
        System.out.println("MACRO_CONFLICT independent_exact_prefix_bounds=" + checked + " wrong_pruning=0");
    }
}
