package org.cgse.core;
import java.math.BigInteger;
import java.util.*;

public final class RegionEarlyExitProbe {
    static void retryChecks() {
        var out = new GraphRecipe<>("out", "out", List.of(new GraphRecipe.Slot<>("C", 1L)), Map.of("A", 1L));
        var main = new GraphRecipe<>("main", "main", List.of(new GraphRecipe.Slot<>("A", 2L), new GraphRecipe.Slot<>("B", 3L)), Map.of("A", 1L, "C", 3L));
        for (boolean reversed : new boolean[]{false, true}) {
            var recipes = new ArrayList<GraphRecipe<String>>(); for (int i = 0; i < 8; i++) recipes.add(new GraphRecipe<>("dead"+i, "dead"+i, List.of(new GraphRecipe.Slot<>("X"+i,1L)), Map.of("C",1L)));
            if (!reversed) { recipes.add(0, main); recipes.add(0, out); } else { recipes.add(main); recipes.add(out); }
            var budget = new PlanningBudget(0, 2_000_000, 128L << 20, () -> false, System::nanoTime);
            var p = new GraphPlanner<>(new GraphCompiler<>(recipes)).plan("C", 9, Map.of("A",1L,"B",14L,"C",1L), false, true, budget);
            if (!p.feasible()) throw new AssertionError("C9 retry failed reversed="+reversed+" "+p.result()+" "+budget.diagnostics());
            try (var verified = new PlanVerification<>(p,budget)) { while(!verified.step()) {} if (verified.physicalProduced("C").compareTo(BigInteger.valueOf(9))<0) throw new AssertionError("C9 production"); }
            System.out.println("C9 dead-first="+reversed+" result="+p.result()+" counts="+p.patternTimesExact()+" work="+budget.nodes());
        }
        var make = new GraphRecipe<>("make","make",List.of(new GraphRecipe.Slot<>("raw",1L),new GraphRecipe.Slot<>("A",1L)),Map.of("A",1L,"C",1L,"Z",1L));
        var turn = new GraphRecipe<>("turn","turn",List.of(new GraphRecipe.Slot<>("A",1L),new GraphRecipe.Slot<>("Z",1L),new GraphRecipe.Slot<>("fuel",1L)),Map.of("C",1L,"Z",1L));
        for (long fuel : new long[]{1,2}) {
            var budget = new PlanningBudget(0, 2_000_000, 128L << 20, () -> false, System::nanoTime);
            var p = new GraphPlanner<>(new GraphCompiler<>(List.of(out,make,turn))).plan("C",3,Map.of("C",2L,"raw",1L,"fuel",fuel),false,true,budget);
            if (p.feasible()) throw new AssertionError("mixed fake production accepted");
            String trace=budget.diagnostics(); int retries=trace.split("retry_with_target_seed",-1).length-1;
            System.out.println("mixed-rejected fuel="+fuel+" result="+p.result()+" retries-in-retained-trace="+retries+" work="+budget.nodes());
        }
    }
    static void caseOne(String label, List<GraphRecipe.Slot<String>> inputs, Map<String, Long> stock) {
        var out = new GraphRecipe<>("out", "out", List.of(new GraphRecipe.Slot<>("C", 1L)), Map.of("A", 1L));
        var main = new GraphRecipe<>("main", "main", inputs, Map.of("C", 3L));
        var budget = new PlanningBudget(0, 2_000_000, 128L << 20, () -> false, System::nanoTime);
        var p = new GraphPlanner<>(new GraphCompiler<>(List.of(out, main))).plan("C", 3, stock, false, true, budget);
        System.out.println(label + " result=" + p.result() + " times=" + p.patternTimesExact() + " initial=" + (p.initialExact().size() < 8 ? p.initialExact() : p.initialExact().size() + " material keys") + " missing=" + p.missingExact() + " work=" + budget.nodes());
        System.out.println(" diagnostics=" + budget.diagnostics());
        if (p.feasible()) { try (var verified = new PlanVerification<>(p, budget)) { while (!verified.step()) {} System.out.println(" gross=" + verified.physicalProduced("C") + " net=" + verified.summary().delta("C")); } }
    }
    public static void main(String[] args) {
        caseOne("consumed-config", List.of(new GraphRecipe.Slot<>("A", 1L), new GraphRecipe.Slot<>("cfg", 1L, 1, true)), Map.of("C", 1L, "cfg", 2L));
        var inputs = new ArrayList<GraphRecipe.Slot<String>>(); var stock = new LinkedHashMap<String, Long>(); inputs.add(new GraphRecipe.Slot<>("A", 1L)); stock.put("C", 1L);
        for (int i = 0; i < 2048; i++) { inputs.add(new GraphRecipe.Slot<>("R" + i, 1L)); stock.put("R" + i, 2L); }
        caseOne("large-proof-admission", inputs, stock);
        retryChecks();
    }
}
