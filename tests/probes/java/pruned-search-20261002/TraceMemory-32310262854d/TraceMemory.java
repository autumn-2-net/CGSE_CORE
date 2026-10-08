package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
public class TraceMemory {
    static Object field(Object o,String name)throws Exception {if(o==null)return null;var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static int size(Object o){return o instanceof Map<?,?> m?m.size():o instanceof Collection<?> c?c.size():-1;}
    static void state(GraphPlanningWork<?> work,PlanningBudget budget)throws Exception{
        var o=new JsonObject();o.addProperty("work",budget.nodes());o.addProperty("bytes",budget.reservedBytes());
        for(String n:List.of("phase","sourceAttempts","allocationTurnStarted","allocationTurnAllowance"))o.addProperty(n,String.valueOf(field(work,n)));
        for(String n:List.of("seen","pending"))o.addProperty(n,size(field(work,n)));
        Object a=field(work,"allocating");if(a!=null){var v=new JsonObject();v.addProperty("bytes",(long)field(a,"memory"));
            for(String n:List.of("phase","index"))v.addProperty(n,String.valueOf(field(a,n)));
            for(String n:List.of("stack","undo","visited"))v.addProperty(n,size(field(a,n)));
            long labels=0,bytes=0;for(var values:((Map<?,?>)field(a,"visited")).values())for(var b:(List<BitSet>)values){labels++;bytes+=96+b.toLongArray().length*8L;}
            v.addProperty("labels",labels);v.addProperty("live_label_bytes",bytes);o.add("allocation",v);}
        for(String n:List.of("parkedCounts","countSearch")){Object c=field(work,n);if(c==null)continue;var v=new JsonObject();
            for(String f:List.of("work","allowance","branches","rounds","paused"))v.addProperty(f,String.valueOf(field(c,f)));
            for(String f:List.of("pending","deferred"))v.addProperty(f,size(field(c,f)));o.add(n,v);}
        System.out.println(o);
    }
    public static void main(String[] args)throws Exception{
        var g=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray().get(0).getAsJsonObject();
        var cat=QuantitySweep.catalog(g.getAsJsonObject("catalog"));var budget=new PlanningBudget(0,20000000,128L<<20,()->false,System::nanoTime);
        var compiler=cat.compiler();var root=new GraphPlanningWork<>(compiler,g.get("target").getAsString(),Long.parseLong(args[1]),QuantitySweep.amounts(g.getAsJsonObject("stock")),cat.external(),Map.of(),true,true,budget).catalysts(new CatalystPolicy(4096,64));
        long next=0;int old=-1;
        try{
            while(!root.step()){
                int phase=(int)field(root,"phase");
                if(phase!=old||budget.nodes()>=next){state(root,budget);old=phase;next=budget.nodes()+250000;}
            }
            state(root,budget);System.out.println(root.result().result());System.out.println(budget.diagnostics());
        }finally{root.close();}
    }
}
