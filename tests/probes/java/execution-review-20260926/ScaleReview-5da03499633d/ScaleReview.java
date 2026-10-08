package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

public final class ScaleReview {
    static BigInteger z(long n){return BigInteger.valueOf(n);}
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);}
    public static void main(String[] args) {
        Random random=new Random(284226);
        int found=0;
        for(int trial=0;trial<1000;trial++) {
            int n=2+random.nextInt(6);
            BigInteger multiplier=trial%3==0?z(Long.MAX_VALUE).add(z(47)):z(65537+random.nextInt(5000));
            BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n],known=new BigInteger[n];
            Arrays.fill(lo,BigInteger.ZERO);
            for(int i=0;i<n;i++){hi[i]=multiplier.multiply(z(3));known[i]=z(random.nextInt(4));}
            List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
            for(int k=0;k<2+random.nextInt(4);k++) {
                Map<Integer,BigInteger> terms=new LinkedHashMap<>();BigInteger total=BigInteger.ZERO;
                for(int i=0;i<n;i++){BigInteger weight=z(random.nextInt(13)-6);if(weight.signum()!=0)terms.put(i,weight);total=total.add(weight.multiply(known[i]));}
                rows.add(new ExactLinearProgram.Constraint(terms,total.multiply(multiplier)));
                Map<Integer,BigInteger> opposite=new LinkedHashMap<>();terms.forEach((i,v)->opposite.put(i,v.negate()));
                rows.add(new ExactLinearProgram.Constraint(opposite,total.negate().multiply(multiplier)));
            }
            var b=budget();
            try(var scale=new CountScale(rows,lo,hi,b)) {
                while(!scale.step()){}
                BigInteger[] value=scale.counts();
                if(value!=null){
                    found++;
                    for(int i=0;i<n;i++)if(value[i].signum()<0||value[i].compareTo(hi[i])>0)throw new AssertionError("Bounds");
                    for(var row:rows){BigInteger sum=BigInteger.ZERO;for(var e:row.terms().entrySet())sum=sum.add(e.getValue().multiply(value[e.getKey()]));if(sum.compareTo(row.upper())>0)throw new AssertionError("Inexact lifted equation");}
                }
            }
            if(b.reservedBytes()!=0)throw new AssertionError("Scale memory leak");
        }
        // A solution may need counts not divisible by the candidate factor.
        // 3*21844 + 5*1 = 65537; requiring both counts divisible by 65537 fails.
        var terms=Map.of(0,z(3),1,z(5));var inverse=Map.of(0,z(-3),1,z(-5));
        var rows=List.of(new ExactLinearProgram.Constraint(terms,z(65537)),new ExactLinearProgram.Constraint(inverse,z(-65537)));
        BigInteger[] low={z(0),z(0)},high={z(65537),z(65537)};
        var b=budget();
        try(var scale=new CountScale(rows,low,high,b)){while(!scale.step()){}if(scale.counts()!=null)throw new AssertionError("Invalid divisible candidate");}
        try(var original=new CountQuickSolve(rows,low,high,b)){while(!original.step()){}if(original.infeasible())throw new AssertionError("Candidate failure leaked into proof");}
        if(b.reservedBytes()!=0)throw new AssertionError("Failed candidate memory leak");
        System.out.println("PASS: 1000 exact scaled systems ("+found+" witnesses), beyond-long factors, original nondivisible solution retained and memory released");
    }
}
