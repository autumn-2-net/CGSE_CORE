package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
public final class ScaleSlackReview {
    static BigInteger z(long n){return BigInteger.valueOf(n);}
    public static void main(String[] args){
        Random rng=new Random(697260);int found=0;
        for(int trial=0;trial<1500;trial++){
            int n=2+rng.nextInt(6);BigInteger scale=trial%3==0?z(Long.MAX_VALUE).add(z(73)):z(65537+rng.nextInt(999));
            BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n],known=new BigInteger[n];Arrays.fill(lo,BigInteger.ZERO);
            for(int i=0;i<n;i++){hi[i]=scale.multiply(z(4)).add(z(trial%2));known[i]=scale.multiply(z(rng.nextInt(5)));}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int k=0;k<2+rng.nextInt(4);k++){
                Map<Integer,BigInteger> terms=new LinkedHashMap<>();BigInteger total=BigInteger.ZERO;
                for(int i=0;i<n;i++){BigInteger c=z(rng.nextInt(15)-7);if(c.signum()!=0)terms.put(i,c);total=total.add(c.multiply(known[i]));}
                rows.add(new ExactLinearProgram.Constraint(terms,total.add(z(trial%3))));
                Map<Integer,BigInteger> opposite=new LinkedHashMap<>();terms.forEach((i,c)->opposite.put(i,c.negate()));
                rows.add(new ExactLinearProgram.Constraint(opposite,total.negate()));
            }
            if(trial%4==0){lo[0]=known[0].signum()>0?known[0].subtract(BigInteger.ONE):BigInteger.ZERO;rows.add(new ExactLinearProgram.Constraint(Map.of(0,z(-1)),lo[0].negate()));}
            var b=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);
            try(var search=new CountScale(rows,lo,hi,b)){
                while(!search.step()){}
                var value=search.counts();
                if(value!=null){found++;for(int i=0;i<n;i++)if(value[i].compareTo(lo[i])<0||value[i].compareTo(hi[i])>0)throw new AssertionError("Bounds");
                    for(var row:rows){BigInteger sum=BigInteger.ZERO;for(var term:row.terms().entrySet())sum=sum.add(term.getValue().multiply(value[term.getKey()]));if(sum.compareTo(row.upper())>0)throw new AssertionError("Invalid slack/floor witness");}}
            }
            if(b.reservedBytes()!=0)throw new AssertionError("Scale workspace leaked");
        }
        System.out.println("PASS: 1500 signed exact systems with slack and rounded singleton bounds; valid witnesses="+found+"; beyond-long arithmetic and workspace release");
    }
}
