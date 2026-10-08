package org.cgse.core;
import java.math.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class MechanismBoundaryOracle {
 static final BigInteger Z=BigInteger.ZERO,O=BigInteger.ONE;
 static PlanningBudget budget(){return new PlanningBudget(0,20000000,256L<<20,()->false,System::nanoTime);}
 static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 public static void main(String[]args)throws Exception {
Files.createDirectories(Path.of("build/test-artifacts/algorithm-review"));
  var recipes=List.of(recipe("move",Map.of("A",1L),Map.of("B",1L)));
  var names=List.of("A","B","Q");var initial=List.of(O,Z,Z);var goal=List.of(Z,Z,O);
  CountInduction.Proof saved;
  var b=budget();var j=new CountProof.Journal(32L<<20);b.proofJournal(j);
  try(var p=new CountInduction<>(recipes,names,initial,goal,b,1000000)){while(!p.step()){}check(p.result()==BackwardCoverability.Result.CLOSED,"induction did not close");saved=p.proof();}
  check(b.reservedBytes()==0,"proof memory");check(j.inductions().size()==1,"missing combined proof");
  var path=Path.of("build/test-artifacts/algorithm-review/induction.cgp");j.write(path);var read=CountProof.read(path);
  check(read.inductions().equals(j.inductions()),"archive round trip");CountProof.main(new String[]{path.toString()});
  check(CountInduction.verify(saved,1,u->{})==CountProof.Verdict.INCOMPLETE,"bounded independent check");
  var falseProblem=new CountInduction.Problem(List.of(Z,Z,O),goal,saved.problem().inputs(),saved.problem().outputs());
  check(CountInduction.verify(new CountInduction.Proof(falseProblem,saved.depth(),saved.base(),saved.induction()),2000000,u->{})==CountProof.Verdict.INVALID,"reused stock proof");
  check(CountInduction.verify(new CountInduction.Proof(saved.problem(),saved.depth()+1,saved.base(),saved.induction()),2000000,u->{})==CountProof.Verdict.INVALID,"wrong depth");
  check(CountInduction.verify(new CountInduction.Proof(saved.problem(),saved.depth(),saved.base(),saved.base()),2000000,u->{})==CountProof.Verdict.INVALID,"wrong induction step");
  var tiny=new CountProof.Journal(1);tiny.add(saved);check(tiny.truncated()&&tiny.inductions().isEmpty(),"truncation");
  for(int cutoff=1;cutoff<=384;cutoff++){
   var ticks=new AtomicInteger();int limit=cutoff;
   var cut=new PlanningBudget(0,20000000,256L<<20,()->ticks.incrementAndGet()>=limit,System::nanoTime);
   try(var p=new CountInduction<>(recipes,names,initial,goal,cut,1000000)){while(!p.step()){}}catch(CancellationException expected){}
   check(cut.reservedBytes()==0,"induction cancel leak "+cutoff);
  }
  for(long memory:new long[]{1,4096,1L<<20,2L<<20,3L<<20}){
   var small=new PlanningBudget(0,20000000,memory,()->false,System::nanoTime);
   try(var p=new CountInduction<>(recipes,names,initial,goal,small,1000000)){while(!p.step()){}check(p.result()!=BackwardCoverability.Result.WITNESS,"false low memory witness");}
   check(small.reservedBytes()==0,"induction memory threshold");
  }
  var huge=O.shiftLeft(120);b=budget();
  try(var p=new CountInduction<>(recipes,names,List.of(huge,Z,Z),List.of(Z,Z,huge),b,1000000)){while(!p.step()){}check(p.result()==BackwardCoverability.Result.CLOSED,"big integer induction");check(CountInduction.verify(p.proof(),2000000,u->{})==CountProof.Verdict.VERIFIED,"big proof");}
  check(b.reservedBytes()==0,"large induction leak");
  var soft=new ArrayList<CountSoftSearch.Soft>();
  for(int i=0;i<10;i++)soft.add(new CountSoftSearch.Soft(new ExactLinearProgram.Constraint(Map.of(0,i<5?O:O.negate()),i<5?Z:BigInteger.TWO.negate()),O.shiftLeft(100).multiply(BigInteger.valueOf(i+1))));
  for(int cutoff=1;cutoff<=512;cutoff++){
   var ticks=new AtomicInteger();int limit=cutoff;var cut=new PlanningBudget(0,20000000,256L<<20,()->ticks.incrementAndGet()>=limit,System::nanoTime);
   try(var p=new CountSoftSearch(List.of(),new BigInteger[]{Z},new BigInteger[]{BigInteger.TWO},soft,cut,1000000)){while(!p.step()){}}catch(CancellationException expected){}
   check(cut.reservedBytes()==0,"core/fixing cancellation leak "+cutoff);
  }
  b=budget();try(var p=new CountSoftSearch(List.of(),new BigInteger[]{Z},new BigInteger[]{BigInteger.TWO},soft,b,1000000)){while(!p.step()){}check(p.optimal()&&p.cost().equals(O.shiftLeft(100).multiply(BigInteger.valueOf(15))),"large weighted soft optimum");check(b.diagnostics().contains("count_core_soft"),"core not dispatched");}check(b.reservedBytes()==0,"soft memory");
  System.out.println("PASS CGPA round trip/tampered stock, horizon and induction; 384 induction + 512 core/fixing cancellations; 5 memory boundaries; 120-bit induction and 100-bit weighted core optimization");
 }
}
