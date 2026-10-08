package org.cgse.core;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public final class ReplayMiracle {
    static Map<String,Long> amounts(JsonObject in) {
        Map<String,Long> out=new LinkedHashMap<>();in.entrySet().forEach(e->out.put(e.getKey(),e.getValue().getAsLong()));return out;
    }
    public static void main(String[] args) throws Exception {
        var json=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        List<GraphRecipe<String>> recipes=new ArrayList<>();Map<String,GraphRecipe<String>> byId=new LinkedHashMap<>();
        for(var entry:json.getAsJsonArray("recipes")) {
            var r=entry.getAsJsonObject();List<GraphRecipe.Slot<String>> slots=new ArrayList<>();
            for(var s:r.getAsJsonArray("slots")) {var v=s.getAsJsonObject();slots.add(new GraphRecipe.Slot<>(v.get("key").getAsString(),v.get("amount").getAsLong(),v.get("input_slot").getAsInt(),v.get("configuration").getAsBoolean(),v.get("reusable").getAsBoolean()));}
            var recipe=new GraphRecipe<>(r.get("id").getAsString(),r.get("binding").getAsString(),slots,amounts(r.getAsJsonObject("outputs")));
            recipes.add(recipe);byId.put(recipe.id(),recipe);
        }
        Map<String,List<GraphRecipe<String>>> producers=new LinkedHashMap<>();
        json.getAsJsonObject("producers").entrySet().forEach(e->{List<GraphRecipe<String>> list=new ArrayList<>();e.getValue().getAsJsonArray().forEach(id->list.add(byId.get(id.getAsString())));producers.put(e.getKey(),list);});
        var stock=amounts(json.getAsJsonObject("stock"));Set<String> external=new LinkedHashSet<>();json.getAsJsonArray("external").forEach(e->external.add(e.getAsString()));
        var compiler=new GraphCompiler<>(List.copyOf(recipes),producers);
        for(int i=2;i<args.length;i++) {
            String target=args[1].equals("snapshot")?json.get("target").getAsString():args[1];
            long amount=Long.parseLong(args[i]);
            var budget=new PlanningBudget(0,Long.getLong("work",20_000_000L),Long.getLong("memory",1024L*1024*1024),()->false,System::nanoTime);
            var policy=new CatalystPolicy(Integer.getInteger("parallel",json.get("parallelism").getAsInt()),json.get("max_extra_copies").getAsInt());
            var work=new CatalystPlanningWork<String>(policy,budget,p->new GraphPlanningWork<>(compiler,target,amount,stock,external,Map.of(),true,true,budget).catalysts(p));
            long begin=System.nanoTime();GraphPlan<String> plan;
            try {
                try {while(!work.step()){}plan=work.result();} catch(PlanningBudget.Exhausted exhausted) {plan=work.limited(exhausted);}
                if(plan.feasible())PlanVerifier.verifyRuntimeInventory(plan);
                var out=new JsonObject();out.addProperty("result",plan.result().toString());out.addProperty("target",target);out.addProperty("amount",Long.toString(amount));out.addProperty("work",budget.nodes());out.addProperty("peak_bytes",budget.peakBytes());out.addProperty("ms",(System.nanoTime()-begin)/1e6);
                var gson=new GsonBuilder().disableHtmlEscaping().create();out.add("missing",gson.toJsonTree(plan.missingExact()));out.add("initial",gson.toJsonTree(plan.initialExact()));out.add("seeds",gson.toJsonTree(plan.seeds()));out.add("counts",gson.toJsonTree(plan.patternTimesExact()));out.addProperty("diagnostics",budget.diagnostics());
                System.out.println(gson.toJson(out));
            } finally {work.close();}
        }
    }
}
