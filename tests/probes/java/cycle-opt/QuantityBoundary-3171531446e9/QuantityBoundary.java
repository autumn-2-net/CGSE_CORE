import org.cgse.core.*;
import java.util.*;

public final class QuantityBoundary {
    public static void main(String[] args) {
        var recipe = ComplexCycleStress.recipe("one", Map.of("R",1L), Map.of("P",1L));
        for (long n : new long[]{1, Integer.MAX_VALUE, (long)Integer.MAX_VALUE+1, 229_064_923_413_333_376L, Long.MAX_VALUE-16384, Long.MAX_VALUE}) {
            for (int kind = 0; kind < 5; kind++) {
                List<GraphRecipe<String>> recipes; Map<String,Long> stock; String target;
                if (kind==0) { recipes=List.of(recipe); stock=Map.of("R",Long.MAX_VALUE); target="P"; }
                else if (kind==1) {
                    recipes=List.of(ComplexCycleStress.recipe("double",Map.of("C",1L,"R",1L),Map.of("C",2L)));
                    stock=Map.of("R",Long.MAX_VALUE,"C",16384L); target="C";
                } else if (kind==4) {
                    recipes=List.of(ComplexCycleStress.recipe("glacio-shape", Map.of("C",4L,"lapis",16L,"dust",1L,"fluid1",100L,"fluid2",900L), Map.of("C",64L)));
                    stock=Map.of("C",16384L,"lapis",Long.MAX_VALUE,"dust",Long.MAX_VALUE,"fluid1",Long.MAX_VALUE,"fluid2",Long.MAX_VALUE); target="C";
                } else {
                    var seed=kind==2 ? ComplexCycleStress.ring(128,1,1) : ComplexCycleStress.nested(2,2,1,1);
                    recipes=seed.recipes(); stock=Map.of("R",Long.MAX_VALUE,"C0",1L); target=seed.target();
                }
                var c=new ComplexCycleStress.Case("boundary-"+kind,recipes,target,n,stock,Map.of(),new PlanStep.Sequence(List.of()),true);
                ComplexCycleStress.print(c,"scan",ComplexCycleStress.run(c,null,3000));
            }
        }
    }
}
