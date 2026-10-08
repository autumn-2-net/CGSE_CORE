package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

public class ExactAnalysisChecks {
    static int checks;
    static BigInteger b(long n) { return BigInteger.valueOf(n); }
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    static PlanningBudget budget() { return new PlanningBudget(0, 10_000_000, 128L << 20, () -> false, System::nanoTime); }
    static ExactLinearProgram.Constraint row(long a, long d, long c) {
        return new ExactLinearProgram.Constraint(Map.of(0,b(a),1,b(d)),b(c));
    }
    static ExactLinearProgram solve(List<ExactLinearProgram.Constraint> rows, BigInteger[] objective, PlanningBudget budget) {
        var lp = new ExactLinearProgram(objective.length, rows, objective, budget);
        while (!lp.step()) {}
        check(budget.reservedBytes() == 0, "LP workspace leak");
        return lp;
    }
    static BigInteger[] vertex(long[] a, long[] c) {
        long determinant = a[0]*c[1]-a[1]*c[0];
        if (determinant == 0) return null;
        long x=a[2]*c[1]-a[1]*c[2], y=a[0]*c[2]-a[2]*c[0];
        if (determinant<0) {determinant=-determinant;x=-x;y=-y;}
        return new BigInteger[]{b(x),b(y),b(determinant)};
    }
    static void linear() {
        Random random = new Random(614730);
        for(int sample=0;sample<2000;sample++) {
            var raw=new ArrayList<long[]>();
            raw.add(new long[]{1,0,8});raw.add(new long[]{0,1,8});
            for(int i=0;i<2+random.nextInt(6);i++) raw.add(new long[]{random.nextInt(11)-5,random.nextInt(11)-5,random.nextInt(29)-8});
            var constraints=new ArrayList<ExactLinearProgram.Constraint>();
            for(var r:raw)constraints.add(row(r[0],r[1],r[2]));
            raw.add(new long[]{-1,0,0});raw.add(new long[]{0,-1,0});
            BigInteger[] objective={b(random.nextInt(11)-5),b(random.nextInt(11)-5)};
            BigInteger bestNumerator=null,bestDenominator=null;
            for(int i=0;i<raw.size();i++) for(int j=i+1;j<raw.size();j++) {
                var v=vertex(raw.get(i),raw.get(j));if(v==null)continue;
                boolean valid=true;
                for(var r:raw)if(b(r[0]).multiply(v[0]).add(b(r[1]).multiply(v[1])).compareTo(b(r[2]).multiply(v[2]))>0){valid=false;break;}
                if(!valid)continue;
                BigInteger score=objective[0].multiply(v[0]).add(objective[1].multiply(v[1]));
                if(bestNumerator==null || score.multiply(bestDenominator).compareTo(bestNumerator.multiply(v[2]))>0){bestNumerator=score;bestDenominator=v[2];}
            }
            var lp=solve(constraints,objective,budget());
            if(bestNumerator==null)check(lp.result()==ExactLinearProgram.Result.INFEASIBLE,"LP expected infeasible sample="+sample+" got="+lp.result());
            else {
                check(lp.result()==ExactLinearProgram.Result.OPTIMAL,"LP expected optimum sample="+sample+" got="+lp.result());
                var point=lp.point();
                var score=point[0].multiply(ExactRational.of(objective[0])).add(point[1].multiply(ExactRational.of(objective[1])));
                check(score.numerator().multiply(bestDenominator).equals(bestNumerator.multiply(score.denominator())),"LP wrong optimum "+sample);
            }
        }
        var lp=solve(List.of(),new BigInteger[]{BigInteger.ONE},budget());
        check(lp.result()==ExactLinearProgram.Result.UNBOUNDED,"unbounded LP");
        lp=solve(List.of(new ExactLinearProgram.Constraint(Map.of(),BigInteger.ONE.negate())),new BigInteger[0],budget());
        check(lp.result()==ExactLinearProgram.Result.INFEASIBLE,"zero-variable infeasible LP");
        lp=solve(List.of(new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE.shiftLeft(3000)),BigInteger.ONE)),new BigInteger[]{BigInteger.ONE},budget());
        check(lp.result()==ExactLinearProgram.Result.UNKNOWN,"precision limit claimed impossibility");
    }
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    static void cache() {
        var compiler=new GraphCompiler<>(List.of(r("cheap",Map.of("A",2L),Map.of("B",1L)),r("costly",Map.of("A",3L),Map.of("B",1L))));
        var first=budget();var q=new QuantityAnalysis<>(compiler,"B",2,Map.of("A",3L),Set.of(),Map.of(),Set.of(),first);
        while(!q.step()){} check(q.blocked(),"shared resource not blocked");
        // Integer bounds can now finish before simplex emits a certificate.
        // Seed this independently valid monotone invariant to test cache scope.
        if(compiler.quantityCertificates(Set.of()).isEmpty()) {
            var weights=Map.of("A",BigInteger.ONE,"B",BigInteger.TWO);
            for(var recipe:compiler.catalog()) {
                BigInteger delta=BigInteger.ZERO;
                for(var entry:weights.entrySet())delta=delta.add(entry.getValue().multiply(RecipeCountModel.delta(recipe,entry.getKey())));
                check(delta.signum()<=0,"invalid test certificate");
            }
            compiler.rememberQuantityCertificate(Set.of(),weights);
        }
        var second=budget();q=new QuantityAnalysis<>(compiler,"B",Long.MAX_VALUE/2+1,Map.of("A",Long.MAX_VALUE),Set.of(),Map.of(),Set.of(),second);
        while(!q.step()){}check(q.blocked(),"cached long deficit");check(second.nodes()<first.nodes(),"cache did not skip solve");
        q=new QuantityAnalysis<>(compiler,"B",2,Map.of("A",4L),Set.of(),Map.of(),Set.of(),budget());while(!q.step()){}check(!q.blocked(),"stale certificate after refill");
        q=new QuantityAnalysis<>(compiler,"B",2,Map.of(),Set.of("A"),Map.of(),Set.of(),budget());while(!q.step()){}check(!q.blocked(),"external stock falsely blocked");
        var changed=new GraphCompiler<>(List.of(r("cheap",Map.of("A",2L),Map.of("B",2L)),r("costly",Map.of("A",3L),Map.of("B",1L))));
        q=new QuantityAnalysis<>(changed,"B",2,Map.of("A",3L),Set.of(),Map.of(),Set.of(),budget());while(!q.step()){}check(!q.blocked(),"changed output inherited old proof");
        check(first.reservedBytes()==0 && second.reservedBytes()==0,"quantity workspace leak");
        System.out.printf("CACHE cold_work=%d hot_work=%d%n",first.nodes(),second.nodes());
    }
    static void cover(String name,List<GraphRecipe<String>> recipes,String target,long n,Map<String,Long> stock,ContinuousCoverability.Result expected) {
        var budget=budget();var model=RecipeCountModel.create(new GraphCompiler<>(recipes),target,n,stock,Map.of(),Set.of(),Set.of(),false,budget);
        var cover=new ContinuousCoverability<>(model,budget);while(!cover.step()){}
        check(cover.result()==expected,"continuous "+name+" got="+cover.result());model.close();check(budget.reservedBytes()==0,"continuous workspace leak");
    }
    static void continuous() {
        cover("zero-seed",List.of(r("copy",Map.of("A",1L),Map.of("A",2L))),"A",1,Map.of(),ContinuousCoverability.Result.BLOCKED);
        cover("seeded",List.of(r("copy",Map.of("A",1L),Map.of("A",2L))),"A",Long.MAX_VALUE,Map.of("A",1L),ContinuousCoverability.Result.POSSIBLE);
        cover("fractional",List.of(r("copy",Map.of("A",2L),Map.of("B",2L))),"B",1,Map.of("A",1L),ContinuousCoverability.Result.POSSIBLE);
        cover("two-output-loss",List.of(r("r",Map.of("A",1L),Map.of("A",1L,"B",1L))),"B",5,Map.of("A",1L),ContinuousCoverability.Result.POSSIBLE);
        cover("reverse-support-obstruction",List.of(r("r",Map.of("A",2L),Map.of("A",1L,"B",1L))),"B",1,Map.of("A",1L),ContinuousCoverability.Result.BLOCKED);
        cover("fractional-finite-prefix",List.of(r("r",Map.of("A",2L),Map.of("A",1L,"B",2L))),"B",1,Map.of("A",1L),ContinuousCoverability.Result.POSSIBLE);
    }
    public static void main(String[] args) {linear();cache();continuous();System.out.println("EXACT_ANALYSIS_PASS "+checks);}
}
