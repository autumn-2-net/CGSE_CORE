package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public final class FallbackAnalysis {
 public static void main(String[]args)throws Exception{
  var groups=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();int total=0,base=0,pruned=0,shuffled=0;
  for(var element:groups){var g=element.getAsJsonObject();var cat=QuantitySweep.catalog(g.getAsJsonObject("catalog"));var stock=QuantitySweep.amounts(g.getAsJsonObject("stock"));
   var reachable=new HashSet<>(cat.external());stock.forEach((k,v)->{if(v>0)reachable.add(k);});boolean changed=true;
   while(changed){changed=false;for(var r:cat.recipes())if(reachable.containsAll(r.inputs().keySet()))changed|=reachable.addAll(r.executionOutputs().keySet());}
   var live=cat.recipes().stream().filter(r->reachable.containsAll(r.inputs().keySet())).toList();
   for(var req:g.getAsJsonArray("requests")){long amount=req.getAsJsonObject().get("amount").getAsLong();String target=g.get("target").getAsString();boolean any=false;total++;
    for(int mode=0;mode<6;mode++){var recipes=new ArrayList<>(mode==0?cat.recipes():live);if(mode>1)Collections.shuffle(recipes,new Random(710000L+mode));
     var producers=new LinkedHashMap<String,List<GraphRecipe<String>>>();cat.producers().forEach((k,v)->producers.put(k,v.stream().filter(live::contains).toList()));
     var compiler=mode==0?cat.compiler():mode==1?new GraphCompiler<>(recipes,producers):new GraphCompiler<>(recipes);var budget=new PlanningBudget(0,131072,16L<<20,()->false,System::nanoTime);
     var p=GraphFallback.plan(compiler,target,amount,stock,cat.external(),Map.of(),true,true,budget);if(p.feasible()){PlanVerifier.verifyRuntimeInventory(p);p.initialExact().forEach((k,v)->{if(!cat.external().contains(k)&&v.compareTo(java.math.BigInteger.valueOf(stock.getOrDefault(k,0L)))>0)throw new AssertionError("overdraw");});if(mode==0)base++;if(mode==1)pruned++;if(mode>1)any=true;}
     System.out.println("CASE "+g.get("id").getAsString()+" amount="+amount+" mode="+mode+" removed="+(cat.recipes().size()-live.size())+" result="+p.result()+" work="+budget.nodes());if(budget.reservedBytes()!=0)throw new AssertionError("leak");
    }shuffled+=any?1:0;
   }
  }System.out.println("PASS total="+total+" baseline="+base+" reachable_prune="+pruned+" prune_plus_4shuffles="+shuffled);
 }
}
