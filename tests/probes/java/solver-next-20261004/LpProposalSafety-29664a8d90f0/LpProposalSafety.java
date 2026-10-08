package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
public class LpProposalSafety {
    static int checks,proposed,accepted,optimal;
    static BigInteger b(long n){return BigInteger.valueOf(n);}
    static void check(boolean p,String s){checks++;if(!p)throw new AssertionError(s);}
    static PlanningBudget budget(long work,long memory){return new PlanningBudget(0,work,memory,()->false,System::nanoTime);}
    static void randomized(){Random random=new Random(518741);
        for(int sample=0;sample<1200;sample++){
            int n=2+random.nextInt(13);BigInteger[] cost=new BigInteger[n];ExactRational[] target=new ExactRational[n];var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int i=0;i<n;i++){BigInteger low=b(random.nextInt(5)),highThird=low.multiply(b(3)).add(b(1+random.nextInt(15)));int sign=random.nextBoolean()?1:-1;cost[i]=b(sign*(1+random.nextInt(5))).shiftLeft(sample%3==0?100:0);target[i]=sign>0?new ExactRational(highThird,b(3)):ExactRational.of(low);BigInteger scale=BigInteger.ONE.shiftLeft(49+random.nextInt(700));rows.add(new ExactLinearProgram.Constraint(Map.of(i,scale.multiply(b(3))),scale.multiply(highThird)));rows.add(new ExactLinearProgram.Constraint(Map.of(i,scale.negate()),scale.multiply(low).negate()));}
            for(int k=0;k<n;k++){BigInteger scale=b(3).shiftLeft(49+random.nextInt(700));var terms=new LinkedHashMap<Integer,BigInteger>();var rhs=ExactRational.ZERO;for(int i=0;i<n;i++)if(random.nextBoolean()){int coefficient=random.nextInt(11)-5;if(coefficient!=0){var value=b(coefficient).multiply(scale);terms.put(i,value);rhs=rhs.add(target[i].multiply(ExactRational.of(value)));}}check(rhs.integral(),"integral constructed rhs");rows.add(new ExactLinearProgram.Constraint(terms,rhs.numerator().add(scale.multiply(b(random.nextInt(3))))));}
            Collections.shuffle(rows,random);var budget=budget(20000000,64L<<20);
            int[] basis=CountLpProposal.propose(n,rows,cost,budget,262144);
            if(basis!=null){proposed++;check(basis.length==rows.size(),"basis dimension");BitSet seen=new BitSet();for(int id:basis){check(id>=0&&id<n+rows.size()&&!seen.get(id),"basis columns");seen.set(id);}try(var exact=new ExactRevisedProgram(n,rows,cost,budget)){if(exact.reconstruct(basis)){accepted++;while(!exact.step()){}if(exact.result()==ExactLinearProgram.Result.OPTIMAL){optimal++;var value=exact.point();var actual=ExactRational.ZERO;var expected=ExactRational.ZERO;for(int i=0;i<n;i++){check(value[i].signum()>=0,"nonnegative");actual=actual.add(value[i].multiply(ExactRational.of(cost[i])));expected=expected.add(target[i].multiply(ExactRational.of(cost[i])));}check(actual.equals(expected),"known endpoint optimum");for(var row:rows){var sum=ExactRational.ZERO;for(var term:row.terms().entrySet())sum=sum.add(value[term.getKey()].multiply(ExactRational.of(term.getValue())));check(sum.compareTo(ExactRational.of(row.upper()))<=0,"original exact row");}}}}
            }
            check(budget.reservedBytes()==0,"random memory leak");
        }
    }
    static void failures(){int n=64;BigInteger scale=BigInteger.ONE.shiftLeft(100);var rows=new ArrayList<ExactLinearProgram.Constraint>();BigInteger[] cost=new BigInteger[n];Arrays.fill(cost,scale);for(int i=0;i<n;i++){rows.add(new ExactLinearProgram.Constraint(Map.of(i,scale),scale.multiply(b(10))));rows.add(new ExactLinearProgram.Constraint(Map.of(i,scale.negate()),scale.negate()));}
        for(int threshold:new int[]{1,2,10,30,100,300,1000,3000}){AtomicInteger ticks=new AtomicInteger();var budget=new PlanningBudget(0,20000000,64L<<20,()->ticks.incrementAndGet()>=threshold,System::nanoTime);try{CountLpProposal.propose(n,rows,cost,budget,262144);}catch(java.util.concurrent.CancellationException expected){}finally{check(budget.reservedBytes()==0,"cancel leak "+threshold);}}
        for(long cap:new long[]{32768,32769,65536,100000,1000000}){var budget=budget(cap,64L<<20);try{CountLpProposal.propose(n,rows,cost,budget,Long.MAX_VALUE);}catch(PlanningBudget.Exhausted expected){}finally{check(budget.reservedBytes()==0,"work leak");check(budget.nodes()<cap/4+200,"proposal local limit bounded "+cap+" work="+budget.nodes());}}
        for(long cap:new long[]{1,1024,8192,65536,100000,1L<<20}){var budget=budget(1000000,cap);CountLpProposal.propose(n,rows,cost,budget,262144);check(budget.reservedBytes()==0,"memory denial leak");}
        for(long allowance:new long[]{0,1,2,15,16,17,100,1000}){var budget=budget(1000000,1L<<20);CountLpProposal.propose(n,rows,cost,budget,allowance);check(budget.reservedBytes()==0,"local memory leak");check(budget.nodes()<=allowance+32,"local work overshoot "+allowance+" work="+budget.nodes());}
        // Floating phase one rounds away a 2^-100 deficit; only exact rejection is acceptable.
        rows=new ArrayList<>(List.of(new ExactLinearProgram.Constraint(Map.of(0,scale),scale),new ExactLinearProgram.Constraint(Map.of(0,scale.negate()),scale.negate().subtract(BigInteger.ONE)),new ExactLinearProgram.Constraint(Map.of(1,BigInteger.ONE),BigInteger.ONE)));
        var budget=budget(1000000,1L<<20);int[] basis=CountLpProposal.propose(2,rows,new BigInteger[]{BigInteger.ONE,BigInteger.ZERO},budget,262144);check(basis!=null,"adversarial proposal should exercise exact rejection");try(var exact=new ExactRevisedProgram(2,rows,new BigInteger[]{BigInteger.ONE,BigInteger.ZERO},budget)){check(!exact.reconstruct(basis),"rounded infeasible basis must be rejected");}check(budget.reservedBytes()==0,"rejected factor memory");
    }
    public static void main(String[] args){randomized();failures();System.out.println("PASS checks="+checks+" proposed="+proposed+" reconstructed="+accepted+" optimal="+optimal);}
}
