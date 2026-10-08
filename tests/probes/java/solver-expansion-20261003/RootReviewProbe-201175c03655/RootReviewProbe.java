package org.cgse.core;
import java.math.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class RootReviewProbe {
 static int assertions;
 static BigInteger b(long x){return BigInteger.valueOf(x);}
 static void ck(boolean v,String why){assertions++;if(!v)throw new AssertionError(why);}
 static final List<ExactLinearProgram.Constraint> ROWS=List.of(new ExactLinearProgram.Constraint(Map.of(0,b(-1),1,b(-2)),b(-3)));
 static final BigInteger[] LO={b(0),b(0)}, HI={b(2),null};
 static final ExactRational[] POINT={ExactRational.ZERO,new ExactRational(b(3),b(2))};
 static void cleanup(){
  for(int cutoff:new int[]{1,2,4,8,16,32,64}){
   var calls=new AtomicInteger();var budget=new PlanningBudget(0,1_000_000,64L<<20,()->calls.incrementAndGet()>cutoff,System::nanoTime);
   try(var dive=new CountDiving(ROWS,LO,HI,POINT,budget)){while(!dive.step()){} }catch(CancellationException expected){}
   ck(budget.reservedBytes()==0,"cancel cutoff="+cutoff+" leak="+budget.reservedBytes());
  }
  var budget=new PlanningBudget(0,1_000_000,64L<<20,()->false,System::nanoTime);
  try(var dive=new CountDiving(ROWS,LO,HI,POINT,budget)){
   dive.step();Thread.currentThread().interrupt();
   try{dive.step();throw new AssertionError("interrupt ignored");}catch(CancellationException expected){}finally{Thread.interrupted();}
  }ck(budget.reservedBytes()==0,"interrupt close");
  for(long memory:new long[]{1,1024,2048,4096,8192,65536}){
   budget=new PlanningBudget(0,1_000_000,memory,()->false,System::nanoTime);
   try(var dive=new CountDiving(ROWS,LO,HI,POINT,budget)){while(!dive.step()){}var result=dive.counts();if(result!=null)ck(ViewProbe.valid(result,ROWS),"low-memory witness");}
   ck(budget.reservedBytes()==0,"memory rejection close="+memory);
  }
 }
 static void inverse(){
  var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,b(1),1,b(-2)),b(1)),new ExactLinearProgram.Constraint(Map.of(0,b(-1),1,b(2)),b(-1)),new ExactLinearProgram.Constraint(Map.of(0,b(-1),1,b(-1)),b(-5)));
  var budget=new PlanningBudget(0,2_000_000,64L<<20,()->false,System::nanoTime);var lo=new BigInteger[]{b(0),b(0)};var hi=new BigInteger[]{null,null};
  try(var reduction=new CountReduction(rows,lo,hi,budget)){
   while(!reduction.step()){}var point=new ExactRational[reduction.variables()];Arrays.fill(point,new ExactRational(b(7),b(2)));
   try(var dive=new CountDiving(reduction.rows(),reduction.lower(),reduction.upper(),point,budget)){
    while(!dive.step()){}var count=dive.counts();ck(count!=null,"reduced unbounded dive unresolved");var original=reduction.expand(count);ck(original.length==2&&ViewProbe.valid(original,rows),"inverse rows");
    ck(original[0].equals(original[1].multiply(b(2)).add(b(1))),"affine inverse offset");
   }
  }ck(budget.reservedBytes()==0,"inverse lifetime");
 }
 static GraphRecipe<String> recipe(String id,String in,long used,String out,long made){return new GraphRecipe<>(id,id,List.of(new GraphRecipe.Slot<>(in,used)),Map.of(out,made));}
 static void macros(){
  long[][] rates={{2,3,5,7},{1,Long.MAX_VALUE,Long.MAX_VALUE-1,1},{Long.MAX_VALUE,1,2,1},{2,4,3,6},{3,1,7,2}};
  for(var rate:rates){
   var originals=new LinkedHashMap<String,GraphRecipe<String>>();originals.put("r0",recipe("r0","A",rate[0],"B",rate[1]));originals.put("r1",recipe("r1","B",rate[2],"C",rate[3]));
   var budget=new PlanningBudget(0,1_000_000,64L<<20,()->false,System::nanoTime);var macro=new LinearMacroCompilation<>(originals,"C",Set.of(),budget);while(!macro.step()){}
   ck(macro.recipes().entrySet().containsAll(originals.entrySet()),"original routes removed");
   for(var e:macro.recipes().entrySet())if(!originals.containsKey(e.getKey()))for(long runs:new long[]{1,2,Long.MAX_VALUE}){
    var expanded=macro.expand(new PlanStep.Batch(e.getKey(),runs));var summary=SequenceSummary.of(expanded,originals);var expected=SequenceSummary.recipe(e.getValue()).repeat(runs);
    for(String key:List.of("A","B","C")){ck(summary.delta(key).equals(expected.delta(key)),"macro delta "+key);ck(summary.required(key).equals(expected.required(key)),"macro prefix "+key);}
    var counter=new PlanCountComputation(expanded);while(!counter.step(budget)){}var actual=counter.result();for(var count:macro.counts(e.getKey()).entrySet())ck(actual.get(count.getKey()).equals(count.getValue().multiply(b(runs))),"inverse count");
   }
  }
  var loop=Map.of("a",recipe("a","A",2,"B",3),"b",recipe("b","B",3,"A",2));var budget=new PlanningBudget(0,100000,()->false);var macro=new LinearMacroCompilation<>(loop,"A",Set.of(),budget);while(!macro.step()){}ck(macro.recipes().size()==2,"cycle macro invented");
 }
 public static void main(String[]args){cleanup();inverse();macros();System.out.println("PASS directed root review assertions="+assertions+" cancellation/interruption/low-memory, reduced unbounded inverse, ratio prefix/counts through Long.MAX_VALUE, unsafe ratio rejection, cycles preserved");}
}
