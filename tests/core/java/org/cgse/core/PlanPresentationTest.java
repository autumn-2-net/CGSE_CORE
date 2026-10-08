package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

/** Exact totals, shared program references and bounded traversal independent of a game host. */
public final class PlanPresentationTest {

    private record Key(String id) {}

    public static void main(String[] args) {
        Key input = new Key("input"), output = new Key("output");
        var recipe = new GraphRecipe<>("recipe", "binding", List.of(new GraphRecipe.Slot<>(input, 1)), Map.of(output, 2L));
        PlanStep shared = new PlanStep.Batch("recipe", 1);
        for (int i = 0; i < 80; i++) shared = new PlanStep.Sequence(List.of(shared, shared));
        BigInteger huge = BigInteger.ONE.shiftLeft(120).add(BigInteger.valueOf(7));
        var plan = new GraphPlan<>(output, 1, true, shared, Map.of("recipe", recipe), Map.of(input, huge),
                Map.of(input, 1L), Map.of(input, huge.subtract(BigInteger.valueOf(6))), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var summary = PlanSummary.entries(plan, Map.of(input, BigInteger.ONE));
        check(summary.size() == 2 && summary.get(0).key().equals(input), "Summary key order");
        check(summary.get(0).stored().equals(BigInteger.valueOf(5)), "Exact subtraction must precede UI clipping");
        check(summary.get(0).missing().equals(huge.subtract(BigInteger.valueOf(6))), "Missing amount clipped");
        check(summary.get(0).crafted().equals(BigInteger.ONE), "Emitted contribution missing");
        check(summary.get(1).crafted().equals(BigInteger.ONE.shiftLeft(81)), "Produced amount clipped");
        check(summary.get(1).runs().equals(BigInteger.ONE.shiftLeft(80)), "Run count clipped");
        immutable(() -> summary.clear());

        var view = new PlanRingView<>(plan, List.of("recipe"));
        check(view.target().key().equals(output) && view.preserveSeeds(), "Plan metadata changed");
        check(view.graphRows() == 2 && view.rows().size() == 163, "Shared program expanded");
        check(view.rows().get(0).count().equals(huge), "Resource exact count clipped");
        check(view.rows().get(0).missing().equals(huge.subtract(BigInteger.valueOf(6))), "Resource exact missing clipped");
        check(view.rows().get(1).count().equals(BigInteger.ONE.shiftLeft(80)), "Recipe exact count clipped");
        check(view.rows().stream().filter(row -> row.kind() == PlanRingView.Kind.REFERENCE).count() == 80, "Shared references missing");
        for (int index = view.graphRows(); index < view.rows().size(); index++) {
            var row = view.rows().get(index);
            check(row.parent() < index, "Forward parent");
            if (row.kind() == PlanRingView.Kind.REFERENCE) {
                int reference = Integer.parseInt(row.id());
                check(reference >= view.graphRows() && reference < index, "Invalid shared reference");
            }
        }
        var slices = new ArrayList<PlanRingView.Row<Key>>();
        for (int offset = 0; offset < view.rows().size(); offset += 37) slices.addAll(view.rows(offset, 37));
        check(slices.equals(view.rows()) && view.rows(view.rows().size(), Integer.MAX_VALUE).isEmpty(), "Slice lost rows");
        immutable(() -> view.rows().clear());
        immutable(() -> view.rows(0, 1).clear());
        immutable(() -> view.rows().get(1).outputs().clear());

        var cursor = new PlanRingView.Cursor<>(plan, List.of("recipe"));
        var streamed = new ArrayList<PlanRingView.Row<Key>>();
        int calls = 0;
        boolean complete;
        do {
            calls++;
            int before = cursor.size();
            complete = cursor.advance();
            check(cursor.size() - before <= 1, "Cursor exceeded one row operation");
            if (cursor.row() != null) streamed.add(cursor.row());
        } while (!complete);
        check(calls == 250, "Traversal changed empty transitions or slice work");
        check(streamed.equals(view.rows()) && cursor.graphRows() == view.graphRows(), "Streamed view changed rows");

        var emptyPlan = new GraphPlan<>(output, 1, false, new PlanStep.Sequence(List.of()), Map.of(), Map.of(), Map.of(), Map.of(),
                GraphPlan.Result.MISSING_INPUT, 0, 0);
        var emptyView = new PlanRingView<>(emptyPlan, List.of());
        check(emptyView.graphRows() == 0 && emptyView.rows().size() == 1, "Empty program lost sequence");

        var failure = new PlanningFailure(GraphPlan.Result.INFEASIBLE, "checked");
        check(failure.detail().equals("checked"), "Failure detail lost");
        check(PlanningFailure.result(new CompletionException(failure)) == GraphPlan.Result.INFEASIBLE, "Outcome hidden by cause");
        for (var limit : PlanningBudget.Limit.values())
            check(PlanningFailure.result(new PlanningBudget.Exhausted(limit)) == GraphPlan.Result.valueOf(limit.name()), "Budget result changed");
        check(PlanningFailure.result(new IllegalStateException("unexpected")) == null, "Crash mislabeled as outcome");
        var first = new RuntimeException();
        var second = new RuntimeException(first);
        first.initCause(second);
        check(PlanningFailure.result(first) == null, "Cyclic cause chain did not terminate");
        System.out.println("PlanPresentationTest passed: exact totals, 80-level sharing, 250 sliced operations, failures");
    }

    private static void immutable(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Writable presentation data");
        } catch (UnsupportedOperationException expected) {
            // Expected for every exposed list.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
