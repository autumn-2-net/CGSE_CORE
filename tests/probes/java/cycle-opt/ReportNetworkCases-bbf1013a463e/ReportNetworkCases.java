import org.cgse.core.*;
import java.util.*;
public class ReportNetworkCases {
    static GraphRecipe<String> r(String id, Map<String,Long> in, Map<String,Long> out) { return ComplexCycleStress.recipe(id,in,out); }
    public static void main(String[] args) {
        var recipes=List.of(r("r1",Map.of("A",1L),Map.of("B",1L)),r("r2",Map.of("B",1L),Map.of("A",1L,"C",1L)),
            r("r3",Map.of("C",1L,"K",1L),Map.of("D",1L)),r("r4",Map.of("D",1L),Map.of("K",1L,"A",1L,"E",1L)),r("r5",Map.of("E",1L),Map.of("T",1L)));
        for(long amount:new long[]{1,100_000_000L,Long.MAX_VALUE}) {
            long start=System.nanoTime();
            var plan=new GraphPlanner<>(new GraphCompiler<>(recipes)).plan("T",amount,Map.of("A",1L,"K",1L),true,true,new PlanningBudget(3000,10_000_000,()->false));
            if(!plan.feasible()) throw new AssertionError("Report example "+amount+" -> "+plan.result());
            PlanVerifier.verify(plan);
            System.out.println("REPORT_FIVE amount="+amount+" result="+plan.result()+" wall_ms="+(System.nanoTime()-start)/1e6+" counts="+plan.patternTimesExact()+" initial="+plan.initialExact());
        }
    }
}
