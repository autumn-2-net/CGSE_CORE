package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;

public final class ConflictCompletionProbe {
 static long checks, transfers, negativeScales, imports, orderings;
 static BigInteger b(long n){return BigInteger.valueOf(n);}
 static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static PlanningBudget budget(){return new PlanningBudget(0,50_000_000,128L<<20,()->false,System::nanoTime);}
 static ExactLinearProgram.Constraint row(Map<Integer,BigInteger> t,BigInteger rhs){return new ExactLinearProgram.Constraint(t,rhs);}
 static CountConflict lower(int id,int n){return new CountConflict(List.of(row(Map.of(id,b(-1)),b(-n))));}
 static CountModelViews.View view(String name,List<ExactLinearProgram.Constraint> rows,CountMapping map){return new CountModelViews.View(name,rows,new BigInteger[]{b(0),b(0)},new BigInteger[]{b(1000000),b(1000000)},new CountModelViews.Shape(2,2,64,2),null,CountModelViews.Semantics.EQUIVALENT,map);}
 static void transfers(Path dir)throws Exception{
  var journal=new CountProof.Journal(64L<<20);
  for(int seed=0;seed<600;seed++){
   var budget=budget();budget.proofJournal(journal);boolean reversed=seed%2==1;var a=b(2+seed%17);var offset=reversed?b(100):b(seed%7);var scale=reversed?a.negate():a;
   // Original x = scale*z+offset, the reduced row z+w<=4. A local z>=3 assumption MUST travel with w>=2.
   var originalRow=row(Map.of(0,reversed?b(-1):b(1),1,a),a.multiply(b(4)).add(reversed?offset.negate():offset));
   var root=view("root",List.of(originalRow),null);
   var mapping=new CountMapping(List.of(new CountMapping.Expression(Map.of(0,scale),offset),CountMapping.Expression.variable(1)));
   var source=view("affine",List.of(row(Map.of(0,b(1),1,b(1)),b(4))),mapping);
   try(var pool=CountViewConflicts.create(budget,root)){
    pool.publish(source,new BigInteger[]{b(3),b(0)},source.upper(),List.of(lower(1,2)),0,new Object());
    check(pool.version()==1,"guarded export missing");var got=new ArrayList<CountConflict>();
    check(pool.transfer(root,0,new Object(),c->{got.add(c);return true;})==1,"guarded affine transfer refused "+seed);transfers++;if(reversed)negativeScales++;
    check(got.get(0).assumptions().size()==2,"local root guard lost");
   }check(budget.reservedBytes()==0,"pool reservation leak");
   var p=journal.affineConflicts().get(journal.affineConflicts().size()-1);
   check(CountAffineConflictProof.verify(p,100000)==CountProof.Verdict.VERIFIED,"portable proof invalid");
   var bad=new CountAffineConflictProof.Certificate(p.originalVariables(),List.of(),p.source(),p.sourceMapping(),p.originalClause(),p.targetVariables(),p.targetAxioms(),p.targetMapping(),p.targetClause());
   check(CountAffineConflictProof.verify(bad,100000)!=CountProof.Verdict.VERIFIED,"foreign original accepted");
   bad=new CountAffineConflictProof.Certificate(p.originalVariables(),p.originalAxioms(),p.source(),p.sourceMapping(),p.originalClause(),p.targetVariables(),List.of(),p.targetMapping(),p.targetClause());
   check(CountAffineConflictProof.verify(bad,100000)!=CountProof.Verdict.VERIFIED,"foreign target accepted");
   var noGuard=p.originalClause().stream().filter(r->!r.terms().containsKey(0)).toList();
   bad=new CountAffineConflictProof.Certificate(p.originalVariables(),p.originalAxioms(),p.source(),p.sourceMapping(),noGuard,p.targetVariables(),p.targetAxioms(),p.targetMapping(),p.targetClause());
   check(CountAffineConflictProof.verify(bad,100000)!=CountProof.Verdict.VERIFIED,"removed guard accepted");
   check(CountAffineConflictProof.verify(p,1)==CountProof.Verdict.INCOMPLETE,"budget exhaustion produced proof");
  }
  var archive=dir.resolve("affine-conflicts.bin");journal.write(archive);var restored=CountProof.read(archive);
  check(restored.affineConflicts().size()==600&&!restored.truncated(),"archive lost clauses");for(var p:restored.affineConflicts())check(CountAffineConflictProof.verify(p,100000)==CountProof.Verdict.VERIFIED,"serialized clause invalid");
  var tiny=new CountProof.Journal(32);check(!tiny.add(restored.affineConflicts().get(0))&&tiny.truncated(),"archive size limit ignored");
  // Combined archive has both formats, neither list may disappear during roundtrip.
  var old=CountProof.read(dir.resolve("affine-proofs.bin"));for(var p:old.affine())journal.add(p);journal.write(archive);restored=CountProof.read(archive);check(restored.affine().size()==500&&restored.affineConflicts().size()==600,"mixed proof archive lost a section");
 }
 static void liveClauses(){for(int seed=0;seed<300;seed++){
  var budget=budget();budget.proofJournal(new CountProof.Journal(4L<<20));
  var rows=List.of(row(Map.of(0,b(2),1,b(2)),b(3)));var lo=new BigInteger[]{b(0),b(0)};var hi=new BigInteger[]{b(1),b(1)};
  var clause=new CountConflict(List.of(row(Map.of(0,b(-1)),b(-1)),row(Map.of(1,b(-1)),b(-1))));
  try(var views=CountModelViews.create(rows,lo,hi,budget);var solver=new CountLcg(rows,lo,hi,budget,100000,true)){
   check(!solver.learn(lower(0,1)),"unproved imported exclusion accepted");
   if(seed%2==0)solver.step();var view=views.available().get(0);views.publishConflicts(view,new CountModelViews.Domains(lo,hi,0),List.of(clause),0,new Object());
   check(views.conflictVersion()==1,"journal export disabled");check(views.importConflicts(view,0,solver,solver)==1,"journal import disabled");imports++;
   while(!solver.step()){}check(solver.counts()!=null,"shared clause excluded solution");check(CountProof.verify(solver.certificate(),100000)==CountProof.Verdict.VERIFIED,"imported clause became unverified axiom");
   check(solver.certificate().forbidden().contains(clause.assumptions().stream().map(CountProof::row).toList()),"import explanation absent");
  }check(budget.reservedBytes()==0,"live clause leak");
 }}
 static void ordering(){
  var drain=ContinuationProbe.recipe("drain",Map.of("seed",1L),Map.of("target",1L));
  var start=ContinuationProbe.recipe("start",Map.of("seed",2L),Map.of("carrier",1L));
  var ret=ContinuationProbe.recipe("return",Map.of("carrier",1L),Map.of("seed",3L));
  var budget=budget();var recipes=List.of(drain,start,ret);
  try(var model=RecipeCountModel.forShell(recipes,Map.of(),Map.of("seed",4L),Set.of(),budget);var hints=new CountScheduleOrder<>(model,recipes.stream().map(SequenceSummary::recipe).toList(),budget)){
   var held=Map.of("seed",b(4));var counts=new BigInteger[]{b(4),b(1),b(1)};
   check(hints.available(),"hint not admitted");check(hints.choose(held,counts,new BitSet())==1,"did not unlock the return path");check(hints.batch(0,b(4),held,counts).equals(b(2)),"spent startup pool on draining batch");
  }check(budget.reservedBytes()==0,"ordering memory leak");
  for(int seed=0;seed<120;seed++){
   budget=budget();var shuffled=new ArrayList<>(recipes);Collections.shuffle(shuffled,new Random(seed));var counts=shuffled.stream().map(r->b(r.id().equals("drain")?4:1)).toArray(BigInteger[]::new);
   try(var model=RecipeCountModel.forShell(shuffled,Map.of(),Map.of("seed",4L),Set.of(),budget);var schedule=new CountSchedule<>(model,counts,budget)){
    while(!schedule.step()){}check(schedule.result()==CountSchedule.Result.WITNESS,"order-dependent deadlock");ContinuationProbe.verify(new ContinuationProbe.Example(shuffled,Map.of("seed",4L),counts),schedule.witness(),budget);orderings++;
   }check(budget.reservedBytes()==0,"ordering closure leak");
  }
 }
 static void memory(){var budget=budget();try(var summaries=new CountProgramSummaries<String>(budget)){
   var amount=BigInteger.ONE.shiftLeft(131072);var summary=new SequenceSummary<String>(Map.of("x",amount),Map.of("x",amount),Map.of("x",amount));var program=new PlanStep.Batch("r",1);
   summaries.put(program,summary);check(summaries.get(program)==summary,"large exact summary not admitted");check(budget.reservedBytes()>3*16384,"BigInteger payload not reserved");
  }check(budget.reservedBytes()==0,"summary memory leak");}
 public static void main(String[] args)throws Exception{transfers(Path.of(args[0]));liveClauses();ordering();memory();var result=Map.of("conflictTransfers",transfers,"negativeScaleTransfers",negativeScales,"liveImports",imports,"orderings",orderings,"assertions",checks);Files.writeString(Path.of(args[0],"conflict-completion.json"),new Gson().toJson(result));System.out.println(result);}
}
