package org.gtlcore.aedump;

import org.cgse.core.CatalystPolicy;
import org.cgse.core.GraphCompiler;
import org.cgse.core.GraphPlan;
import org.cgse.core.GraphRecipe;
import org.cgse.core.PlanningBudget;
import org.cgse.core.PlanningScheduler;
import org.cgse.core.RequestPlanningWork;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Exercises named diagnostic reflection against the actual extracted coordinator. */
public final class RequestStateTest {

    private static final class Host {
        private final RequestPlanningWork<String> planning;
        private final long snapshotNanos = 9_007_199_254_740_993L;
        private final long snapshotElapsedNanos = 13;
        private final long catalogPreparationNanos = 17;
        private final int catalogParallelBatches = 3;
        private final long catalogPreparationElapsed = 19;
        private final long catalogParallelNanos = 23;

        Host(RequestPlanningWork<String> planning) { this.planning = planning; }
    }

    public static void main(String[] args) throws Exception {
        var recipe = new GraphRecipe<>("one", "binding", List.of(new GraphRecipe.Slot<>("ore", 1)), Map.of("product", 1L));
        var compiler = new GraphCompiler<>(List.of(recipe));
        var budget = new PlanningBudget(0, 1_000_000, () -> false);
        var ordinary = request(compiler, Map.of("ore", 2L), Set.of(), false, budget);
        var host = new Host(ordinary);
        check(RequestState.solver(host) == ordinary, "Host planning delegate was not selected");
        check(Reflect.<Map<String, Long>>get(RequestState.solver(host), "available").equals(Map.of("ore", 2L)), "Actual availability missing");
        var before = RequestState.coordinator(host);
        check(Boolean.FALSE.equals(before.get("directEmission")) && Boolean.FALSE.equals(before.get("fallbackAttempted")), "Initial coordinator flags missing");
        check("9007199254740993".equals(before.get("snapshotNanos")), "Host timing rounded through double");
        check(before.get("catalogParallelBatches").equals(3) && before.get("catalogParallelNanos").equals("23"), "Host timing fields missing");
        GraphPlan<String> plan = finish(ordinary, budget);
        check(plan.feasible() && Reflect.get(RequestState.solver(host), "selected") == plan, "Selected plan is hidden behind delegate");

        budget = new PlanningBudget(0, 1_000_000, () -> false);
        var direct = request(new GraphCompiler<>(List.of()), Map.of("product", 7L), Set.of("product"), false, budget);
        host = new Host(direct);
        check(Boolean.TRUE.equals(RequestState.coordinator(host).get("directEmission")), "Direct emission would become force-craft");
        plan = finish(direct, budget);
        check(plan.feasible() && Reflect.<Map<String, Long>>get(RequestState.solver(host), "available").isEmpty(), "Direct-emission actual input lost");

        budget = new PlanningBudget(0, 1_000_000, () -> false);
        var fallback = request(compiler, Map.of(), Set.of(), true, budget);
        host = new Host(fallback);
        plan = finish(fallback, budget);
        var after = RequestState.coordinator(host);
        check(Boolean.TRUE.equals(after.get("fallbackAttempted")) && Boolean.TRUE.equals(after.get("fallbackMode")), "Fallback would not be auto-exported");
        check(Reflect.get(RequestState.solver(host), "selected") == plan, "Fallback plan missing from diagnostic capture");

        host = new Host(null);
        check(RequestState.solver(host) == host, "Early host capture must remain available");
        check(Reflect.get(RequestState.solver(host), "available") == null, "Early capture falsely claims actual solver input");
        check(RequestState.coordinator(host).containsKey("snapshotNanos"), "Early capture lost host timing");
        check(RequestState.solver(ordinary) == ordinary, "Direct coordinator inspection changed");
        System.out.println("RequestStateTest passed: host timing, exact integers, ordinary plan, emission, fallback, early capture");
    }

    private static RequestPlanningWork<String> request(GraphCompiler<String> compiler, Map<String, Long> stock,
                                                        Set<String> emitters, boolean bounded, PlanningBudget budget) {
        return new RequestPlanningWork<>(compiler, "product", 1, stock, emitters, null, true, false, "DEFAULT",
                CatalystPolicy.MINIMAL, true, bounded, budget, () -> false, report -> {});
    }

    private static GraphPlan<String> finish(RequestPlanningWork<String> work, PlanningBudget budget) throws Exception {
        try (var scheduler = new PlanningScheduler(1, 2, 1_000, 2_000_000)) {
            return scheduler.submit(work, budget).get(10, TimeUnit.SECONDS);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
