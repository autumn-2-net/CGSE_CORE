package org.cgse.core;
import java.util.*;
import java.nio.file.*;
public final class CacheBoundaryProbe {
 static int found,dropped,retainedOnExhaustion;static long checks;
 public static void main(String[]args)throws Exception{
  var recipe=OrderRetryRegression.recipe("r",Map.of("A",1L),Map.of("B",1L));
  for(int cap=100;cap<1800;cap++){
   var b=new PlanningBudget(0,cap,128L<<20,()->false,System::nanoTime);var stock=Map.of("A",1L);
   try(var model=RecipeCountModel.create(new GraphCompiler<>(List.of(recipe)),"B",1,stock,Map.of(),Set.of(),Set.of(),true,b);
    var execution=new CountExecution<>(model,b);
    var branch=new IntegerCountBranch<>(model,execution,"B",1,stock,Map.of(),Set.of(),false,true,b,0,List.of())){
     while(branch.state==IntegerCountBranch.State.OPEN)branch.run(1,List.of(),List.of(),List.of(),null,()->false);
     if(branch.state==IntegerCountBranch.State.FOUND){found++;if(b.diagnostics().contains("verified_witness_cache_declined")){retainedOnExhaustion++;PlanVerifier.verifyRuntimeInventory(branch.plan);}}
     if(branch.limit!=null&&Arrays.stream(branch.limit.getStackTrace()).anyMatch(s->s.getClassName().endsWith("CountScheduleContinuations")&&s.getMethodName().startsWith("remember")))dropped++;
   }catch(PlanningBudget.Exhausted expected){}
   checks++;if(b.reservedBytes()!=0)throw new AssertionError("leak cap="+cap+" bytes="+b.reservedBytes());
  }
  String result="caps=1700, found="+found+", droppedFromCache="+dropped+", retainedAfterOptionalExhaustion="+retainedOnExhaustion+", assertions="+checks;Files.writeString(Path.of(args[0],"cache-boundary.txt"),result);System.out.println(result);
  if(args.length>1&&(dropped!=0||retainedOnExhaustion==0))throw new AssertionError(result);
 }
}
