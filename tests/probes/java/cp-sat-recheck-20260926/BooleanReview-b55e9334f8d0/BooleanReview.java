package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
public final class BooleanReview {
    public static void main(String[] args)throws Exception {
        Random r=new Random(12926);int solved=0,unsat=0;long learned=0;
        for(int test=0;test<2400;test++) {
            int n=3+r.nextInt(8),m=test%4==0?4*n:1+r.nextInt(24);
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            BigInteger scale=test%3==0?BigInteger.valueOf(Long.MAX_VALUE).pow(2):BigInteger.ONE;
            for(int j=0;j<m;j++) {
                Map<Integer,BigInteger> terms=new LinkedHashMap<>();
                if(test%4==0){int ones=0;while(terms.size()<3){int id=r.nextInt(n);if(terms.containsKey(id))continue;boolean positive=r.nextBoolean();terms.put(id,positive?scale:scale.negate());if(positive)ones++;}rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(ones-1L).multiply(scale)));continue;}
                for(int i=0;i<n;i++)if(r.nextBoolean()){int c=r.nextInt(13)-6;if(c!=0)terms.put(i,BigInteger.valueOf(c).multiply(scale));}
                rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(r.nextInt(17)-8).multiply(scale).add(BigInteger.valueOf(test%2))));
            }
            boolean possible=false;
            for(int bits=0;bits<(1<<n);bits++) {
                boolean ok=true;
                for(var row:rows){BigInteger sum=BigInteger.ZERO;for(var e:row.terms().entrySet())if((bits&(1<<e.getKey()))!=0)sum=sum.add(e.getValue());if(sum.compareTo(row.upper())>0){ok=false;break;}}
                if(ok){possible=true;break;}
            }
            var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,BigInteger.ZERO);Arrays.fill(hi,BigInteger.ONE);
            var b=new PlanningBudget(10000,2_000_000,32L<<20,()->false,System::nanoTime);
            try(var solver=new CountBoolean(rows,lo,hi,b)){
                while(!solver.step()){}
                var counts=solver.counts();
                var field=CountBoolean.class.getDeclaredField("rows");field.setAccessible(true);var clauses=(List<?>)field.get(solver);
                for(int c=rows.size();c<clauses.size();c++){
                    Object clause=clauses.get(c);var type=clause.getClass();var vm=type.getDeclaredMethod("variables");var cm=type.getDeclaredMethod("coefficients");var um=type.getDeclaredMethod("upper");vm.setAccessible(true);cm.setAccessible(true);um.setAccessible(true);
                    int[] vars=(int[])vm.invoke(clause);BigInteger[] coefficients=(BigInteger[])cm.invoke(clause);BigInteger bound=(BigInteger)um.invoke(clause);
                    for(int bits=0;bits<(1<<n);bits++){
                        boolean valid=true;
                        for(var row:rows){BigInteger sum=BigInteger.ZERO;for(var e:row.terms().entrySet())if((bits&(1<<e.getKey()))!=0)sum=sum.add(e.getValue());if(sum.compareTo(row.upper())>0){valid=false;break;}}
                        if(valid){BigInteger sum=BigInteger.ZERO;for(int v=0;v<vars.length;v++)if((bits&(1<<vars[v]))!=0)sum=sum.add(coefficients[v]);if(sum.compareTo(bound)>0)throw new AssertionError("unsound learned cut test="+test);}
                    }
                    learned++;
                }
                if(possible!=(counts!=null))throw new AssertionError("test="+test+" possible="+possible+" trace="+b.diagnostics());
                if(counts!=null){for(var row:rows){BigInteger s=BigInteger.ZERO;for(var e:row.terms().entrySet())s=s.add(e.getValue().multiply(counts[e.getKey()]));if(s.compareTo(row.upper())>0)throw new AssertionError("bad witness");}solved++;}else unsat++;
            }
        }
        System.out.println("PASS exhaustive weighted binary cases=2400 feasible="+solved+" unsat="+unsat+" exact coefficients up to long^2; independently checked learned cuts="+learned);
    }
}
