package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.math.BigInteger;

/** Isolation experiment, never a proposed production replacement. */
public class KernelProbe {
    static Map<String,Object> direct(QuantitySweep.Catalog c, JsonObject g, long amount, boolean reduce) {
        var out=new LinkedHashMap<String,Object>();long started=System.nanoTime();
        var b=new PlanningBudget(5000,20_000_000,128L<<20,()->false,System::nanoTime);
        var counts=new LinkedHashMap<String,BigInteger>();
        try(var m=RecipeCountModel.create(c.compiler(),g.get("target").getAsString(),amount,QuantitySweep.amounts(g.getAsJsonObject("stock")),Map.of(),Set.of(),Set.of(),true,b);
            var bounds=new CountBounds(m.recipes.size(),m.constraints,b)) {
            while(!bounds.step()){}
            out.put("variables",m.recipes.size());out.put("rows",m.constraints.size());out.put("bound_work",b.nodes());
            if(bounds.blocked()) throw new AssertionError("Known-feasible model blocked");
            BigInteger[] result;
            if(reduce) {
                try(var view=new CountReduction(m.constraints,bounds.lowerBounds(),bounds.upperBounds(),b)) {
                    while(!view.step()){}
                    out.put("reduced_variables",view.variables());out.put("reduced_rows",view.rows().size());out.put("reduction_work",b.nodes());
                    try(var s=new CountLcg(view.rows(),view.lower(),view.upper(),b,2_000_000)) {
                        while(!s.step()){} result=view.expand(s.counts());out.put("infeasible",s.infeasible());
                    }
                }
            } else try(var s=new CountLcg(m.constraints,bounds.lowerBounds(),bounds.upperBounds(),b,2_000_000)) {
                while(!s.step()){}result=s.counts();out.put("infeasible",s.infeasible());
            }
            if(result!=null) for(int i=0;i<result.length;i++)counts.put(m.recipes.get(i).id(),result[i]);
        } catch(PlanningBudget.Exhausted e) {out.put("limit",e.limit().name());}
        long end=System.nanoTime();
        if(!counts.isEmpty())StabilityProbe.replay(c,g,amount,counts);
        out.put("verified",!counts.isEmpty());out.put("counts",counts);out.put("ms",(end-started)/1e6);out.put("work",b.nodes());out.put("peak_bytes",b.peakBytes());out.put("diagnostics",b.diagnostics());return out;
    }
    public static void main(String[] args)throws Exception {
        var gs=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        try(var writer=Files.newBufferedWriter(Path.of(args[1]))) {
            for(var e:gs) {
                var g=e.getAsJsonObject();var c=QuantitySweep.catalog(g.getAsJsonObject("catalog"));
                for(var req:g.getAsJsonArray("requests")) {
                    long n=req.getAsJsonObject().get("amount").getAsLong();var row=new LinkedHashMap<String,Object>();
                    row.put("id",g.get("id").getAsString());row.put("amount",n);
                    row.put("full",StabilityProbe.cgse(c,g,n,true,false));
                    row.put("counts_only",StabilityProbe.cgse(c,g,n,true,true));
                    row.put("direct_lcg",direct(c,g,n,false));row.put("reduced_lcg",direct(c,g,n,true));
                    writer.write(StabilityProbe.JSON.toJson(row));writer.newLine();writer.flush();System.out.println(row.get("id")+" "+n);
                }
            }
        }
    }
}
