import org.cgse.core.*;
import java.util.*;
import java.math.BigInteger;
public class VirtualCoreChecks implements GraphJobRuntime.Adapter<String> {
 static int checks; GraphJobRuntime<String> runtime; long capacity=Long.MAX_VALUE,delivered,returned,pushed;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static GraphRecipe<String> recipe(String id,String input,String output){return new GraphRecipe<>(id,id,List.of(new GraphRecipe.Slot<>("V",1,0,true,true),new GraphRecipe.Slot<>(input,1,1)),Map.of("V",1L,output,1L));}
 static GraphPlan<String> plan(List<GraphRecipe<String>> recipes,long n,Map<String,Long> stock){var b=new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);var work=new GraphPlanningWork<>(new GraphCompiler<>(recipes),"P",n,stock,true,true,b);while(!work.step()){}return work.result();}
 public long capacity(GraphRecipe<String> recipe,long n){return Math.min(capacity,n);}
 public GraphJobRuntime.Outcome push(GraphRecipe<String> r,long n,Map<String,Long> inputs){
  check(!inputs.containsKey("V"),"virtual credential debited");check(runtime.owned().getOrDefault("V",0L)==1,"credential lost");
  for(var e:r.executionOutputs().entrySet()) check(runtime.accept(e.getKey(),e.getValue()*n,false)==e.getValue()*n,"return rejected");
  pushed+=n;return GraphJobRuntime.Outcome.ACCEPTED;
 }
 public long deliver(String key,long n){check(key.equals("P"),"unexpected delivery");delivered+=n;return n;}
 public long refund(String key,long n){if(key.equals("V"))returned+=n;return n;}
 public static void main(String[]args){var recipes=List.of(recipe("r","R","P"));
  for(long n:new long[]{16,96,3_000_000_000L,Long.MAX_VALUE}){
   var missing=plan(recipes,n,Map.of("R",n));check(missing.missingExact().equals(Map.of("V",BigInteger.ONE)),"wrong missing "+n+" "+missing.result()+missing.missingExact());
   var p=plan(recipes,n,Map.of("R",n,"V",Long.MAX_VALUE));check(p.feasible(),"funded virtual "+p.result());check(p.initial().get("V")==1,"inflated virtual borrow "+p.initial());PlanVerifier.verifyRuntimeInventory(p);
   var f=new VirtualCoreChecks();f.runtime=new GraphJobRuntime<>(p,p.initial(),Map.of());
   for(int tick=0;tick<100&&!f.runtime.finished();tick++)f.runtime.tick(f,tick,4);
   check(f.runtime.state()==GraphJobRuntime.State.COMPLETED,"virtual completion blocked "+f.runtime.reason());check(f.delivered==n&&f.returned==1&&f.pushed==n,"virtual quantity/return");
  }
  var chain=List.of(recipe("a","R","M"),recipe("b","M","P"));var p=plan(chain,96,Map.of("R",96L,"V",1L));check(p.feasible(),"virtual shared chain");
  var f=new VirtualCoreChecks();f.capacity=1;f.runtime=new GraphJobRuntime<>(p,p.initial(),Map.of());
  for(int tick=0;tick<400&&!f.runtime.finished();tick++){f.runtime.tick(f,tick,4);if(tick%11==0)f.runtime=new GraphJobRuntime<>(f.runtime.snapshot());}
  check(f.runtime.state()==GraphJobRuntime.State.COMPLETED&&f.delivered==96&&f.returned==1,"single dispatch + reload stalled");
  var compiler=new GraphCompiler<>(chain);check(compiler.producers("V").isEmpty(),"virtual treated as manufacturing source");check(compiler.compile("P",Map.of(),Set.of(),new PlanningBudget(0,100000,()->false)).regions().stream().noneMatch(r->r.cyclic()),"virtual introduced fake SCC");
  System.out.println("VIRTUAL_CORE_PASS "+checks);
 }
}
