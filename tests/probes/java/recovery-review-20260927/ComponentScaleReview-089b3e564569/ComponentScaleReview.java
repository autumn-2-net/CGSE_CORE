package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

public final class ComponentScaleReview {
    static final BigInteger ZERO=BigInteger.ZERO, ONE=BigInteger.ONE;
    static void check(List<ExactLinearProgram.Constraint> rows,BigInteger[] low,BigInteger[] high,BigInteger[] values) {
        for(int i=0;i<low.length;i++)if(values[i].compareTo(low[i])<0||values[i].compareTo(high[i])>0)throw new AssertionError("Domain");
        for(var row:rows){BigInteger total=ZERO;for(var e:row.terms().entrySet())total=total.add(e.getValue().multiply(values[e.getKey()]));if(total.compareTo(row.upper())>0)throw new AssertionError("Row");}
    }
    public static void main(String[] args) {
        Random random=new Random(827544);int witnesses=0;
        for(int trial=0;trial<1000;trial++) {
            int modules=2+random.nextInt(3),n=3+random.nextInt(4),size=modules*n;
            BigInteger[] low=new BigInteger[size], high=new BigInteger[size],planted=new BigInteger[size];
            List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
            for(int part=0;part<modules;part++) {
                BigInteger scale=ONE.shiftLeft(65).add(BigInteger.valueOf(39+random.nextInt(200)));
                int[] weights=new int[n];
                for(int i=0;i<n;i++){int id=part*n+i;weights[i]=1+random.nextInt(13);int k=1+random.nextInt(4);planted[id]=scale.multiply(BigInteger.valueOf(k));low[id]=planted[id].subtract(scale).subtract(ONE);high[id]=planted[id].add(scale).add(BigInteger.TWO);if(low[id].signum()<0)low[id]=ZERO;}
                for(int d=0;d<2;d++) {
                    Map<Integer,BigInteger> terms=new LinkedHashMap<>(),reverse=new LinkedHashMap<>();BigInteger rhs=ZERO;
                    for(int i=0;i<n;i++){int id=part*n+i;BigInteger w=BigInteger.valueOf(d==0?weights[i]:1+random.nextInt(13));terms.put(id,w);reverse.put(id,w.negate());rhs=rhs.add(w.multiply(planted[id]));}
                    rows.add(new ExactLinearProgram.Constraint(terms,rhs));rows.add(new ExactLinearProgram.Constraint(reverse,rhs.negate()));
                }
            }
            var budget=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);
            try(var work=new CountComponents(rows,low,high,budget)) {
                while(!work.step()){}
                if(work.infeasible())throw new AssertionError("Deleted planted witness");
                if(work.counts()!=null){check(rows,low,high,work.counts());witnesses++;}
            }
            if(budget.reservedBytes()!=0)throw new AssertionError("Workspace leak "+budget.reservedBytes());
        }
        System.out.println("PASS: 1000 independent scaled systems >long, rounded lower bounds, "+witnesses+" witnesses independently checked and all memory released");
    }
}
