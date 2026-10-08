package org.cgse.core;
import java.math.*;import java.util.*;
public class SeedPricingOracle {
 static final BigInteger O=BigInteger.ONE;
 static GraphRecipe<String> recipe(String name,Map<String,Long> in){var out=new LinkedHashMap<>(in);out.put("goal",1L);return new GraphRecipe<>(name,name,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 public static void main(String[]args){int checked=0,priced=0;for(int n:new int[]{3,4,6,8})for(long amount:new long[]{1,1000000000L,Long.MAX_VALUE})for(boolean mandatory:new boolean[]{false,true}){
  var stock=new LinkedHashMap<String,Long>();for(int i=0;i<n;i++)stock.put("s"+i,1L);var originalRecipe=recipe("all",stock);var catalog=new ArrayList<GraphRecipe<String>>();catalog.add(originalRecipe);for(int i=0;i<n;i++)for(int j=i+1;j<n;j++)catalog.add(recipe("pair"+i+"_"+j,Map.of("s"+i,1L,"s"+j,1L)));
  var compiler=new GraphCompiler<>(catalog);var initial=new LinkedHashMap<String,BigInteger>();stock.forEach((k,v)->initial.put(k,BigInteger.valueOf(v)));var original=new GraphPlan<>("goal",amount,true,new PlanStep.Batch("all",amount),Map.of("all",originalRecipe),initial,stock,Map.of(),GraphPlan.Result.FEASIBLE,0,0);var b=new PlanningBudget(0,20000000,256L<<20,()->false,System::nanoTime);
  try(var p=new SeedOptimization<>(compiler,original,stock,mandatory?Map.of("s0",1L):Map.of(),Set.of(),Set.of(),true,b)){while(!p.step()){}var result=p.result();if(result.seeds().size()!=2||p.lowerBound()>2||p.cardinalityProven()&&p.lowerBound()!=2)throw new AssertionError("seed support "+n+" "+amount+" "+mandatory+" "+result.seeds()+" "+b.diagnostics());try(var verification=new PlanVerification<>(result,b)){while(!verification.step()){}}checked++;}
  if(b.diagnostics().matches("(?s).*pricing_work=[1-9].*"))priced++;if(b.reservedBytes()!=0)throw new AssertionError("seed workspace leak "+b.reservedBytes());
 }if(priced==0)throw new AssertionError("seed pricing unexercised");System.out.println("PASS global seed support plans="+checked+" priced="+priced+" exact lower bounds and execution witnesses including Long.MAX_VALUE");}
}
