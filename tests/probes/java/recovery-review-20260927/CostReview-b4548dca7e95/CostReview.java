package org.cgse.core;
import java.math.BigInteger;import java.util.*;

public final class CostReview {
    record Key(int id,long units){}
    static GraphRecipe<Key> recipe(String id,Map<Key,Long> input,Map<Key,Long> output) {
        return new GraphRecipe<>(id,id,input.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),output);
    }
    static BigInteger compact(GraphPlan<Key> p){return CraftingCostModel.bytes(p,CraftingCostModel.Mode.COMPACT,Key::units);}
    static void expect(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    static BigInteger independent(Map<Key,BigInteger> amounts) {
        BigInteger denominator=BigInteger.ONE;
        for(Key key:amounts.keySet())denominator=denominator.multiply(BigInteger.valueOf(key.units));
        BigInteger numerator=BigInteger.ZERO;
        for(var e:amounts.entrySet())numerator=numerator.add(e.getValue().multiply(denominator.divide(BigInteger.valueOf(e.getKey().units))));
        return numerator.add(denominator).subtract(BigInteger.ONE).divide(denominator);
    }
    public static void main(String[] args) {
        Key iron=new Key(0,8),copper=new Key(1,8),target=new Key(2,8),cat=new Key(3,8);
        var r=recipe("r",Map.of(iron,7L,copper,7L),Map.of(target,1L));
        var one=new GraphPlan<>(target,1,true,new PlanStep.Batch("r",1),Map.of("r",r),Map.of(iron,7L,copper,7L),Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
        expect(compact(one),BigInteger.TEN); // ceil(7/8+7/8) + 8
        var cycle=recipe("loop",Map.of(cat,1L),Map.of(cat,1L,target,1L));
        var huge=new GraphPlan<>(target,Long.MAX_VALUE,true,new PlanStep.Batch("loop",Long.MAX_VALUE),Map.of("loop",cycle),Map.of(cat,1L),Map.of(cat,1L),Map.of(),GraphPlan.Result.FEASIBLE,9999999,12345);
        expect(compact(huge),BigInteger.valueOf(9));
        var missing=new GraphPlan<>(target,Long.MAX_VALUE,true,huge.steps(),huge.recipes(),huge.initialExact(),huge.seeds(),Map.of(cat,1L),GraphPlan.Result.MISSING_SEED,0,0);
        expect(compact(huge),compact(missing));
        BigInteger beyond=BigInteger.ONE.shiftLeft(90).add(BigInteger.ONE);
        var large=new GraphPlan<>(target,Long.MAX_VALUE,true,huge.steps(),huge.recipes(),Map.of(cat,beyond),Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
        expect(compact(large),beyond.add(BigInteger.valueOf(7)).divide(BigInteger.valueOf(8)).add(BigInteger.valueOf(8)));
        expect(ExactAmounts.capped(compact(large)),Long.MAX_VALUE);
        Random random=new Random(902710);long[] units={8,8000,7,13,1024};
        for(int trial=0;trial<5000;trial++) {
            Map<String,GraphRecipe<Key>> recipes=new LinkedHashMap<>();Map<Key,BigInteger> initial=new LinkedHashMap<>();List<PlanStep> plain=new ArrayList<>(),packed=new ArrayList<>();
            int size=1+random.nextInt(9);BigInteger executions=BigInteger.ZERO;
            for(int i=0;i<size;i++) {
                Key raw=new Key(10+i,units[random.nextInt(units.length)]);long batch=1+random.nextInt(11),count=1+random.nextInt(20);
                var next=recipe("r"+i,Map.of(raw,batch),Map.of(target,1L));recipes.put(next.id(),next);
                initial.put(raw,BigInteger.valueOf(count*batch));
                for(int j=0;j<count;j++)plain.add(new PlanStep.Batch(next.id(),1));
                packed.add(PlanStep.repeat(new PlanStep.Batch(next.id(),1),BigInteger.valueOf(count)));
                executions=executions.add(BigInteger.valueOf(count));
            }
            var expanded=new GraphPlan<>(target,executions.longValueExact(),true,new PlanStep.Sequence(plain),recipes,initial,Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
            var compressed=new GraphPlan<>(target,executions.longValueExact(),true,new PlanStep.Sequence(packed),recipes,initial,Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,999999,999999);
            BigInteger expected=independent(initial).add(BigInteger.valueOf(8L*size));
            expect(compact(expanded),expected);expect(compact(compressed),expected);
            Map<Key,BigInteger> oldMaterial=new HashMap<>(initial);oldMaterial.put(target,executions);oldMaterial.replaceAll((key,n)->n.multiply(BigInteger.valueOf(8)));
            BigInteger old=independent(oldMaterial).add(executions).add(PlanNodeCost.count(expanded,recipes.keySet()).multiply(BigInteger.valueOf(8)));
            expect(CraftingCostModel.bytes(expanded,CraftingCostModel.Mode.LEGACY,Key::units),old);
            expect(CraftingCostModel.bytes(compressed,CraftingCostModel.Mode.LEGACY,Key::units),old);
        }
        System.out.println("PASS 5000 exact item/fluid/general-unit costs, unchanged LEGACY, compression invariance, missing/seed partition, Long.MAX_VALUE runs and >long byte totals");
    }
}
