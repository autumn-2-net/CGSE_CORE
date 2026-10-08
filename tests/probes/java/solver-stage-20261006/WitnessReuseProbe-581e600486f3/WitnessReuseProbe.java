package org.cgse.core;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.gson.Gson;

public final class WitnessReuseProbe {
    static long checks, attempts, witnesses, reachable;
    static final List<String> KEYS = List.of("A", "B", "X", "Y", "C");
    static void ok(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    static PlanningBudget budget(long work, long memory) { return new PlanningBudget(0, work, memory, () -> false, System::nanoTime); }
    static GraphRecipe<String> recipe(String id, Map<String,Long> in, Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    static void verify(GraphPlan<String> plan, Map<String,Long> stock, Set<String> external, boolean force) {
        PlanVerifier.verifyRuntimeInventory(plan);
        for (var e : plan.initialExact().entrySet()) if (!external.contains(e.getKey()))
            ok(e.getValue().compareTo(BigInteger.valueOf(force && e.getKey().equals("C") ? 0 : stock.getOrDefault(e.getKey(),0L))) <= 0, "borrowed inventory " + e);
        ok(plan.missingExact().isEmpty() && plan.feasible(), "not a positive witness");
    }
    static GraphPlan<String> proposal(GraphCompiler<String> compiler, long amount, Map<String,Long> stock, Set<String> external, Map<String,Long> seeds, boolean force) {
        var b=budget(250_000,16L<<20); b.reserve(512); b.failureDetail("prior"); b.phase(PlanningBudget.Phase.SOLVE);
        var p=GraphFallback.witnessByCost(compiler,"C",amount,stock,external,seeds,true,force,b,32_768);
        attempts++; ok(b.reservedBytes()==512,"workspace leak "+b.reservedBytes()); ok(b.failureDetail().equals("prior"),"failure mutated");
        ok(b.phase()==PlanningBudget.Phase.SOLVE,"phase mutated"); ok(b.nodes()<40_000,"work escaped local allowance");
        if(p!=null) { witnesses++; verify(p,stock,external,force); }
        b.release(512); return p;
    }
    static boolean oracle(List<GraphRecipe<String>> rs, Map<String,Long> stock, long amount, Map<String,Long> seeds, boolean force) {
        var start=new ArrayList<Long>(); for(var key:KEYS)start.add(force&&key.equals("C")?0L:stock.getOrDefault(key,0L));
        var seen=new HashSet<List<Long>>(); var queue=new ArrayDeque<List<Long>>(); seen.add(start);queue.add(start);
        while(!queue.isEmpty()) {
            var state=queue.removeFirst(); boolean goal=true;
            for(int k=0;k<5;k++)goal&=state.get(k)>=seeds.getOrDefault(KEYS.get(k),0L)+(k==4?amount:0);
            if(goal)return true;
            for(var r:rs) {
                boolean enabled=true;var next=new ArrayList<Long>();
                for(int k=0;k<5;k++) { String key=KEYS.get(k);long take=r.inputs().getOrDefault(key,0L);enabled&=state.get(k)>=take;next.add(state.get(k)-take+r.outputs().getOrDefault(key,0L)); }
                if(enabled&&seen.add(next))queue.addLast(next);
            }
        }
        return false;
    }
    static void randomDag() {
        var random=new Random(71_802_119);
        for(int sample=0;sample<300;sample++) {
            var rs=new ArrayList<GraphRecipe<String>>();
            for(int k=0;k<8;k++) {
                int out=2+random.nextInt(3);var in=new LinkedHashMap<String,Long>();in.put(KEYS.get(random.nextInt(out)),1L+random.nextInt(2));
                if(random.nextBoolean())in.merge(KEYS.get(random.nextInt(out)),1L,Long::sum);
                rs.add(recipe("r"+k,in,Map.of(KEYS.get(out),1L)));
            }
            var stock=Map.of("A",(long)random.nextInt(7),"B",(long)random.nextInt(7),"X",(long)random.nextInt(3),"Y",(long)random.nextInt(3),"C",4L);
            var seeds=sample%3==0?Map.of("A",1L):Map.<String,Long>of();long amount=1+random.nextInt(4);
            for(boolean force:List.of(true,false)) {
                boolean expected=oracle(rs,stock,amount,seeds,force);if(expected)reachable++;
                for(int order=0;order<3;order++) {
                    Collections.shuffle(rs,random);var compiler=new GraphCompiler<>(rs);
                    var before=List.copyOf(compiler.catalog()); var sources=List.copyOf(compiler.producers("C"));
                    var plan=proposal(compiler,amount,stock,Set.of(),seeds,force);
                    ok(plan==null||expected,"false feasible random DAG "+sample);ok(before.equals(compiler.catalog())&&sources.equals(compiler.producers("C")),"catalog changed");
                }
            }
        }
    }
    static void scopes()throws Exception {
        var rs=List.of(recipe("root",Map.of("X",1L,"Y",1L),Map.of("C",1L)),recipe("xA",Map.of("A",1L),Map.of("X",1L)),recipe("yA",Map.of("A",1L),Map.of("Y",1L)),recipe("xB",Map.of("B",1L),Map.of("X",1L)));
        var compiler=new GraphCompiler<>(rs);
        ok(proposal(compiler,3,Map.of("A",3L,"B",3L),Set.of(),Map.of(),true)!=null,"sibling backtrack failed");
        ok(proposal(compiler,3,Map.of("A",3L),Set.of(),Map.of(),true)==null,"old stock reused");
        ok(proposal(compiler,3,Map.of("A",3L,"B",3L),Set.of(),Map.of("A",1L),true)==null,"seed spent twice");
        ok(proposal(compiler,3,Map.of("A",3L),Set.of("B"),Map.of(),true)!=null,"external supply not admitted");
        ok(proposal(compiler,3,Map.of("A",3L),Set.of(),Map.of(),true)==null,"external scope escaped");
        ok(proposal(new GraphCompiler<>(rs.subList(0,3)),3,Map.of("A",3L,"B",3L),Set.of(),Map.of(),true)==null,"removed pattern reused");
        var simple=new GraphCompiler<>(List.of(recipe("make",Map.of("A",1L),Map.of("C",1L))));
        ok(proposal(simple,3,Map.of("C",10L),Set.of(),Map.of(),false)!=null,"stored non-force target");
        ok(proposal(simple,3,Map.of("C",10L),Set.of(),Map.of(),true)==null,"stored target force leak");
        var tool=new GraphCompiler<>(List.of(recipe("tool",Map.of("A",1L,"B",1L),Map.of("A",1L,"C",1L))));
        ok(proposal(tool,7,Map.of("A",1L,"B",7L),Set.of(),Map.of("A",1L),true)!=null,"returned tool seed");
        ok(proposal(tool,7,Map.of("B",7L),Set.of(),Map.of(),true)==null,"invented tool seed");
        var ring=new GraphCompiler<>(List.of(recipe("a",Map.of("C",1L),Map.of("A",1L)),recipe("c",Map.of("A",1L,"B",1L),Map.of("C",1L))));
        ok(proposal(ring,1,Map.of("C",10L,"B",10L),Set.of(),Map.of(),true)==null,"zero-output cycle");
        var pool=Executors.newFixedThreadPool(4);
        try {
            var calls=new ArrayList<Callable<Boolean>>();
            for(int i=0;i<400;i++) { final boolean funded=i%2==0;calls.add(()->{
                var b=budget(250_000,16L<<20);var p=GraphFallback.witnessByCost(simple,"C",3,Map.of("A",funded?3L:2L),Set.of(),Map.of(),false,true,b,32768);
                if(p!=null)PlanVerifier.verifyRuntimeInventory(p);return (p!=null)==funded&&b.reservedBytes()==0;
            }); }
            for(var f:pool.invokeAll(calls))ok(f.get(),"concurrent scope or memory leak");
        } finally { pool.shutdownNow(); }
    }
    static void limits() {
        var compiler=new GraphCompiler<>(List.of(recipe("make",Map.of("A",1L),Map.of("C",1L))));
        for(long memory:new long[]{512,700,4096,64000,68000,70000,75000,100000,1L<<20}) {
            var b=budget(250_000,memory);b.reserve(512);b.failureDetail("old");
            var p=GraphFallback.witnessByCost(compiler,"C",1,Map.of("A",1L),Set.of(),Map.of(),false,true,b,32768);
            ok(b.reservedBytes()==512,"memory decline leaked");ok(b.failureDetail().equals("old"),"optional failure overwrote order failure");
            if(p!=null)verify(p,Map.of("A",1L),Set.of(),true);b.release(512);
        }
        for(long quota:new long[]{0,1,16,8192,32768}) {
            var b=budget(250_000,16L<<20);var p=GraphFallback.witnessByCost(compiler,"C",1,Map.of("A",1L),Set.of(),Map.of(),false,true,b,quota);
            ok(b.reservedBytes()==0,"local limit leaked");if(quota==0)ok(p==null&&b.nodes()==0,"zero quota searched");
        }
        var b=budget(10,16L<<20);for(int i=0;i<10;i++)b.check();boolean exhausted=false;
        try {GraphFallback.witnessByCost(compiler,"C",1,Map.of("A",1L),Set.of(),Map.of(),false,true,b,32768);}catch(PlanningBudget.Exhausted e){exhausted=e.limit()==PlanningBudget.Limit.SEARCH_LIMIT;}
        ok(exhausted&&b.reservedBytes()==0,"global quota swallowed");
        b=budget(250_000,16L<<20);b.cancel();boolean cancelled=false;
        try {GraphFallback.witnessByCost(compiler,"C",1,Map.of("A",1L),Set.of(),Map.of(),false,true,b,32768);}catch(CancellationException e){cancelled=true;}
        ok(cancelled&&b.reservedBytes()==0,"cancel swallowed");
    }
    public static void main(String[]args)throws Exception {randomDag();scopes();limits();ok(witnesses>500,"insufficient witness coverage");var report=Map.of("assertions",checks,"attempts",attempts,"witnesses",witnesses,"oracle_reachable",reachable,"concurrent_requests",400);Files.writeString(Path.of(args[0],"witness-reuse.json"),new Gson().toJson(report));System.out.println(report);}
}
