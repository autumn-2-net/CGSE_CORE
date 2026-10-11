// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
// Migrated from NestedCompilerChecks-e3dde4ae2b8a with its original nested/runtime helpers.
package org.cgse.core;
import java.math.BigInteger;
import java.util.*;

public final class HistoricalNestedCompilerTest {
    static GraphPlan<String> plan(List<GraphRecipe<String>> recipes, long amount, Map<String,Long> stock) {
        var b=new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);
        var w=new GraphPlanningWork<>(new GraphCompiler<>(recipes),"Q0",amount,stock,true,true,b).catalysts(CatalystPolicy.MINIMAL);
        try{while(!w.step()){}return w.result();}finally{w.close();if(b.reservedBytes()!=0)throw new AssertionError("nested workspace leaked");}
    }
    static int nodes(PlanStep p) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<PlanStep,Boolean>());
        var todo=new ArrayDeque<PlanStep>();todo.add(p);
        while(!todo.isEmpty()) {
            var s=todo.removeFirst();if(!seen.add(s))continue;
            if(s instanceof PlanStep.Sequence q)todo.addAll(q.children());
            if(s instanceof PlanStep.Repeat r)todo.add(r.body());
        }
        return seen.size();
    }
    static void check(boolean condition,String detail) {if(!condition)throw new AssertionError(detail);}
    static void execute(GraphPlan<String> p,int reload) {
        var m=new Machines(p,reload);
        for(int tick=0;tick<250_000&&!m.runtime.finished();tick++)m.step();
        check(m.runtime.state()==GraphJobRuntime.State.COMPLETED,"Runtime "+m.runtime.state()+" "+m.runtime.reason());
        check(m.accepted.equals(p.patternTimes()),"Wrong dispatch counts");
        check(m.delivered.equals(Map.of(p.target(),p.amount())),"Wrong delivery");
        check(m.physical.isEmpty()&&m.flights.isEmpty(),"Stranded material");
        p.seeds().forEach((k,v)->check(m.refunded.getOrDefault(k,0L)>=v,"Lost catalyst "+k));
    }
    public static void main(String[] args) {
        var random=new Random(2985625);int passed=0;
        for(int depth:new int[]{24,40,60})for(long amount:new long[]{1,100_000_017,Long.MAX_VALUE})for(int permutation=0;permutation<5;permutation++) {
            var recipes=new ArrayList<>(nested(depth));Collections.shuffle(recipes,random);
            var p=plan(recipes,amount,Map.of("C0",1L));
            check(p.feasible(),"Nested "+depth+" n="+amount+" "+p.result());
            PlanVerifier.verify(p);PlanVerifier.verifyRuntimeInventory(p);
            check(p.initialExact().equals(Map.of("C0",BigInteger.ONE)),"Unexpected starting material");
            var counts=p.patternTimesExact();
            var independent=HistoricalFixtures.summary(p.steps(),p.recipes());
            HistoricalFixtures.verify(new HistoricalFixtures.Fixture("nested",recipes,Map.of("C0",1L),"Q0",amount,false),independent);
            check(independent.delta().getOrDefault("C0",BigInteger.ZERO).signum()>=0,"starting catalyst not returned");
            // Retaining a safe physical batch can leave surplus lower-level Q.
            // Require the exact independent execution and causal production floor,
            // not the obsolete assertion that every feasible plan is a unique optimum.
            for(int level=0;level<depth;level++)for(String prefix:List.of("open","close"))
                check(counts.get(prefix+level).compareTo(BigInteger.valueOf(amount).shiftLeft(level))>=0,"Wrong exact count "+prefix+level+" depth="+depth+" amount="+amount+" expected="+BigInteger.valueOf(amount).shiftLeft(level)+" actual="+counts.get(prefix+level));
            check(counts.get("inner").compareTo(BigInteger.valueOf(amount).shiftLeft(depth))>=0,"Wrong exact inner count");
            check(nodes(p.steps())<12*depth+16,"Expanded AST "+nodes(p.steps()));
            if(permutation==0)System.out.println("NESTED_CHECK depth="+depth+" request="+amount+" nodes="+nodes(p.steps())+" inner="+counts.get("inner"));
            passed++;
        }
        for(int depth=1;depth<=7;depth++) {
            var p=plan(nested(depth),7,Map.of("C0",1L));
            check(p.feasible(),"Executable nested "+depth);
            execute(p,0);execute(p,97);passed+=2;
        }
        for(int depth:new int[]{24,40,60}) {
            var c=nested(depth);var withRaw=new ArrayList<GraphRecipe<String>>();
            for(var recipe:c){var slots=new ArrayList<>(recipe.slots());if(!recipe.id().startsWith("close"))slots.add(new GraphRecipe.Slot<>("R",1));withRaw.add(new GraphRecipe<>(recipe.id(),recipe.binding(),slots,recipe.outputs()));}
            var p=plan(withRaw,1,Map.of("C0",1L,"R",BigInteger.ONE.shiftLeft(depth+1).subtract(BigInteger.TWO).longValueExact()));
            check(!p.feasible(),"Invented raw material depth="+depth);
            System.out.println("MISSING_RAW depth="+depth+" result="+p.result()+" deficit="+p.missingExact());passed++;
        }
        var growth=growth();
        // Total production may exceed long: the runtime drains bounded physical
        // batches. The old rejection expectation predates that support.
        PlanVerifier.verifyRuntimeInventory(growth);GraphLongBoundaryTest.execute(growth);
        var overReserved=new GraphPlan<>(growth.target(),growth.amount(),growth.preserveSeeds(),growth.steps(),growth.recipes(),
                Map.of("C",BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),growth.seeds(),Map.<String,BigInteger>of(),growth.result(),0,0);
        boolean rejected=false;try{PlanVerifier.verifyRuntimeInventory(overReserved);}catch(ArithmeticException expected){rejected=true;}
        check(rejected,"Oversized physical initial reservation must not wrap");passed++;
        var recipes=nested(60);
        var b=new PlanningBudget(0,200,128L<<20,()->false,System::nanoTime);
        var w=new GraphPlanningWork<>(new GraphCompiler<>(recipes),"Q0",Long.MAX_VALUE,Map.of("C0",1L),true,true,b);
        try{while(!w.step()){}check(w.result().result()==GraphPlan.Result.SEARCH_LIMIT,"Global work limit lost");}finally{w.close();check(b.reservedBytes()==0,"limited nested plan leaked");}passed++;
        System.out.println("NESTED_CHECKS_PASS "+passed);
    }
    static List<GraphRecipe<String>> nested(int d){
        var out=new ArrayList<GraphRecipe<String>>();
        for(int i=0;i<d;i++){
            out.add(r("open"+i,Map.of("C"+i,1L),Map.of("C"+(i+1),1L)));
            out.add(r("close"+i,Map.of("C"+(i+1),1L,"Q"+(i+1),2L),Map.of("C"+i,1L,"Q"+i,1L)));
        }
        out.add(r("inner",Map.of("C"+d,1L),Map.of("C"+d,1L,"Q"+d,1L)));return out;
    }

    record Flight(long due,String recipe,Map<String,Long> output) {}
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    static GraphPlan<String> growth(){
        var b=new PlanningBudget(0,10000000,128L<<20,()->false,System::nanoTime);
        var w=new GraphPlanningWork<>(new GraphCompiler<>(List.of(r("g",Map.of("C",1L),Map.of("C",2L)))),"C",Long.MAX_VALUE,Map.of("C",1L),true,true,b).catalysts(CatalystPolicy.MINIMAL);
        try{while(!w.step()){}check(w.result().feasible(),"missing growth");return w.result();}finally{w.close();check(b.reservedBytes()==0,"growth memory leak");}
    }
    static final class Machines implements GraphJobRuntime.Adapter<String> {
        GraphJobRuntime<String> runtime;
        final Map<String, Long> physical = new TreeMap<>(), accepted = new TreeMap<>(), delivered = new TreeMap<>(), refunded = new TreeMap<>();
        final List<Flight> flights = new ArrayList<>();
        final Random random = new Random(49367);
        final int reload;
        long tick, pushes, overlaps, nanos, maxNanos, maxFlight;
        Machines(GraphPlan<String> p, int reload) { runtime = new GraphJobRuntime<>(p, p.initial(), Map.of()); physical.putAll(p.initial()); this.reload = reload; }
        void step() {
            Collections.shuffle(flights, random);
            for (var it = flights.iterator(); it.hasNext();) {
                var f = it.next(); if (f.due > tick) continue;
                f.output.forEach((k, v) -> {
                    long got = runtime.accept(k, v, false); check(got == v, "Dropped or double-counted physical output"); physical.merge(k, got, Math::addExact);
                }); it.remove();
            }
            long start = System.nanoTime(); runtime.tick(this, tick++, 8); long elapsed = System.nanoTime() - start;
            nanos += elapsed; maxNanos = Math.max(maxNanos, elapsed);
            physical.values().removeIf(v -> v == 0); check(physical.equals(runtime.owned()), "Physical ownership differs");
            maxFlight = Math.max(maxFlight, flights.size());
            if (reload > 0 && tick % reload == 0 && !runtime.finished()) runtime = new GraphJobRuntime<>(runtime.snapshot());
        }
        public long capacity(GraphRecipe<String> r, long requested) {
            if (tick < 41 && r.id().equals("a1")) return 0;
            return Math.min(requested, 1);
        }
        public GraphJobRuntime.Outcome push(GraphRecipe<String> r, long runs, Map<String, Long> input) {
            input.forEach((k, v) -> debit(physical, k, v));
            if (flights.stream().anyMatch(f -> !f.recipe.equals(r.id()))) overlaps++;
            long ordinal = accepted.merge(r.id(), runs, Math::addExact);
            long delay = 1 + Math.floorMod(r.id().hashCode() + ordinal * 7, 13);
            // Return byproducts and main outputs separately and out of order.
            int offset = 0;
            for (var e : r.outputs().entrySet()) flights.add(new Flight(tick + delay + (offset++ * 3), r.id(), Map.of(e.getKey(), Math.multiplyExact(e.getValue(), runs))));
            pushes++; return GraphJobRuntime.Outcome.ACCEPTED;
        }
        public long deliver(String key, long n) { debit(physical, key, n); delivered.merge(key, n, Math::addExact); return n; }
        public long refund(String key, long n) { debit(physical, key, n); refunded.merge(key, n, Math::addExact); return n; }
    }

    static void debit(Map<String, Long> map, String k, long n) {
        long before = map.getOrDefault(k, 0L); check(before >= n, "Spending unowned material " + k); map.put(k, before - n);
    }
}
