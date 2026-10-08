package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

public final class LoopRuntimeReview {
    public static void main(String[] args) {
        PlanStep body = new PlanStep.Sequence(List.of(new PlanStep.Batch("a",1),new PlanStep.Batch("b",1)));
        for (int i=0;i<60;i++) body = new PlanStep.Sequence(List.of(body,body));
        if (!LoopBatching.counts(body).equals(Map.of("a",BigInteger.ONE.shiftLeft(60),"b",BigInteger.ONE.shiftLeft(60))))
            throw new AssertionError("Shared loop count expanded");
        Random random = new Random(341928);
        int accepted=0;
        for (int trial=0;trial<5000;trial++) {
            Map<String,GraphRecipe<String>> recipes = new LinkedHashMap<>();
            Map<String,Long> stock = new LinkedHashMap<>();
            for (int key=0;key<5;key++) stock.put("k"+key,(long)random.nextInt(50));
            List<PlanStep> parts = new ArrayList<>();
            for (int i=0,n=2+random.nextInt(5);i<n;i++) {
                Map<String,Long> inputs=new LinkedHashMap<>(),outputs=new LinkedHashMap<>();
                for (String key:stock.keySet()) {
                    long take=random.nextInt(4),give=random.nextInt(4);
                    if(take>0)inputs.put(key,take); if(give>0)outputs.put(key,give);
                }
                if(outputs.isEmpty())outputs.put("k0",1L);
                String id="r"+i;
                recipes.put(id,new GraphRecipe<>(id,id,inputs.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),outputs));
                parts.add(new PlanStep.Batch(id,1+random.nextInt(3)));
            }
            PlanStep program=new PlanStep.Sequence(parts);
            for(int i=0,n=random.nextInt(4);i<n;i++)program=new PlanStep.Sequence(List.of(program,program));
            Map<String,BigInteger> counts=LoopBatching.counts(program);
            long iterations=LoopBatching.iterations(counts,20,recipes,key->BigInteger.valueOf(stock.getOrDefault(key,0L)));
            if(iterations<2)continue;
            accepted++;
            Map<String,BigInteger> current=new HashMap<>();stock.forEach((k,v)->current.put(k,BigInteger.valueOf(v)));
            for(var entry:counts.entrySet()) {
                GraphRecipe<String> recipe=recipes.get(entry.getKey());
                BigInteger runs=entry.getValue().multiply(BigInteger.valueOf(iterations));
                for(String key:stock.keySet()) {
                    BigInteger take=BigInteger.valueOf(recipe.inputs().getOrDefault(key,0L));
                    BigInteger net=BigInteger.valueOf(recipe.outputs().getOrDefault(key,0L)).subtract(take);
                    BigInteger held=current.get(key),required=take.add(net.negate().max(BigInteger.ZERO).multiply(runs.subtract(BigInteger.ONE)));
                    if(held.compareTo(required)<0)throw new AssertionError("Unfunded regrouping "+trial);
                    BigInteger after=held.add(net.multiply(runs));
                    if(after.signum()<0||after.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0)throw new AssertionError("Invalid regrouped capacity");
                    current.put(key,after);
                }
            }
        }
        System.out.println("PASS: 5000 independently checked loop regrouping trials, "+accepted+" accepted regroupings; 60-level shared loop");
    }
}
