import org.cgse.core.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Local finite-state oracle: conservation makes complete BFS independent of solver heuristics. */
public final class TinyOracle {
    static int encode(int[] values) { int code = 0; for (int i = values.length - 1; i >= 0; i--) code = code * 8 + values[i]; return code; }
    static int[] decode(int code, int size) { var out = new int[size]; for (int i = 0; i < size; i++) { out[i] = code % 8; code /= 8; } return out; }
    static boolean reachable(List<GraphRecipe<String>> recipes, int[] stock, int target, long[] visits) {
        var seen = new HashSet<Integer>(); var todo = new ArrayDeque<Integer>();
        todo.add(encode(stock)); seen.add(encode(stock));
        while (!todo.isEmpty()) {
            int[] held = decode(todo.removeFirst(), stock.length); visits[0]++;
            if (held[held.length - 1] >= target) return true;
            for (var recipe : recipes) {
                boolean ok = true;
                for (var e : recipe.inputs().entrySet()) if (held[Integer.parseInt(e.getKey())] < e.getValue()) { ok = false; break; }
                if (!ok) continue;
                int[] next = held.clone();
                recipe.inputs().forEach((k,v) -> next[Integer.parseInt(k)] -= v.intValue());
                recipe.outputs().forEach((k,v) -> next[Integer.parseInt(k)] += v.intValue());
                int code = encode(next); if (seen.add(code)) todo.addLast(code);
            }
        }
        return false;
    }
    static PlanningBudget budget() { return new PlanningBudget(2000, 1_000_000, 128L << 20, () -> false, System::nanoTime); }
    public static void main(String[] args) throws Exception {
        Random random = new Random(2470193);
        int possible = 0, feasible = 0, missed = 0, impossible = 0;
        long[] visits = {0};
        for (int sample = 0; sample < 400; sample++) {
            int size = 3 + random.nextInt(3), total = 2 + random.nextInt(5);
            int[] initial = new int[size];
            for (int i = 0; i < total; i++) initial[random.nextInt(size - 1)]++;
            var stock = new LinkedHashMap<String,Long>();
            for (int i = 0; i < size; i++) if (initial[i] > 0) stock.put("" + i, (long) initial[i]);
            var recipes = new ArrayList<GraphRecipe<String>>();
            int count = 2 + random.nextInt(7);
            for (int i = 0; i < count; i++) {
                int tokens = 1 + random.nextInt(3);
                var in = new LinkedHashMap<String,Long>(); var out = new LinkedHashMap<String,Long>();
                for (int j = 0; j < tokens; j++) { in.merge("" + random.nextInt(size), 1L, Long::sum); out.merge("" + random.nextInt(size), 1L, Long::sum); }
                recipes.add(ComplexCycleStress.recipe("r" + i, in, out));
            }
            int amount = 1 + random.nextInt(total);
            boolean reference = reachable(recipes, initial, amount, visits);
            var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "" + (size-1), amount, stock, false, false, budget()).catalysts(CatalystPolicy.MINIMAL);
            while (!work.step()) {}
            var plan = work.result();
            if (plan.feasible()) {
                if (!reference) throw new AssertionError("Unsound acceptance sample=" + sample);
                var c = new ComplexCycleStress.Case("oracle" + sample, recipes, "" + (size-1), amount, stock, Map.of(), plan.steps(), true);
                ComplexCycleStress.validate(c, plan, true);
                feasible++;
            } else if (reference) {
                missed++;
                System.out.println("MISS sample=" + sample + " result=" + plan.result());
            }
            if (reference) possible++; else impossible++;
        }
        System.out.println("ORACLE cases=400 feasible=" + feasible + " reachable=" + possible + " missed=" + missed + " unreachable=" + impossible + " unsound=0 bfs_visits=" + visits[0]);
        if (args.length == 0) parallelHubs();
    }
    static void parallelHubs() throws Exception {
        var c = ComplexCycleStress.hub(512, 100_000_017, 4);
        var compiler = new GraphCompiler<>(c.recipes());
        var reference = compiler.compile(c.target(), Map.of(), Set.of(), budget());
        for (int threads : new int[]{1,2,4}) try (var scheduler = new PlanningScheduler(threads, 4, 7, 1_000_000_000)) {
            var budget = budget();
            var result = scheduler.submit(compiler.begin(c.target(), Map.of(), Set.of(), budget), budget).get(10, TimeUnit.SECONDS);
            if (!reference.equals(result)) throw new AssertionError("Shared hub parallel graph changed with threads=" + threads);
            if (result.regions().stream().filter(GraphCompiler.Region::cyclic).count() != 1) throw new AssertionError("Shared catalyst did not join SCC");
            System.out.println("PARALLEL_HUB recipes=" + result.recipes().size() + " threads=" + threads + " match=true");
        }
    }
}
