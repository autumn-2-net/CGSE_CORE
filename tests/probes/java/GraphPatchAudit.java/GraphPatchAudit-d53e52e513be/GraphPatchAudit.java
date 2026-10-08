import org.cgse.core.*;
import java.util.*;

/** Measures the existing implementation against new requirements, not a passing release gate. */
public final class GraphPatchAudit {
    static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    static GraphPlan<String> plan(List<GraphRecipe<String>> recipes, String key, long amount, Map<String, Long> stock) {
        return new GraphPlanner<>(new GraphCompiler<>(recipes)).plan(key, amount, stock, true, true,
                new PlanningBudget(30_000, 2_000_000, () -> false));
    }
    static int nodes(PlanStep step) {
        if (step instanceof PlanStep.Batch) return 1;
        if (step instanceof PlanStep.Repeat repeat) return 1 + nodes(repeat.body());
        return 1 + ((PlanStep.Sequence) step).children().stream().mapToInt(GraphPatchAudit::nodes).sum();
    }
    static void require(boolean test, String message) { if (!test) throw new AssertionError(message); }
    public static void main(String[] args) {
        var split = plan(List.of(r("x", Map.of("X", 1L), Map.of("A", 1L)), r("y", Map.of("Y", 1L), Map.of("A", 1L))),
                "A", 5, Map.of("X", 3L, "Y", 2L));
        System.out.println("T27 split sources: " + split.result() + ", missing=" + split.missing() + ", runs=" + split.patternTimes());
        require(!split.feasible(), "Audit baseline changed: source splitting now works");
        var multi = plan(List.of(r("co", Map.of("X", 1L), Map.of("A", 2L, "B", 1L)),
                r("target", Map.of("A", 6L, "B", 3L), Map.of("P", 1L))), "P", 1, Map.of("X", 3L));
        require(multi.feasible() && multi.patternTimes().get("co") == 3, "T28 multi-output");
        System.out.println("T28 coupled outputs: PASS runs=" + multi.patternTimes());
        var alternatives = plan(List.of(r("ba", Map.of("B", 1L), Map.of("A", 1L)),
                r("ab", Map.of("A", 1L), Map.of("B", 1L)), r("xa", Map.of("X", 1L), Map.of("A", 1L))),
                "B", 1, Map.of("X", 1L));
        require(alternatives.feasible(), "T29 selected acyclic path");
        System.out.println("T29 candidate cycle with external route: PASS runs=" + alternatives.patternTimes());
        for (int length : new int[]{3, 50}) for (long count : new long[]{100, 1_000_000_000_000L}) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            var stock = new LinkedHashMap<String, Long>();
            stock.put("C0", 1L);
            for (int i = 0; i < length; i++) {
                stock.put("X" + i, count);
                recipes.add(r("r" + i, Map.of("C" + i, 1L, "X" + i, 1L),
                        i == length - 1 ? Map.of("C0", 1L, "P", 1L) : Map.of("C" + (i + 1), 1L)));
            }
            var p = plan(recipes, "P", count, stock);
            require(p.feasible(), "T03 recovery chain " + length + ": " + p.result());
            require(p.seeds().equals(Map.of("C0", 1L)), "T03 exact recovery seed");
            require(p.patternTimes().values().stream().allMatch(n -> n == count), "T03 finite runs");
            System.out.println("T03/T26 length=" + length + " repeats=" + count + " nodes=" + nodes(p.steps()) + " seed=" + p.seeds());
            if (length == 3 && count == 100) runtime(p);
        }
        try { new PlanningBudget(0, 100, () -> false).check(); throw new AssertionError("Audit baseline changed: zero timeout supported"); }
        catch (PlanningBudget.Exhausted expected) { System.out.println("T52 timeout=0: immediate exhaustion (requires change)"); }
    }
    static void runtime(GraphPlan<String> plan) {
        class Provider implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            int pushes;
            Map<String, Long> refund = new HashMap<>();
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                pushes++;
                return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long amount) { throw new AssertionError("Premature delivery"); }
            public long refund(String key, long amount) { refund.merge(key, amount, Math::addExact); return amount; }
        }
        var provider = new Provider();
        provider.job.tick(provider, 0, 100);
        require(provider.job.held("C0") == 0 && provider.job.expected().equals(Map.of("C1", 1L)), "T57 only accepted stage is in flight");
        provider.job.tick(provider, 10000, 100);
        require(provider.pushes == 1, "T23 slow provider is not redispatched");
        provider.job.accept("C1", 1, false);
        provider.job.suspend(true);
        provider.job = new GraphJobRuntime<>(provider.job.snapshot());
        provider.job.tick(provider, 10001, 100);
        require(provider.pushes == 1 && provider.job.held("C1") == 1, "T15 paused intermediate retained");
        provider.job.suspend(false);
        provider.job.tick(provider, 10002, 100);
        require(provider.pushes == 2 && provider.job.expected().equals(Map.of("C2", 1L)), "T04 stage after actual receipt");
        provider.job.accept("C2", 1, false);
        provider.job.cancel();
        provider.job.tick(provider, 10003, 100);
        require(provider.job.finished() && provider.refund.get("C2") == 1 && !provider.refund.containsKey("C0"), "T58 no fabricated recovery on cancel");
        System.out.println("T04/T15/T23/T57/T58 staged ownership, pause/reload, delayed receipt and intermediate cancel: PASS");
    }
}
