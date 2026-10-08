package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;

public final class CandidateGuardProbe {
    static int checks;
    static void ck(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    static Map<String,GraphRecipe<String>> recipes(){var out=new LinkedHashMap<String,GraphRecipe<String>>();BootstrapOracleProbe.recipes(BootstrapOracleProbe.ORIGINAL,0,1).subList(0,2).forEach(r->out.put(r.id(),r));return out;}
    static PlanStep program(){return new PlanStep.Sequence(List.of(new PlanStep.Batch("recycle",1),new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("recycle",1),new PlanStep.Batch("main",1))),3)));}
    static AllocationSearch.Candidate<String> original(PlanningBudget b,boolean preview,Set<String> external){return new AllocationSearch.Candidate<>(program(),recipes(),"C",6,Map.of("B",9L,"C",2L),Map.of(),external,false,true,preview,b,System.nanoTime());}
    static PlanningBudget budget(){return new PlanningBudget(0,1_000_000,64L<<20,()->false,System::nanoTime);}
    static void publicationAndCleanup(){
        var b=budget();try(var c=original(b,false,Set.of())){
            boolean done=false;int count=0;while(!done){done=c.step();ck(done||c.plan==null,"candidate published before proof completed");ck(++count<10000,"candidate stuck");}
            ck(c.plan!=null,"valid bootstrap candidate discarded");BootstrapOracleProbe.verify(c.plan,Map.of("B",9L,"C",2L),true);c.close();c.close();
        }ck(b.reservedBytes()==0,"success close leak="+b.reservedBytes());
        for(int offset:new int[]{0,1,2,4,8,16,32,64,128}){
            var armed=new AtomicBoolean();var calls=new AtomicInteger();b=new PlanningBudget(0,1_000_000,64L<<20,()->armed.get()&&calls.incrementAndGet()>offset,System::nanoTime);
            try(var c=original(b,false,Set.of())){
                while(c.stage!=5)ck(!c.step(),"did not reach proof stage");ck(c.plan==null,"preproof plan visible");armed.set(true);
                try{while(!c.step())ck(c.plan==null,"pending proof plan visible");}catch(CancellationException expected){ck(c.plan==null,"cancelled proof plan visible");}
            }ck(b.reservedBytes()==0,"proof cancellation leak offset="+offset+" bytes="+b.reservedBytes());
        }
        b=budget();try(var c=original(b,false,Set.of())){
            while(c.stage!=5)ck(!c.step(),"interrupt phase");Thread.currentThread().interrupt();
            try{c.step();throw new AssertionError("interrupt ignored");}catch(CancellationException expected){ck(c.plan==null,"interrupt published plan");}finally{Thread.interrupted();}
        }ck(b.reservedBytes()==0,"interrupt leak");
        for(long memory:new long[]{1024,4096,8192,16384,32768,65536,1L<<20}){
            b=new PlanningBudget(0,1_000_000,memory,()->false,System::nanoTime);
            try(var c=original(b,false,Set.of())){while(!c.step())ck(c.plan==null,"low-memory pending plan");if(c.plan!=null)BootstrapOracleProbe.verify(c.plan,Map.of("B",9L,"C",2L),true);}
            catch(PlanningBudget.Exhausted expected){}
            ck(b.reservedBytes()==0,"memory leak cap="+memory+" bytes="+b.reservedBytes());
        }
        for(boolean preview:new boolean[]{false,true}){
            b=budget();try(var c=original(b,preview,preview?Set.of():Set.of("C"))){while(!c.step()){}ck(c.plan==null,"external/preview net contract expanded");}ck(b.reservedBytes()==0,"old-contract leak");
        }
    }
    static void mixedQuota(){
        var rs=new LinkedHashMap<String,GraphRecipe<String>>();
        rs.put("out",BootstrapOracleProbe.recipe("out",Map.of("C",1L),Map.of("A",1L)));
        rs.put("turn",BootstrapOracleProbe.recipe("turn",Map.of("A",1L,"Z",1L,"fuel",1L),Map.of("C",1L,"Z",1L,"bonus",1L)));
        rs.put("make",BootstrapOracleProbe.recipe("make",Map.of("raw",1L,"A",1L),Map.of("A",1L,"C",1L,"Z",1L)));
        for(long n:new long[]{2,100,Long.MAX_VALUE})for(long quota:new long[]{4000,16384,100000,1_000_000}){
            var steps=new PlanStep.Sequence(List.of(new PlanStep.Batch("out",n),new PlanStep.Batch("make",1),new PlanStep.Batch("turn",n)));
            var stock=Map.of("C",n,"raw",1L,"fuel",n);var b=new PlanningBudget(0,quota,64L<<20,()->false,System::nanoTime);
            try(var c=new AllocationSearch.Candidate<>(steps,rs,"C",3,stock,Map.of(),Set.of(),false,true,false,b,System.nanoTime())){
                while(!c.step())ck(c.plan==null,"mixed pending plan published");ck(c.plan==null,"mixed fake force accepted n="+n+" quota="+quota);
            }catch(PlanningBudget.Exhausted expected){}
            ck(b.reservedBytes()==0,"mixed candidate leak n="+n+" quota="+quota+" bytes="+b.reservedBytes());
        }
        for(boolean force:new boolean[]{false,true}){
            var b=budget();try(var c=new AllocationSearch.Candidate<>(new PlanStep.Sequence(List.of()),Map.<String,GraphRecipe<String>>of(),"C",3,Map.of("C",100L),Map.of(),Set.of(),false,force,false,b,System.nanoTime())){
                while(!c.step()){}ck((c.plan==null)==force,"zero-runs force contract");
            }ck(b.reservedBytes()==0,"zero runs leak");
        }
    }
    public static void main(String[]args){publicationAndCleanup();mixedQuota();System.out.println("PASS candidate proof lifecycle/quota checks="+checks+" cancellation=9 memoryCaps=7 mixedCompressed=12 zeroRunModes=2");}
}
