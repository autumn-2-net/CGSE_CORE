// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Check the independent witness oracle's production semantics, including read arcs. */
public final class FixtureProductionTest {
    public static void main(String[] args) {
        var copy = new GraphRecipe<>("copy", "copy", List.of(new GraphRecipe.Slot<>("A", 1),
                new GraphRecipe.Slot<>("F", 1)), Map.of("A", 2L));
        var read = new GraphRecipe<>("read", "read", List.of(new GraphRecipe.Slot<>("A", 1, 0, true, true)),
                Map.of("A", 1L, "B", 3L));
        var recipes = Map.of("copy", copy, "read", read);
        var step = new PlanStep.Batch("copy", 31);
        var summary = GraphFixtureRegression.summary(step, recipes, new IdentityHashMap<>());
        check(summary.delta().get("A").equals(BigInteger.valueOf(31)), "self copy net change");
        check(produced(step, "A", recipes).equals(BigInteger.valueOf(62)), "self copy gross production");
        check(produced(new PlanStep.Sequence(List.of()), "A", recipes).signum() == 0, "idle plan manufactured inventory");
        check(produced(new PlanStep.Batch("read", 50), "A", recipes).signum() == 0, "read token counted as production");
        check(produced(new PlanStep.Batch("read", 50), "B", recipes).equals(BigInteger.valueOf(150)), "read arc lost actual outputs");
        PlanStep repeated = new PlanStep.Repeat(new PlanStep.Batch("copy", Long.MAX_VALUE), Long.MAX_VALUE);
        PlanStep shared = new PlanStep.Sequence(List.of(repeated, repeated));
        check(produced(shared, "A", recipes).equals(BigInteger.valueOf(Long.MAX_VALUE).pow(2).multiply(BigInteger.valueOf(4))),
                "nested/shared step output overflowed or was counted once");
        check(produced(new PlanStep.Repeat(step, 0), "A", recipes).signum() == 0, "zero repeat produced output");
        System.out.println("Independent production oracle: self copy, empty plans, reusable inputs, shared steps and counts beyond long passed");
    }

    private static BigInteger produced(PlanStep step, String target, Map<String, GraphRecipe<String>> recipes) {
        return GraphFixtureRegression.production(step, target, recipes, new IdentityHashMap<>());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
