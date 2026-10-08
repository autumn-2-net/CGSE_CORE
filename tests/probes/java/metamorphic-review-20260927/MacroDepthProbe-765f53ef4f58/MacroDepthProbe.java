package org.cgse.core;
import java.util.*;
public final class MacroDepthProbe {
    public static void main(String[] args) {
        var budget = new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);
        Map<String,GraphRecipe<String>> recipes = new LinkedHashMap<>();
        recipes.put("a",new GraphRecipe<>("a","a",List.of(new GraphRecipe.Slot<>("A",1)),Map.of("B",1L)));
        recipes.put("b",new GraphRecipe<>("b","b",List.of(new GraphRecipe.Slot<>("B",1)),Map.of("C",1L)));
        var compilation = new LinearMacroCompilation<>(recipes,"C",Set.of(),budget);
        while(!compilation.step()){}
        String macro = compilation.recipes().keySet().stream().filter(id -> !recipes.containsKey(id)).findFirst().orElseThrow();
        PlanStep step = new PlanStep.Batch(macro,1);
        for(int i=0;i<10000;i++)step=new PlanStep.Sequence(List.of(step));
        try {
            PlanStep expanded=compilation.expand(step);
            var counts=PlanCountComputation.of(expanded);
            if(!counts.equals(Map.of("a",java.math.BigInteger.ONE,"b",java.math.BigInteger.ONE)))throw new AssertionError(counts);
            System.out.println("PASS: 10000-level macro expansion preserves primitive counts");
        } catch(StackOverflowError error) {
            System.out.println("COUNTEREXAMPLE: valid 10000-level macro expansion throws StackOverflowError");
        }
    }
}
