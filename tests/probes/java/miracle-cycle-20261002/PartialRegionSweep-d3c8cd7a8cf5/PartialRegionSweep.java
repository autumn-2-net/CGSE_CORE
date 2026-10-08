package org.cgse.core;
import java.util.*;
import java.math.BigInteger;

public final class PartialRegionSweep {
    public static void main(String[] args) {
        int passed=0,failed=0;long work=0;var random=new Random(20261002);
        for(int sample=0;sample<192;sample++) {
            int n=3+sample%4;long amount=1+random.nextInt(100000);
            long[] inputs=new long[n],outputs=new long[n];
            for(int i=0;i<n-1;i++){inputs[i]=1+random.nextInt(97);outputs[i]=1+random.nextInt(25);}
            inputs[n-1]=1_000_000_000;outputs[n-1]=1;
            var counts=new BigInteger[n-1];BigInteger need=BigInteger.valueOf(amount);
            for(int i=n-2;i>=0;i--){counts[i]=CheckedAmounts.ceilDiv(need,BigInteger.valueOf(outputs[i]));need=counts[i].multiply(BigInteger.valueOf(inputs[i]));}
            var stock=new LinkedHashMap<String,Long>();stock.put("N0",need.longValueExact());stock.put("RAW",Long.MAX_VALUE);
            var recipes=new ArrayList<GraphRecipe<String>>();
            for(int i=0;i<n;i++)recipes.add(new GraphRecipe<>("R"+i,"R"+i,List.of(new GraphRecipe.Slot<>("N"+i,inputs[i]),new GraphRecipe.Slot<>("RAW",1)),Map.of("N"+((i+1)%n),outputs[i])));
            Collections.shuffle(recipes,random);var compiler=new GraphCompiler<>(recipes);
            for(int pass=0;pass<3;pass++) {
                var b=new PlanningBudget(0,300_000,64L<<20,()->false,System::nanoTime);
                var planner=new GraphPlanningWork<>(compiler,"N"+(n-1),amount,stock,true,true,b).catalysts(CatalystPolicy.MINIMAL);
                GraphPlan<String> plan;
                try {while(!planner.step()){}plan=planner.result();}finally{planner.close();}
                work+=b.nodes();
                if(!plan.feasible()) {failed++;System.out.println("FAIL sample="+sample+" n="+n+" pass="+pass+" amount="+amount+" in="+Arrays.toString(inputs)+" out="+Arrays.toString(outputs)+" result="+plan.result()+" work="+b.nodes()+" trace="+b.diagnostics());}
                else {PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);passed++;}
            }
        }
        System.out.println("PARTIAL SWEEP feasible="+passed+" failed="+failed+" work="+work);
        if(failed>0)throw new AssertionError("Known executable partial paths failed: "+failed);
    }
}
