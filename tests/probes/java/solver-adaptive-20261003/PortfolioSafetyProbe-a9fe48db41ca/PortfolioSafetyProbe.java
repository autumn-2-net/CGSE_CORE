package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public class PortfolioSafetyProbe {
    static int checks,cases,answers;
    static BigInteger b(long n){return BigInteger.valueOf(n);}
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,BigInteger[] x){
        for(int i=0;i<x.length;i++)if(x[i].compareTo(lo[i])<0||x[i].compareTo(hi[i])>0)return false;
        for(var row:rows){BigInteger sum=b(0);for(var t:row.terms().entrySet())sum=sum.add(t.getValue().multiply(x[t.getKey()]));if(sum.compareTo(row.upper())>0)return false;}return true;
    }
    static boolean oracle(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi){
        BigInteger[] x=lo.clone();while(true){if(valid(rows,lo,hi,x))return true;int i=0;for(;i<x.length;i++){if(x[i].compareTo(hi[i])<0){x[i]=x[i].add(b(1));break;}x[i]=lo[i];}if(i==x.length)return false;}
    }
    static void run(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,boolean possible,PlanningBudget budget,int mode){
        // CountQuickSolve is the recipe-count interface (nonnegative counts).
        // The domain and view backends additionally support signed offsets.
        if(mode==2 && Arrays.stream(lo).anyMatch(v->v.signum()<0))return;
        cases++;BigInteger[] x=null;boolean impossible=false;
        try{
            if(mode==0){try(var models=CountModelViews.create(rows,lo,hi,budget)){if(models!=null){models.compileLight();try(var search=new CountViewSearch(models,budget)){for(int i=0;i<200&&budget.remainingWork()>8192;i++){search.resume(16384);while(!search.step()){}x=search.counts();impossible=search.infeasible();if(x!=null||impossible||!search.retained())break;}}}}}
            else if(mode==1){try(var search=new CountDomainSearch(rows,lo,hi,budget,3_000_000)){while(!search.step()){}x=search.counts();impossible=search.infeasible();}}
            else{try(var search=new CountQuickSolve(rows,lo,hi,budget)){while(!search.step()){}x=search.counts();impossible=search.infeasible();}}
            if(x!=null){answers++;check(possible,"falseSAT mode="+mode);check(valid(rows,lo,hi,x),"bad witness");}
            if(impossible){answers++;check(!possible,"falseUNSAT mode="+mode+" case="+cases+" lo="+Arrays.toString(lo)+" hi="+Arrays.toString(hi)+" rows="+rows+" diag="+budget.diagnostics());}
        }catch(PlanningBudget.Exhausted|CancellationException cutoff){}
        check(budget.reservedBytes()==0,"memory leak mode="+mode+" bytes="+budget.reservedBytes());
    }
    public static void main(String[] args){Random r=new Random(10031831);for(int t=0;t<1200;t++){
        int n=2+r.nextInt(6);BigInteger[]lo=new BigInteger[n],hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=b(r.nextInt(7)-(t%2==0?0:3));hi[i]=lo[i].add(b(r.nextInt(t%3==0?4:2)));}
        List<ExactLinearProgram.Constraint> rows=new ArrayList<>();for(int j=0,m=2+r.nextInt(18);j<m;j++){Map<Integer,BigInteger>a=new LinkedHashMap<>();for(int i=0;i<n;i++){int c=r.nextInt(13)-6;if(c!=0)a.put(i,b(c));}rows.add(new ExactLinearProgram.Constraint(a,b(r.nextInt(49)-24)));}
        boolean possible=oracle(rows,lo,hi);for(int mode=0;mode<3;mode++)run(rows,lo,hi,possible,new PlanningBudget(0,3_000_000,64L<<20,()->false,System::nanoTime),mode);
        if(t<80)for(int mode=0;mode<3;mode++){
            run(rows,lo,hi,possible,new PlanningBudget(0,1+t*7,64L<<20,()->false,System::nanoTime),mode);
            int stop=t*3+1;var polls=new AtomicInteger();run(rows,lo,hi,possible,new PlanningBudget(0,3_000_000,64L<<20,()->polls.incrementAndGet()>stop,System::nanoTime),mode);
            run(rows,lo,hi,possible,new PlanningBudget(0,3_000_000,1+t*377,()->false,System::nanoTime),mode);
        }
    }
    for(int mode=0;mode<3;mode++){int n=548;BigInteger[]lo=new BigInteger[n],hi=new BigInteger[n];Arrays.fill(lo,b(0));Arrays.fill(hi,b(1));List<ExactLinearProgram.Constraint>rows=new ArrayList<>();for(int i=0;i<n;i++)rows.add(new ExactLinearProgram.Constraint(Map.of(i,b(-1)),b(-1)));run(rows,lo,hi,true,new PlanningBudget(0,3_000_000,64L<<20,()->false,System::nanoTime),mode);}
    System.out.println("PASS cases="+cases+" answers="+answers+" assertions="+checks);
    }
}
