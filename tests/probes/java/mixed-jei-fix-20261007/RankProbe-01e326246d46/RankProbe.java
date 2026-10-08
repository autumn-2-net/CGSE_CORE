package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public final class RankProbe {
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]), output=Path.of(args[1]);
        var manual=MixedSweep.read(input.resolve("manual.json"));var extra=MixedSweep.read(input.resolve("reduced-extra.json"));
        var data=JsonParser.parseString(Files.readString(input.resolve("reduced-extra.json"))).getAsJsonObject();var request=data.getAsJsonObject("request");String target=request.get("target").getAsString();long amount=request.get("amount").getAsLong();
        var baseline=MixedSweep.compiler(manual,extra,"manual",0).compile(target,Map.of(),Set.of(),budget());
        var results=new JsonArray();
        for(String mode:List.of("manual","manual-first","extra-first")) {
            var compiler=MixedSweep.compiler(manual,extra,mode,0);var b=budget();
            var proposals=new ArrayList<Map<String,Integer>>();proposals.add(Map.of());proposals.addAll(GraphSourceScout.choices(compiler,target,manual.stock(),manual.external(),Set.of(),true,b));
            var baselineLeaves=new LinkedHashMap<String,Integer>();
            for(var e:manual.stock().entrySet()) if(manual.producers().getOrDefault(e.getKey(),List.of()).isEmpty()&&e.getValue()>0&&!e.getKey().equals(target))baselineLeaves.put(e.getKey(),compiler.producers(e.getKey()).size());
            proposals.add(Map.copyOf(baselineLeaves));
            var stockLeaves=new LinkedHashMap<String,Integer>();
            for(var e:manual.stock().entrySet()) if(e.getValue()>0&&!e.getKey().equals(target))stockLeaves.put(e.getKey(),compiler.producers(e.getKey()).size());
            proposals.add(Map.copyOf(stockLeaves));
            int n=0;
            for(var proposal:proposals) {
                b=budget();var graph=compiler.compile(target,proposal,Set.of(),b);var row=new JsonObject();row.addProperty("mode",mode);row.addProperty("proposal",n++);row.add("choices",MixedSweep.JSON.toJsonTree(proposal));row.addProperty("recipes",graph.recipes().size());row.addProperty("max_cycle",graph.regions().stream().filter(GraphCompiler.Region::cyclic).mapToInt(r->r.recipes().size()).max().orElse(0));
                var differing=new JsonArray();
                for(var e:graph.selected().entrySet()) {var old=baseline.selected().get(e.getKey());if(old==null||!old.id().equals(e.getValue().id())){var d=new JsonObject();d.addProperty("key",e.getKey());d.addProperty("selected",e.getValue().id());d.addProperty("baseline",old==null?null:old.id());d.add("recipe",MinimizeStress.recipe(e.getValue()));if(old!=null)d.add("old_recipe",MinimizeStress.recipe(old));d.addProperty("stock",manual.stock().getOrDefault(e.getKey(),0L));differing.add(d);}}
                row.add("differences",differing);
                try {
                    var solve=new GraphSolve<>(graph,target,amount,manual.stock(),manual.external(),Map.of(),true,true,b,System.nanoTime(),new CatalystPolicy(4096,64),manual.stock());while(!solve.step()){}var plan=solve.result();row.addProperty("result",plan.result().name());row.add("missing",MixedSweep.JSON.toJsonTree(plan.missingExact()));row.add("initial",MixedSweep.JSON.toJsonTree(plan.initialExact()));row.add("counts",MixedSweep.JSON.toJsonTree(plan.patternTimesExact()));row.add("seeds",MixedSweep.JSON.toJsonTree(plan.seeds()));
                }catch(Throwable t){row.addProperty("error",t.toString());}
                row.addProperty("work",b.nodes());row.addProperty("diagnostics",b.diagnostics());results.add(row);System.out.println(mode+" proposal="+(n-1)+" choices="+proposal.size()+" differences="+differing.size()+" recipes="+graph.recipes().size()+" "+row.get("result")+" "+row.get("missing"));
            }
        }
        Files.writeString(output,MixedSweep.JSON.toJson(results));
    }
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);}
}
