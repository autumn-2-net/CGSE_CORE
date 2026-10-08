package org.cgse.core;

import java.util.*;
import java.util.concurrent.*;

public final class CompilerFusionProbe {
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 128L << 20, () -> false, System::nanoTime); }
    static GraphRecipe<String> recipe(String name, Map<String,Long> inputs, Map<String,Long> outputs) {
        return new GraphRecipe<>(name, name, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }
    static GraphCompiler.Compiled<String> compile(GraphCompiler<String> c, String key, Map<String,Integer> choices, Set<String> excluded, Set<String> leaves, PlanningBudget b) {
        try (var work = c.beginStockView(key, Set.of(), choices, excluded, leaves, b)) {
            while (!work.step()) {}
            return work.result();
        }
    }
    static void accounts() throws Exception {
        var b = budget(1024);
        long before = b.threadWork(); long searchBefore = b.threadSearchWork();
        b.compilationCharge(1024); require(b.threadSearchWork()==searchBefore,"compile shifted local search quota");
        require(b.remainingWork() == 1024 && b.searchWork() == 0 && b.compilationWork() == 1024, "compile stole search allowance");
        b.charge(1024); require(b.threadSearchWork()-searchBefore==1024,"local search quota mismatch");
        require(b.nodes() == 2048 && b.threadWork() - before == 2048 && b.remainingWork() == 0, "work disappeared");
        boolean search = false, compile = false;
        try { b.check(); } catch (PlanningBudget.Exhausted e) { search = e.getMessage().contains("search_work="); }
        try { b.compilationCheck(); } catch (PlanningBudget.Exhausted e) { compile = e.getMessage().contains("compilation_work="); }
        require(search && compile, "independent caps");
        var parallel = budget(200_000);
        var threads = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<Future<?>>();
            for (int j=0;j<4;j++) jobs.add(threads.submit(() -> {
                long start = parallel.threadWork();
                for (int i=0;i<100_000;i++) { parallel.compilationScan(); parallel.operation(PlanningBudget.Operation.SCAN,1); }
                require(parallel.threadWork()-start == 50_000, "worker clock");
            }));
            for (var f:jobs) f.get();
        } finally { threads.shutdownNow(); }
        require(parallel.searchWork()==100_000 && parallel.compilationWork()==100_000 && parallel.nodes()==200_000, "parallel accounting");
        for (boolean structural : new boolean[]{false,true}) {
            var huge = budget(Long.MAX_VALUE); boolean stopped=false;
            try { if(structural)huge.compilationCharge(Long.MAX_VALUE);else huge.charge(Long.MAX_VALUE); }
            catch(PlanningBudget.Exhausted e){stopped=true;}
            require(stopped && huge.nodes()>0, "overflow");
            try {if(structural)huge.compilationCheck();else huge.check();throw new AssertionError("overflow reopened");}
            catch(PlanningBudget.Exhausted expected){}
        }
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var timeout = new PlanningBudget(1,100,1000,()->false,clock::get);
        timeout.start(); clock.set(1_000_000);
        try {timeout.compilationCheck();throw new AssertionError("compile timeout missing");}catch(PlanningBudget.Exhausted e){require(e.limit()==PlanningBudget.Limit.TIMEOUT,"timeout type");}
        var cancelled=budget(100);cancelled.cancel();
        try{cancelled.compilationCheck();throw new AssertionError("compile cancellation missing");}catch(CancellationException expected){}
        System.out.println("independent compiler/search caps, fractional parallel accounting, shared cancellation/timeout and overflow passed");
    }
    static void topology() {
        var recipes=new ArrayList<GraphRecipe<String>>();
        for(int i=0;i<800;i++) recipes.add(recipe("r"+i,Map.of("p"+i,1L),i==799?Map.of("T",1L,"U",1L):Map.of("p"+(i+1),1L)));
        var c=new GraphCompiler<>(recipes);var cold=budget(10_000_000);var a=compile(c,"T",Map.of(),Set.of(),Set.of(),cold);
        var warm=budget(10_000_000);var b=compile(c,"U",Map.of(),Set.of(),Set.of(),warm);long reusedWork=warm.compilationWork();
        require(a.regions()==b.regions(),"topology not shared");
        require(!b.selected().containsKey("T") && b.selected().containsKey("U"),"borrowed source map");
        require(warm.compilationWork()<cold.compilationWork()/2,"no compilation saving");
        var c2=new GraphCompiler<>(recipes);var reference=compile(c2,"U",Map.of(),Set.of(),Set.of(),budget(10_000_000));
        require(b.regions().equals(reference.regions()),"warm SCC traversal changed");
        var shortened=compile(c,"T",Map.of(),Set.of(),Set.of("p600"),warm);
        require(shortened.recipes().size()==200 && shortened.regions()!=a.regions(),"stock boundary topology reused unsafely");
        var excluded=compile(c,"T",Map.of(),Set.of("r600"),Set.of(),warm);
        require(excluded.recipes().size()==199,"exclusions ignored");
        require(cold.reservedBytes()==0 && warm.reservedBytes()==0,"topology leak");
        System.out.println("topology cold_work="+cold.compilationWork()+" warm_work="+reusedWork+"; exact SCC order, source maps, boundaries and exclusions passed");
    }
    static void quantitative() {
        var bad=recipe("needs_two",Map.of("A",2L),Map.of("T",1L));
        var good=recipe("funded",Map.of("B",1L),Map.of("T",1L));
        var growth=recipe("unfunded_growth",Map.of("A",3L),Map.of("A",4L));
        var c=new GraphCompiler<>(List.of(bad,good,growth));var b=budget(10_000_000);
        try(var rank=GraphSourceRanking.createQuantitative(c,Map.of("A",1L,"B",1L),Set.of(),"T",true,b,300_000)) {
            require(rank.sources("T",false).get(0)==good,"presence mistaken for full batch");
            require(rank.sources("T",false).size()==2,"ranking deleted provider");
        }
        require(b.reservedBytes()==0,"ranking leak");
        System.out.println("quantity-aware source ranking retains all alternatives passed");
    }
    static GraphPlan<String> preview(GraphCompiler.Compiled<String> graph,Map<String,Long> stock,PlanningBudget b) {
        try(var solve=new GraphSolve<>(graph,"T",2,stock,Set.of(),Map.of(),false,true,b,System.nanoTime(),CatalystPolicy.MINIMAL,stock)){
            while(!solve.step()){}return solve.result();
        }
    }
    static void fusion() {
        var recipes=List.of(recipe("use_A",Map.of("A",1L),Map.of("T",1L)),recipe("use_B",Map.of("B",1L),Map.of("T",1L)));
        var c=new GraphCompiler<>(recipes);var b=budget(10_000_000);var stock=Map.of("A",1L,"B",1L);
        try(var pool=new GraphSupportNeighborhood<>("T",2,stock,Set.of(),Map.of(),false,true,b,System.nanoTime())) {
            for(int i=0;i<2;i++) {
                var g=compile(c,"T",Map.of("T",i),Set.of(),stock.keySet(),b);
                var missing=preview(g,stock,b);require(!missing.feasible(),"single support should be short");pool.offer(g,missing);
            }
            for(int turn=0;turn<8 && pool.result()==null && pool.beginTurn(500_000);turn++)while(!pool.step()){}
            require(pool.result()!=null && pool.result().feasible(),"cross-view counts did not combine A and B");
            PlanVerifier.verify(pool.result());PlanVerifier.verifyRuntimeInventory(pool.result());
            require(pool.result().patternTimesExact().size()==2,"joint support missing");
        }
        require(b.reservedBytes()==0,"support search leaked");
        System.out.println("two individually missing supports combine to exact executable counts; no reservations leaked");
    }
    public static void main(String[] args)throws Exception {accounts();topology();quantitative();fusion();System.out.println("PASS compiler-fusion");}
}
