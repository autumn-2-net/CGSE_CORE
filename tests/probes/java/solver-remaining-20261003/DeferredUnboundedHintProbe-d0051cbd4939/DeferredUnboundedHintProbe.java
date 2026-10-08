package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;

public final class DeferredUnboundedHintProbe {
 static BigInteger b(long v){return BigInteger.valueOf(v);}
 static Field f(String name)throws Exception{Field f=CountLcg.class.getDeclaredField(name);f.setAccessible(true);return f;}
 static Object get(CountLcg s,String n)throws Exception{return f(n).get(s);}
 public static void main(String[]args)throws Exception{
  int colors=5,vertices=colors+1,n=colors*vertices+1,x=n-1;
  var rows=new ArrayList<ExactLinearProgram.Constraint>();
  for(int v=0;v<vertices;v++){
   Map<Integer,BigInteger>a=new LinkedHashMap<>(),d=new LinkedHashMap<>();
   for(int c=0;c<colors;c++){a.put(v*colors+c,b(1));d.put(v*colors+c,b(-1));}
   rows.add(new ExactLinearProgram.Constraint(a,b(1)));rows.add(new ExactLinearProgram.Constraint(d,b(-1)));
  }
  for(int v=0;v<vertices;v++)for(int w=v+1;w<vertices;w++)for(int c=0;c<colors;c++)
   rows.add(new ExactLinearProgram.Constraint(Map.of(v*colors+c,b(2),w*colors+c,b(2),x,b(-1)),b(2)));
  BigInteger[]lo=new BigInteger[n],hi=new BigInteger[n];Arrays.fill(lo,b(0));Arrays.fill(hi,b(1));hi[x]=null;
  var budget=new PlanningBudget(0,4000000,64L<<20,()->false,System::nanoTime);
  boolean seam=false,rootDeferred=false,finiteChildHint=false,restoredUnbounded=false;int resumes=0;
  try(var solver=new CountLcg(rows,lo,hi,budget,131072)){
   for(int step=0;step<1000000;step++){
    if(!seam&&(boolean)get(solver,"differenceChecked")&&((Deque<?>)get(solver,"queue")).isEmpty()&&(int)get(solver,"decisions")==0){
     // Set only the cooperative allowance at an observed root fixpoint. Domains,
     // LP hints, branching, conflicts and backtracks are all the real solver.
     f("allowance").setLong(solver,(long)get(solver,"work")+64);seam=true;
    }
    boolean done=solver.step();
    rootDeferred|=(long)get(solver,"finiteHintDeferredAllowance")>=0;
    BigInteger[]high=(BigInteger[])get(solver,"high");
    if(get(solver,"finiteHint")!=null&&high[x]!=null)finiteChildHint=true;
    if(finiteChildHint&&high[x]==null)restoredUnbounded=true;
    if(done){
     if(solver.paused()){solver.resume(131072);resumes++;continue;}
     if(solver.counts()==null)throw new AssertionError("expected a feasible escape coloring");
     BigInteger[]z=solver.counts();for(var row:rows){BigInteger sum=b(0);for(var term:row.terms().entrySet())sum=sum.add(term.getValue().multiply(z[term.getKey()]));if(sum.compareTo(row.upper())>0)throw new AssertionError("invalid witness");}
     if(!seam||!rootDeferred||!finiteChildHint||!restoredUnbounded)throw new AssertionError("missing target path "+seam+" "+rootDeferred+" "+finiteChildHint+" "+restoredUnbounded);
     System.out.println("PASS deferred=true finiteChildHint=true naturalUnboundedBacktrack=true resumes="+resumes+" restarts="+get(solver,"restarts")+" work="+budget.nodes());return;
    }
   }throw new AssertionError("did not complete");
  }finally{if(budget.reservedBytes()!=0)throw new AssertionError("reservation leak "+budget.reservedBytes());System.out.println("TRACE seam="+seam+" deferred="+rootDeferred+" finiteChildHint="+finiteChildHint+" restoredUnbounded="+restoredUnbounded+" reservedAfterClose="+budget.reservedBytes());}
 }
}
