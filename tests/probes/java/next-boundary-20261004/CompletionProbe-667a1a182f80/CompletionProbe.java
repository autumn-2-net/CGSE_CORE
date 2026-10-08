package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.gson.Gson;

public final class CompletionProbe {
    static long checks, transfers, shared, hits, modes;
    static BigInteger b(long n){return BigInteger.valueOf(n);}
    static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    static PlanningBudget budget(){return new PlanningBudget(0,50_000_000,128L<<20,()->false,System::nanoTime);}
    static ExactLinearProgram.Constraint row(Map<Integer,BigInteger> t,BigInteger rhs){return new ExactLinearProgram.Constraint(t,rhs);}
    static CountModelViews.View view(String name,List<ExactLinearProgram.Constraint> rows,int n,CountMapping map){var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,b(0));Arrays.fill(hi,b(1000000));return new CountModelViews.View(name,rows,lo,hi,new CountModelViews.Shape(n,rows.size(),64,n),null,CountModelViews.Semantics.EQUIVALENT,map);}
    static void proofs(Path directory)throws Exception{
        var journal=new CountProof.Journal(32L<<20);
        for(int seed=0;seed<500;seed++){
            var budget=budget();budget.proofJournal(journal);var a=b(2+seed%17);var offset=b(seed%7);var bound=b(20+seed%67);
            // x = a*z+offset, 2*x <= odd. Lift via the integral original z coordinate.
            var original=List.of(row(Map.of(0,b(2)),bound),row(Map.of(0,b(1),1,a.negate()),offset),row(Map.of(0,b(-1),1,a),offset.negate()));
            var root=view("root",original,2,null);var sourceRows=List.of(row(Map.of(0,a.multiply(b(2))),bound.subtract(offset.multiply(b(2)))));
            var map=new CountMapping(List.of(new CountMapping.Expression(Map.of(0,a),offset),CountMapping.Expression.variable(0)));
            var source=view("stride",sourceRows,1,map);var cut=ViewCutsProbe.combine(sourceRows,Map.of(0,b(1)));
            try(var pool=CountViewCuts.create(budget,root)){
                pool.publish(source,List.of(cut),0,new Object());check(pool.version()==1,"missing proof cut");
                var out=new ArrayList<ExactLinearProgram.Constraint>();int imported=pool.transfer(root,0,new Object(),r->{out.add(r);return true;});
                check(imported==1,"scoped affine cut refused "+seed);transfers++;
                for(int z=0;z<60;z++) {var x=a.multiply(b(z)).add(offset);if(x.multiply(b(2)).compareTo(bound)<=0)check(out.get(0).terms().get(1).multiply(b(z)).compareTo(out.get(0).upper())<=0,"excluded feasible original");}
            }
            check(budget.reservedBytes()==0,"affine memory leak");
            var proof=journal.affine().get(journal.affine().size()-1);
            check(CountAffineProof.verify(proof,100000)==CountProof.Verdict.VERIFIED,"portable checker refused");
            var bad=new CountAffineProof.Certificate(proof.originalVariables(),List.of(),proof.source(),proof.sourceMapping(),proof.originalCut(),proof.targetVariables(),proof.targetAxioms(),proof.targetMapping(),proof.targetCut());
            check(CountAffineProof.verify(bad,100000)!=CountProof.Verdict.VERIFIED,"inverse mistaken for coverage");
            bad=new CountAffineProof.Certificate(proof.originalVariables(),proof.originalAxioms(),proof.source(),proof.sourceMapping(),proof.originalCut(),proof.targetVariables(),List.of(),proof.targetMapping(),proof.targetCut());
            check(CountAffineProof.verify(bad,100000)!=CountProof.Verdict.VERIFIED,"foreign target axioms accepted");
            bad=new CountAffineProof.Certificate(proof.originalVariables(),proof.originalAxioms(),proof.source(),proof.sourceMapping(),proof.originalCut(),proof.targetVariables(),proof.targetAxioms(),proof.targetMapping(),new CountProof.Row(proof.targetCut().terms(),proof.targetCut().upper().subtract(b(1))));
            check(CountAffineProof.verify(bad,100000)!=CountProof.Verdict.VERIFIED,"altered cut accepted");
            check(CountAffineProof.verify(proof,1)==CountProof.Verdict.INCOMPLETE,"bounded replay invented proof");
        }
        var archive=directory.resolve("affine-proofs.bin");journal.write(archive);var replay=CountProof.read(archive);
        check(replay.affine().size()==500&&!replay.truncated(),"archive lost facts");for(var proof:replay.affine())check(CountAffineProof.verify(proof,100000)==CountProof.Verdict.VERIFIED,"serialized proof invalid");
        var tiny=new CountProof.Journal(32);check(!tiny.add(replay.affine().get(0))&&tiny.truncated(),"journal overflow accepted");
        for(int seed=0;seed<300;seed++){
            var budget=budget();var j=new CountProof.Journal(1L<<20);budget.proofJournal(j);
            var rows=List.of(row(Map.of(0,b(2),1,b(2)),b(3)));var low=new BigInteger[]{b(0),b(0)};var high=new BigInteger[]{b(1),b(1)};
            try(var views=CountModelViews.create(rows,low,high,budget);var solver=new CountLcg(rows,low,high,budget,100000,true)){
                if(seed%2==0)solver.step();views.publishCuts(views.available().get(0),List.of(ViewCutsProbe.combine(rows,Map.of(0,b(1)))),0,new Object());
                check(views.importCuts(views.available().get(0),0,solver,solver)==1,"live proof import declined");while(!solver.step()){}check(solver.counts()!=null,"import changed feasible result");
                check(CountProof.verify(solver.certificate(),100000)==CountProof.Verdict.VERIFIED,"imported axiom wasn't justified");
                check(!solver.certificate().axioms().contains(new CountProof.Row(Map.of(0,b(1),1,b(1)),b(1))),"derived row became axiom");
                check(!solver.certificate().forbidden().isEmpty(),"proof omission");
            }check(budget.reservedBytes()==0,"journal production leak");
        }
    }
    static void sharing()throws Exception{
        var executor=Executors.newFixedThreadPool(4);
        try{
            for(int seed=0;seed<160;seed++){
                var example=ContinuationProbe.example(seed);var budget=budget();
                try(var model=RecipeCountModel.forShell(example.recipes(),Map.of(),example.stock(),Set.of(),budget);var pool=new CountScheduleContinuations<>(model,budget)){
                    var original=pool.acquire(example.counts());check(pool.retain(original),"cannot suspend");
                    var one=executor.submit(()->pool.acquire(example.counts())).get();check(one==original,"cross-worker reuse missed");
                    var second=executor.submit(()->pool.acquire(example.counts())).get();check(second!=one,"shared live mutable scheduler");second.close();
                    try(one){while(!one.step()){}if(one.result()==CountSchedule.Result.WITNESS){ContinuationProbe.verify(example,one.witness(),budget);pool.remember(example.counts(),one.witness());check(pool.witness(example.counts().clone()).equals(one.witness()),"positive program missed");hits++;}}
                    shared++;
                }check(budget.reservedBytes()==0,"shared close leaked");
            }
        }finally{executor.shutdownNow();}
        var budget=budget();try(var summaries=new CountProgramSummaries<String>(budget)){
            var a=new PlanStep.Batch("a",1);var z=new PlanStep.Batch("z",1);var ab=new PlanStep.Sequence(List.of(a,z));var ba=new PlanStep.Sequence(List.of(z,a));
            var summary=new SequenceSummary<String>(Map.of("seed",b(1)),Map.of("target",b(2)),Map.of("target",b(2)));
            summaries.put(ab,summary);check(summaries.get(new PlanStep.Sequence(List.of(a,z)))==summary,"equal program not reused");check(summaries.get(ba)==null,"same counts different order conflated");
            summaries.put(new PlanStep.Batch("a",2),summary);check(summaries.get(new PlanStep.Repeat(a,2))==null,"batch boundary conflated");
        }check(budget.reservedBytes()==0,"program cache leak");
    }
    static void policy(){for(int sample=0;sample<1000;sample++){
        var p=new CountPortfolioPolicy();var a=p.add(1,"same");p.candidateFeedback(a,100,10000,true,false);double first=p.candidateEfficiency(a);check(first<1,"first witness observation lost");
        p.mode(CountPortfolioPolicy.Mode.PROOF);check(p.candidateEfficiency(a)==1,"primal failure poisoned proof mode");p.candidateFeedback(a,500,20,false,false);double proof=p.candidateEfficiency(a);
        p.mode(CountPortfolioPolicy.Mode.IMPROVEMENT);check(p.candidateEfficiency(a)==1,"improvement history not isolated");p.candidateFeedback(a,Long.MAX_VALUE,Long.MAX_VALUE,true,true);check(Double.isFinite(p.candidateEfficiency(a)),"overflow");
        p.mode(CountPortfolioPolicy.Mode.PROOF);check(p.candidateEfficiency(a)==proof,"mode history overwritten");p.mode(CountPortfolioPolicy.Mode.FIRST_WITNESS);check(p.candidateEfficiency(a)==first,"first history overwritten");modes++;
    }}
    public static void main(String[] args)throws Exception{proofs(Path.of(args[0]));sharing();policy();var result=Map.of("affineTransfers",transfers,"sharedWorkerCases",shared,"positiveHits",hits,"modeCases",modes,"assertions",checks);Files.writeString(Path.of(args[0],"completion-probe.json"),new Gson().toJson(result));System.out.println(result);}
}
