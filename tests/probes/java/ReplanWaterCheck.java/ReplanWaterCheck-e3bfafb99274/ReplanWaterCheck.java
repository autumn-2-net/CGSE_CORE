import org.cgse.core.*;
import java.util.*;
public class ReplanWaterCheck {
  static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out) { return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out); }
  public static void main(String[]args) {
    var recipes=List.of(r("wet",Map.of("R",1L,"W",1000L),Map.of("I",1L)),r("finish",Map.of("I",1L),Map.of("P",1L,"W",3000L)));
    for(int i=0;i<=9;i++) {
      var work=new GraphPlanningWork<>(new GraphCompiler<>(recipes),"P",9,Map.of("R",9L-i,"I",(long)i,"W",Long.MAX_VALUE),Set.of(),Map.of("W",2000L),true,false,new PlanningBudget(3000,1000000,()->false)).catalysts(CatalystPolicy.MINIMAL);
      while(!work.step()){}
      var p=work.result();
      System.out.println("intermediate="+i+" result="+p.result()+" nodes="+p.searchNodes()+" initial="+p.initial()+" missing="+p.missing()+" times="+p.patternTimes());
    }
  }
}
