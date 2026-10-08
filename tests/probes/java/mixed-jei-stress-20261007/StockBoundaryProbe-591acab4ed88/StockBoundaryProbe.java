package org.cgse.core;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Experimental local positive-only view. A failed restricted view proves nothing about the full catalog. */
public final class StockBoundaryProbe {
    public static void main(String[] args)throws Exception {
        Path area=Path.of(args[0]);String mode=args[1];var manual=MixedSweep.read(area.resolve("manual.json"));var extra=MixedSweep.read(area.resolve("extra.json"));
        var full=MixedSweep.compiler(manual,extra,mode,73);var config=JsonParser.parseString(Files.readString(area.resolve("cases/"+mode+"-default.json"))).getAsJsonObject();
        config.addProperty("work",131072);config.addProperty("memory",16L<<20);config.addProperty("timeout",250);config.addProperty("fallback",false);
        int passed=0,done=0;long started=System.nanoTime();
        try(var out=Files.newBufferedWriter(area.resolve("runs/"+mode+"-stock-boundary.jsonl"))) {
            for(var entry:config.getAsJsonArray("requests")) {
                var request=entry.getAsJsonObject();String target=request.get("target").getAsString();
                var producers=new LinkedHashMap<String,List<GraphRecipe<String>>>();
                var outputKeys=new LinkedHashSet<String>();for(var r:full.catalog())outputKeys.addAll(r.executionOutputs().keySet());
                for(var key:outputKeys)if(key.equals(target)||manual.stock().getOrDefault(key,0L)==0)producers.put(key,full.producers(key));
                // No full-catalog certificates/caches are imported or exported. All results are independently validated.
                var view=new GraphCompiler<>(full.catalog(),producers);var row=MixedSweep.solve(manual,view,config,request);
                row.addProperty("prototype",true);row.addProperty("positive_only",true);
                if(row.get("feasible").getAsBoolean())passed++;
                if(row.get("result").getAsString().equals("ERROR"))throw new AssertionError(row);
                out.write(MixedSweep.JSON.toJson(row));out.newLine();out.flush();done++;
            }
        }
        System.out.printf("stock boundary: mode=%s successes=%d/%d seconds=%.2f%n",mode,passed,done,(System.nanoTime()-started)/1e9);
    }
}
