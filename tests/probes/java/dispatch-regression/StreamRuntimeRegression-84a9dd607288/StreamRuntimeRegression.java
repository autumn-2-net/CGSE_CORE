import org.cgse.core.*;
import java.math.BigInteger;
import java.util.*;

public class StreamRuntimeRegression extends DumpRuntimeReplay {
 static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 static GraphPlan<String> plan(String target,long amount,List<GraphRecipe<String>> recipes,PlanStep steps,Map<String,Long> initial,Map<String,Long> seeds){var r=new LinkedHashMap<String,GraphRecipe<String>>();recipes.forEach(x->r.put(x.id(),x));return new GraphPlan<>(target,amount,true,steps,r,initial,seeds,Map.of(),GraphPlan.Result.FEASIBLE,0,0);}
 static void execution(String name,GraphPlan<String> plan){for(int mode=0;mode<3;mode++){var result=new Machines(plan,mode).execute();System.out.println(name+" mode="+mode+" "+result);check(result.get("state").getAsString().equals("COMPLETED"),name+" stalled");}}
 static GraphPlan<String> rounding(){long batches=Long.MAX_VALUE/2+1;return plan("P",Long.MAX_VALUE,List.of(recipe("round",Map.of("R",1L),Map.of("P",2L))),new PlanStep.Sequence(List.of(new PlanStep.Batch("round",batches))),Map.of("R",batches),Map.of());}
 public static void main(String[] args){
  execution("rounding",rounding());
  for(int scale:new int[]{2,32,64,97}){
   var expand=recipe("expand",Map.of("R",1L),Map.of("I",(long)scale));var convert=recipe("convert",Map.of("I",1L),Map.of("J",1L));var pack=recipe("pack",Map.of("J",(long)scale),Map.of("P",1L));
   var steps=new PlanStep.Sequence(List.of(new PlanStep.Batch("expand",Long.MAX_VALUE),PlanStep.batch("convert",MAX.multiply(BigInteger.valueOf(scale))),new PlanStep.Batch("pack",Long.MAX_VALUE)));
   execution("wide_work_"+scale,plan("P",Long.MAX_VALUE,List.of(expand,convert,pack),steps,Map.of("R",Long.MAX_VALUE),Map.of()));
  }
  var grow=recipe("grow",Map.of("C",4L),Map.of("C",64L));var growth=new GraphPlanner<>(new GraphCompiler<>(List.of(grow))).plan("C",Long.MAX_VALUE,Map.of("C",4L),true,true,new PlanningBudget(5000,1000000,()->false));
  execution("growth",growth);
  migration();faults();System.out.println("PASS checks="+checks);
 }
 static void migration(){
  var a=recipe("expand",Map.of("R",1L),Map.of("I",2L));var b=recipe("convert",Map.of("I",1L),Map.of("J",1L));var c=recipe("pack",Map.of("J",2L),Map.of("P",1L));
  var steps=new PlanStep.Sequence(List.of(new PlanStep.Batch("expand",Long.MAX_VALUE),PlanStep.batch("convert",MAX.multiply(BigInteger.TWO)),new PlanStep.Batch("pack",Long.MAX_VALUE)));
  var p=plan("P",Long.MAX_VALUE,List.of(a,b,c),steps,Map.of("R",Long.MAX_VALUE),Map.of());long sent=Long.MAX_VALUE/2;
  var m=new Machines(p,1);var fresh=m.runtime.snapshot();var pending=List.of(new PlanStep.Batch("expand",Long.MAX_VALUE-sent),new PlanStep.Batch("convert",Long.MAX_VALUE),new PlanStep.Batch("convert",Long.MAX_VALUE),new PlanStep.Batch("pack",Long.MAX_VALUE));
  var old=new GraphJobRuntime.Snapshot<>(p,Map.of("R",Long.MAX_VALUE-sent,"I",sent*2),Map.<String,Long>of(),Map.<String,Long>of(),Map.of("expand",BigInteger.valueOf(sent)),List.<PlanCursor.Position>of(),pending,Long.MAX_VALUE,fresh.state(),false,"",fresh.obligations(),fresh.recovery(),Map.<String,BigInteger>of());
  m.runtime=new GraphJobRuntime<>(old);add(m.inputs,"R",sent);add(m.outputs,"I",sent*2);add(m.runs,"expand",sent);
  check(m.execute().get("state").getAsString().equals("COMPLETED"),"old serial wide plan migrates and completes without repeating work");
  var corrupt=new GraphJobRuntime.Snapshot<>(p,old.owned(),old.expected(),old.uncertainInputs(),old.acceptedRuns(),old.cursor(),pending.subList(1,pending.size()),old.remainingDelivery(),old.state(),false,"",old.obligations(),old.recovery(),Map.<String,BigInteger>of());
  boolean refused=false;try{new GraphJobRuntime<>(corrupt);}catch(IllegalArgumentException e){refused=true;}check(refused,"migration accepts corrupt old pending work");
 }
 static void faults(){
  var p=rounding();
  var f=new Machines(p,0){int attempts;public long deliver(String k,long n){if(attempts++==0)return 0;return super.deliver(k,n);}};
  check(f.execute().get("state").getAsString().equals("COMPLETED"),"blocked then partial delivery resumes");
  var rejected=new Machines(p,0){int attempts;public GraphJobRuntime.Outcome push(GraphRecipe<String> r,long n,Map<String,Long> in){if(attempts++==0)return GraphJobRuntime.Outcome.REJECTED;return super.push(r,n,in);}};
  check(rejected.execute().get("state").getAsString().equals("COMPLETED"),"rejection cannot spend pending input reservation");
  var ambiguous=new Machines(p,0){public long deliver(String k,long n){throw new IllegalStateException("after uncertain delivery");}};
  for(int t=0;t<4;t++)ambiguous.runtime.tick(ambiguous,t,8);
  check(ambiguous.runtime.state()==GraphJobRuntime.State.NEEDS_ATTENTION,"streaming failure retains escrow");
  var saved=ambiguous.runtime.snapshot();check(!saved.uncertainInputs().isEmpty(),"handoff saved");var restored=new GraphJobRuntime<>(saved);restored.tick(ambiguous,100,8);check(saved.equals(restored.snapshot()),"ambiguous saved handoff must not retry");
  var cancelled=new Machines(p,0){public long deliver(String k,long n){long sent=super.deliver(k,n);runtime.cancel();return sent;}};
  for(int t=0;t<30&&!cancelled.runtime.finished();t++)cancelled.runtime.tick(cancelled,t,8);
  check(cancelled.runtime.state()==GraphJobRuntime.State.CANCELLED,"cancel during delivery");cancelled.conserved();
  var fairnessOut=new LinkedHashMap<String,Long>();var initial=new LinkedHashMap<String,Long>();for(int i=0;i<40;i++){fairnessOut.put("X"+i,1L);initial.put("X"+i,Long.MAX_VALUE);}
  initial.put("R",1L);fairnessOut.put("P",1L);var r=recipe("many",Map.of("R",1L),fairnessOut);var fairPlan=plan("P",1,List.of(r),new PlanStep.Sequence(List.of(new PlanStep.Batch("many",1))),initial,Map.of());
  var fairness=new Machines(fairPlan,0){public long refund(String k,long n){if(k.equals("X0"))return 0;return super.refund(k,n);}};
  for(int t=0;t<100;t++)fairness.runtime.tick(fairness,t,1);
  check(fairness.refunds.getOrDefault("X39",BigInteger.ZERO).signum()>0,"blocked first refund starved later streaming keys");fairness.conserved();
 }
}
