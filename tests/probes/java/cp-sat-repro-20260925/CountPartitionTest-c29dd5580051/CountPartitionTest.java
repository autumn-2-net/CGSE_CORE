package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Independent exhaustive arithmetic oracle; no solver or planted solution used as oracle. */
public final class CountPartitionTest {
    private static int checked;
    private static final BigInteger ZERO=BigInteger.ZERO, ONE=BigInteger.ONE;
    static ExactLinearProgram.Constraint row(Map<Integer,BigInteger> terms,BigInteger upper){return new ExactLinearProgram.Constraint(terms,upper);}
    static PlanningBudget budget(){return new PlanningBudget(0,4_000_000,16L<<20,()->false,System::nanoTime);}
    static BigInteger[] solve(int n,List<ExactLinearProgram.Constraint> rows,BigInteger[] lower,BigInteger[] upper,PlanningBudget b){
        long before=b.reservedBytes();
        try(var dp=new CountPartition(rows,lower,upper,b)) {
            while(!dp.step()){}
            var counts=dp.counts();
            if(counts!=null){
                for(int i=0;i<n;i++)require(counts[i].compareTo(lower[i])>=0&&counts[i].compareTo(upper[i])<=0,"Domain violation");
                for(var r:rows){BigInteger sum=ZERO;for(var t:r.terms().entrySet())sum=sum.add(t.getValue().multiply(counts[t.getKey()]));require(sum.compareTo(r.upper())<=0,"Ignored joint constraint");}
            }
            checked++;
            return counts;
        } finally {require(b.reservedBytes()==before,"Workspace leaked");}
    }
    static BigInteger[] zeros(int n){var a=new BigInteger[n];Arrays.fill(a,ZERO);return a;}
    static BigInteger[] ones(int n){var a=new BigInteger[n];Arrays.fill(a,ONE);return a;}
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static Map<Integer,BigInteger> negate(Map<Integer,BigInteger> in){var out=new LinkedHashMap<Integer,BigInteger>();in.forEach((k,v)->out.put(k,v.negate()));return out;}
    static boolean exists(int[] coefficients,int lo,int hi){
        for(int mask=0;mask<(1<<coefficients.length);mask++){int sum=0;for(int i=0;i<coefficients.length;i++)if((mask&(1<<i))!=0)sum+=coefficients[i];if(sum>=lo&&sum<=hi)return true;}return false;
    }
    static void intervals() {
        var rng=new Random(672345);
        for(int test=0;test<600;test++){
            int n=1+rng.nextInt(14);int[] w=new int[n];var terms=new LinkedHashMap<Integer,BigInteger>();
            for(int i=0;i<n;i++){w[i]=rng.nextInt(81)-40;if(w[i]!=0)terms.put(i,BigInteger.valueOf(w[i]));}
            int lo=rng.nextInt(201)-100,hi=lo+rng.nextInt(8);
            var rows=List.of(row(terms,BigInteger.valueOf(hi)),row(negate(terms),BigInteger.valueOf(-lo)));
            var result=solve(n,rows,zeros(n),ones(n),budget());
            require((result!=null)==exists(w,lo,hi),"Signed interval mismatch case "+test);
        }
        int[] shifts={1,63,64,65,127,128,129};
        for(int target=0;target<600;target++){
            var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<shifts.length;i++)terms.put(i,BigInteger.valueOf(shifts[i]));
            var rows=List.of(row(terms,BigInteger.valueOf(target)),row(negate(terms),BigInteger.valueOf(-target)));
            require((solve(shifts.length,rows,zeros(shifts.length),ones(shifts.length),budget())!=null)==exists(shifts,target,target),"Bit-boundary mismatch "+target);
        }
    }
    static List<ExactLinearProgram.Constraint> paired(int[] w,int target,BigInteger scale){
        var rows=new ArrayList<ExactLinearProgram.Constraint>();var x=new LinkedHashMap<Integer,BigInteger>();var y=new LinkedHashMap<Integer,BigInteger>();int total=0;
        for(int i=0;i<w.length;i++){total+=w[i];rows.add(row(Map.of(i*2,ONE,i*2+1,ONE),ONE));x.put(i*2,BigInteger.valueOf(-w[i]).multiply(scale));y.put(i*2+1,BigInteger.valueOf(-w[i]).multiply(scale));}
        rows.add(row(x,BigInteger.valueOf(-target).multiply(scale)));rows.add(row(y,BigInteger.valueOf(target-total).multiply(scale)));return rows;
    }
    static void complements(){
        var rng=new Random(1257);
        for(int test=0;test<350;test++){
            int n=2+rng.nextInt(11);int[] w=new int[n];int total=0;for(int i=0;i<n;i++){w[i]=1+rng.nextInt(80);total+=w[i];}
            int target=rng.nextInt(total+1);var scale=test%2==0?ONE:ONE.shiftLeft(80).add(ONE);
            var rows=paired(w,target,scale);Collections.shuffle(rows,rng);
            var result=solve(n*2,rows,zeros(n*2),ones(n*2),budget());
            require((result!=null)==exists(w,target,target),"Complement mismatch "+test);
        }
        // An optional pair must keep its zero-use alternative.
        var unused=solve(2,List.of(row(Map.of(0,ONE,1,ONE),ONE)),zeros(2),ones(2),budget());
        require(Arrays.equals(unused,zeros(2)),"Forced an unnecessary source");
        // A further shared-resource constraint cannot disappear during substitution.
        var joint=paired(new int[]{3,5,8},8,ONE);joint.add(row(Map.of(0,ONE,4,ONE),ZERO));
        require(solve(6,joint,zeros(6),ones(6),budget())==null,"Accepted an impossible shared-resource assignment");
        var trial=new ArrayList<ExactLinearProgram.Constraint>();
        for(int i=0;i<3;i++)trial.add(row(Map.of(i*2,ONE,i*2+1,ONE),ONE));
        trial.add(row(Map.of(0,BigInteger.valueOf(-3),2,BigInteger.valueOf(-5),4,BigInteger.valueOf(-8),6,BigInteger.valueOf(8)),ZERO));
        trial.add(row(Map.of(1,BigInteger.valueOf(-3),3,BigInteger.valueOf(-5),5,BigInteger.valueOf(-8),6,BigInteger.valueOf(8)),ZERO));
        var trialLower=zeros(7);var trialUpper=ones(7);trialLower[6]=ONE;trialUpper[6]=BigInteger.TWO;
        require(solve(7,trial,trialLower,trialUpper,budget())!=null,"Could not try minimum finish count");
        require(trialUpper[6].equals(BigInteger.TWO),"Trial escaped into caller bounds");
    }
    static void limits(){
        var terms=Map.of(0,BigInteger.valueOf(400000),1,BigInteger.valueOf(400001));
        var wide=List.of(row(terms,BigInteger.valueOf(400000)),row(negate(terms),BigInteger.valueOf(-400000)));
        var b=budget();require(solve(2,wide,zeros(2),ones(2),b)==null&&b.nodes()<100,"Enumerated oversized range");
        var small=paired(new int[]{3,5,8},8,ONE);
        var memory=new PlanningBudget(0,1_000_000,128,()->false,System::nanoTime);
        require(solve(6,small,zeros(6),ones(6),memory)==null,"Ignored workspace limit");
        var work=new PlanningBudget(0,1000,()->false);require(solve(6,small,zeros(6),ones(6),work)==null,"Ignored optional work allowance");
        var large=ONE.shiftLeft(95).add(BigInteger.valueOf(23));var coefficient=ONE.shiftLeft(71).add(ONE);
        var fixed=Map.of(0,BigInteger.valueOf(63),1,BigInteger.valueOf(65),2,coefficient);
        var target=large.multiply(coefficient).add(BigInteger.valueOf(65));
        var rows=List.of(row(fixed,target),row(negate(fixed),target.negate()));
        var result=solve(3,rows,new BigInteger[]{ZERO,ZERO,large},new BigInteger[]{ONE,ONE,large},budget());
        require(Arrays.equals(result,new BigInteger[]{ZERO,ONE,large}),"Fixed-count BigInteger substitution lost precision");
    }
    public static void main(String[] args){intervals();complements();limits();System.out.println("Count partition: "+checked+" exhaustive/boundary cases passed");}
}
