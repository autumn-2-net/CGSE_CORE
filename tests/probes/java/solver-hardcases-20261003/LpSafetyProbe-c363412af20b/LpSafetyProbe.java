package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

public class LpSafetyProbe {
  static int assertions, warm, optimal, stopped;
  static void check(boolean b,String m){assertions++;if(!b)throw new AssertionError(m);}
  static BigInteger b(long v){return BigInteger.valueOf(v);}
  static List<ExactLinearProgram.Constraint> rows(int n, int seed){
    Random r=new Random(seed);List<ExactLinearProgram.Constraint>a=new ArrayList<>();
    for(int i=0;i<n;i++)a.add(new ExactLinearProgram.Constraint(Map.of(i,b(1)),b(i<6?1:0)));
    for(int row=0;row<7;row++){Map<Integer,BigInteger>t=new TreeMap<>();int limit=0;for(int j=0;j<6;j++){int v=r.nextInt(7)-3;if(v!=0)t.put(j,b(v));if(v>0)limit+=v;}a.add(new ExactLinearProgram.Constraint(t,b(limit/2)));}
    return a;
  }
  static BigInteger[] objective(int n,int seed){BigInteger[]a=new BigInteger[n];Arrays.fill(a,b(0));Random r=new Random(seed+71);for(int i=0;i<6;i++)a[i]=b(r.nextInt(7)-3);return a;}
  static ExactRational value(ExactRational[]p,BigInteger[]c){ExactRational v=ExactRational.ZERO;for(int i=0;i<p.length;i++)v=v.add(p[i].multiply(ExactRational.of(c[i])));return v;}
  static void verify(ExactRational[]p,List<ExactLinearProgram.Constraint>rows){for(var v:p)check(v.signum()>=0,"negative");for(var r:rows){ExactRational v=ExactRational.ZERO;for(var t:r.terms().entrySet())v=v.add(p[t.getKey()].multiply(ExactRational.of(t.getValue())));check(v.compareTo(ExactRational.of(r.upper()))<=0,"row");}}
  public static void main(String[]args){
    for(int seed=0;seed<160;seed++){
      PlanningBudget budget=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);
      ExactRational[]base;ExactLinearProgram.Result status;
      try(var lp=new ExactLinearProgram(63,rows(63,seed),objective(63,seed),budget)){while(!lp.step()){}status=lp.result();base=lp.point();}
      try(var lp=new ExactLinearProgram(64,rows(64,seed),objective(64,seed),budget,null,true)){
        while(!lp.step()){}check(lp.result()==status,"cold status");
        if(status==ExactLinearProgram.Result.OPTIMAL){optimal++;verify(lp.point(),rows(64,seed));check(value(base,objective(63,seed)).equals(value(lp.point(),objective(64,seed))),"cold optimum");}
        try(var basis=lp.takeBasis()){
          if(basis!=null){var childRows=new ArrayList<>(rows(64,seed));childRows.add(new ExactLinearProgram.Constraint(Map.of(seed%6,b(1)),b(0)));
            ExactRational coldValue=null;ExactLinearProgram.Result cold;
            try(var child=new ExactLinearProgram(64,childRows,objective(64,seed),budget)){while(!child.step()){}cold=child.result();if(cold==ExactLinearProgram.Result.OPTIMAL)coldValue=value(child.point(),objective(64,seed));}
            try(var child=new ExactLinearProgram(64,childRows,objective(64,seed),budget,basis,true)){while(!child.step()){}check(child.result()==cold,"warm status");if(child.hot())warm++;if(cold==ExactLinearProgram.Result.OPTIMAL){verify(child.point(),childRows);check(value(child.point(),objective(64,seed)).equals(coldValue),"warm optimum");}}
            // Dropping a parent row and changing objective must invalidate reuse.
            var weaker=new ArrayList<>(childRows);weaker.remove(0);BigInteger[]other=objective(64,seed);other[1]=b(7);
            try(var child=new ExactLinearProgram(64,weaker,other,budget,basis,true)){while(!child.step()){}check(!child.hot(),"incompatible warm");if(child.result()==ExactLinearProgram.Result.OPTIMAL)verify(child.point(),weaker);}
          }
        }
      }
      check(budget.reservedBytes()==0,"normal leaked "+budget.reservedBytes());
    }
    for(int cap:new int[]{1,32,512,2048,32768,65536,250000,1000000})for(long memory:new long[]{1024,65536,1<<20,128L<<20}){
      PlanningBudget budget=new PlanningBudget(0,cap,memory,()->false,System::nanoTime);
      try(var lp=new ExactLinearProgram(64,rows(64,1),objective(64,1),budget,null,true)){while(!lp.step()){} }
      catch(PlanningBudget.Exhausted expected){stopped++;}
      check(budget.reservedBytes()==0,"quota leaked "+cap+" "+memory+" "+budget.reservedBytes());
    }
    for(int cancel=0;cancel<18000;cancel+=37){final int stop=cancel;int[]checks={0};PlanningBudget budget=new PlanningBudget(0,20_000_000,128L<<20,()->checks[0]++>=stop,System::nanoTime);
      try(var lp=new ExactLinearProgram(64,rows(64,77),objective(64,77),budget,null,true)){while(!lp.step()){} }
      catch(CancellationException expected){stopped++;}
      check(budget.reservedBytes()==0,"cancel leaked "+cancel+" "+budget.reservedBytes());
    }
    System.out.println("LP_SAFETY assertions="+assertions+" optimal="+optimal+" warm="+warm+" stopped="+stopped);
  }
}
