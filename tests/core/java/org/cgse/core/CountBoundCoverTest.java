// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Resource covers must include real variable bounds without inventing singleton capacities. */
public final class CountBoundCoverTest {
    public static void main(String[] args) {
        Random random=new Random(121045);
        for(int sample=0;sample<400;sample++) {
            int n=3+sample%5, radix=sample%3==0?3:2;
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int r=0;r<2+sample%7;r++) {
                Map<Integer,BigInteger> terms=new LinkedHashMap<>();
                for(int i=0;i<n;i++)if(random.nextBoolean())terms.put(i,BigInteger.ONE);
                rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.ONE));
                if(r==0&&sample%4==0)rows.add(rows.get(rows.size()-1));
            }
            Map<Integer,BigInteger> need=new LinkedHashMap<>();
            for(int i=0;i<n;i++)if(i<2||random.nextBoolean())need.put(i,BigInteger.ONE.negate());
            rows.add(new ExactLinearProgram.Constraint(need,BigInteger.valueOf(-random.nextInt(n+1))));
            BigInteger[] low=new BigInteger[n],high=new BigInteger[n];
            Arrays.fill(low,BigInteger.ZERO);Arrays.fill(high,BigInteger.valueOf(radix-1));
            var budget=new PlanningBudget(0,1_000_000,32L<<20,()->false,System::nanoTime);
            try(var reduction=new CountReduction(rows,low,high,budget)) {
                while(!reduction.step()){}
                Set<List<BigInteger>> original=new HashSet<>(),restored=new HashSet<>();
                enumerate(rows,low,high,0,new BigInteger[n],values->original.add(List.of(values.clone())));
                enumerate(reduction.rows(),reduction.lower(),reduction.upper(),0,new BigInteger[reduction.variables()],
                        values->restored.add(List.of(reduction.expand(values))));
                check(original.equals(restored),"Capacity padding changed feasible assignments: "+sample);
            }
            check(budget.reservedBytes()==0,"Cover reduction leaked");
        }
        for(int cap:new int[]{1,2}) {
            var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,z(1),1,z(1)),z(1)),
                    new ExactLinearProgram.Constraint(Map.of(0,z(-1),1,z(-1),2,z(-1)),z(-2)));
            var budget=new PlanningBudget(0,1_000_000,32L<<20,()->false,System::nanoTime);
            try(var reduction=new CountReduction(rows,new BigInteger[]{z(0),z(0),z(0)},new BigInteger[]{z(1),z(1),z(cap)},budget)) {
                while(!reduction.step()){}
                check(budget.diagnostics().contains("bound_rows=1")== (cap==1),"Invented a unit bound or missed the singleton capacity");
            }
            check(budget.reservedBytes()==0,"Singleton capacity reservation leaked");
        }
        System.out.println("CountBoundCoverTest: 400 complete finite assignment sets and singleton/non-unit boundaries passed");
    }

    private static void enumerate(List<ExactLinearProgram.Constraint> rows,BigInteger[] low,BigInteger[] high,int i,
                                  BigInteger[] values,java.util.function.Consumer<BigInteger[]> consume) {
        if(i==values.length) {
            for(var row:rows) {
                BigInteger sum=BigInteger.ZERO;
                for(var term:row.terms().entrySet())sum=sum.add(term.getValue().multiply(values[term.getKey()]));
                if(sum.compareTo(row.upper())>0)return;
            }
            consume.accept(values);return;
        }
        check(high[i]!=null,"Finite source box lost its upper bound");
        for(BigInteger v=low[i];v.compareTo(high[i])<=0;v=v.add(BigInteger.ONE)) {
            values[i]=v;enumerate(rows,low,high,i+1,values,consume);
        }
    }
    private static BigInteger z(long value){return BigInteger.valueOf(value);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
