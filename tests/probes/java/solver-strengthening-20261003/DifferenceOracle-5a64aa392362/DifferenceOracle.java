package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

public final class DifferenceOracle {
    static BigInteger b(long n){return BigInteger.valueOf(n);}
    static void edge(BigInteger[][] d,int from,int to,BigInteger weight){d[from][to]=d[from][to]==null?weight:d[from][to].min(weight);}
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,64L<<20,()->false,System::nanoTime);}
    public static void main(String[] args){
        Random rng=new Random(0xD1FF);int contradictions=0;
        for(int sample=0;sample<2500;sample++){
            int n=2+rng.nextInt(10);var low=new BigInteger[n];var high=new BigInteger[n];BigInteger[][] d=new BigInteger[n+1][n+1];
            for(int i=0;i<=n;i++)d[i][i]=b(0);
            for(int i=0;i<n;i++){
                low[i]=b(rng.nextInt(4));high[i]=sample%5==0?null:low[i].add(b(rng.nextInt(20)));
                edge(d,i,n,low[i].negate());if(high[i]!=null)edge(d,n,i,high[i]);
            }
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int r=0;r<2+rng.nextInt(30);r++){
                int a=rng.nextInt(n),other=(a+1+rng.nextInt(n-1))%n;BigInteger c=b(rng.nextInt(21)-10),scale=b(1+rng.nextInt(16));
                if(sample%17==0)scale=scale.shiftLeft(120);
                rows.add(new ExactLinearProgram.Constraint(Map.of(a,scale,other,scale.negate()),c.multiply(scale)));edge(d,other,a,c);
            }
            for(int k=0;k<=n;k++)for(int i=0;i<=n;i++)for(int j=0;j<=n;j++)if(d[i][k]!=null&&d[k][j]!=null)edge(d,i,j,d[i][k].add(d[k][j]));
            boolean impossible=false;for(int i=0;i<=n;i++)impossible|=d[i][i].signum()<0;
            var budget=budget();var proof=CountDifference.contradiction(rows,low,high,budget,8192);
            if((proof!=null)!=impossible)throw new AssertionError("closure mismatch "+sample);
            if(proof!=null){contradictions++;if(CountProof.verify(proof,100000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("invalid cycle proof");}
            if(budget.reservedBytes()!=0)throw new AssertionError("leak");
        }
        var huge=b(1).shiftLeft(120);
        var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,b(1),1,b(-1)),b(-1)),new ExactLinearProgram.Constraint(Map.of(0,b(-1),1,b(1)),b(0)));
        for(BigInteger cap:new BigInteger[]{huge,null}){
            var budget=budget();try(var solver=new CountLcg(rows,new BigInteger[]{b(0),b(0)},new BigInteger[]{cap,cap},budget,1024,true)){
                while(!solver.step()){}
                if(!solver.infeasible()||solver.paused()||CountProof.verify(solver.certificate(),10000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("unit-step cycle was not closed");
                if(budget.nodes()>300)throw new AssertionError("cycle cost depends on quantity "+budget.nodes());
                System.out.println("wide/unbounded negative-cycle work="+budget.nodes());
            }
            if(budget.reservedBytes()!=0)throw new AssertionError("LCG cycle leak");
        }
        for(int stop=1;stop<=300;stop++){
            int[] at={0};int limit=stop;var budget=new PlanningBudget(0,20_000_000,64L<<20,()->++at[0]>=limit,System::nanoTime);
            try(var solver=new CountLcg(rows,new BigInteger[]{b(0),b(0)},new BigInteger[]{huge,huge},budget,1024,true)){while(!solver.step()){} }
            catch(java.util.concurrent.CancellationException expected){}
            if(budget.reservedBytes()!=0)throw new AssertionError("cancel leak "+stop);
        }
        for(long memory:new long[]{1,2048,8192,16384}){
            var budget=new PlanningBudget(0,20_000_000,memory,()->false,System::nanoTime);
            try(var solver=new CountLcg(rows,new BigInteger[]{b(0),b(0)},new BigInteger[]{huge,huge},budget,1024,true)){while(!solver.step()){}if(solver.infeasible()&&CountProof.verify(solver.certificate(),10000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("memory proof");}
            if(budget.reservedBytes()!=0)throw new AssertionError("memory leak");
        }
        System.out.println("PASS difference graphs=2500 checked_contradictions="+contradictions+" cancellation=300 memory_limits=4");
    }
}
