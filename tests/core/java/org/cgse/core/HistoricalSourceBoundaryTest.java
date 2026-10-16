// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import org.cgse.core.*;

public final class HistoricalSourceBoundaryTest {
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    static GraphPlan<String> run(String name,List<GraphRecipe<String>> recipes,String key,long n,Map<String,Long> stock,boolean expected){
        var b=new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);long start=System.nanoTime();
        var w=new GraphPlanningWork<>(new GraphCompiler<>(recipes),key,n,stock,true,true,b).catalysts(CatalystPolicy.MINIMAL);
        while(!w.step()){}var p=w.result();
        if(p.feasible())validate(recipes,key,n,stock,p);
        System.out.printf("SOURCE %s request=%d recipes=%d result=%s ms=%.3f work=%d missing=%s detail=%s EXPECT=%s%n",name,n,recipes.size(),p.result(),(System.nanoTime()-start)/1e6,b.nodes(),p.missingExact(),b.failureDetail(),expected);
        if(p.feasible()!=expected)throw new AssertionError(name+" expected="+expected);
        if(!expected && !p.missingExact().values().stream().reduce(BigInteger.ZERO,BigInteger::add).equals(BigInteger.ONE))throw new AssertionError(name+" did not report one missing unit");
        return p;
    }
    public static void main(String[] args){
        for(long n:new long[]{2_147_483_647L,Long.MAX_VALUE})for(int paths:new int[]{4,16,64}){
            var recipes=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();
            for(int i=0;i<paths;i++){recipes.add(r("s"+i,Map.of("R"+i,1L),Map.of("Q",1L)));stock.put("R"+i,n);}
            recipes.add(r("p",Map.of("Q",(long)paths),Map.of("P",1L)));
            run("split-"+paths,recipes,"P",n,stock,true);
            stock.put("R"+(paths-1),n-1);
            run("split-one-short-"+paths,recipes,"P",n,stock,false);
            stock.put("Q",1L);
            run("split-rescued-one-"+paths,recipes,"P",n,stock,true);
        }
        for(int depth:new int[]{256,8192}){
            var c=dag(depth,Long.MAX_VALUE);var recipes=new ArrayList<>(c.recipes());
            recipes.add(0,r("bad",Map.of("scarce",2L),Map.of(c.target(),1L)));var stock=new HashMap<>(c.stock());stock.put("scarce",Long.MAX_VALUE);
            run("deep-alternate-"+depth,recipes,c.target(),c.amount(),stock,true);
        }
        for(int d:new int[]{24,40,60}){
            var c=nested(d);var stock=new HashMap<>(c.stock());stock.compute("R",(k,v)->v-1);
            run("nested-one-short-"+d,c.recipes(),c.target(),1,stock,false);
        }
    }

    record Input(List<GraphRecipe<String>> recipes,String target,long amount,Map<String,Long> stock) {}
    static Input dag(int depth,long n) {
        var recipes=new ArrayList<GraphRecipe<String>>();
        for(int i=0;i<depth;i++)recipes.add(r("r"+i,Map.of(i==0?"R":"Q"+(i-1),1L),Map.of("Q"+i,1L)));
        return new Input(recipes,"Q"+(depth-1),n,Map.of("R",n));
    }
    static Input nested(int depth) {
        var recipes=new ArrayList<GraphRecipe<String>>();long raw=1;
        for(int i=0;i<depth;i++){
            recipes.add(r("open"+i,Map.of("C"+i,1L,"R",1L),Map.of("C"+(i+1),1L)));
            recipes.add(r("close"+i,Map.of("C"+(i+1),1L,"Q"+(i+1),2L),Map.of("C"+i,1L,"Q"+i,1L)));
            raw=Math.addExact(1,Math.multiplyExact(raw,2));
        }
        recipes.add(r("inner",Map.of("C"+depth,1L,"R",1L),Map.of("C"+depth,1L,"Q"+depth,1L)));
        return new Input(recipes,"Q0",1,Map.of("R",raw,"C0",1L));
    }
    static void validate(List<GraphRecipe<String>> recipes,String target,long amount,Map<String,Long> stock,GraphPlan<String> plan) {
        PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);
        plan.initialExact().forEach((k,v)->{if(v.compareTo(BigInteger.valueOf(stock.getOrDefault(k,0L)))>0)throw new AssertionError("Invented stock "+k);});
        var ledger=new HashMap<>(plan.initialExact());var available=new HashSet<>(recipes);
        plan.patternTimesExact().forEach((id,times)->{
            var recipe=plan.recipes().get(id);if(!available.contains(recipe))throw new AssertionError("Foreign recipe "+id);
            recipe.inputs().forEach((k,v)->ledger.merge(k,BigInteger.valueOf(v).multiply(times).negate(),BigInteger::add));
            recipe.outputs().forEach((k,v)->ledger.merge(k,BigInteger.valueOf(v).multiply(times),BigInteger::add));
        });
        ledger.forEach((k,v)->{if(v.signum()<0)throw new AssertionError("Negative "+k);});
        if(ledger.getOrDefault(target,BigInteger.ZERO).compareTo(BigInteger.valueOf(amount))<0)throw new AssertionError("Missing output");
    }
}
