package org.cgse.core;
import java.math.BigInteger;
import java.util.*;

public final class NeighborhoodReviewProbe {
    static BigInteger b(long x){return BigInteger.valueOf(x);}
    static void dense(){
        var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<256;i++)terms.put(i,BigInteger.ONE);
        var rows=new ArrayList<ExactLinearProgram.Constraint>();for(int i=0;i<1024;i++)rows.add(new ExactLinearProgram.Constraint(terms,b(256)));
        var lower=new BigInteger[256];var upper=new BigInteger[256];var point=new ExactRational[256];Arrays.fill(lower,BigInteger.ZERO);Arrays.fill(upper,b(2));Arrays.fill(point,new ExactRational(BigInteger.ONE,b(2)));
        for(long limit:new long[]{65536,1_000_000,10_000_000}){
            long allowance=Math.min(131072,limit/16);var budget=new PlanningBudget(0,limit,128L<<20,()->false,System::nanoTime);
            try(var search=new CountNeighborhood(rows,lower,upper,point,budget).pump(false)){
                while(!search.step()){}
                if(budget.nodes()>allowance)throw new AssertionError("constructor exceeds local allowance "+budget.nodes()+" / "+allowance);
                if(search.counts()!=null)throw new AssertionError("dense cutoff returned unverified counts");
            }
            if(budget.reservedBytes()!=0)throw new AssertionError("dense close leaked "+budget.reservedBytes());
            System.out.println("dense 1024x256 request="+limit+" local="+allowance+" spent="+budget.nodes()+" remaining="+budget.remainingWork()+" closed=0");
        }
    }
    static void relaxed(){
        var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,b(2),1,b(-37)),b(1)),new ExactLinearProgram.Constraint(Map.of(0,b(-2),1,b(37)),b(-1)));
        var lo=new BigInteger[]{b(0),b(0)};var hi=new BigInteger[]{b(128),null};var point=new ExactRational[]{new ExactRational(b(1),b(2)),ExactRational.ZERO};var budget=new PlanningBudget(0,5_000_000,128L<<20,()->false,System::nanoTime);
        try(var search=new CountNeighborhood(rows,lo,hi,point,budget).pump(false)){
            while(!search.step()){}var result=search.counts();if(result==null)throw new AssertionError("relaxed domain witness missed");
            if(result[0].signum()<0||result[0].compareTo(hi[0])>0||result[1].signum()<0||!result[0].multiply(b(2)).subtract(result[1].multiply(b(37))).equals(b(1)))throw new AssertionError("invalid relaxed witness");
            System.out.println("relaxed witness="+Arrays.toString(result)+" work="+budget.nodes());
        }if(budget.reservedBytes()!=0)throw new AssertionError("relaxed close leaked");
    }
    public static void main(String[]args){dense();relaxed();System.out.println("PASS independent Neighborhood dense quota and original-domain witness review");}
}
