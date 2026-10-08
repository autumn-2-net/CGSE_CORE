import org.cgse.core.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public class ChargeReplay {
    static Map<String,String> labels(Map<String,?> map) {var out=new TreeMap<String,String>();map.forEach((k,v)->out.put(DumpReplay.labels.getOrDefault(k,k),v.toString()));return out;}
    public static void main(String[] args)throws Exception {
        DumpReplay.main(new String[]{".local/ae-dumps/sky2-all.json",".local/strategy-scheduling/empty.jsonl","1","__none__"});
        var compiler=DumpReplay.compiler("k747");
        var previous=JsonParser.parseString(Files.readString(Path.of(".local/sky2-server/charge-comparison.json"))).getAsJsonArray().get(0).getAsJsonObject();
        var old=new HashSet<String>();previous.getAsJsonArray("patterns").forEach(p->old.add(p.getAsJsonObject().get("binding").getAsString()));
        for(String mode:args){
            var chosen=mode.equals("legacy-selected")?compiler.catalog().stream().filter(r->old.contains(r.binding())).toList():compiler.catalog();
            var b=new PlanningBudget(5000,8_000_000,128L<<20,()->false,System::nanoTime);long start=System.nanoTime();
            var work=new GraphPlanningWork<>(new GraphCompiler<>(chosen),"k747",Long.MAX_VALUE,DumpReplay.stock,DumpReplay.external,Map.of(),true,true,b).catalysts(mode.equals("lossy-stock")?CatalystPolicy.STOCK:CatalystPolicy.MINIMAL);
            while(!work.step()){}var p=work.result();
            System.out.printf("CHARGE %s recipes=%d result=%s selected=%d work=%d ms=%.3f%n",mode,chosen.size(),p.result(),p.patternTimesExact().size(),b.nodes(),(System.nanoTime()-start)/1e6);
            System.out.println("TRACE "+b.diagnostics());
            System.out.println("SEEDS "+labels(p.seeds()));
            if(!p.missingExact().isEmpty())PlanVerifier.verify(new GraphPlan<>(p.target(),p.amount(),p.preserveSeeds(),p.steps(),p.recipes(),p.initialExact(),p.seeds(),Map.of(),GraphPlan.Result.FEASIBLE,0,0));
            var row=new JsonObject();row.addProperty("result",p.result().name());row.add("missing",new Gson().toJsonTree(labels(p.missingExact())));row.add("seeds",new Gson().toJsonTree(labels(p.seeds())));
            var counts=new TreeMap<String,String>();p.patternTimesExact().forEach((k,v)->counts.put(p.recipes().get(k).binding(),v.toString()));row.add("counts",new Gson().toJsonTree(counts));
            Files.writeString(Path.of(".local/strategy-scheduling/charge-"+mode+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(row));
        }
    }
}
