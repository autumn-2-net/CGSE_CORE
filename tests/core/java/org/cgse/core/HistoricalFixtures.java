// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;
import java.math.BigInteger;

/** Original generators and independent prefix arithmetic, without the native benchmark dependencies. */
final class HistoricalFixtures {
    record Fixture(String name,List<GraphRecipe<String>> recipes,Map<String,Long> stock,String target,long amount,boolean dag) {}
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    static Fixture sat(int n,int m,int seed) {
        Random rng=new Random(seed);boolean[] planted=new boolean[n];for(int i=0;i<n;i++)planted[i]=rng.nextBoolean();
        List<int[]> clauses=new ArrayList<>();
        while(clauses.size()<m){
            int[] c=new int[3];Set<Integer> used=new HashSet<>();boolean satisfied=false;
            for(int j=0;j<3;j++){int v;do{v=rng.nextInt(n);}while(!used.add(v));boolean positive=rng.nextBoolean();c[j]=positive?v+1:-v-1;satisfied|=planted[v]==positive;}
            if(satisfied)clauses.add(c);
        }
        List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>(),goalIn=new LinkedHashMap<>();
        for(int i=0;i<n;i++){
            stock.put("U"+i,1L);goalIn.put("D"+i,1L);
            for(int val=0;val<2;val++){
                Map<String,Long> outputs=new LinkedHashMap<>();outputs.put("D"+i,1L);
                for(int j=0;j<m;j++)for(int lit:clauses.get(j))if(Math.abs(lit)==i+1 && (lit>0)==(val==1))outputs.put("C"+j,1L);
                rs.add(r("v"+i+"_"+val,Map.of("U"+i,1L),outputs));
            }
        }
        for(int j=0;j<m;j++)goalIn.put("C"+j,1L);
        rs.add(r("finish",goalIn,Map.of("GOAL",1L)));
        return new Fixture("sat_n"+n+"_m"+m+"_seed"+seed,List.copyOf(rs),stock,"GOAL",1,true);
    }
    static Fixture multi(int n,int dims,int scale,int seed,int shift) {
        Random rng=new Random(seed);List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>(),need=new LinkedHashMap<>();
        for(int i=0;i<n;i++){
            String u="U"+i;stock.put(u,1L);boolean chosen=rng.nextBoolean();Map<String,Long> a=new LinkedHashMap<>(),b=new LinkedHashMap<>();
            for(int d=0;d<dims;d++){long w=1+rng.nextInt(scale);a.put("X"+d,w);b.put("Y"+d,w);need.merge((chosen?"X":"Y")+d,w,Long::sum);}
            rs.add(r("a"+i,Map.of(u,1L),a));rs.add(r("b"+i,Map.of(u,1L),b));
        }
        if(shift!=0){need.merge("X0",(long)shift,Long::sum);need.merge("Y0",-(long)shift,Long::sum);}
        need.values().removeIf(v->v==0);rs.add(r("finish",need,Map.of("GOAL",1L)));
        return new Fixture("multi_n"+n+"_d"+dims+"_w"+scale+"_s"+seed+"_shift"+shift,rs,stock,"GOAL",1,true);
    }
    static Fixture explicitPairs(Fixture base) {
        List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> need=new LinkedHashMap<>(base.recipes().get(base.recipes().size()-1).inputs());
        for(int i=0;i<base.recipes().size()-1;i++) {var r=base.recipes().get(i);Map<String,Long> outputs=new LinkedHashMap<>(r.outputs());outputs.put("PAIR"+(i/2),1L);need.put("PAIR"+(i/2),1L);rs.add(r(r.id(),r.inputs(),outputs));}
        rs.add(r("finish",need,Map.of("GOAL",1L)));return new Fixture(base.name()+"_explicit_pairs",rs,base.stock(),base.target(),base.amount(),true);
    }
    static Fixture randomSat(int n,int m,int seed) {
        Random rng=new Random(seed);List<int[]> clauses=new ArrayList<>();
        for(int j=0;j<m;j++){int[] c=new int[3];Set<Integer> used=new HashSet<>();for(int k=0;k<3;k++){int v;do{v=rng.nextInt(n);}while(!used.add(v));c[k]=rng.nextBoolean()?v+1:-v-1;}clauses.add(c);}
        List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>(),need=new LinkedHashMap<>();
        for(int i=0;i<n;i++){stock.put("U"+i,1L);need.put("D"+i,1L);for(int v=0;v<2;v++){Map<String,Long> outputs=new LinkedHashMap<>();outputs.put("D"+i,1L);for(int j=0;j<m;j++)for(int lit:clauses.get(j))if(Math.abs(lit)==i+1&&(lit>0)==(v==1))outputs.put("C"+j,1L);rs.add(r("v"+i+"_"+v,Map.of("U"+i,1L),outputs));}}
        for(int j=0;j<m;j++)need.put("C"+j,1L);rs.add(r("finish",need,Map.of("GOAL",1L)));
        return new Fixture("randomsat_n"+n+"_m"+m+"_s"+seed,rs,stock,"GOAL",1,true);
    }
    record Summary(Map<String,BigInteger> need,Map<String,BigInteger> delta,int nodes) {}
    static Summary summary(PlanStep step,Map<String,GraphRecipe<String>> recipes){
        if(step instanceof PlanStep.Batch b){
            GraphRecipe<String> r=recipes.get(b.recipe());Map<String,BigInteger> need=new LinkedHashMap<>(),delta=new LinkedHashMap<>();Set<String> keys=new LinkedHashSet<>(r.inputs().keySet());keys.addAll(r.outputs().keySet());
            BigInteger n=BigInteger.valueOf(b.runs());
            if(n.signum()==0)return new Summary(need,delta,1);
            for(String k:keys){BigInteger in=BigInteger.valueOf(r.inputs().getOrDefault(k,0L)),d=BigInteger.valueOf(r.outputs().getOrDefault(k,0L)).subtract(in);need.put(k,in.add(d.negate().max(BigInteger.ZERO).multiply(n.subtract(BigInteger.ONE))));delta.put(k,d.multiply(n));}
            return new Summary(need,delta,1);
        }
        if(step instanceof PlanStep.Repeat r){
            Summary s=summary(r.body(),recipes);Map<String,BigInteger> need=new LinkedHashMap<>(),delta=new LinkedHashMap<>();BigInteger n=BigInteger.valueOf(r.times());
            if(n.signum()==0)return new Summary(need,delta,s.nodes+1);
            for(String k:s.need.keySet()){BigInteger d=s.delta.getOrDefault(k,BigInteger.ZERO);need.put(k,s.need.get(k).add(d.negate().max(BigInteger.ZERO).multiply(n.subtract(BigInteger.ONE))));delta.put(k,d.multiply(n));}
            return new Summary(need,delta,s.nodes+1);
        }
        Map<String,BigInteger> need=new LinkedHashMap<>(),delta=new LinkedHashMap<>();int nodes=1;
        for(PlanStep child:((PlanStep.Sequence)step).children()){
            Summary s=summary(child,recipes);nodes+=s.nodes;
            for(String k:s.need.keySet())need.merge(k,s.need.get(k).subtract(delta.getOrDefault(k,BigInteger.ZERO)).max(BigInteger.ZERO),BigInteger::max);
            s.delta.forEach((k,v)->delta.merge(k,v,BigInteger::add));
        }
        return new Summary(need,delta,nodes);
    }
    static void verify(Fixture f,Summary s){
        for(var e:s.need.entrySet())if(e.getValue().compareTo(BigInteger.valueOf(f.stock.getOrDefault(e.getKey(),0L)))>0)throw new AssertionError("Unfunded prefix "+f.name+" "+e);
        if(BigInteger.valueOf(f.stock.getOrDefault(f.target,0L)).add(s.delta.getOrDefault(f.target,BigInteger.ZERO)).compareTo(BigInteger.valueOf(f.amount))<0)throw new AssertionError("Unmet goal");
    }
}
