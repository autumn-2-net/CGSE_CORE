package org.cgse.fixtures;

import org.cgse.core.*;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** Shared test fixture for exact executor and AE persistence checks. */
public final class LongBoundaryFixture {
    private LongBoundaryFixture() {}

    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    public static GraphJobRuntime.Snapshot<String> nearFinish() {
        var a = recipe("a", Map.of("C", 1L), Map.of("I", 1L));
        var b = recipe("b", Map.of("I", 1L), Map.of("C", 1L, "X", 1L));
        var c = recipe("c", Map.of("X", 2L), Map.of("P", 1L));
        var round = new PlanStep.Sequence(List.of(new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("a", 1), new PlanStep.Batch("b", 1))), 2), new PlanStep.Batch("c", 1)));
        var plan = new GraphPlan<>("P", Long.MAX_VALUE, true, new PlanStep.Repeat(round, Long.MAX_VALUE), Map.of("a", a, "b", b, "c", c),
                Map.of("C", 1L), Map.of("C", 1L), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        var state = new GraphJobRuntime<>(plan, Map.of("C", 1L), Map.of()).snapshot();
        BigInteger done = BigInteger.valueOf(Long.MAX_VALUE).subtract(BigInteger.ONE);
        return new GraphJobRuntime.Snapshot<>(plan, Map.of("C", 1L, "P", Long.MAX_VALUE - 1), Map.of(), Map.of(),
                Map.of("a", done.multiply(BigInteger.TWO), "b", done.multiply(BigInteger.TWO), "c", done),
                List.of(new PlanCursor.Position(0, 1)), List.of(), Long.MAX_VALUE, state.state(), false, "", state.obligations(), state.recovery(), Map.of());
    }
}
