package org.cgse.core;
import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.*;

public final class StartupProbe {
    static long checks, cases;static int cancellations;
    static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);}
    record Truth(boolean blocked,Set<String> unreachable){}
    static Truth oracle(List<GraphRecipe<String>> rs,String target,Map<String,Long> stock,Set<String> external,Set<String> required,Set<String> excluded,boolean force){
        Set<String> available=new HashSet<>(external),produced=new HashSet<>(),fired=new HashSet<>();stock.forEach((k,v)->{if(v>0)available.add(k);});boolean change;
        do{change=false;for(var r:rs)if(!excluded.contains(r.id())&&!fired.contains(r.id())&&available.containsAll(r.inputs().keySet())){fired.add(r.id());available.addAll(r.outputs().keySet());produced.addAll(r.outputs().keySet());change=true;}}while(change);
        Set<String> missing=new HashSet<>();for(var r:rs)if(!excluded.contains(r.id())&&!fired.contains(r.id()))missing.add(r.id());
        return new Truth((force?!produced.contains(target)&&!external.contains(target):!available.contains(target))||!available.containsAll(required),missing);
    }
    static List<GraphRecipe<String>> recipes(int seed){
        var r=new Random(seed*17041L+913);int n=4+r.nextInt(24);var rs=new ArrayList<GraphRecipe<String>>();
        for(int i=0;i<n;i++){var in=new LinkedHashMap<String,Long>();for(int j=0;j<r.nextInt(4);j++)in.merge("k"+r.nextInt(12),1L+r.nextInt(7),Long::sum);var out=new LinkedHashMap<String,Long>();out.put("k"+r.nextInt(12),1L+r.nextInt(9));
            var slots=new ArrayList<GraphRecipe.Slot<String>>();for(var e:in.entrySet())slots.add(new GraphRecipe.Slot<>(e.getKey(),e.getValue()));
            if(i%7==0){boolean reusable=i%14==0;slots.add(new GraphRecipe.Slot<>("tool",1,0,true,reusable));if(reusable)out.merge("tool",1L,Long::sum);}
            rs.add(new GraphRecipe<>("r"+i,"r"+i,slots,out));}
        return rs;
    }
    static void run(GraphCompiler<String> compiler,int seed,int profile){
        var r=new Random(seed*6709L+profile);var stock=new LinkedHashMap<String,Long>();var ext=new HashSet<String>();var excluded=new HashSet<String>();
        for(int k=0;k<13;k++){String key=k==12?"tool":"k"+k;if(r.nextInt(3)==0)stock.put(key,profile%3==0?Long.MAX_VALUE:1L+r.nextInt(7));if(r.nextInt(9)==0)ext.add(key);}
        for(var recipe:compiler.catalog())if(r.nextInt(5)==0)excluded.add(recipe.id());
        String target="k"+r.nextInt(12);Set<String> required=profile%3==0?Set.of("k"+r.nextInt(12),"tool"):Set.of();boolean force=profile%2==0;
        var truth=oracle(compiler.catalog(),target,stock,ext,required,excluded,force);var b=budget();
        try(var analysis=new MissingStockAnalysis<>(compiler,target,stock,ext,required,excluded,force,b)){
            while(!analysis.step()){}check(analysis.blocked()==truth.blocked,"blocked mismatch "+seed+"/"+profile);check(analysis.unreachableRecipes().equals(truth.unreachable),"unreachable mismatch "+seed+"/"+profile);
        }check(b.reservedBytes()==0,"analysis leak");cases++;
    }
    static void lifecycle()throws Exception {
        var compiler=new GraphCompiler<>(recipes(112));run(compiler,112,0);
        for(int stop=0;stop<180;stop++){
            var cancel=new AtomicBoolean();var b=new PlanningBudget(0,20_000_000,128L<<20,cancel::get,System::nanoTime);
            var analysis=new MissingStockAnalysis<>(compiler,"k1",Map.of("k0",1L),Set.of(),Set.of(),Set.of(),true,b);
            try{for(int step=0;step<stop&&!analysis.step();step++){}cancel.set(true);try{analysis.step();throw new AssertionError("ignored cancellation");}catch(CancellationException expected){}}
            finally{analysis.close();analysis.close();}check(b.reservedBytes()==0,"cancel leak at "+stop);cancellations++;
        }
        for(int cap=1;cap<=180;cap++){
            var b=new PlanningBudget(0,cap,128L<<20,()->false,System::nanoTime);try(var analysis=new MissingStockAnalysis<>(compiler,"k1",Map.of("k0",1L),Set.of(),Set.of(),Set.of(),true,b)){while(!analysis.step()){} }catch(PlanningBudget.Exhausted expected){check(expected.limit()==PlanningBudget.Limit.SEARCH_LIMIT,"wrong work limit");}
            check(b.reservedBytes()==0,"work cap leak "+cap);
        }
        for(int bytes:new int[]{64,128,256,512,1024,2048,4096,8192,16384,32768}){
            var c=new GraphCompiler<>(recipes(712));var b=new PlanningBudget(0,1_000_000,bytes,()->false,System::nanoTime);
            try(var analysis=new MissingStockAnalysis<>(c,"k1",Map.of("k0",1L),Set.of(),Set.of(),Set.of(),true,b)){while(!analysis.step()){} }catch(PlanningBudget.Exhausted expected){check(expected.limit()==PlanningBudget.Limit.MEMORY_LIMIT,"wrong memory limit");}
            check(b.reservedBytes()==0,"memory cap leak "+bytes);
        }
        // Exercise ownership from both callers when interrupted midway through startup analysis.
        for(boolean quantity:List.of(false,true)){
            var b=budget();var analysis=new MissingStockAnalysis<>(compiler,"k1",Map.of(),Set.of(),Set.of(),Set.of(),true,b);for(int i=0;i<4;i++)analysis.step();
            if(quantity){var owner=new QuantityAnalysis<>(compiler,"k1",1,Map.of(),Set.of(),Map.of(),Set.of(),b);var field=QuantityAnalysis.class.getDeclaredField("startup");field.setAccessible(true);field.set(owner,analysis);owner.discard();}
            else{var owner=new GraphPlanningWork<>(compiler,"k1",1,Map.of(),true,true,b);var field=GraphPlanningWork.class.getDeclaredField("missingAnalysis");field.setAccessible(true);field.set(owner,analysis);owner.close();}
            check(b.reservedBytes()==0,"owner startup leak "+quantity);
        }
        // No half-built index may be published, and another catalog's numbering is rejected.
        var c=new GraphCompiler<>(recipes(219));var b=budget();try(var builder=new GraphCatalogIndex.Builder<>(c.catalog(),b)){builder.add(c.catalog().get(0),0);check(builder.result()==null,"partial publication");}
        check(b.reservedBytes()==0,"builder leak");
        GraphCatalogIndex<String> index;try(var builder=new GraphCatalogIndex.Builder<>(c.catalog(),b)){for(int i=0;i<c.catalog().size();i++)builder.add(c.catalog().get(i),i);while(!builder.step()){}index=builder.result();check(index!=null,"no index");}
        var other=new GraphCompiler<>(recipes(220));other.rememberCatalogIndex(index);check(other.catalogIndex()==null,"foreign index accepted");check(b.reservedBytes()==0,"publication leak");
    }
    static void concurrent()throws Exception {
        var compiler=new GraphCompiler<>(recipes(171));var pool=Executors.newFixedThreadPool(3);try{
            var jobs=new ArrayList<Future<?>>();for(int id=0;id<96;id++){final int profile=id;jobs.add(pool.submit(()->{var b=budget();var stock=Map.of("k0",1L,"tool",1L);var ext=Set.of("k"+(profile%12));var excluded=Set.of("r"+(profile%compiler.catalog().size()));var expected=oracle(compiler.catalog(),"k1",stock,ext,Set.of(),excluded,true);
                try(var analysis=new MissingStockAnalysis<>(compiler,"k1",stock,ext,Set.of(),excluded,true,b)){while(!analysis.step()){}if(analysis.blocked()!=expected.blocked||!analysis.unreachableRecipes().equals(expected.unreachable))throw new AssertionError("concurrent scope contamination");}if(b.reservedBytes()!=0)throw new AssertionError("concurrent leak");}));}
            for(var job:jobs)job.get();
        }finally{pool.shutdownNow();}
    }
    public static void main(String[] args)throws Exception{
        for(int seed=0;seed<10000;seed++){var compiler=new GraphCompiler<>(recipes(seed));for(int profile=0;profile<8;profile++)run(compiler,seed,profile);}
        lifecycle();concurrent();var report=Map.of("oracleAnalyses",cases,"catalogs",10000,"profilesPerCatalog",8,"cancellationCases",cancellations,"concurrentRequests",96,"assertions",checks);
        Files.writeString(Path.of(args[0],"startup-probe.json"),new Gson().toJson(report));System.out.println(report);
    }
}
