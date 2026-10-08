package org.cgse.core;

import com.google.gson.Gson;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public final class DivingProbe {
    static long assertions;
    static void check(boolean b,String s){assertions++;if(!b)throw new AssertionError(s);}
    record Case(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,ExactRational[] point){}
    static Case sample(int seed){Random random=new Random(seed);int n=4+seed%5;var lo=new BigInteger[n];var hi=new BigInteger[n];var point=new ExactRational[n];int[]hidden=new int[n];
        for(int i=0;i<n;i++){lo[i]=BigInteger.ZERO;hi[i]=BigInteger.valueOf(3);hidden[i]=random.nextInt(4);point[i]=new ExactRational(BigInteger.valueOf(1+random.nextInt(5)),BigInteger.TWO);}
        var rows=new ArrayList<ExactLinearProgram.Constraint>();
        for(int j=0;j<3+n;j++){var terms=new LinkedHashMap<Integer,BigInteger>();long bound=0;for(int i=0;i<n;i++){int w=random.nextInt(11)-5;if(w!=0){terms.put(i,BigInteger.valueOf(w));bound+=w*hidden[i];}}bound+=j%3==0?random.nextInt(4):0;rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(bound)));}
        return new Case(rows,lo,hi,point);
    }
    static boolean run(Case c,PlanningBudget b){try(var d=new CountDiving(c.rows,c.lo,c.hi,c.point,b)){while(!d.step()){}var counts=d.counts();if(counts!=null){CountBenchmark.verify(c.rows,c.lo,c.hi,counts);assertions+=c.rows.size()+counts.length;return true;}return false;}}
    public static void main(String[]args)throws Exception{
        var results=new ArrayList<Map<String,Object>>();long work=0;int solved=0;
        for(int seed=0;seed<1600;seed++){var b=new PlanningBudget(0,1000000,128L<<20,()->false,System::nanoTime);boolean yes=run(sample(seed),b);if(yes)solved++;work+=b.nodes();check(b.reservedBytes()==0,"dive leak");results.add(Map.of("id",seed,"solved",yes,"work",b.nodes(),"diagnostics",b.diagnostics()));}
        for(int limit=1;limit<300;limit++){AtomicInteger calls=new AtomicInteger();final int end=limit*17;var b=new PlanningBudget(0,1000000,128L<<20,()->calls.incrementAndGet()>end,System::nanoTime);try{run(sample(limit),b);}catch(CancellationException|PlanningBudget.Exhausted accepted){}check(b.reservedBytes()==0,"dive cancel leak");}
        Files.writeString(Path.of(args[0],"diving-results.json"),new Gson().toJson(results));System.out.println(Map.of("cases",1600,"solved",solved,"work",work,"assertions",assertions));
    }
}
