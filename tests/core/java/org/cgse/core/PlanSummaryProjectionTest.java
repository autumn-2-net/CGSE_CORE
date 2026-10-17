package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Display products never alter exact tool ownership or execution transitions. */
public final class PlanSummaryProjectionTest {
    public static void main(String[] args) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        var steps = new ArrayList<PlanStep>();
        for (int wear = 0; wear < 100; wear++) {
            String id = "use" + wear;
            recipes.put(id, new GraphRecipe<>(id, "use", List.of(new GraphRecipe.Slot<>("tool" + wear, 1),
                    new GraphRecipe.Slot<>("raw", 1)), Map.of("product", 1L, "tool" + (wear + 1), 1L)));
            steps.add(new PlanStep.Batch(id, 1));
        }
        var plan = new GraphPlan<>("product", 100, false, new PlanStep.Sequence(steps), recipes,
                Map.of("tool0", 1L, "raw", 100L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        check(PlanSummary.entries(plan, Map.of()).size() == 103, "Reproducer lost the intermediate states");
        var projected = PlanSummary.entries(plan, Map.of(), recipe -> Map.of("product", 1L));
        check(projected.size() == 3, "Intermediate tools leaked into the confirmation");
        var tool = projected.stream().filter(row -> row.key().equals("tool0")).findFirst().orElseThrow();
        check(tool.stored().equals(BigInteger.ONE) && tool.crafted().signum() == 0 && tool.runs().signum() == 0,
                "One reused physical tool became manufactured tools");
        var product = projected.stream().filter(row -> row.key().equals("product")).findFirst().orElseThrow();
        check(product.crafted().intValueExact() == 100 && product.runs().intValueExact() == 100, "Products lost");
        check(plan.initial().get("tool0") == 1 && plan.recipes().size() == 100, "Display changed the physical program");
        check(plan.recipes().get("use99").outputs().get("tool100") == 1, "Final return disappeared from execution");

        BigInteger huge = BigInteger.ONE.shiftLeft(90);
        var wide = new GraphPlan<>("product", 1, false, PlanStep.batch("use0", huge),
                recipes, Map.of("tool0", huge.add(BigInteger.TEN)), Map.of(),
                Map.of("tool0", huge), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var rows = PlanSummary.entries(wide, Map.of("tool0", BigInteger.valueOf(3)), r -> Map.of("product", 1L));
        var first = rows.get(0);
        check(first.stored().equals(BigInteger.valueOf(7)) && first.missing().equals(huge), "Projected stock was clipped");
        check(first.crafted().equals(BigInteger.valueOf(3)), "External supply disappeared");
        check(rows.get(1).crafted().equals(huge) && rows.get(1).runs().equals(huge), "Projected counts were clipped");
        System.out.println("PlanSummaryProjectionTest passed: 100 states, exact ownership and wide quantities");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
