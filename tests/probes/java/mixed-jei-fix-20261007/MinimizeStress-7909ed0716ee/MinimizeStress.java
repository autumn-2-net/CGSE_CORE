package org.cgse.core;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Reduce an observed catalog-extension regression while retaining an independently verified witness. */
public final class MinimizeStress {
    static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    static MixedSweep.Data manual;static JsonObject config,request;static BufferedWriter log;static int trials;static String order="manual-first";
    static MixedSweep.Data data(List<GraphRecipe<String>> list) {
        var producers=new LinkedHashMap<String,List<GraphRecipe<String>>>();
        for(var recipe:list)for(var key:recipe.executionOutputs().keySet())producers.computeIfAbsent(key,k->new ArrayList<>()).add(recipe);
        return new MixedSweep.Data(list,producers,Map.of(),Set.of());
    }
    static boolean bad(List<GraphRecipe<String>> extra)throws Exception {
        var compiler=MixedSweep.compiler(manual,data(extra),order,0);
        var row=MixedSweep.solve(manual,compiler,config,request);row.addProperty("trial",++trials);row.addProperty("extra_patterns",extra.size());
        log.write(JSON.toJson(row));log.newLine();log.flush();
        if(row.get("result").getAsString().equals("ERROR"))throw new AssertionError(row);
        boolean bad=!row.get("effective_feasible").getAsBoolean();
        System.out.printf("trial=%d extras=%d bad=%s main=%s effective=%s work=%s%n",trials,extra.size(),bad,row.get("result"),row.get("effective_result"),row.get("work"));
        return bad;
    }
    static JsonObject recipe(GraphRecipe<String> r) {
        JsonObject o=new JsonObject();o.addProperty("id",r.id());o.addProperty("binding",r.binding());JsonArray slots=new JsonArray();
        for(var s:r.slots()){JsonObject slot=new JsonObject();slot.addProperty("key",s.key());slot.addProperty("amount",Long.toString(s.amount()));slot.addProperty("input_slot",s.inputSlot());slot.addProperty("configuration",s.configuration());slot.addProperty("reusable",s.reusable());slots.add(slot);}
        o.add("slots",slots);o.add("outputs",JSON.toJsonTree(r.outputs()));return o;
    }
    public static void main(String[] args)throws Exception {
        Path area=Path.of(args[0]);manual=MixedSweep.read(area.resolve("manual.json"));var extra=MixedSweep.read(area.resolve("extra.json"));
        String target=args.length>1?args[1]:"{\"#c\":\"ae2:i\",id:\"gtceu:hpca_bridge_component\"}";long amount=args.length>2?Long.parseLong(args[2]):1000;
        if(args.length>3)order=args[3];
        request=new JsonObject();request.addProperty("id","hpca-bridge-1000");request.addProperty("target",target);request.addProperty("amount",amount);request.addProperty("dump",true);
        config=new JsonObject();config.addProperty("mode",order);config.addProperty("seed",0);config.addProperty("work",20_000_000L);config.addProperty("memory",128L<<20);config.addProperty("timeout",0);config.addProperty("fallback",true);
        var b=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);GraphPlan<String> witness;
        var work=new CatalystPlanningWork<String>(new CatalystPolicy(4096,64),b,p->new GraphPlanningWork<>(MixedSweep.compiler(manual,extra,"manual",0),target,amount,manual.stock(),manual.external(),Map.of(),true,true,b).catalysts(p));
        try {
            while(!work.step()){}witness=work.result();
        }finally {work.close();}
        if(!witness.feasible())throw new AssertionError("Baseline witness absent");PlanVerifier.verify(witness);PlanVerifier.verifyRuntimeInventory(witness);
        var known=new JsonObject();known.addProperty("verified",true);known.add("stock",JSON.toJsonTree(manual.stock()));known.add("request",request);known.add("initial",JSON.toJsonTree(witness.initialExact()));known.add("counts",JSON.toJsonTree(witness.patternTimesExact()));known.add("steps",JSON.toJsonTree(witness.steps()));
        var used=new JsonArray();witness.recipes().values().forEach(r->used.add(recipe(r)));known.add("recipes",used);Files.writeString(area.resolve("known-hpca-witness.json"),JSON.toJson(known));
        try(var writer=Files.newBufferedWriter(area.resolve("minimize.jsonl"))) {
            log=writer;List<GraphRecipe<String>> current=new ArrayList<>(extra.recipes());
            if(!bad(current)||!bad(current))throw new AssertionError("Cold reproduction not stable");
            int parts=2;
            while(!current.isEmpty()&&trials<110){
                int width=(current.size()+parts-1)/parts;boolean reduced=false;
                for(int start=0;start<current.size()&&trials<110;start+=width){
                    var candidate=new ArrayList<>(current);candidate.subList(start,Math.min(current.size(),start+width)).clear();
                    if(bad(candidate)){current=candidate;parts=Math.max(2,parts-1);reduced=true;break;}
                }
                if(!reduced){if(parts>=current.size())break;parts=Math.min(current.size(),parts*2);}
            }
            JsonArray output=new JsonArray();current.forEach(r->output.add(recipe(r)));JsonObject reduced=new JsonObject();reduced.add("recipes",output);reduced.add("request",request);reduced.add("config",config);reduced.addProperty("trials",trials);reduced.addProperty("one_minimal",trials<110);Files.writeString(area.resolve("reduced-extra.json"),JSON.toJson(reduced));
            if(!bad(current))throw new AssertionError("Reduced final replay lost failure");
            System.out.println("Reduced extra patterns "+current.size()+"; original witness recipes "+witness.recipes().size());
        }
    }
}
