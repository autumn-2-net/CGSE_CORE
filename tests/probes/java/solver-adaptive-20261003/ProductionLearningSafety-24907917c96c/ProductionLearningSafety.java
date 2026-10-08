package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
public class ProductionLearningSafety {
    public static void main(String[]args) {
        Random random=new Random(910031);int models=0,sat=0,unsat=0,proofs=0;long assignments=0;int derived=0;
        for(int test=0;test<2200;test++) {
            int n=3+random.nextInt(8);var low=new BigInteger[n];var high=new BigInteger[n];Arrays.fill(low,BigInteger.ZERO);Arrays.fill(high,BigInteger.ONE);
            for(int i=0;i<n;i++)if(random.nextInt(12)==0){low[i]=high[i]=random.nextBoolean()?BigInteger.ONE:BigInteger.ZERO;}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();int planted=random.nextInt(1<<n);
            BigInteger scale=test%17==0?BigInteger.TEN.pow(35):BigInteger.ONE;
            for(int row=0,count=n+random.nextInt(10);row<count;row++) {
                var terms=new LinkedHashMap<Integer,BigInteger>();long upper=0;
                for(int i=0;i<n;i++)if(random.nextInt(4)!=0){int a=random.nextInt(23)-11;if(a!=0){terms.put(i,BigInteger.valueOf(a).multiply(scale));upper+=((planted>>i)&1)*a;}}
                upper+=random.nextInt(9)-4;rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(upper).multiply(scale)));
            }
            if(test>=2000) {
                n=3+2*((test-2000)%4); low=new BigInteger[n];high=new BigInteger[n];Arrays.fill(low,BigInteger.ZERO);Arrays.fill(high,BigInteger.ONE);rows.clear();
                for(int i=0;i<n;i++) {
                    var q=BigInteger.TEN.pow(((test+i)%5)*35);rows.add(new ExactLinearProgram.Constraint(Map.of(i,q,(i+1)%n,q),q));
                }
                var t=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<n;i++)t.put(i,BigInteger.ONE.negate());
                rows.add(new ExactLinearProgram.Constraint(t,BigInteger.valueOf(-(n+1)/2)));
            }
            boolean feasible=false;
            for(int mask=0;mask<(1<<n);mask++) {
                assignments++;boolean valid=true;
                for(int i=0;i<n;i++){int x=(mask>>i)&1;if(x<low[i].intValue()||x>high[i].intValue())valid=false;}
                if(!valid)continue;
                for(var row:rows){BigInteger sum=BigInteger.ZERO;for(var t:row.terms().entrySet())if(((mask>>t.getKey())&1)!=0)sum=sum.add(t.getValue());if(sum.compareTo(row.upper())>0){valid=false;break;}}
                feasible|=valid;
            }
            var budget=new PlanningBudget(0,1_000_000,64L<<20,()->false,System::nanoTime);
            try(var search=new CountLcg(rows,low,high,budget,1_000_000,true).learnedRelaxation()) {
                do{while(!search.step()){}if(!search.paused())break;search.resume(32768);}while(budget.remainingWork()>8192);
                var x=search.counts();
                if(search.certificate()!=null) {
                    var proof=search.certificate();derived+=proof.derived().size();
                    if(proof.axioms().size()!=rows.size()+2*n)throw new AssertionError("learned rows disguised as axioms "+test);
                    if(!proof.derived().isEmpty()) {
                        var first=proof.derived().get(0);var corrupted=new CountProof.Combination(first.parents(),first.divisor(),new CountProof.Row(first.consequence().terms(),first.consequence().upper().subtract(BigInteger.ONE)));
                        var steps=new ArrayList<>(proof.derived());steps.set(0,corrupted);
                        var invalid=new CountProof.Certificate(proof.scope(),proof.variables(),proof.axioms(),proof.forbidden(),proof.farkas(),proof.closed(),steps);
                        if(CountProof.verify(invalid,2_000_000)!=CountProof.Verdict.INVALID)throw new AssertionError("tampered LP derivation accepted");
                    }
                }
                if(x!=null){CountBenchmark.verify(rows,low,high,x);sat++;}
                else if(search.infeasible()){
                    if(CountProof.verify(search.certificate(),2_000_000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("invalid final LCG proof test="+test);
                    unsat++;proofs++;
                }else throw new AssertionError("unexpected tiny UNKNOWN "+test);
                if(feasible!=(x!=null))throw new AssertionError("oracle mismatch "+test+" "+feasible+" "+search.infeasible());
            }
            if(budget.reservedBytes()!=0)throw new AssertionError("memory leak "+budget.reservedBytes());models++;
        }
        System.out.println("SAFETY models="+models+" assignments="+assignments+" sat="+sat+" unsat="+unsat+" final_proofs="+proofs+" LP_derivations="+derived+" leaks=0");
    }
}
