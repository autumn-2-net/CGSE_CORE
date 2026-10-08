package org.cgse.core;
import java.math.BigInteger;import java.util.*;
public final class ProductionSupportProbe {
 static int witnesses,blocked,checked;
 static GraphRecipe<String> recipe(String id,Map<String,Long> inputs,Map<String,Long> outputs){return new GraphRecipe<>(id,id,inputs.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),outputs);}
 public static void main(String[] args) {
  boolean require=args.length>0&&args[0].equals("require");boolean partial=args.length>1&&args[1].equals("partial");
  for(int seed=0;seed<2048;seed++){
   Random rnd=new Random(12071L+seed);int depth=2+seed%(partial?4:6);boolean feasible=seed%5!=0;long amount=1+seed%3;
   Map<String,Long> stock=new LinkedHashMap<>();stock.put("T",seed%2==0?Long.MAX_VALUE:amount);stock.put("K",1L);stock.put("P",amount);stock.put("S0",feasible?amount:0L);
   List<GraphRecipe<String>> rs=new ArrayList<>();
   for(int i=0;i<depth;i++)rs.add(recipe("advance"+i,Map.of("S"+i,1L,"K",1L),Map.of("S"+(i+1),1L,"K",1L)));
   rs.add(recipe("finish",Map.of("S"+depth,1L,"P",1L),Map.of("T",1L)));
   // An available target must not satisfy the new-production obligation;
   // unrelated legal moves and return paths exercise ordering and deduplication.
   for(int i=1;i<depth;i++)rs.add(recipe("return"+i,Map.of("S"+i,1L),Map.of("S0",1L)));
   Collections.shuffle(rs,rnd);var compiler=new GraphCompiler<>(rs);var b=new PlanningBudget(0,40_000_000,128L<<20,()->false,System::nanoTime);
   try(var model=RecipeCountModel.create(compiler,"T",amount,stock,Map.of(),Set.of(),Set.of(),true,b);var search=partial?new CountSupportSearch<>(model,Collections.nCopies(model.recipes.size(),BigInteger.ONE).toArray(BigInteger[]::new),b):CountSupportSearch.allSources(model,b)){
    while(!search.step()){}
    if(search.result()==CountSupportSearch.Result.WITNESS){
     var counts=search.counts();BigInteger produced=BigInteger.ZERO;
     for(int i=0;i<counts.length;i++)produced=produced.add(counts[i].multiply(BigInteger.valueOf(model.recipes.get(i).executionOutputs().getOrDefault("T",0L))));
     if(produced.compareTo(BigInteger.valueOf(amount))<0){if(require)throw new AssertionError("empty forced witness "+seed);continue;}
     if(!feasible)throw new AssertionError("false witness "+seed);witnesses++;
     var initial=new LinkedHashMap<String,BigInteger>();stock.forEach((k,v)->{if(v>0)initial.put(k,BigInteger.valueOf(v));});
     var byId=new LinkedHashMap<String,GraphRecipe<String>>();rs.forEach(r->byId.put(r.id(),r));
     var plan=new GraphPlan<>("T",amount,false,search.witness(),byId,initial,Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,b.nodes(),0);
     PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);
     for(var row:model.constraints){BigInteger sum=BigInteger.ZERO;for(var e:row.terms().entrySet())sum=sum.add(e.getValue().multiply(counts[e.getKey()]));if(sum.compareTo(row.upper())>0)throw new AssertionError("bad integer balance");checked++;}
    }else {if(feasible&&require)throw new AssertionError("missed chain "+seed+" "+b.diagnostics());blocked++;if(search.cut()!=null)throw new AssertionError("production progress exported material cut");}
   }
   if(b.reservedBytes()!=0)throw new AssertionError("leak "+b.reservedBytes());
  }
  System.out.println("PASS samples=2048 valid_forced_witnesses="+witnesses+" blocked="+blocked+" checked_rows="+checked);
 }
}
