package org.cgse.core;

import com.google.gson.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

public final class FallbackSweep {
    public static void main(String[] args) throws Exception {
        var groups = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        int total=0, feasible=0, missing=0, limited=0, errors=0;
        try(var output=Files.newBufferedWriter(Path.of(args[1]))) {
            for(var element:groups) {
                var group=element.getAsJsonObject();
                if(!group.has("catalog") || !group.has("known_feasible_max")) continue;
                var catalog=QuantitySweep.catalog(group.getAsJsonObject("catalog"));
                var compiler=catalog.compiler();
                var stock=group.has("stock")?QuantitySweep.amounts(group.getAsJsonObject("stock")):catalog.stock();
                for(var requestElement:group.getAsJsonArray("requests")) {
                    var request=requestElement.getAsJsonObject();
                    long amount=request.get("amount").getAsLong();
                    if(amount>group.get("known_feasible_max").getAsLong()) continue;
                    String target=group.get("target").getAsString();
                    var budget=new PlanningBudget(0,131072,16L<<20,()->false,System::nanoTime);
                    var row=new JsonObject(); row.addProperty("group",group.get("id").getAsString());
                    row.addProperty("target",target); row.addProperty("amount",amount); total++;
                    long started=System.nanoTime();
                    try {
                        var plan=GraphFallback.plan(compiler,target,amount,stock,catalog.external(),Map.of(),true,true,budget);
                        row.addProperty("result",plan.result().name());
                        if(plan.feasible()) {
                            PlanVerifier.verifyRuntimeInventory(plan);
                            plan.initialExact().forEach((key,need)->{
                                if(!catalog.external().contains(key)&&need.compareTo(BigInteger.valueOf(stock.getOrDefault(key,0L)))>0)
                                    throw new AssertionError("Unfunded "+key+" "+need);
                            });
                            BigInteger made=BigInteger.ZERO;
                            for(var entry:plan.patternTimesExact().entrySet())
                                made=made.add(entry.getValue().multiply(BigInteger.valueOf(plan.recipes().get(entry.getKey()).executionOutputs().getOrDefault(target,0L))));
                            if(made.compareTo(BigInteger.valueOf(amount))<0 && !catalog.external().contains(target))
                                throw new AssertionError("Insufficient fresh output "+made);
                            feasible++;
                        } else { missing++; row.add("missing",QuantitySweep.JSON.toJsonTree(plan.missingExact())); }
                    } catch(PlanningBudget.Exhausted exhausted) {
                        limited++; row.addProperty("result",exhausted.limit().name());
                    } catch(Throwable error) {
                        errors++; row.addProperty("result","ERROR"); row.addProperty("error",error.toString());
                    }
                    row.addProperty("work",budget.nodes()); row.addProperty("memory",budget.peakBytes());
                    row.addProperty("ms",(System.nanoTime()-started)/1e6);
                    output.write(row.toString());output.newLine();
                }
            }
        }
        System.out.println("TOTAL="+total+" FEASIBLE="+feasible+" MISSING="+missing+" LIMITED="+limited+" ERRORS="+errors);
        if(errors>0)throw new AssertionError("Fallback sweep errors="+errors);
    }
}
