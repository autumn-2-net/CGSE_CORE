package org.cgse.core;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.math.BigInteger;

/** Local offline catalog replay. No Minecraft classes or saved world writes. */
public final class MixedSweep {
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    static final StringPool KEYS = new StringPool();
    static final class StringPool { final Map<String,String> values = new HashMap<>(); String key(String s) {return values.computeIfAbsent(s,k->k);} }
    static Map<String,Long> amounts(JsonObject object) {
        var result=new LinkedHashMap<String,Long>(); object.entrySet().forEach(e->result.put(KEYS.key(e.getKey()),e.getValue().getAsLong())); return result;
    }
    record Data(List<GraphRecipe<String>> recipes, Map<String,List<GraphRecipe<String>>> producers, Map<String,Long> stock,Set<String> external) {}
    static Data read(Path path) throws Exception {
        JsonObject object=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        var recipes=new ArrayList<GraphRecipe<String>>(); var byId=new HashMap<String,GraphRecipe<String>>();
        for(var element:object.getAsJsonArray("recipes")) {
            var o=element.getAsJsonObject(); var slots=new ArrayList<GraphRecipe.Slot<String>>();
            for(var se:o.getAsJsonArray("slots")) {
                var s=se.getAsJsonObject(); slots.add(new GraphRecipe.Slot<>(KEYS.key(s.get("key").getAsString()),s.get("amount").getAsLong(),
                    s.has("input_slot")?s.get("input_slot").getAsInt():slots.size(),s.has("configuration")&&s.get("configuration").getAsBoolean(),s.has("reusable")&&s.get("reusable").getAsBoolean()));
            }
            String id=o.get("id").getAsString(); var recipe=new GraphRecipe<>(id,o.has("binding")?o.get("binding").getAsString():id,slots,amounts(o.getAsJsonObject("outputs")));
            if(byId.put(id,recipe)!=null)throw new IllegalArgumentException("Duplicate recipe "+id); recipes.add(recipe);
        }
        var producers=new LinkedHashMap<String,List<GraphRecipe<String>>>();
        if(object.has("producers")) object.getAsJsonObject("producers").entrySet().forEach(e->{
            var list=new ArrayList<GraphRecipe<String>>();e.getValue().getAsJsonArray().forEach(v->list.add(Objects.requireNonNull(byId.get(v.getAsString()))));producers.put(KEYS.key(e.getKey()),list);
        }); else for(var recipe:recipes) for(var key:recipe.executionOutputs().keySet())producers.computeIfAbsent(key,k->new ArrayList<>()).add(recipe);
        Set<String> external=new HashSet<>(); if(object.has("external"))object.getAsJsonArray("external").forEach(v->external.add(KEYS.key(v.getAsString())));
        return new Data(recipes,producers,object.has("stock")?amounts(object.getAsJsonObject("stock")):Map.of(),external);
    }
    static GraphCompiler<String> compiler(Data manual,Data extra,String mode,long seed) {
        if(mode.equals("manual"))return new GraphCompiler<>(manual.recipes,manual.producers);
        var all=new ArrayList<>(manual.recipes);all.addAll(extra.recipes);
        if(mode.equals("random")) {Collections.shuffle(all,new Random(seed));return new GraphCompiler<>(all);}
        var producers=new LinkedHashMap<String,List<GraphRecipe<String>>>();
        Set<String> keys=new LinkedHashSet<>(manual.producers.keySet());keys.addAll(extra.producers.keySet());
        for(var key:keys) {
            var list=new ArrayList<GraphRecipe<String>>();
            list.addAll((mode.equals("manual-first")?manual:extra).producers.getOrDefault(key,List.of()));
            list.addAll((mode.equals("manual-first")?extra:manual).producers.getOrDefault(key,List.of()));producers.put(key,list);
        }
        return new GraphCompiler<>(all,producers);
    }
    static JsonObject solve(Data data,GraphCompiler<String> compiler,JsonObject config,JsonObject request) {
        String target=KEYS.key(request.get("target").getAsString());long amount=request.get("amount").getAsLong();
        long limit=config.get("work").getAsLong(),memory=config.get("memory").getAsLong(),timeout=config.get("timeout").getAsLong();
        var budget=new PlanningBudget(timeout,limit,memory,()->false,System::nanoTime);
        var row=request.deepCopy();row.addProperty("mode",config.get("mode").getAsString());row.addProperty("seed",config.get("seed").getAsLong());
        row.addProperty("work_limit",limit);row.addProperty("memory_limit",memory);row.addProperty("timeout",timeout);
        long start=System.nanoTime();
        var work=new CatalystPlanningWork<String>(new CatalystPolicy(4096,64),budget,
            policy->new GraphPlanningWork<>(compiler,target,amount,data.stock,data.external,Map.of(),true,true,budget).catalysts(policy));
        try {
            GraphPlan<String> plan;
            try {while(!work.step()){}plan=work.result();}catch(PlanningBudget.Exhausted exhausted){plan=work.limited(exhausted);}
            row.addProperty("result",plan.result().name());row.addProperty("feasible",plan.feasible());
            row.add("missing",JSON.toJsonTree(plan.missingExact()));row.addProperty("recipes",plan.patternTimesExact().size());
            row.addProperty("seed_types",plan.seeds().size());
            if(plan.feasible()) {
                PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);
                plan.initialExact().forEach((key,needed)->{if(!data.external.contains(key)&&needed.compareTo(BigInteger.valueOf(data.stock.getOrDefault(key,0L)))>0)throw new AssertionError("Overdraw "+key);});
                plan.executionDependencies();row.addProperty("verified",true);
                long extras=plan.patternTimesExact().keySet().stream().filter(id->id.startsWith("jei:")).count();row.addProperty("extra_recipe_types",extras);
            }
            if(request.has("dump")&&request.get("dump").getAsBoolean()) {
                row.add("initial",JSON.toJsonTree(plan.initialExact()));row.add("counts",JSON.toJsonTree(plan.patternTimesExact()));row.add("seeds",JSON.toJsonTree(plan.seeds()));
            }
        }catch(Throwable ex) {
            row.addProperty("result","ERROR");row.addProperty("feasible",false);StringWriter sw=new StringWriter();ex.printStackTrace(new PrintWriter(sw));row.addProperty("error",sw.toString());
        }finally {work.close();}
        row.addProperty("ms",(System.nanoTime()-start)/1e6);row.addProperty("work",budget.nodes());row.addProperty("peak_bytes",budget.peakBytes());row.addProperty("diagnostics",budget.diagnostics());
        row.addProperty("effective_feasible",row.get("feasible").getAsBoolean());row.add("effective_result",row.get("result"));
        if(config.has("fallback")&&config.get("fallback").getAsBoolean()&&!row.get("feasible").getAsBoolean()&&
            Set.of("UNKNOWN","INFEASIBLE","TIMEOUT","SEARCH_LIMIT","MEMORY_LIMIT","GRAPH_LIMIT").contains(row.get("result").getAsString())) {
            JsonObject fallback=fallback(data,compiler,target,amount);row.add("fallback",fallback);
            if(fallback.has("feasible")) {row.add("effective_feasible",fallback.get("feasible"));row.add("effective_result",fallback.get("result"));}
        }
        return row;
    }
    static JsonObject fallback(Data data,GraphCompiler<String> compiler,String target,long amount) {
        var row=new JsonObject();long start=System.nanoTime();var budget=new PlanningBudget(250,131_072,16L<<20,()->false,System::nanoTime);
        try {
            var plan=GraphFallback.plan(compiler,target,amount,data.stock,data.external,Map.of(),true,true,budget);
            row.addProperty("result",plan.result().name());row.addProperty("feasible",plan.feasible());row.add("missing",JSON.toJsonTree(plan.missingExact()));
            if(plan.feasible()) {
                PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);
                plan.initialExact().forEach((key,needed)->{if(!data.external.contains(key)&&needed.compareTo(BigInteger.valueOf(data.stock.getOrDefault(key,0L)))>0)throw new AssertionError("Fallback overdraw "+key);});
                row.addProperty("verified",true);
            }
        }catch(PlanningBudget.Exhausted e){row.addProperty("result",e.limit().name());}
        catch(Throwable e){row.addProperty("result","ERROR");StringWriter sw=new StringWriter();e.printStackTrace(new PrintWriter(sw));row.addProperty("error",sw.toString());}
        row.addProperty("work",budget.nodes());row.addProperty("ms",(System.nanoTime()-start)/1e6);row.addProperty("peak_bytes",budget.peakBytes());return row;
    }
    public static void main(String[] args)throws Exception {
        Path base=Path.of(args[0]);JsonObject config=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        Data manual=read(base.resolve("manual.json")), extra=read(base.resolve(config.has("extra_catalog")?config.get("extra_catalog").getAsString():"extra.json"));
        var compiler=compiler(manual,extra,config.get("mode").getAsString(),config.get("seed").getAsLong());
        int done=0,errors=0,feasible=0;long start=System.nanoTime();
        System.out.println("loaded recipes="+compiler.catalog().size()+" resources="+KEYS.values.size()+" config="+args[1]);
        try(var writer=Files.newBufferedWriter(Path.of(args[2]))) {
            for(var r:config.getAsJsonArray("requests")) {
                JsonObject request=r.getAsJsonObject();
                if(config.has("cold")&&config.get("cold").getAsBoolean())compiler=compiler(manual,extra,config.get("mode").getAsString(),config.get("seed").getAsLong());
                JsonObject row;
                if(config.has("fallback_only")&&config.get("fallback_only").getAsBoolean()){
                    row=fallback(manual,compiler,request.get("target").getAsString(),request.get("amount").getAsLong());
                    for(var e:request.entrySet())row.add(e.getKey(),e.getValue());row.add("mode",config.get("mode"));row.add("seed",config.get("seed"));
                }else row=solve(manual,compiler,config,request);
                writer.write(JSON.toJson(row));writer.newLine();writer.flush();
                if(row.get("result").getAsString().equals("ERROR")||row.has("fallback")&&row.getAsJsonObject("fallback").get("result").getAsString().equals("ERROR"))errors++;
                if(row.has("feasible")&&row.get("feasible").getAsBoolean())feasible++;
                if(++done%50==0)System.out.printf("done=%d feasible=%d errors=%d elapsed_s=%.1f last=%s%n",done,feasible,errors,(System.nanoTime()-start)/1e9,request.get("id"));
            }
        }
        System.out.printf("completed=%d feasible=%d errors=%d elapsed_s=%.1f%n",done,feasible,errors,(System.nanoTime()-start)/1e9);
        if(errors>0)throw new AssertionError("Invalid/exceptional plans: "+errors);
    }
}
