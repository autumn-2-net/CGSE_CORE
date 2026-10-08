import org.cgse.core.*;
import java.util.*;
public class WaterCheck {
  static GraphRecipe<String> r(String name, Map<String,Long> in, Map<String,Long> out) {
    return new GraphRecipe<>(name,name,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
  }
  static void run(String name,List<GraphRecipe<String>> recipes,long amount,Map<String,Long> stock) {
    long start=System.nanoTime();
    var plan=new GraphPlanner<>(new GraphCompiler<>(recipes)).plan("P",amount,stock,true,true,new PlanningBudget(3000,1000000,()->false));
    if(plan.feasible()) PlanVerifier.verify(plan);
    System.out.printf(Locale.ROOT,"%s amount=%d status=%s ms=%.3f nodes=%d initial=%s seeds=%s missing=%s patterns=%s%n",name,amount,plan.result(),(System.nanoTime()-start)/1e6,plan.searchNodes(),plan.initial(),plan.seeds(),plan.missing(),plan.patternTimes());
  }
  public static void main(String[] args) {
    for(long amount:new long[]{10,100_000_000L,1_000_000_000_000L}) {
      var stock=Map.of("R",amount,"W",Long.MAX_VALUE);
      run("self",List.of(r("p",Map.of("R",1L,"W",1000L),Map.of("P",1L,"W",1000L))),amount,stock);
      for(long water:new long[]{500,1000,2000}) run("chain"+water,List.of(r("up",Map.of("R",1L,"W",1000L),Map.of("I",1L)),r("down",Map.of("I",1L),Map.of("P",1L,"W",water))),amount,stock);
      run("unneeded-cycle",List.of(r("p",Map.of("R",1L,"W",1000L),Map.of("P",1L)),r("side",Map.of("P",1L,"S",1L),Map.of("Q",1L,"W",1000L))),amount,stock);
    }
    var chain=List.of(r("up",Map.of("R",1L,"W",1000L),Map.of("I",1L)),r("down",Map.of("I",1L),Map.of("P",1L,"W",1000L)));
    run("no-water",chain,10,Map.of("R",10L));
    run("one-seed",chain,10,Map.of("R",10L,"W",1000L));
  }
}
