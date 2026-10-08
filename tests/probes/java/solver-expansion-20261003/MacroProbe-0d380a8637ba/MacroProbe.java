package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
public final class MacroProbe {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[]args){Random r=new Random(20261003);int macros=0,checks=0;
  for(int sample=0;sample<1300;sample++){
   var original=new LinkedHashMap<String,GraphRecipe<String>>();int n=2+r.nextInt(10);
   for(int i=0;i<n;i++){long in=1+r.nextInt(12),out=1+r.nextInt(12);if(sample==0){in=1;out=1;} if(sample==1){in=i%2==0?Long.MAX_VALUE:1;out=i%2==0?1:Long.MAX_VALUE;}
    var recipe=new GraphRecipe<String>("r"+i,"r"+i,List.of(new GraphRecipe.Slot<>("k"+i,in)),Map.of("k"+(i+1),out));original.put(recipe.id(),recipe);
   }
   var budget=new PlanningBudget(0,2000000,64L<<20,()->false,System::nanoTime);
   var c=new LinearMacroCompilation<>(original,"k"+n,Set.of(),budget);while(!c.step()){}
   check(c.recipes().entrySet().containsAll(original.entrySet()),"lost original");
   for(var e:c.recipes().entrySet())if(!original.containsKey(e.getKey())){macros++;
    for(BigInteger count:List.of(BigInteger.ONE,BigInteger.valueOf(3),BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))){
     var compact=PlanStep.batch(e.getKey(),count);var restored=c.expand(compact);
     var a=SequenceSummary.of(compact,c.recipes());var b=SequenceSummary.of(restored,original);var keys=new HashSet<>(a.keys());keys.addAll(b.keys());
     for(var key:keys){check(a.delta(key).equals(b.delta(key)),"delta sample="+sample+" "+key);check(a.required(key).equals(b.required(key)),"prefix sample="+sample+" "+key);checks+=2;}
     var actual=PlanCountComputation.of(restored);for(var f:c.counts(e.getKey()).entrySet()){check(f.getValue().multiply(count).equals(actual.get(f.getKey())),"count inverse");checks++;}
    }
   }
  }
  System.out.println("PASS ratio_chain_samples=1300 macros="+macros+" assertions="+checks+" exact_prefix_and_delta_restoration=true");
 }
}
