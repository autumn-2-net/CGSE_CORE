import org.cgse.core.*;import java.util.*;
public class CatalystDebug {
 static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 public static void main(String[]args){var b=new PlanningBudget(0,4_000_000,128L<<20,()->false,System::nanoTime);var w=new GraphPlanningWork<>(new GraphCompiler<>(List.of(r("ab",Map.of("A",1L,"R",1L),Map.of("B",1L)),r("ba",Map.of("B",1L),Map.of("A",1L,"P",1L)),r("seed",Map.of("Q",1L),Map.of("A",1L)))),"P",8,Map.of("A",1L,"Q",3L,"R",8L),true,true,b).catalysts(new CatalystPolicy(4,10));while(!w.step()){}var p=w.result();System.out.println(p.result()+" initial="+p.initialExact()+" seeds="+p.seeds()+" times="+p.patternTimesExact()+" missing="+p.missingExact());System.out.println(b.diagnostics());}
}
