package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

public final class FeedbackProofAudit {
    static long checks, boundImports, rejectedBounds, policyModes, archives;
    static BigInteger b(long x) { return BigInteger.valueOf(x); }
    static void ok(boolean v, String why) { checks++; if (!v) throw new AssertionError(why); }
    static PlanningBudget budget() { return new PlanningBudget(0, 20_000_000, 128L << 20, () -> false, System::nanoTime); }
    static ExactLinearProgram.Constraint row(long upper, int... terms) { var m = new TreeMap<Integer, BigInteger>(); for (int i=0;i<terms.length;i+=2) m.put(terms[i], b(terms[i+1])); return new ExactLinearProgram.Constraint(m,b(upper)); }
    static Object get(Object o, String field) throws Exception { var f=o.getClass().getDeclaredField(field); f.setAccessible(true); return f.get(o); }
    @SuppressWarnings("unchecked") static List<CountModelViews.View> views(CountModelViews v) throws Exception { return (List<CountModelViews.View>) get(v,"views"); }
    static CountModelViews.View view(String name, List<ExactLinearProgram.Constraint> rows, BigInteger[] lo, BigInteger[] hi, CountMapping mapping, CountModelViews.Semantics semantics) { return new CountModelViews.View(name,rows,lo,hi,new CountModelViews.Shape(lo.length,rows.stream().mapToLong(r->r.terms().size()).sum(),8,lo.length),null,semantics,mapping); }
    static void policy() {
        for (int s=0;s<300;s++) {
            var p=new CountPortfolioPolicy(); var a=p.add(1,"a"); var second=p.add(2,"b");
            p.selected(a); p.feedback(a, 32768+s, 100+s);
            long first=p.quantum(a,100000); ok(first==32768,"first cost model did not learn");
            p.mode(CountPortfolioPolicy.Mode.PROOF);
            ok(p.quantum(a,100000)==4096,"first witness cost leaked into proof quantum");
            ok(p.select()==a && p.selections(a)==0,"proof exploration lost");
            p.selected(a); p.mode(CountPortfolioPolicy.Mode.IMPROVEMENT); p.feedback(a,12000,0);
            ok(p.quantum(a,100000)==4096,"pending proof feedback leaked into improvement");
            ok(a.modes.get(CountPortfolioPolicy.Mode.PROOF).observations==1,"pending proof feedback lost");
            ok(a.modes.get(CountPortfolioPolicy.Mode.IMPROVEMENT).observations==0,"pending proof charged to new mode");
            p.selected(a); p.feedback(a,25000,1); ok(p.quantum(a,100000)==25000,"improvement did not learn own cost");
            p.mode(CountPortfolioPolicy.Mode.FIRST_WITNESS); ok(p.quantum(a,100000)==first,"original cost changed after mode roundtrip");
            ok(a.observations==1 && a.work==32768+s,"other mode polluted original counters");
            p.mode(CountPortfolioPolicy.Mode.PROOF); ok(p.idleSlices(a)==1,"proof idle state lost");
            p.selected(second); p.feedback(second,Long.MAX_VALUE,Long.MAX_VALUE); ok(Double.isFinite(second.modes.get(CountPortfolioPolicy.Mode.PROOF).reward),"overflow in isolated statistics");
            policyModes++;
        }
    }
    @SuppressWarnings("unchecked") static void decisiveProof() throws Exception {
        var budget=budget();
        try (var m=CountModelViews.create(List.of(row(0,0,1),row(-1,0,-1)),new BigInteger[]{b(0)},new BigInteger[]{b(1)},budget); var s=new CountViewSearch(m,budget)) {
            s.commonWork(173); s.resume(65536); while(!s.step()) {}
            ok(s.infeasible(),"test proof not completed");
            var p=get(s,"policy"); var modes=(Map<?,?>)get(p,"candidateCosts"); var costs=(Map<?,?>)modes.get(CountPortfolioPolicy.Mode.PROOF);
            ok(costs!=null && !costs.isEmpty(),"proof observation missing");
            var cost=costs.values().iterator().next(); double upstream=(double)get(cost,"upstream");
            var searches=(List<?>)get(s,"searches"); long total=0; for(var search:searches)total+=(long)get(search,"work");
            ok(upstream==total && total>0,"decisive step omitted: " + upstream + "/" + total);
            ok((double)get(cost,"downstream")==173,"common proof cost misattributed");
        }
        ok(budget.reservedBytes()==0,"proof test leaked");
    }
    @SuppressWarnings("unchecked") static void rejectedRestoration() throws Exception {
        var budget=budget();
        try(var m=CountModelViews.create(List.of(row(-1,0,-1)),new BigInteger[]{b(0)},new BigInteger[]{b(3)},budget);var s=new CountViewSearch(m,budget)) {
            var all=views(m);all.clear();
            for(int i=0;i<2;i++)all.add(view("rejected"+i,List.of(row(0,0,1)),new BigInteger[]{b(0)},new BigInteger[]{b(3)},null,CountModelViews.Semantics.HINT));
            s.commonWork(173);s.resume(65536);while(!s.step()){}
            ok(s.counts()==null&&!s.infeasible(),"hint manufactured global result");
            var p=get(s,"policy");var modes=(Map<?,?>)get(p,"candidateCosts");var costs=(Map<?,?>)modes.get(CountPortfolioPolicy.Mode.FIRST_WITNESS);var cost=costs.values().iterator().next();
            ok((long)get(cost,"observations")==2 && (long)get(cost,"resolved")==2 && (long)get(cost,"verified")==0,"rejected restores omitted");
            ok((long)get(s,"reportedCommonWork")==173,"common compilation charge not consumed once");
            // Each original-row rejection costs two units; the shared 173 must
            // be charged once, not divided and counted again on candidate #2.
            ok((double)get(cost,"downstream")>=86.5 && (double)get(cost,"downstream")<100,"common cost counted repeatedly");
        } ok(budget.reservedBytes()==0,"restoration test leaked");
    }
    static void bounds(Path output) throws Exception {
        var archive=new CountProof.Journal(64L<<20);
        for(int seed=0;seed<180;seed++) {
            boolean negative=(seed&1)==0; long offset=10+seed%7;
            var budget=budget();budget.proofJournal(archive);
            var rows=negative?List.of(row(-11,0,-2)):List.of(row(11,0,2),row(1,0,1,1,-3),row(-1,0,-1,1,3));
            var lo=negative?new BigInteger[]{b(0)}:new BigInteger[]{b(0),b(0)};
            var hi=negative?new BigInteger[]{b(offset)}:new BigInteger[]{b(100),b(100)};
            try(var m=CountModelViews.create(rows,lo,hi,budget)) {
                var base=m.available().get(0);
                CountMapping mapping=negative?new CountMapping(List.of(new CountMapping.Expression(Map.of(0,b(-1)),b(offset)))):
                    new CountMapping(List.of(new CountMapping.Expression(Map.of(0,b(3)),b(1)),CountMapping.Expression.variable(0)));
                var targetRows=negative?List.of(row(2*offset-11,0,2)):List.of(row(9,0,6));
                var target=view("target",targetRows,new BigInteger[]{b(0)},new BigInteger[]{b(100)},mapping,CountModelViews.Semantics.EQUIVALENT);views(m).add(target);
                m.publishBounds(base,negative?new BigInteger[]{b(6)}:lo,negative?hi:new BigInteger[]{b(4),b(100)});
                var domains=m.domains(target);ok(domains.version()==0 && domains.upper()[0].equals(b(100)),"proof bounds smuggled into initial axioms");
                try(var solver=new CountLcg(target.rows(),domains.lower(),domains.upper(),budget,100000,true)) {
                    int imported=m.importProofBounds(target,0,solver);ok(imported==1,"valid bound not imported "+seed);boundImports+=imported;
                    while(!solver.step()){}
                    var cert=solver.certificate();ok(cert!=null && CountProof.verify(cert,1000000)==CountProof.Verdict.VERIFIED,"target proof invalid");
                    var bound=new CountProof.Row(Map.of(0,b(1)),negative?b(offset-6):b(1));
                    ok(!cert.axioms().contains(bound),"shared bound became target axiom");
                    ok(cert.forbidden().contains(List.of(CountAffineProof.opposite(bound))),"shared bound explanation missing");
                    ok(solver.counts()!=null,"valid import lost feasible witness");
                }
            }ok(budget.reservedBytes()==0,"bound scratch reservation leaked");
        }
        var path=output.resolve("bound-transfers.bin");archive.write(path);var replay=CountProof.read(path);
        ok(replay.affineConflicts().size()==180,"bound archive incomplete");
        for(var proof:replay.affineConflicts()) {
            ok(CountAffineConflictProof.verify(proof,1000000)==CountProof.Verdict.VERIFIED,"bound archive cannot replay");
            var bad=new CountAffineConflictProof.Certificate(proof.originalVariables(),List.of(),proof.source(),proof.sourceMapping(),proof.originalClause(),proof.targetVariables(),proof.targetAxioms(),proof.targetMapping(),proof.targetClause());
            ok(CountAffineConflictProof.verify(bad,1000000)!=CountProof.Verdict.VERIFIED,"bound proof trusted source without original implication");
            archives++;
        }
        for(boolean tiny:List.of(false,true)) {
            var budget=budget();var journal=new CountProof.Journal(tiny?32:1L<<20);budget.proofJournal(journal);
            try(var m=CountModelViews.create(List.of(row(-11,0,-2)),new BigInteger[]{b(0)},new BigInteger[]{b(10)},budget)) {
                var base=m.available().get(0);m.publishBounds(base,new BigInteger[]{b(tiny?6:8)},new BigInteger[]{b(10)});
                try(var solver=new CountLcg(base.rows(),base.lower(),base.upper(),budget,100000,true)) {
                    ok(m.importProofBounds(base,0,solver)==0,tiny?"overfull journal imported unexplained bound":"unprovable bound imported");
                    while(!solver.step()){}ok(solver.counts()[0].compareTo(b(8))<0,"rejected bound nevertheless constrained solver");rejectedBounds++;
                }
            }ok(budget.reservedBytes()==0,"declined bound leaked");
        }
        var normal=budget();try(var m=CountModelViews.create(List.of(row(-11,0,-2)),new BigInteger[]{b(0)},new BigInteger[]{b(10)},normal)) {
            var v=m.available().get(0);m.publishBounds(v,new BigInteger[]{b(6)},new BigInteger[]{b(10)});ok(m.domains(v).lower()[0].equals(b(6)),"non-journal bound sharing changed");
        }ok(normal.reservedBytes()==0,"normal bounds leaked");
    }
    static void closedBoundProof(Path output) throws Exception {
        var budget=budget();var archive=new CountProof.Journal(1L<<20);budget.proofJournal(archive);
        // Boolean x+y=1 and x=y need branching without a learned bound. The
        // consequence x<=0 is RUP, and then root propagation closes the model.
        var rows=List.of(row(1,0,1,1,1),row(-1,0,-1,1,-1),row(0,0,1,1,-1),row(0,0,-1,1,1));
        var lo=new BigInteger[]{b(0),b(0)};var hi=new BigInteger[]{b(1),b(1)};
        try(var m=CountModelViews.create(rows,lo,hi,budget)) {
            var base=m.available().get(0);m.publishBounds(base,lo,new BigInteger[]{b(0),b(1)});
            var domains=m.domains(base);ok(domains.upper()[0].equals(b(1)),"closed proof started from unproved bound");
            try(var solver=new CountLcg(rows,domains.lower(),domains.upper(),budget,100000,true)) {
                ok(m.importProofBounds(base,0,solver)==1,"closing bound not admitted");while(!solver.step()){}
                var cert=solver.certificate();ok(solver.infeasible()&&cert.closed(),"closing bound did not lead to UNSAT");
                var bound=new CountProof.Row(Map.of(0,b(1)),b(0));
                ok(!cert.axioms().contains(bound),"UNSAT root contains imported fact as axiom");
                ok(cert.forbidden().contains(List.of(CountAffineProof.opposite(bound))),"UNSAT explanation missing");
                ok(CountProof.verify(cert,1000000)==CountProof.Verdict.VERIFIED,"closed certificate not independently replayable");
            }
        }ok(budget.reservedBytes()==0,"closed proof scratch leaked");archive.write(output.resolve("closed-bound-proof.bin"));
    }
    public static void main(String[] args) throws Exception {
        var output=Path.of(args[0]);Files.createDirectories(output);policy();decisiveProof();rejectedRestoration();bounds(output);closedBoundProof(output);
        String result="{\"assertions\":"+checks+",\"modeCases\":"+policyModes+",\"boundImports\":"+boundImports+",\"replayedTransfers\":"+archives+",\"rejectedBounds\":"+rejectedBounds+"}";
        Files.writeString(output.resolve("feedback-proof-audit.json"),result);System.out.println(result);
    }
}
