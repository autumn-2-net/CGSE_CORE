import org.cgse.core.*;
import java.util.*;

public class SchedulingCases {
    record Case(List<GraphRecipe<String>> recipes, String target, long amount, Map<String, Long> stock) {}
    static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    static Map<String, Long> a(Object... pairs) {
        var result = new LinkedHashMap<String, Long>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String)pairs[i], ((Number)pairs[i + 1]).longValue());
        return result;
    }
    static Case fixture(String name) {
        long n = name.contains("long") ? Long.MAX_VALUE : 1_000_000L;
        if (name.startsWith("chain")) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            for (int i = 0; i < 64; i++) recipes.add(r("r" + i, a("x" + i, 1), a("x" + (i + 1), 1)));
            return new Case(recipes, "x64", n, a("x0", n));
        }
        if (name.startsWith("alternatives")) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            int count = name.contains("late") ? 16 : 2;
            for (int i = 0; i < count - 1; i++) recipes.add(r("cost" + i, a("raw", 3), a("P", 1)));
            recipes.add(r("cheap", a("raw", 2), a("P", 1)));
            n = name.contains("long") ? Long.MAX_VALUE / 2 : 1_000_000;
            return new Case(recipes, "P", n, a("raw", Math.multiplyExact(n, 2) - (name.contains("missing") ? 1 : 0)));
        }
        var recipes = List.of(
            r("forge", a("rod",16,"anomaly",1,"excited",1000,"S",1000), a("H",64,"D",100)),
            r("space", a("H",1,"anomaly",1,"plate",16,"T",10000,"stellar",10000), a("S",10000)),
            r("time", a("charge",4,"H",1,"spacetime",1000,"D",100), a("T",500,"S",500)));
        var stock = a("rod",Long.MAX_VALUE,"anomaly",Long.MAX_VALUE,"excited",Long.MAX_VALUE,"spacetime",Long.MAX_VALUE,
            "plate",Long.MAX_VALUE,"stellar",Long.MAX_VALUE,"charge",Long.MAX_VALUE,"H",2,"D",1100);
        if (name.contains("small")) n = 1;
        if (name.contains("missing")) { stock.put("rod",15L); n = 1; }
        if (name.contains("no-seed")) { stock.remove("H"); stock.remove("D"); n = 1; }
        return new Case(recipes,"H",n,stock);
    }
    public static Object[] run(String name) {
        var c = fixture(name);
        var budget = new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);
        long started = System.nanoTime();
        var work = new GraphPlanningWork<>(new GraphCompiler<>(c.recipes()),c.target(),c.amount(),c.stock(),true,true,budget).catalysts(CatalystPolicy.MINIMAL);
        while (!work.step()) {}
        var p = work.result();
        long elapsed = System.nanoTime() - started;
        if (p.feasible()) PlanVerifier.verify(p);
        else if (!p.missingExact().isEmpty()) PlanVerifier.verify(new GraphPlan<>(p.target(),p.amount(),p.preserveSeeds(),p.steps(),p.recipes(),p.initialExact(),p.seeds(),Map.of(),GraphPlan.Result.FEASIBLE,0,0));
        String trace = "unavailable in old version";
        try { trace = (String)budget.getClass().getMethod("diagnostics").invoke(budget); } catch (ReflectiveOperationException ignored) {}
        return new Object[]{elapsed,budget.nodes(),p.result().name(),trace,p.missingExact().toString()};
    }
    public static void main(String[] args) {
        for (String name : args) {
            Object[] result = run(name);
            System.out.println(name + " ms=" + (long)result[0]/1e6 + " nodes=" + result[1] + " result=" + result[2] + " missing=" + result[4]);
            System.out.println(result[3]);
        }
    }
}
