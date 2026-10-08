package org.cgse.core;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.reflect.*;
public final class MacroLifetimeProbe {
 static int checks;
 static void ck(boolean b,String reason){checks++;if(!b)throw new AssertionError(reason);}
 static Map<String,GraphRecipe<String>> chain(int size){var result=new LinkedHashMap<String,GraphRecipe<String>>();for(int i=0;i<size;i++){var r=RootReviewProbe.recipe("r"+i,"X"+i,2+(i%3),"X"+(i+1),3+(i%2));result.put(r.id(),r);}return result;}
 static void direct(){
  var originals=chain(8);
  for(int stop:new int[]{1,2,4,8,16,32,64,128}){
   var calls=new AtomicInteger();var budget=new PlanningBudget(0,1_000_000,64L<<20,()->calls.incrementAndGet()>stop,System::nanoTime);
   try(var macro=new LinearMacroCompilation<>(originals,"X8",Set.of(),budget)){while(!macro.step()){} }catch(CancellationException expected){}
   ck(budget.reservedBytes()==0,"macro compile cancel "+stop+" leak "+budget.reservedBytes());
  }
  for(int stop:new int[]{1,2,4,8,16,32,64}){
   var calls=new AtomicInteger();var limit=new AtomicInteger(Integer.MAX_VALUE);var budget=new PlanningBudget(0,1_000_000,64L<<20,()->calls.incrementAndGet()>limit.get(),System::nanoTime);
   try(var macro=new LinearMacroCompilation<>(originals,"X8",Set.of(),budget)){
    while(!macro.step()){}String id=macro.recipes().keySet().stream().filter(s->!originals.containsKey(s)).findFirst().orElseThrow();
    var kept=macro.expand(new PlanStep.Batch(id,1));long once=budget.reservedBytes();macro.expand(new PlanStep.Batch(id,2));ck(budget.reservedBytes()==once,"abandoned expansion accumulates");
    limit.set(calls.get()+stop);
    try{macro.expand(new PlanStep.Sequence(Collections.nCopies(16,new PlanStep.Batch(id,3))));}catch(CancellationException expected){}
    ck(SequenceSummary.of(kept,originals).delta("X8").signum()>0,"old witness damaged by next expansion");
   }
   ck(budget.reservedBytes()==0,"macro expand cancel "+stop+" leak "+budget.reservedBytes());
  }
 }
 static void allocation()throws Exception{
  var rs=chain(40);var field=AllocationSearch.class.getDeclaredField("phase");field.setAccessible(true);
  for(int phase:new int[]{0,5})for(int extra:new int[]{0,1,4,12,30}){
   var cancel=new AtomicBoolean();var budget=new PlanningBudget(0,5_000_000,64L<<20,cancel::get,System::nanoTime);
   var search=new AllocationSearch<>(new GraphCompiler<>(List.copyOf(rs.values())),"X40",1,Map.of("X0",100000L),Set.of(),Map.of(),false,true,Set.of(),budget,System.nanoTime());
   try{
    while(field.getInt(search)!=phase){ck(!search.step(),"allocation ended before phase");}
    for(int i=0;i<extra;i++)search.step();cancel.set(true);
    try{search.step();throw new AssertionError("cancel ignored");}catch(CancellationException expected){}
   }finally{search.discard();}
   ck(budget.reservedBytes()==0,"allocation phase="+phase+" offset="+extra+" leak="+budget.reservedBytes());
  }
 }
 public static void main(String[]args)throws Exception{direct();allocation();System.out.println("PASS macro lifecycle checks="+checks+" constructor/index/expansion cancel, replacement accounting, preserved witness, AllocationSearch phase0/5 discard release=0");}
}
