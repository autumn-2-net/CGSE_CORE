import java.math.BigInteger;
import java.util.*;
import org.cgse.core.*;

public class QuantityRecommendationProbe {
    static void run(long stock, long target, boolean reverse) {
        var cheap = new GraphRecipe<String>("cheap", "cheap",
                List.of(new GraphRecipe.Slot<>("A", 2L)), Map.of("B", 1L));
        var costly = new GraphRecipe<String>("costly", "costly",
                List.of(new GraphRecipe.Slot<>("A", 3L)), Map.of("B", 1L));
        var recipes = reverse ? List.of(costly, cheap) : List.of(cheap, costly);
        var budget = new PlanningBudget(2000, 10_000_000, 128L << 20, () -> false, System::nanoTime);
        var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "B", target,
                Map.of("A", stock), true, true, budget).catalysts(CatalystPolicy.MINIMAL);
        long started = System.nanoTime();
        while (!work.step()) {}
        var plan = work.result();
        BigInteger gap = BigInteger.valueOf(target).multiply(BigInteger.TWO).subtract(BigInteger.valueOf(stock));
        System.out.printf("stock=%d goal=%d reversed=%s exact_minimum_gap=%s result=%s missing=%s work=%d ms=%.3f%n",
                stock, target, reverse, gap, plan.result(), plan.missingExact(), budget.nodes(), (System.nanoTime()-started)/1e6);
        if (gap.signum() > 0 && plan.feasible()) throw new AssertionError("Infeasible shared-resource plan accepted");
        if (gap.signum() <= 0 && !plan.feasible()) throw new AssertionError("Funded control failed");
    }
    public static void main(String[] args) {
        for (boolean reverse : new boolean[] {false, true}) {
            run(3, 2, reverse);
            run(4, 2, reverse);
            run(Long.MAX_VALUE, Long.MAX_VALUE / 2 + 1, reverse);
        }
    }
}
