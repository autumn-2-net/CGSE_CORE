package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public final class InspectFirstChoice {
 public static void main(String[] args)throws Exception{
    var json=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();List<GraphRecipe<String>> recipes=new ArrayList<>();Map<String,GraphRecipe<String>> ids=new LinkedHashMap<>();
    for(var entry:json.getAsJsonArray("recipes")){var r=entry.getAsJsonObject();List<GraphRecipe.Slot<String>> slots=new ArrayList<>();for(var s:r.getAsJsonArray("slots")){var v=s.getAsJsonObject();slots.add(new GraphRecipe.Slot<>(v.get("key").getAsString(),v.get("amount").getAsLong(),v.get("input_slot").getAsInt(),v.get("configuration").getAsBoolean(),v.get("reusable").getAsBoolean()));}var x=new GraphRecipe<>(r.get("id").getAsString(),r.get("binding").getAsString(),slots,ReplayMiracle.amounts(r.getAsJsonObject("outputs")));recipes.add(x);ids.put(x.id(),x);}
    Map<String,List<GraphRecipe<String>>> sources=new LinkedHashMap<>();json.getAsJsonObject("producers").entrySet().forEach(e->{List<GraphRecipe<String>> list=new ArrayList<>();e.getValue().getAsJsonArray().forEach(id->list.add(ids.get(id.getAsString())));sources.put(e.getKey(),list);});
    var compiler=new GraphCompiler<>(List.copyOf(recipes),sources);var stock=ReplayMiracle.amounts(json.getAsJsonObject("stock"));Set<String> external=new HashSet<>();json.getAsJsonArray("external").forEach(v->external.add(v.getAsString()));String target=json.get("target").getAsString();long amount=Long.parseLong(args[1]);
    for(var policy:List.of(new CatalystPolicy(4096,64),new CatalystPolicy(4096,0),CatalystPolicy.MINIMAL)){
      var b=new PlanningBudget(0,20_000_000,1024L<<20,()->false,System::nanoTime);var graph=compiler.compile(target,Map.of(),Set.of(),b);var solve=new GraphSolve<>(graph,target,amount,stock,external,Map.of(),true,true,b,System.nanoTime(),policy,stock);while(!solve.step()){}var plan=solve.result();var out=new JsonObject();var gson=new GsonBuilder().disableHtmlEscaping().create();out.addProperty("policy",policy.toString());out.addProperty("result",plan.result().toString());out.add("missing",gson.toJsonTree(plan.missingExact()));out.add("seeds",gson.toJsonTree(plan.seeds()));out.addProperty("work",b.nodes());out.addProperty("diagnostics",b.diagnostics());System.out.println(gson.toJson(out));
    }
 }
}
