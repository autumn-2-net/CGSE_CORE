import org.cgse.core.*;
import java.util.*;

public class LongLoopRepro {
    static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    public static void main(String[] args) {
        long amount = 1_000_000_000_000L;
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.put("a", recipe("a", Map.of("seed", 1L, "raw", 1L), Map.of("mid", 1L)));
        recipes.put("b", recipe("b", Map.of("mid", 1L), Map.of("seed", 1L, "A", 1L)));
        recipes.put("independent", recipe("independent", Map.of("other", 1L), Map.of("B", 1L)));
        recipes.put("join", recipe("join", Map.of("A", 1L, "B", 1L), Map.of("P", 1L)));
        var cycle = new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("a", 1), new PlanStep.Batch("b", 1))), amount);
        var steps = new PlanStep.Sequence(List.of(cycle, new PlanStep.Batch("independent", amount), new PlanStep.Batch("join", amount)));
        var stock = Map.of("seed", 1L, "raw", amount, "other", amount);
        var plan = new GraphPlan<>("P", amount, true, steps, recipes, stock, Map.of("seed", 1L), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        var runtime = new GraphJobRuntime<>(plan, stock, Map.of());
        var accepted = new LinkedHashMap<String, Long>();
        var adapter = new GraphJobRuntime.Adapter<String>() {
            public long capacity(GraphRecipe<String> r, long n) { return r.id().equals("a") ? 0 : n; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> r, long n, Map<String, Long> in) {
                accepted.merge(r.id(), n, Math::addExact);
                return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String k, long n) { return n; }
            public long refund(String k, long n) { return n; }
        };
        for (int i = 0; i < 100; i++) runtime.tick(adapter, i, 16);
        System.out.println("accepted=" + accepted + ", reason=" + runtime.reason());
        if (accepted.getOrDefault("independent", 0L) != amount)
            throw new AssertionError("Independent branch hidden behind long loop");
        if (accepted.containsKey("join")) throw new AssertionError("Spent predicted output");
    }
}
