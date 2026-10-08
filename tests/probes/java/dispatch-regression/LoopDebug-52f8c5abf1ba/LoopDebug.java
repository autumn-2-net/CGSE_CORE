import java.util.*;
import org.cgse.core.*;
public class LoopDebug {
 static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 public static void main(String[] args){
 var recipes=List.of(r("wet",Map.of("R",1L,"W",1000L),Map.of("I",1L)),r("finish",Map.of("I",1L),Map.of("P",1L,"W",2000L)));
 var work=new GraphPlanningWork<>(new GraphCompiler<>(recipes),"P",9,Map.of("R",9L,"W",Long.MAX_VALUE),true,true,new PlanningBudget(5000,10000,()->false)).catalysts(new CatalystPolicy(2,0));
 while(!work.step()){} var p=work.result();System.out.println(p.steps()+" initial="+p.initial()+" seeds="+p.seeds());
 var rt=new GraphJobRuntime<>(p,p.initial(),Map.of());
 var a=new GraphJobRuntime.Adapter<String>(){
 public long capacity(GraphRecipe<String> r,long n){System.out.println("capacity "+r.id()+" "+n);return Math.min(1,n);}
 public GraphJobRuntime.Outcome push(GraphRecipe<String> r,long n,Map<String,Long> in){System.out.println("push "+r.id()+" "+n+" "+in);return GraphJobRuntime.Outcome.ACCEPTED;}
 public long refund(String k,long n){return n;}public long deliver(String k,long n){return n;}};
 rt.tick(a,0,2);System.out.println(rt.snapshot());
 }
}
