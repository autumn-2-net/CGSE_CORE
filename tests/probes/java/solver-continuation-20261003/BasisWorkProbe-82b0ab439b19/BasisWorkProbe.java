package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
public final class BasisWorkProbe {
    static BigInteger b(long x){return BigInteger.valueOf(x);}
    static List<ExactLinearProgram.Constraint> rows(int n,int face,int step,boolean change){
        var random=new Random(117+face);var rows=new ArrayList<ExactLinearProgram.Constraint>();
        for(int r=0;r<n;r++) {var terms=new TreeMap<Integer,BigInteger>();for(int i=0;i<n;i++){int a=random.nextInt(11)-5;if(a!=0)terms.put(i,b(a));}rows.add(new ExactLinearProgram.Constraint(terms,b(10+random.nextInt(20)+(change&&r==step%n?1:0))));}
        for(int i=0;i<n;i++)rows.add(new ExactLinearProgram.Constraint(Map.of(i,b(1)),b(20)));
        return rows;
    }
    public static void main(String[]args)throws Exception{
        for(boolean change:new boolean[]{false,true})for(int n:new int[]{8,32,64}){
            long work=0,time=0,pivots=0,rebuilt=0,reused=0,restored=0;
            for(int seed=0;seed<5;seed++){
                var random=new Random(seed);var cost=new BigInteger[n];for(int i=0;i<n;i++)cost[i]=b(random.nextInt(21)-10);
                var budget=new PlanningBudget(0,Long.MAX_VALUE,256L<<20,()->false,()->0L);
                try(var session=new CountNumericRelaxation.Session()){
                    for(int step=0;step<60;step++){
                        var rows=rows(n,step%2,step,change);long start=System.nanoTime();var result=session.solve(n,rows,cost,budget,10_000_000);time+=System.nanoTime()-start;
                        if(result==null||result.point()==null)throw new AssertionError("no point");work+=result.work();pivots+=result.pivots();
                        for(var row:rows){double sum=0;for(var t:row.terms().entrySet())sum+=t.getValue().doubleValue()*result.point()[t.getKey()];if(sum>row.upper().doubleValue()+1e-5)throw new AssertionError("bad point");}
                    }
                    rebuilt+=session.rebuilt();reused+=session.reused();
                    try{var m=session.getClass().getDeclaredMethod("restored");m.setAccessible(true);restored+=(long)m.invoke(session);}catch(NoSuchMethodException ignored){}
                }
                if(budget.reservedBytes()!=0)throw new AssertionError("leak");
            }
            System.out.println("BasisWorkProbe changing="+change+" n="+n+" work="+work+" ms="+(time/1e6)+" pivots="+pivots+" rebuilt="+rebuilt+" reused="+reused+" restored="+restored);
        }
    }
}
