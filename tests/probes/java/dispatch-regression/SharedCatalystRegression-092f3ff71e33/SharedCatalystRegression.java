import java.util.*;
import org.cgse.core.*;

public class SharedCatalystRegression {
 static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 public static void main(String[] args){
  for(long amount:new long[]{71,100, Integer.MAX_VALUE,Long.MAX_VALUE}){
   var r1=recipe("plate",Map.of("raw",1L,"hammer",1L),Map.of("plate",2L,"hammer",1L));
   var r2=recipe("double",Map.of("plate",2L,"hammer",1L),Map.of("target",1L,"hammer",1L));
   var work=new GraphPlanner<>(new GraphCompiler<>(List.of(r1,r2))).begin("target",amount,Map.of("raw",amount,"hammer",4096L),true,true,new PlanningBudget(0,1_000_000,()->false)).catalysts(new CatalystPolicy(64,0));
   while(!work.step()){}
   var plan=work.result();
   System.out.println("SHARED_CATALYST amount="+amount+" result="+plan.result()+" initial="+plan.initialExact()+" seeds="+plan.seeds());
   if(!plan.feasible())throw new AssertionError("Feasible shared catalyst order failed");
   if(plan.initial().get("hammer")<64)throw new AssertionError("Shared catalyst stock is ignored: "+plan.initial());
   PlanVerifier.verifyRuntimeInventory(plan);
   var machine=new Machine(plan);
   machine.rt.tick(machine,0,1);
   if(machine.first<2)throw new AssertionError("Working catalysts still dispatch singly");
   machine.rt=new GraphJobRuntime<>(machine.rt.snapshot());
   if(amount<=100){
    int tick=1;
    for(;tick<100&&!machine.rt.finished();tick++){
     for(var e:machine.rt.expected().entrySet())machine.rt.accept(e.getKey(),e.getValue(),false);
     machine.rt.tick(machine,tick,64);
     if(tick%3==0)machine.rt=new GraphJobRuntime<>(machine.rt.snapshot());
    }
    if(!machine.rt.finished()||machine.delivered!=amount||machine.refunded.getOrDefault("hammer",0L)!=64)throw new AssertionError("Shared tool execution/refund failed");
    System.out.println("SHARED_EXECUTION first_batch="+machine.first+" pushes="+machine.pushes+" ticks="+tick+" delivered="+machine.delivered);
   }
  }
  long wide=Long.MAX_VALUE/2;
  var a=recipe("wide_plate",Map.of("raw",1L,"hammer",1L),Map.of("plate",wide,"hammer",1L));
  var b=recipe("wide_double",Map.of("plate",wide,"hammer",1L),Map.of("target",1L,"hammer",1L));
  var p=new GraphPlan<>("target",4,true,new PlanStep.Sequence(List.of(new PlanStep.Batch(a.id(),4),new PlanStep.Batch(b.id(),4))),Map.of(a.id(),a,b.id(),b),Map.of("raw",4L,"hammer",4L),Map.of("hammer",1L),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
  PlanVerifier.verifyRuntimeInventory(p);
  var m=new Machine(p);
  for(int tick=0;tick<100&&!m.rt.finished();tick++){
   for(var e:m.rt.expected().entrySet())m.rt.accept(e.getKey(),e.getValue(),false);
   m.rt.tick(m,tick,64);
   if(tick%3==0)m.rt=new GraphJobRuntime<>(m.rt.snapshot());
  }
  if(!m.rt.finished()||m.delivered!=4)throw new AssertionError("Conserved-tool pipeline blocked by wide intermediate: "+m.rt.reason()+" held="+m.rt.owned()+" remaining="+m.rt.pendingRuns());
  System.out.println("SHARED_WIDE_EXECUTION delivered="+m.delivered+" pushes="+m.pushes);
 }
 static class Machine implements GraphJobRuntime.Adapter<String>{
  GraphJobRuntime<String> rt;long first,delivered;int pushes;Map<String,Long> refunded=new HashMap<>();
  Machine(GraphPlan<String> p){rt=new GraphJobRuntime<>(p,p.initial(),Map.of());}
  public long capacity(GraphRecipe<String> r,long n){return Math.min(n,37);}
  public GraphJobRuntime.Outcome push(GraphRecipe<String> r,long n,Map<String,Long> in){if(pushes++==0)first=n;return GraphJobRuntime.Outcome.ACCEPTED;}
  public long deliver(String k,long n){delivered+=n;return n;}
  public long refund(String k,long n){refunded.merge(k,n,Math::addExact);return n;}
 }
}
