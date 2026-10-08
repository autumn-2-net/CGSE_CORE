package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public class SourceProbe {
    public static void main(String[] args) throws Exception {
        var groups=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        for(var e:groups){var group=e.getAsJsonObject();if(!group.get("id").getAsString().equals(args[1]))continue;
            var cat=QuantitySweep.catalog(group.getAsJsonObject("catalog"));var compiler=cat.compiler();
            var stock=QuantitySweep.amounts(group.getAsJsonObject("stock"));String target=group.get("target").getAsString();
            long amount=Long.parseLong(args[2]);
            var budget=new PlanningBudget(0,200_000_000L,1024L<<20,()->false,System::nanoTime);
            var graph=compiler.compile(target,Map.of(),Set.of(),budget);
            var solve=new GraphSolve<>(graph,target,amount,stock,cat.external(),Map.of(),true,true,budget,System.nanoTime(),new CatalystPolicy(4096,64),stock);
            while(!solve.step()){}var plan=solve.result();
            System.out.println("BASE result="+plan.result()+" work="+budget.nodes()+" missing="+plan.missingExact());
            var counts=plan.patternTimesExact();
            Map<String,List<String>> consumers=new LinkedHashMap<>();
            for(var selected:graph.selected().entrySet()) {
                var recipe=selected.getValue();if(!counts.containsKey(recipe.id()))continue;
                for(var input:recipe.inputs().keySet()) consumers.computeIfAbsent(input,k->new ArrayList<>()).add(selected.getKey());
            }
            var distance=new LinkedHashMap<String,Integer>();var pending=new ArrayDeque<String>();
            for(var key:plan.missingExact().keySet()){distance.put(key,0);pending.add(key);}
            List<String> keys=new ArrayList<>();
            while(!pending.isEmpty()) {
                String key=pending.removeFirst();
                if(graph.selected().containsKey(key)&&compiler.producers(key).size()>1)keys.add(key);
                for(var output:consumers.getOrDefault(key,List.of()))if(!distance.containsKey(output)){distance.put(output,distance.get(key)+1);pending.add(output);}
            }
            System.out.println("RELATED alternatives="+keys.size()+" all="+graph.selected().keySet().stream().filter(k->compiler.producers(k).size()>1).count());
            for(String key:keys.subList(0,Math.min(30,keys.size()))) {
                var alternatives=compiler.producers(key);
                var b=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);
                var changed=compiler.compile(target,Map.of(key,1),Set.of(),b);
                var trial=new GraphSolve<>(changed,target,amount,stock,cat.external(),Map.of(),true,true,b,System.nanoTime(),new CatalystPolicy(4096,64),stock);
                try{while(!trial.step()){}var p=trial.result();System.out.println("TRY distance="+distance.get(key)+" key="+key+" alternatives="+alternatives.size()+" result="+p.result()+" missing="+p.missingExact()+" work="+b.nodes());}
                catch(Exception x){System.out.println("ERROR "+x);}
            }
            JsonObject out=new JsonObject();out.add("missing",QuantitySweep.JSON.toJsonTree(plan.missingExact()));out.add("related",QuantitySweep.JSON.toJsonTree(keys));
            out.add("selected",QuantitySweep.JSON.toJsonTree(graph.selected().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,x->x.getValue().id()))));
            Files.writeString(Path.of(args[3]),QuantitySweep.JSON.toJson(out));
        }
    }
}
