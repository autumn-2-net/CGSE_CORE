package org.cgse.core;

import com.google.gson.Gson;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ViewCutsProbe {
    static long checks, facts, engineCuts, imports, live, retainedLpCuts;
    static BigInteger b(long v){return BigInteger.valueOf(v);}
    static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);}
    static BigInteger[] fill(int n,long v){var a=new BigInteger[n];Arrays.fill(a,b(v));return a;}
    static CountModelViews.View view(String name,List<ExactLinearProgram.Constraint> rows,int n,CountMapping map,CountModelViews.Semantics sem){return new CountModelViews.View(name,rows,fill(n,-2),fill(n,3),new CountModelViews.Shape(n,rows.size(),1,n),null,sem,map);}
    static CountLpLearning.Cut combine(List<ExactLinearProgram.Constraint> rows,Map<Integer,BigInteger> parents){
        var sum=new TreeMap<Integer,BigInteger>();var rhs=b(0);for(var p:parents.entrySet()){var r=rows.get(p.getKey());rhs=rhs.add(r.upper().multiply(p.getValue()));r.terms().forEach((id,c)->sum.merge(id,c.multiply(p.getValue()),BigInteger::add));}sum.values().removeIf(c->c.signum()==0);var gcd=b(0);for(var v:sum.values())gcd=gcd.gcd(v);if(gcd.signum()==0)gcd=b(1);final var d=gcd;sum.replaceAll((id,c)->c.divide(d));var qr=rhs.divideAndRemainder(d);return new CountLpLearning.Cut(new ExactLinearProgram.Constraint(sum,qr[1].signum()<0?qr[0].subtract(b(1)):qr[0]),parents,d);
    }
    static List<ExactLinearProgram.Constraint> transferred(CountViewCuts pool,CountModelViews.View view){var result=new ArrayList<ExactLinearProgram.Constraint>();pool.transfer(view,0,new Object(),r->{result.add(r);return true;});return result;}
    static void run(CountLcg s){int rounds=0;do{while(!s.step()){}if(!s.paused())break;s.resume(131072);}while(++rounds<100);check(!s.paused(),"unexpected local cutoff");}

    static void affine(){
        for(int seed=0;seed<1600;seed++){
            var rng=new Random(seed*7919L+81);int n=2+seed%3;var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int j=0;j<2+seed%4;j++){var t=new TreeMap<Integer,BigInteger>();for(int i=0;i<n;i++){int v=rng.nextInt(13)-6;if(v!=0)t.put(i,b(v*(seed%3+1)));}rows.add(new ExactLinearProgram.Constraint(t,b(rng.nextInt(21)-10)));}
            var expr=new ArrayList<CountMapping.Expression>();for(int i=0;i<n;i++)expr.add(new CountMapping.Expression(Map.of(n-1-i,b((i%2==0?1:-1)*(2+rng.nextInt(7)))),b(rng.nextInt(11)-5)));
            // Redundant original coordinates must not distort a lift.
            expr.add(new CountMapping.Expression(Map.of(0,b(2),n-1,b(3)),b(-3)));
            var mapping=new CountMapping(expr);var v=view("affine",rows,n,mapping,CountModelViews.Semantics.EQUIVALENT);var root=view("root",List.of(),expr.size(),null,CountModelViews.Semantics.EQUIVALENT);var budget=budget();
            try(var p=CountViewCuts.create(budget)){
                for(int j=0;j<8;j++){var weights=new LinkedHashMap<Integer,BigInteger>();for(int k=0;k<rows.size();k++)weights.put(k,b(rng.nextInt(5)));p.publish(v,List.of(combine(rows,weights)),0,new Object());}
                var lifted=transferred(p,root);var pulled=transferred(p,v);facts+=lifted.size();check(lifted.size()==pulled.size(),"round trip count");
                var solutions=StrideProbe.enumerate(rows,v.lower(),v.upper(),x->x);
                for(var point:solutions){var z=point.toArray(BigInteger[]::new);var x=mapping.restore(z,budget);check(StrideProbe.accepts(lifted,x),"lift excluded solution seed="+seed);check(StrideProbe.accepts(pulled,z),"pull excluded solution "+seed);}
                var nextExpr=new ArrayList<CountMapping.Expression>();for(int i=0;i<n;i++)nextExpr.add(new CountMapping.Expression(Map.of(i,b(i%2==0?2:-3)),b(i-2)));var next=new CountMapping(nextExpr);var composed=mapping.then(next,budget);
                for(int j=0;j<16;j++){var z=new BigInteger[n];for(int i=0;i<n;i++)z[i]=b(rng.nextInt(9)-4);check(Arrays.equals(composed.restore(z,budget),mapping.restore(next.restore(z,budget),budget)),"composition changed offsets/sign");}
            }check(budget.reservedBytes()==0,"affine leak");
        }
    }

    static void guards(){
        var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,b(2),1,b(2)),b(3)));var valid=combine(rows,Map.of(0,b(1)));var budget=budget();
        try(var p=CountViewCuts.create(budget)){
            var v=view("valid",rows,2,null,CountModelViews.Semantics.EQUIVALENT);
            var broken=List.of(new CountLpLearning.Cut(valid.row(),Map.of(0,b(-1)),valid.divisor()),new CountLpLearning.Cut(valid.row(),Map.of(2,b(1)),b(2)),new CountLpLearning.Cut(valid.row(),Map.of(0,b(1)),b(0)),new CountLpLearning.Cut(valid.row(),Map.of(0,b(1)),b(3)),new CountLpLearning.Cut(new ExactLinearProgram.Constraint(valid.row().terms(),b(0)),Map.of(0,b(1)),b(2)));
            p.publish(v,broken,0,new Object());check(p.version()==0,"corrupt certificate accepted");
            for(var sem:List.of(CountModelViews.Semantics.RESTRICTED,CountModelViews.Semantics.HINT))p.publish(view("restricted",rows,2,null,sem),List.of(valid),0,new Object());check(p.version()==0,"restricted row escaped");
            var mapping=new CountMapping(List.of(CountMapping.Expression.variable(0),CountMapping.Expression.variable(1)),List.of(new ExactLinearProgram.Constraint(Map.of(0,b(1)),b(0))));p.publish(view("guarded",rows,2,mapping,CountModelViews.Semantics.EQUIVALENT),List.of(valid),0,new Object());check(p.version()==0,"guard escaped");
            var unsupported=new CountMapping(List.of(new CountMapping.Expression(Map.of(0,b(1),1,b(1)),b(0))));p.publish(view("noninvertible",rows,2,unsupported,CountModelViews.Semantics.EQUIVALENT),List.of(valid),0,new Object());check(p.version()==0,"invented inverse");
            p.publish(v,List.of(valid),0,new Object());check(p.version()==1,"valid refused");p.publish(v,List.of(valid),0,new Object());check(p.version()==1,"duplicate retained");
        }check(budget.reservedBytes()==0,"guards leak");
        for(boolean proof:List.of(false,true)){
            budget=budget();if(proof)budget.proofJournal(new CountProof.Journal(1L<<20));try(var models=CountModelViews.create(rows,fill(2,0),fill(2,1),budget);var s=new CountLcg(rows,fill(2,0),fill(2,1),budget,65536)){
                models.publishCuts(view("foreign",rows,2,null,CountModelViews.Semantics.EQUIVALENT),List.of(valid),0,new Object());check(models.cutVersion()==0,"foreign view accepted");models.publishCuts(models.available().get(0),List.of(valid),0,new Object());check(models.cutVersion()==1,"owned cut export");check(!proof||s.learn(valid.row()),"independently provable journal consequence refused");
            }check(budget.reservedBytes()==0,"ownership leak");
        }
    }

    static void production()throws Exception{
        var rnd=new Random(61993489);var level=CountLcg.class.getDeclaredField("level");level.setAccessible(true);var allowance=CountLcg.class.getDeclaredField("allowance");allowance.setAccessible(true);var work=CountLcg.class.getDeclaredField("work");work.setAccessible(true);
        for(int seed=0;seed<1800;seed++){
            int n=3+rnd.nextInt(5),m=3+rnd.nextInt(10),plant=rnd.nextInt(1<<n);var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int j=0;j<m;j++){var terms=new TreeMap<Integer,BigInteger>();var rhs=b(rnd.nextInt(7)-(seed%4==0?5:0));for(int i=0;i<n;i++){int a=rnd.nextInt(21)-10;if(a!=0){terms.put(i,b(a));if(seed%4!=0&&(plant&(1<<i))!=0)rhs=rhs.add(b(a));}}rows.add(new ExactLinearProgram.Constraint(terms,rhs));}
            var lo=fill(n,0);var hi=fill(n,1);var oracle=StrideProbe.enumerate(rows,lo,hi,x->x);var budget=budget();
            try(var models=CountModelViews.create(rows,lo,hi,budget);var r=new CountReduction(rows,lo,hi,budget)){
                r.retainStrideView();while(!r.step()){}models.compileLight();models.addReduced(r);
                try(var lp=CountLpSearch.create(r,n,budget)){
                    if(lp!=null){lp.shareRows();while(!lp.step()){}lp.publishCuts(models,r);engineCuts+=models.cutVersion();retainedLpCuts+=models.cutVersion();}
                }
                // Exercise original certificates independently of LP admission.
                try(var proposal=CountLpLearning.solve(rows,lo,hi,budget,200000)){
                    if(proposal!=null&&proposal.cut!=null){models.publishCuts(models.available().get(0),List.of(proposal.cut),0,new Object());engineCuts++;}
                }
                for(var v:models.available())try(var s=new CountLcg(v.rows(),v.lower(),v.upper(),budget,65536)){
                    int steps=0;while(level.getInt(s)==0&&!s.step()&&++steps<20000){}
                    if(level.getInt(s)>0&&s.counts()==null){allowance.setLong(s,work.getLong(s));if(s.step()&&s.paused()){s.resume(65536);live++;}}
                    imports+=models.importCuts(v,0,s,s);run(s);var solution=s.counts();check(s.infeasible()==oracle.isEmpty(),"solver result mismatch "+seed+" "+v.name());if(solution!=null)check(oracle.contains(List.of(v.restore(solution))),"restored solution invalid "+seed);
                }
            }check(budget.reservedBytes()==0,"production leak "+seed+" bytes="+budget.reservedBytes());
        }check(engineCuts>0&&imports>0&&live>0&&retainedLpCuts>0,"integration wasn't exercised");
    }
    static void lifecycle(){
        for(int cap=1;cap<=300;cap++){
            var checks=new AtomicInteger();final int limit=cap;var budget=new PlanningBudget(0,2_000_000,32L<<20,()->checks.incrementAndGet()>limit,System::nanoTime);
            var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,b(2),1,b(2)),b(3)));var v=view("v",rows,2,new CountMapping(List.of(new CountMapping.Expression(Map.of(0,b(3)),b(2)),new CountMapping.Expression(Map.of(1,b(-5)),b(-3)))),CountModelViews.Semantics.EQUIVALENT);
            try(var p=CountViewCuts.create(budget)){if(p!=null){p.publish(v,List.of(combine(rows,Map.of(0,b(1)))),0,new Object());transferred(p,v);}}catch(java.util.concurrent.CancellationException expected){}check(budget.reservedBytes()==0,"cancel leak "+cap);
        }
    }
    public static void main(String[] args)throws Exception{affine();guards();production();lifecycle();var report=Map.of("affineModels",1600,"productionModels",1800,"facts",facts,"engineCuts",engineCuts,"retainedLpCuts",retainedLpCuts,"imports",imports,"liveTrails",live,"cancellations",300,"checks",checks);Files.writeString(Path.of(args[0],"view-cuts-probe.json"),new Gson().toJson(report));System.out.println(report);}
}
