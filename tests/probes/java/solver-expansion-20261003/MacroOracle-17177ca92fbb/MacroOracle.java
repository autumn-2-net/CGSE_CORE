package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
public final class MacroOracle {
 static int checked;
 static void verify(LinearMacroCompilation<String> macro,Map<String,GraphRecipe<String>> originals){
  for(var merged:macro.recipes().values())if(!originals.containsKey(merged.id())){
   var counts=macro.counts(merged.id());for(int repeats:new int[]{1,7,999}){
    var held=new HashMap<String,BigInteger>();merged.inputs().forEach((k,v)->held.put(k,BigInteger.valueOf(v).multiply(BigInteger.valueOf(repeats))));
    var cursor=new PlanCursor(macro.expand(new PlanStep.Batch(merged.id(),repeats)));var seen=new HashMap<String,BigInteger>();
    for(var batch=cursor.current();batch!=null;batch=cursor.current()){
     var r=originals.get(batch.recipe());if(r==null)throw new AssertionError("macro not restored");var n=BigInteger.valueOf(batch.runs());
     for(var in:r.inputs().entrySet()){var need=BigInteger.valueOf(in.getValue()).multiply(n);if(held.getOrDefault(in.getKey(),BigInteger.ZERO).compareTo(need)<0)throw new AssertionError("unfunded expanded macro");held.merge(in.getKey(),need.negate(),BigInteger::add);}
     r.outputs().forEach((k,v)->held.merge(k,BigInteger.valueOf(v).multiply(n),BigInteger::add));seen.merge(r.id(),n,BigInteger::add);cursor.dispatched(batch.runs());
    }
    for(var e:held.entrySet()){var expected=BigInteger.valueOf(merged.outputs().getOrDefault(e.getKey(),0L)).multiply(BigInteger.valueOf(repeats));if(!e.getValue().equals(expected))throw new AssertionError("stoichiometry changed "+merged.id()+" "+held);}
    for(var e:counts.entrySet())if(!e.getValue().multiply(BigInteger.valueOf(repeats)).equals(seen.get(e.getKey())))throw new AssertionError("inverse counts changed");checked++;
   }
  }
 }
 public static void main(String[]args){
  var rnd=new Random(31620261003L);int systems=0;
  for(int sample=0;sample<360;sample++)for(boolean order:new boolean[]{false,true}){
   int size=2+rnd.nextInt(5);var rs=new ArrayList<GraphRecipe<String>>();for(int i=0;i<size;i++)rs.add(OrderProbe.recipe("r"+i,Map.of("K"+i,1L+rnd.nextInt(9)),Map.of("K"+(i+1),1L+rnd.nextInt(9))));
   if(order)Collections.shuffle(rs,rnd);var map=new LinkedHashMap<String,GraphRecipe<String>>();rs.forEach(r->map.put(r.id(),r));var budget=new PlanningBudget(0,2_000_000,64L<<20,()->false,System::nanoTime);
   try(var macro=new LinearMacroCompilation<>(map,"K"+size,Set.of(),budget)){while(!macro.step()){}if(macro.recipes().size()<=rs.size())throw new AssertionError("ratio chain not compiled");verify(macro,map);}if(budget.reservedBytes()!=0)throw new AssertionError("macro memory leak");systems++;
  }
  var huge=new LinkedHashMap<String,GraphRecipe<String>>();for(int i=0;i<12;i++)huge.put("h"+i,OrderProbe.recipe("h"+i,Map.of("H"+i,97L),Map.of("H"+(i+1),101L)));
  var budget=new PlanningBudget(0,2_000_000,64L<<20,()->false,System::nanoTime);try(var macro=new LinearMacroCompilation<>(huge,"H12",Set.of(),budget)){while(!macro.step()){}if(!macro.recipes().keySet().equals(huge.keySet()))throw new AssertionError("overflow macro accepted");}if(budget.reservedBytes()!=0)throw new AssertionError("overflow memory leak");
  System.out.println("PASS ratio_systems="+systems+" exact_expanded_batches="+checked+" overflow_skip=1 false_witness=0 memory_leaks=0");
 }
}
