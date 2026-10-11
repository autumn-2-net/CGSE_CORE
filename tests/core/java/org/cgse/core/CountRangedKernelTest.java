// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Exhaustive signed boxes and independently restored thin integer bands. */
public final class CountRangedKernelTest {
    public static void main(String[] args) {
        exhaustive();
        narrowAffine();
        refusalAndCancellation();
        System.out.println("CountRangedKernelTest: signed/large-offset exhaustive boxes, retained thin bands and lifecycle passed");
    }

    private static void exhaustive() {
        Random random = new Random(121037);
        for (int sample = 0; sample < 2400; sample++) {
            BigInteger shift = sample % 3 == 0 ? BigInteger.ONE.shiftLeft(100).multiply(z(sample % 5 - 2)) : BigInteger.ZERO;
            BigInteger[] low = {shift.add(z(random.nextInt(9) - 6)), shift.negate().add(z(random.nextInt(9) - 6))};
            BigInteger[] high = {low[0].add(z(1 + random.nextInt(7))), low[1].add(z(1 + random.nextInt(7)))};
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            // This bound limits activity width independently of the randomly
            // generated inequalities; every small box is searched completely.
            rows.add(row(1, 0, high[0]));
            for (int r = 0; r < 1 + sample % 7; r++) {
                int a = random.nextInt(15) - 7, b = random.nextInt(15) - 7;
                BigInteger rhs = z(a).multiply(low[0]).add(z(b).multiply(low[1])).add(z(random.nextInt(41) - 20));
                rows.add(row(a, b, rhs));
                if (r == 0 && sample % 4 == 0) rows.add(row(-3 * a, -3 * b, rhs.negate().multiply(z(3))));
            }
            Collections.shuffle(rows, random);
            boolean possible = false;
            for (BigInteger x = low[0]; x.compareTo(high[0]) <= 0; x = x.add(BigInteger.ONE))
                for (BigInteger y = low[1]; y.compareTo(high[1]) <= 0; y = y.add(BigInteger.ONE))
                    possible |= fits(rows, new BigInteger[] {x, y});
            var budget = budget(1_000_000);
            try (var search = new CountRangedKernel(rows, low, high, budget)) {
                while (!search.step()) {}
                var values = search.counts();
                check((values != null) == possible, "Signed box differs from exhaustive oracle: " + sample);
                if (values != null) {
                    check(fits(rows, values), "False ranged witness");
                    for (int i = 0; i < 2; i++) check(values[i].compareTo(low[i]) >= 0 && values[i].compareTo(high[i]) <= 0, "Domain escape");
                }
            }
            check(budget.reservedBytes() == 0, "Ranged workspace leaked");
        }
    }

    private static void narrowAffine() {
        long[][] coefficients = {{495,558,609,866}, {782,773,36,321}, {317,127,726,195}};
        long[] left = {967206347,545260065,425546456};
        BigInteger[] low = {z(0),z(0),z(0),z(0)}, high = {z(1000008),z(1000010),z(1000012),z(1000014)};
        long previous = -1;
        BigInteger[] witness = null;
        for (int order : new int[] {0,17,1009}) for (long quantum : new long[] {100_000, 37}) {
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int r = 0; r < 3; r++) {
                Map<Integer,BigInteger> forward = new LinkedHashMap<>(), reverse = new LinkedHashMap<>();
                for (int i = 0; i < 4; i++) { forward.put(i,z(coefficients[r][i])); reverse.put(i,z(-coefficients[r][i])); }
                rows.add(new ExactLinearProgram.Constraint(forward,z(left[r] + (r == 0 ? 1 : 0))));
                rows.add(new ExactLinearProgram.Constraint(reverse,z(-left[r])));
            }
            if (order != 0) Collections.shuffle(rows,new Random(order));
            var budget = budget(100_000);
            try (var search = new CountAffineLattice(rows,low,high,budget,Math.max(1024,quantum)).retained()) {
                int pauses = 0;
                for (;;) {
                    while (!search.step()) {}
                    if (!search.paused()) break;
                    long work = budget.searchWork(), bytes = budget.reservedBytes();
                    check(search.step() && budget.searchWork() == work && budget.reservedBytes() == bytes, "Paused kernel repeated work");
                    search.resume(quantum);
                    check(++pauses < 3000,"No ranged continuation progress");
                }
                var values = search.counts();
                check(values != null && fits(rows,values),"Thin band exhausted its allowance");
                for (int i = 0; i < 4; i++) check(values[i].compareTo(low[i]) >= 0 && values[i].compareTo(high[i]) <= 0,"Restored coordinate escaped box");
                check(budget.diagnostics().contains("count_ranged_kernel"),"Thin-band path not covered");
                if (quantum == 37) check(previous == budget.searchWork() && Arrays.equals(witness, values),"Continuation changed charged work or witness");
                previous = budget.searchWork(); witness = values;
            }
            check(budget.reservedBytes() == 0,"Retained affine workspace leaked");
        }
    }

    private static void refusalAndCancellation() {
        var rows = List.of(row(1,1,z(10000)), row(-1,-1,z(-1000)));
        BigInteger[] low={z(0),z(0)},high={z(10000),z(10000)};
        for (long memory : new long[] {1,1<<20}) {
            var budget = new PlanningBudget(0,100000,memory,()->false,System::nanoTime);
            try (var search=new CountRangedKernel(rows,low,high,budget)) {
                while(!search.step()) {}
                check(search.counts()==null,"Wide or refused model should remain with its parent");
            }
            check(budget.reservedBytes()==0,"Refused workspace leaked");
        }
        var budget=budget(100000);
        boolean cancelled=false;
        try(var search=new CountRangedKernel(rows,low,high,budget)) {
            check(!search.step(),"Expected unfinished preparation");
            budget.cancel();search.step();
        } catch(CancellationException expected) {cancelled=true;}
        check(cancelled&&budget.reservedBytes()==0,"Cancellation retained band scratch");
    }

    private static ExactLinearProgram.Constraint row(long a,long b,BigInteger rhs) {
        var terms=new LinkedHashMap<Integer,BigInteger>();
        if(a!=0)terms.put(0,z(a)); if(b!=0)terms.put(1,z(b));
        return new ExactLinearProgram.Constraint(terms,rhs);
    }
    private static boolean fits(List<ExactLinearProgram.Constraint> rows,BigInteger[] values) {
        for(var row:rows) {
            BigInteger sum=BigInteger.ZERO;
            for(var term:row.terms().entrySet()) sum=sum.add(term.getValue().multiply(values[term.getKey()]));
            if(sum.compareTo(row.upper())>0)return false;
        }
        return true;
    }
    private static BigInteger z(long value){return BigInteger.valueOf(value);}
    private static PlanningBudget budget(long work){return new PlanningBudget(0,work,32L<<20,()->false,System::nanoTime);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
