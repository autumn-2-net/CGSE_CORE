package org.cgse.core;

import java.math.BigInteger;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;

public final class WarmBasisProbe {
    static long assertions, calls, coldWork, warmWork, coldPivots, warmPivots, reuses, cuts, feasiblePoints;
    static BigInteger b(long x){return BigInteger.valueOf(x);}
    static BigInteger[] fill(int n,long v){var a=new BigInteger[n];Arrays.fill(a,b(v));return a;}
    static PlanningBudget budget(){return new PlanningBudget(0,100_000_000,128L<<20,()->false,()->0L);}
    static void check(boolean x,String message){assertions++;if(!x)throw new AssertionError(message);}
    static boolean valid(ExactLinearProgram.Constraint r,BigInteger[]x){var sum=BigInteger.ZERO;for(var t:r.terms().entrySet())sum=sum.add(t.getValue().multiply(x[t.getKey()]));return sum.compareTo(r.upper())<=0;}
    static void numericPoint(List<ExactLinearProgram.Constraint>rows,double[]x){
        for(double v:x)check(Double.isFinite(v)&&v>=-1e-6,"invalid numerical coordinate");
        for(var r:rows){double sum=0,norm=1+Math.abs(r.upper().doubleValue());for(var t:r.terms().entrySet()){double v=t.getValue().doubleValue()*x[t.getKey()];sum+=v;norm+=Math.abs(v);}check(sum<=r.upper().doubleValue()+1e-6*norm,"numerical row violated");}
    }
    static void numeric(){
        var random=new Random(16183019);
        for(int test=0;test<300;test++){
            int n=2+random.nextInt(7),m=3+random.nextInt(9);var initial=new ArrayList<ExactLinearProgram.Constraint>();
            for(int r=0;r<m;r++){var terms=new TreeMap<Integer,BigInteger>();for(int i=0;i<n;i++){int v=random.nextInt(11)-5;if(v!=0)terms.put(i,b(v));}initial.add(new ExactLinearProgram.Constraint(terms,b(3+random.nextInt(10))));}
            for(int i=0;i<n;i++)initial.add(new ExactLinearProgram.Constraint(Map.of(i,BigInteger.ONE),BigInteger.ONE));
            var costs=new BigInteger[n];for(int i=0;i<n;i++)costs[i]=b(random.nextInt(9)-4);
            var budget=budget();
            try(var session=new CountNumericRelaxation.Session()){
                for(int step=0;step<20;step++){
                    var rows=new ArrayList<ExactLinearProgram.Constraint>();for(int r=0;r<initial.size();r++){var base=initial.get(r);var rhs=r>=m?b(random.nextInt(5)==0?0:1):base.upper().add(b(random.nextInt(7)-3));rows.add(new ExactLinearProgram.Constraint(base.terms(),rhs));}
                    var cold=CountNumericRelaxation.solve(n,rows,costs,budget,200000);var warm=session.solve(n,rows,costs,budget,200000);calls++;
                    check(cold!=null&&warm!=null,"numeric declined test="+test+" step="+step);
                    check(cold.phaseOneInfeasible()==warm.phaseOneInfeasible(),"warm/cold feasibility differs test="+test+" step="+step);
                    if(cold.point()!=null&&warm.point()!=null){numericPoint(rows,warm.point());double c=0,w=0;for(int i=0;i<n;i++){c+=costs[i].doubleValue()*cold.point()[i];w+=costs[i].doubleValue()*warm.point()[i];}check(Math.abs(c-w)<=1e-6*(1+Math.abs(c)+Math.abs(w)),"warm objective differs "+c+" "+w);}
                    coldWork+=cold.work();warmWork+=warm.work();coldPivots+=cold.pivots();warmPivots+=warm.pivots();
                }reuses+=session.reused();
            }check(budget.reservedBytes()==0,"numeric cache leak");
        }
    }
    static void cuts(){
        var random=new Random(372775);
        for(int test=0;test<500;test++){
            int n=3+random.nextInt(5),m=3+random.nextInt(8);var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int r=0;r<m;r++){var terms=new TreeMap<Integer,BigInteger>();var scale=r%3==0?BigInteger.TEN.pow(test%2==0?35:70):BigInteger.ONE;var rhs=b(random.nextInt(17)-5).multiply(scale);for(int i=0;i<n;i++){int v=random.nextInt(11)-5;if(v!=0)terms.put(i,b(v).multiply(scale));}rows.add(new ExactLinearProgram.Constraint(terms,rhs));}
            var budget=budget();try(var session=new CountLpLearning.Session()){
                for(int step=0;step<12;step++){
                    var lo=fill(n,0);var hi=fill(n,1);for(int i=0;i<n;i++)if(random.nextInt(3)==0)lo[i]=hi[i]=b(random.nextInt(2));
                    try(var result=session.solve(rows,lo,hi,budget,200000)){
                        if(result==null)continue;
                        if(result.cut!=null){cuts++;var proof=new CountProof.Derivation("warm_cut",n,rows.stream().map(CountProof::row).toList(),List.of(new CountProof.Combination(result.cut.parents(),result.cut.divisor(),CountProof.row(result.cut.row()))));check(CountProof.verify(proof,2_000_000)==CountProof.Verdict.VERIFIED,"warm cut certificate");}
                        for(int mask=0;mask<(1<<n);mask++){var x=fill(n,0);for(int i=0;i<n;i++)if((mask&(1<<i))!=0)x[i]=BigInteger.ONE;if(rows.stream().anyMatch(r->!valid(r,x)))continue;feasiblePoints++;if(result.cut!=null)check(valid(result.cut.row(),x),"warm cut violated GLOBAL point");}
                    }
                }
            }check(budget.reservedBytes()==0,"learning cache leak");
        }
    }
    static List<ExactLinearProgram.Constraint> box(long rhs){return List.of(new ExactLinearProgram.Constraint(Map.of(0,b(2),1,b(3)),b(rhs)),new ExactLinearProgram.Constraint(Map.of(0,b(1)),b(1)),new ExactLinearProgram.Constraint(Map.of(1,b(1)),b(1)));}
    static Object field(Object target,String name)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static void lifecycle()throws Exception{
        var budget=budget();try(var session=new CountNumericRelaxation.Session()){
            var cost=new BigInteger[]{b(3),b(4)};check(session.solve(2,box(4),cost,budget,200000)!=null,"cold init");
            check(session.solve(2,box(3),cost,budget,200000)!=null&&session.reused()==1,"RHS did not reuse");
            var changed=new ArrayList<>(box(3));changed.set(0,new ExactLinearProgram.Constraint(Map.of(0,b(3),1,b(3)),b(3)));
            check(session.solve(2,changed,cost,budget,200000)!=null&&session.invalidated()==1,"changed A did not invalidate");
            var otherCost=new BigInteger[]{b(4),b(3)};check(session.solve(2,changed,otherCost,budget,200000)!=null&&session.invalidated()==2,"objective did not invalidate");
            var grown=new ArrayList<>(changed);grown.add(new ExactLinearProgram.Constraint(Map.of(0,b(-1)),b(0)));check(session.solve(2,grown,otherCost,budget,200000)!=null&&session.invalidated()==3,"row count did not invalidate");
            Collections.reverse(grown);check(session.solve(2,grown,otherCost,budget,200000)!=null&&session.invalidated()==4,"row order did not invalidate");
            // Test numerical corruption through reflection, never a production hook.
            Object solver=field(session,"solver");double[][] tableau=(double[][])field(solver,"tableau");tableau[grown.size()][0]=Double.NaN;
            long fallbacks=session.fallbacks();var repaired=session.solve(2,grown,otherCost,budget,200000);check(repaired!=null&&session.fallbacks()>fallbacks,"bad basis did not cold-fallback");numericPoint(grown,repaired.point());
            var next=budget();try{check(session.solve(2,box(2),cost,next,200000)!=null,"budget change declined");check(budget.reservedBytes()==0,"old order retained after budget change");}finally{session.close();check(next.reservedBytes()==0,"new order leak");}
        }check(budget.reservedBytes()==0,"session lifecycle leak");
        for(int after:new int[]{0,1,2,5,10,30,100,300}){
            var enabled=new AtomicBoolean();var calls=new AtomicInteger();var b=new PlanningBudget(0,10000000,8L<<20,()->enabled.get()&&calls.incrementAndGet()>after,()->0L);
            try(var session=new CountNumericRelaxation.Session()){session.solve(2,box(4),new BigInteger[]{b(3),b(4)},b,200000);enabled.set(true);try{session.solve(2,box(2),new BigInteger[]{b(3),b(4)},b,200000);}catch(CancellationException expected){}}check(b.reservedBytes()==0,"warm cancellation leak");
        }
        for(long memory:new long[]{1024,4096,8192,16384,65536}){var b=new PlanningBudget(0,1000000,memory,()->false,()->0L);try(var session=new CountLpLearning.Session()){try(var result=session.solve(box(4),fill(2,0),fill(2,1),b,200000)){}try(var result=session.solve(box(3),fill(2,0),fill(2,1),b,200000)){}}check(b.reservedBytes()==0,"low memory leak");}
        for(long limit:new long[]{1,16,32,128,1024}){var b=budget();try(var session=new CountNumericRelaxation.Session()){session.solve(2,box(4),new BigInteger[]{b(3),b(4)},b,200000);long start=b.nodes();session.solve(2,box(1),new BigInteger[]{b(3),b(4)},b,limit);check(b.nodes()-start<=limit+32,"local quota overrun "+limit+" used="+(b.nodes()-start));}check(b.reservedBytes()==0,"local limit leak");}
    }
    public static void main(String[]args)throws Exception{numeric();cuts();lifecycle();System.out.println("WarmBasisProbe calls="+calls+" reused="+reuses+" coldWork="+coldWork+" warmWork="+warmWork+" coldPivots="+coldPivots+" warmPivots="+warmPivots+" globalCuts="+cuts+" feasiblePoints="+feasiblePoints+" assertions="+assertions+" invalid=0 leaks=0");}
}
