package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.math.BigInteger;
import java.util.*;
public final class MatrixAttributionProbe {
 public static void main(String[]args)throws Exception{
  var cases=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();var wanted=Map.of("scip/p0548-objective-12466",List.of(0,15),"scip/p0548-objective-12467",List.of(0,2));var output=new ArrayList<Map<String,Object>>();
  for(var e:cases){var c=e.getAsJsonObject();String id=c.get("id").getAsString();if(!wanted.containsKey(id))continue;for(int variation:wanted.get(id)){
   int n=c.getAsJsonArray("lower").size();BigInteger[] low=new BigInteger[n],high=new BigInteger[n];var order=new ArrayList<Integer>();for(int i=0;i<n;i++)order.add(i);if(variation>0)Collections.shuffle(order,new Random(7001+variation));int[] remap=new int[n];for(int i=0;i<n;i++)remap[order.get(i)]=i;for(int i=0;i<n;i++){low[remap[i]]=c.getAsJsonArray("lower").get(i).getAsBigInteger();var h=c.getAsJsonArray("upper").get(i);high[remap[i]]=h.isJsonNull()?null:h.getAsBigInteger();}
   var rows=new ArrayList<ExactLinearProgram.Constraint>();for(var raw:c.getAsJsonArray("rows")){var r=raw.getAsJsonObject();var terms=new LinkedHashMap<Integer,BigInteger>();for(var t:r.getAsJsonObject("terms").entrySet())terms.put(remap[Integer.parseInt(t.getKey())],t.getValue().getAsBigInteger());rows.add(new ExactLinearProgram.Constraint(terms,r.get("upper").getAsBigInteger()));}if(variation>0)Collections.shuffle(rows,new Random(8009+variation));
   var budget=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);budget.enableMetrics();var embedding=new CountBenchmark.Embedding(rows,low,high);GraphPlan<String> plan=null;long started=System.nanoTime();
   try(var search=new IntegerCountSearch<>(new GraphCompiler<>(embedding.recipes),"target",1,embedding.stock,Map.of(),Set.of(),Set.of(),false,true,budget,started)){int rounds=0;do{while(!search.step()){}if(search.result()!=null||search.infeasible()||!search.paused())break;search.resume();}while(++rounds<10000);plan=search.result();}
   var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("variation",variation);result.put("work",budget.nodes());result.put("verified",plan!=null);result.put("millis",(System.nanoTime()-started)/1e6);result.put("diagnostics",budget.diagnostics());if(plan!=null)PlanVerifier.verifyRuntimeInventory(plan);if(budget.reservedBytes()!=0)throw new AssertionError("leak");output.add(result);System.out.println(id+"/"+variation+" work="+budget.nodes());
  }}Files.writeString(Path.of(args[1]),new Gson().toJson(output));
 }
}
