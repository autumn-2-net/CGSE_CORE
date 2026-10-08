package org.cgse.core;

import com.google.gson.*;
import java.nio.file.*;
import java.math.BigInteger;
import java.util.*;

public final class FixedReplay {
    record Summary(Map<String,BigInteger> need, Map<String,BigInteger> delta) {}
    static Map<String,Long> longs(JsonObject o) {
        Map<String,Long> r=new LinkedHashMap<>();
        o.entrySet().forEach(e->r.put(e.getKey(),e.getValue().getAsLong()));return r;
    }
    static Summary summary(PlanStep s,Map<String,GraphRecipe<String>> recipes,IdentityHashMap<PlanStep,Summary> memo) {
        Summary old=memo.get(s);if(old!=null)return old;
        Map<String,BigInteger> n=new LinkedHashMap<>(),d=new LinkedHashMap<>();
        if(s instanceof PlanStep.Batch b){
            var r=recipes.get(b.recipe());BigInteger times=BigInteger.valueOf(b.runs());
            Set<String> keys=new HashSet<>(r.inputs().keySet());keys.addAll(r.outputs().keySet());
            if(times.signum()>0)for(String k:keys){
                BigInteger input=BigInteger.valueOf(r.inputs().getOrDefault(k,0L));
                BigInteger delta=BigInteger.valueOf(r.outputs().getOrDefault(k,0L)).subtract(input);
                n.put(k,input.add(delta.negate().max(BigInteger.ZERO).multiply(times.subtract(BigInteger.ONE))));
                d.put(k,delta.multiply(times));
            }
        }else if(s instanceof PlanStep.Repeat r){
            Summary b=summary(r.body(),recipes,memo);BigInteger times=BigInteger.valueOf(r.times());
            if(times.signum()>0)for(String k:b.need.keySet()){
                BigInteger delta=b.delta.getOrDefault(k,BigInteger.ZERO);
                n.put(k,b.need.get(k).add(delta.negate().max(BigInteger.ZERO).multiply(times.subtract(BigInteger.ONE))));
                d.put(k,delta.multiply(times));
            }
        }else for(PlanStep child:((PlanStep.Sequence)s).children()){
            Summary b=summary(child,recipes,memo);
            b.need.forEach((k,v)->n.merge(k,v.subtract(d.getOrDefault(k,BigInteger.ZERO)).max(BigInteger.ZERO),BigInteger::max));
            b.delta.forEach((k,v)->d.merge(k,v,BigInteger::add));
        }
        Summary result=new Summary(n,d);memo.put(s,result);return result;
    }
    public static void main(String[] args)throws Exception{
        Path folder=Path.of(args[0]);long ms=Long.parseLong(args[1]);String filter=args.length>2?args[2]:".*";
        Gson json=new Gson();
        try(var paths=Files.walk(folder)){
            for(Path path:paths.filter(p->p.toString().endsWith(".json")).sorted().toList()){
                JsonObject o=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if(!o.has("recipes")||!o.has("stock")||!o.get("name").getAsString().matches(filter))continue;
                String name=o.get("name").getAsString(), target=o.get("target").getAsString();
                Map<String,Long> stock=longs(o.getAsJsonObject("stock"));long amount=o.get("amount").getAsLong();
                List<GraphRecipe<String>> recipes=new ArrayList<>();
                for(JsonElement e:o.getAsJsonArray("recipes")){
                    var r=e.getAsJsonObject();String id=r.get("id").getAsString();
                    recipes.add(new GraphRecipe<>(id,id,longs(r.getAsJsonObject("inputs")).entrySet().stream().map(t->new GraphRecipe.Slot<>(t.getKey(),t.getValue())).toList(),longs(r.getAsJsonObject("outputs"))));
                }
                String truth="UNKNOWN";
                if(o.has("detail"))try{truth=JsonParser.parseString(o.get("detail").getAsString()).getAsJsonObject().get("truth").getAsString();}catch(Exception ignored){}
                long maxWork=args.length>3?Long.parseLong(args[3]):20_000_000;
                long maxMemory=args.length>4?Long.parseLong(args[4])<<20:256L<<20;
                PlanningBudget b=new PlanningBudget(ms,maxWork,maxMemory,()->false,System::nanoTime);
                long start=System.nanoTime();GraphPlan<String> p=new GraphPlanner<>(new GraphCompiler<>(recipes)).plan(target,amount,stock,true,true,b);
                double elapsed=(System.nanoTime()-start)/1e6;
                IdentityHashMap<PlanStep,Summary> memo=new IdentityHashMap<>();
                if(p.feasible()||!p.missingExact().isEmpty()){
                    Summary s=summary(p.steps(),p.recipes(),memo);
                    Map<String,BigInteger> funded=new HashMap<>();stock.forEach((k,v)->funded.put(k,BigInteger.valueOf(v)));
                    p.missingExact().forEach((k,v)->funded.merge(k,v,BigInteger::add));
                    for(var e:s.need.entrySet())if(funded.getOrDefault(e.getKey(),BigInteger.ZERO).compareTo(e.getValue())<0)throw new AssertionError(name+" prefix "+e);
                    if(funded.getOrDefault(target,BigInteger.ZERO).add(s.delta.getOrDefault(target,BigInteger.ZERO)).compareTo(BigInteger.valueOf(amount))<0)throw new AssertionError(name+" goal");
                    for(var e:p.seeds().entrySet())if(funded.getOrDefault(e.getKey(),BigInteger.ZERO).add(s.delta.getOrDefault(e.getKey(),BigInteger.ZERO)).compareTo(BigInteger.valueOf(e.getValue()))<0)throw new AssertionError(name+" seed");
                }
                if(truth.equals("UNSAT")&&p.feasible()||truth.equals("SAT")&&(p.result()==GraphPlan.Result.INFEASIBLE||p.result()==GraphPlan.Result.MISSING_INPUT||p.result()==GraphPlan.Result.MISSING_SEED))throw new AssertionError(name+" false conclusion "+p.result());
                System.out.println(json.toJson(Map.of("case",name,"status",p.result().name(),"ms",elapsed,"nodes",b.nodes(),"program_nodes",memo.size(),"trace",b.diagnostics().toString())));
            }
        }
    }
}
