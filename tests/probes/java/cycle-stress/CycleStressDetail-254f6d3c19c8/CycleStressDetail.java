import org.cgse.core.*;
import java.lang.reflect.*;
import java.util.*;

public class CycleStressDetail {
    static Object field(Object owner, String name) throws Exception {
        if (owner == null) return null;
        var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    public static void main(String[] args) throws Exception {
        List<ComplexCycleStress.Case> cases = new ArrayList<>();
        for (long n : new long[]{1, 9, 1000, 100_000_017L, 1_000_000_000_000L}) {
            cases.add(ComplexCycleStress.hub(2, n, 4));
            cases.add(ComplexCycleStress.braid(2, n, 4));
            cases.add(ComplexCycleStress.nested(2, 2, n, 1));
            cases.add(ComplexCycleStress.nested(3, 2, n, 1));
        }
        for (var c : cases) {
            ComplexCycleStress.validate(c, c.known(), true);
            diagnose(c, 10_000_000, 128L << 20);
        }
        // Show whether more memory helps a search-limited case; no production defaults changed.
        var smallest = ComplexCycleStress.nested(2, 2, 1, 1);
        diagnose(smallest, 10_000_000, 1L << 30);
        diagnose(smallest, 100_000_000, 128L << 20);
        // Local diagnostic ONLY: call the existing fallback without the fast path
        // to determine whether fast-path enumeration consumes its whole budget.
        fallback(smallest);
        fallback(ComplexCycleStress.nested(2, 2, 100_000_017L, 1));
        for (int size : new int[]{128, 512, 1024}) diagnose(ComplexCycleStress.ring(size, 100_000_017L, 8), 10_000_000, 128L << 20);
        System.out.println("DETAIL END");
    }
    static void diagnose(ComplexCycleStress.Case c, long limit, long bytes) throws Exception {
        var compiler = new GraphCompiler<>(c.recipes());
        var budget = new PlanningBudget(6000, limit, bytes, () -> false, System::nanoTime);
        var work = new GraphPlanningWork<>(compiler, c.target(), c.amount(), c.stock(), true, true, budget).catalysts(new CatalystPolicy(8, 0));
        long start = System.nanoTime(); while (!work.step()) {}
        var plan = work.result();
        if (plan.feasible()) ComplexCycleStress.validate(c, plan, false);
        Object solve = field(work, "solving"), select = field(solve, "selection"), allocation = field(work, "allocating");
        Object graph = field(work, "graph");
        String regions = graph instanceof GraphCompiler.Compiled<?> g ? g.regions().stream().map(r -> r.recipes().size() + (r.cyclic() ? "c" : "a")).toList().toString().replace(" ", "") : "none";
        Object stack = field(allocation, "stack");
        System.out.printf(Locale.ROOT, "DETAIL name=%s amount=%d result=%s wall_ms=%.3f nodes=%d node_limit=%d mem_mib=%d phase=%s regions=%s trial_order=%s trial_variant=%s fallback_depth=%s%n",
                c.name(), c.amount(), plan.result(), (System.nanoTime() - start)/1e6, budget.nodes(), limit, bytes >> 20,
                field(work, "phase"), regions, field(select, "order"), field(select, "variant"), stack instanceof Deque<?> q ? q.size() : null);
    }
    static void fallback(ComplexCycleStress.Case c) throws Exception {
        var cls = Class.forName("org.cgse.core.AllocationSearch");
        var constructor = cls.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var budget = new PlanningBudget(6000, 10_000_000, 128L << 20, () -> false, System::nanoTime);
        var start = System.nanoTime();
        var work = constructor.newInstance(new GraphCompiler<>(c.recipes()), c.target(), c.amount(), c.stock(), Set.of(), Map.of(), true, true, Set.of(), budget, start);
        var step = cls.getDeclaredMethod("step"); step.setAccessible(true);
        var result = cls.getDeclaredMethod("result"); result.setAccessible(true);
        String status;
        try {
            while (!(boolean) step.invoke(work)) {}
            var plan = (GraphPlan<String>) result.invoke(work);
            if (plan != null) ComplexCycleStress.validate(c, plan, false);
            status = plan == null ? "NO_RESULT" : plan.result().toString();
        } catch (InvocationTargetException e) { status = e.getCause().toString().replace(' ', '_'); }
        System.out.printf(Locale.ROOT, "FALLBACK_ONLY name=%s amount=%d result=%s nodes=%d wall_ms=%.3f%n", c.name(), c.amount(), status, budget.nodes(), (System.nanoTime()-start)/1e6);
    }
}
