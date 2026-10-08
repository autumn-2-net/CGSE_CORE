package org.cgse.core;
import java.math.BigInteger;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public class SupportScopeProbe {
 static GraphRecipe<String> r(String id,long yield){return new GraphRecipe<>(id,id,List.of(new GraphRecipe.Slot<>("P",1L)),Map.of("T",yield));}
 public static void main(String[] args)throws Exception {
  var compiler=new GraphCompiler<>(List.of(r("one",1)));var journal=new CountProof.Journal(1L<<20);int scopes=0,proofs=0,cancelled=0;
  for(boolean force:new boolean[]{false,true})for(long initial:new long[]{0,1,2,1000,Long.MAX_VALUE})for(long amount:new long[]{1,2,3,Long.MAX_VALUE}){
   var b=new PlanningBudget(0,40_000_000,128L<<20,()->false,System::nanoTime);b.proofJournal(journal);
   try(var model=RecipeCountModel.create(compiler,"T",amount,Map.of("T",initial,"P",1L),Map.of(),Set.of(),Set.of(),force,b);var search=CountSupportSearch.allSources(model,b)){
    while(!search.step()){}
    if(search.result()==CountSupportSearch.Result.WITNESS){
     var counts=search.counts();BigInteger produced=Arrays.stream(counts).reduce(BigInteger.ZERO,BigInteger::add);
     if(force&&produced.compareTo(BigInteger.valueOf(amount))<0)throw new AssertionError("production not checked");
     if(BigInteger.valueOf(initial).add(produced).compareTo(BigInteger.valueOf(amount))<0)throw new AssertionError("stock not checked");
    }
    if(search.result()==CountSupportSearch.Result.CLOSED){if(BigInteger.valueOf(initial).add(BigInteger.ONE).compareTo(BigInteger.valueOf(amount))>=0)throw new AssertionError("bad projected material proof");proofs++;}
   }
   if(b.reservedBytes()!=0)throw new AssertionError("scope leak");scopes++;
  }
  Path archive=Path.of(args[0]);journal.write(archive);var loaded=CountProof.read(archive);
  if(loaded.executions().isEmpty())throw new AssertionError("no projected certificates");
  for(var proof:loaded.executions())if(ExecutionProof.verify(proof,2_000_000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("invalid replay");
  for(int stop=1;stop<=256;stop++){
   var tick=new AtomicInteger();final int boundary=stop;var b=new PlanningBudget(0,40_000_000,128L<<20,()->tick.incrementAndGet()>=boundary,System::nanoTime);
   try(var model=RecipeCountModel.create(compiler,"T",3,Map.of("P",2L),Map.of(),Set.of(),Set.of(),true,b)){
    if(model!=null)try(var search=CountSupportSearch.allSources(model,b)){while(!search.step()){}}
   }catch(CancellationException expected){cancelled++;}
   if(b.reservedBytes()!=0)throw new AssertionError("cancel leak "+stop+" "+b.reservedBytes());
  }
  var b=new PlanningBudget(0,40_000_000,128L<<20,()->false,System::nanoTime);
  try(var model=RecipeCountModel.create(new GraphCompiler<>(List.of(r("huge",Long.MAX_VALUE))),"T",Long.MAX_VALUE,Map.of("P",1L,"T",Long.MAX_VALUE),Map.of(),Set.of(),Set.of(),true,b);var search=CountSupportSearch.allSources(model,b)){
   while(!search.step()){}if(search.result()!=CountSupportSearch.Result.WITNESS||!search.counts()[0].equals(BigInteger.ONE))throw new AssertionError("long production boundary");
  }
  if(b.reservedBytes()!=0)throw new AssertionError("long leak");
  System.out.println("PASS scopes="+scopes+" closed_material_proofs="+proofs+" serialized_replays="+loaded.executions().size()+" cancellation_boundaries=256 actual_interruptions="+cancelled+" long_max_exact=true");
 }
}
