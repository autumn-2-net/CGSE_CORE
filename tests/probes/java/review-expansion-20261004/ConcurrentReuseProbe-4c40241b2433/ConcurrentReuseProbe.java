package org.cgse.core;
import com.google.gson.Gson;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
public final class ConcurrentReuseProbe {
    public static void main(String[] args)throws Exception{
        var rs=RecoveryProbe.chain(7);var compiler=new GraphCompiler<>(rs);String target="k"+rs.size();var stock=Map.of("k0",Long.MAX_VALUE);var graph=compiler.compile(target,Map.of(),Set.of(),RecoveryProbe.budget());
        var executor=Executors.newFixedThreadPool(4);try{
            var jobs=new ArrayList<Future<?>>();for(int id=0;id<192;id++){final int request=id;jobs.add(executor.submit(()->{try{
                var inventory=Map.of("k0",Long.MAX_VALUE,"k1",(long)(request%17));var seeds=request%4==0?Map.of("k1",1L):Map.<String,Long>of();var ext=request%5==0?Set.of("k0"):Set.<String>of();var excluded=request%7==0?Set.of("r0"):Set.<String>of();
                RecoveryProbe.compile(compiler,target,1+request,inventory,seeds,ext,excluded,false);
                var b=RecoveryProbe.budget();var program=compiler.demandProgram(graph,b);var a=DemandProbe.solve(graph,null,target,1+request,inventory,seeds,ext,true,false);var z=DemandProbe.solve(graph,program,target,1+request,inventory,seeds,ext,true,false);DemandProbe.compare(a,z,"concurrent="+request);if(b.reservedBytes()!=0)throw new AssertionError("concurrent preparation leak");
            }catch(Exception failure){throw new RuntimeException(failure);}}));}for(var job:jobs)job.get();
        }finally{executor.shutdownNow();}
        Files.writeString(Path.of(args[0],"concurrent-reuse-probe.json"),new Gson().toJson(Map.of("requests",192,"workers",4,"failures",0)));System.out.println("Concurrent macro/demand reuse: 192 requests, 4 workers, 0 failures");
    }
}
