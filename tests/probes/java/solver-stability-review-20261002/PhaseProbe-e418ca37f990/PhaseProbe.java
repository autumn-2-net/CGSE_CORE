package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public class PhaseProbe {
    public static void main(String[] args)throws Exception {
        var gs=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        var pf=GraphPlanningWork.class.getDeclaredField("phase");pf.setAccessible(true);
        try(var writer=Files.newBufferedWriter(Path.of(args[1]))) {
            for(int rep=0;rep<2;rep++)for(var e:gs) {
                var g=e.getAsJsonObject();var c=QuantitySweep.catalog(g.getAsJsonObject("catalog"));
                for(var req:g.getAsJsonArray("requests")) {
                    long n=req.getAsJsonObject().get("amount").getAsLong(),start=System.nanoTime();
                    var b=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);b.enableMetrics();
                    var work=new GraphPlanningWork<>(c.compiler(),g.get("target").getAsString(),n,QuantitySweep.amounts(g.getAsJsonObject("stock")),c.external(),Map.of(),true,true,b).catalysts(new CatalystPolicy(c.parallel(),c.extra()));
                    Map<Integer,long[]> phases=new LinkedHashMap<>();var row=new LinkedHashMap<String,Object>();
                    try {
                        while(true) {
                            int p=pf.getInt(work);long before=b.nodes(),at=System.nanoTime();boolean done=work.step();
                            var stats=phases.computeIfAbsent(p,k->new long[3]);stats[0]+=System.nanoTime()-at;stats[1]+=b.nodes()-before;stats[2]++;
                            if(done)break;
                        }
                        var plan=work.result();row.put("result",plan.result());row.put("verified",plan.feasible());
                        if(plan.feasible()){PlanVerifier.verifyRuntimeInventory(plan);StabilityProbe.replay(c,g,n,plan.patternTimesExact());}
                    }finally{work.close();}
                    row.put("id",g.get("id").getAsString());row.put("amount",n);row.put("repeat",rep);row.put("ms",(System.nanoTime()-start)/1e6);row.put("work",b.nodes());row.put("phases_ns_work_steps",phases);row.put("strategies",b.metrics().strategies());row.put("diagnostics",b.diagnostics());
                    writer.write(StabilityProbe.JSON.toJson(row));writer.newLine();writer.flush();System.out.println(row.get("id")+" "+n+" "+row.get("result"));
                }
            }
        }
    }
}
