package org.cgse.core;
import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import java.lang.management.ManagementFactory;
public final class StartupComparison {
    public static void main(String[] args)throws Exception{
        Method close=null;try{close=MissingStockAnalysis.class.getDeclaredMethod("close");close.setAccessible(true);}catch(NoSuchMethodException old){}
        var rs=new ArrayList<GraphRecipe<String>>();for(int i=0;i<2000;i++)rs.add(new GraphRecipe<>("r"+i,"r"+i,List.of(new GraphRecipe.Slot<>("k"+i,1)),Map.of("k"+(i+1),1L)));
        var compiler=new GraphCompiler<>(rs);long work=0,peak=0,bytes=0,nanos=0;var allocation=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();long thread=Thread.currentThread().getId();
        for(int repetition=0;repetition<64;repetition++){
            var b=new PlanningBudget(0,1_000_000,128L<<20,()->false,System::nanoTime);long allocated=allocation.getThreadAllocatedBytes(thread),start=System.nanoTime();
            var analysis=new MissingStockAnalysis<>(compiler,"k2000",Map.of("k0",1L),Set.of(),Set.of(),Set.of(),true,b);try{while(!analysis.step()){}if(analysis.blocked())throw new AssertionError("chain blocked");}finally{if(close!=null)close.invoke(analysis);}
            if(b.reservedBytes()!=0)throw new AssertionError("complete leak");if(repetition>=4){work+=b.nodes();peak=Math.max(peak,b.peakBytes());bytes+=allocation.getThreadAllocatedBytes(thread)-allocated;nanos+=System.nanoTime()-start;}
        }
        long[] leaked=new long[2];for(int i=0;i<2;i++){var b=new PlanningBudget(0,1_000_000,128L<<20,()->false,System::nanoTime);var analysis=new MissingStockAnalysis<>(compiler,"k2000",Map.of(),Set.of(),Set.of(),Set.of(),true,b);for(int s=0;s<10;s++)analysis.step();
            if(i==0){var owner=new GraphPlanningWork<>(compiler,"k2000",1,Map.of(),true,true,b);var f=GraphPlanningWork.class.getDeclaredField("missingAnalysis");f.setAccessible(true);f.set(owner,analysis);owner.close();}
            else{var owner=new QuantityAnalysis<>(compiler,"k2000",1,Map.of(),Set.of(),Map.of(),Set.of(),b);var f=QuantityAnalysis.class.getDeclaredField("startup");f.setAccessible(true);f.set(owner,analysis);owner.discard();}
            leaked[i]=b.reservedBytes();
        }
        var report=Map.of("newLifecycle",close!=null,"warmRequests",60,"work",work,"peakBytes",peak,"allocatedBytes",bytes,"milliseconds",nanos/1e6,"interruptedGraphOwnerLeak",leaked[0],"interruptedQuantityOwnerLeak",leaked[1]);
        Files.writeString(Path.of(args[0],"startup-comparison.json"),new Gson().toJson(report));System.out.println(report);
    }
}
