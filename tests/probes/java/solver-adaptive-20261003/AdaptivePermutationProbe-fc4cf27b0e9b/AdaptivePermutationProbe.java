package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Independent truth and metamorphic checks, deliberately outside the shipped sources. */
public class AdaptivePermutationProbe {
    static BigInteger b(long value) { return BigInteger.valueOf(value); }
    static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, int mask) {
        for (int i = 0; i < low.length; i++) {
            int value = (mask >>> i) & 1;
            if (value < low[i].intValue() || value > high[i].intValue()) return false;
        }
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) if (((mask >>> term.getKey()) & 1) != 0) sum = sum.add(term.getValue());
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    public static void main(String[] args) {
        var random = new Random(10320269317L);
        int cases = 0, sat = 0, unsat = 0, proofs = 0;
        long assignments = 0, resumes = 0, work = 0;
        for (int model = 0; model < 500; model++) {
            int n = 6 + random.nextInt(6), planted = random.nextInt(1 << n);
            var low = new BigInteger[n]; var high = new BigInteger[n];
            Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
            for (int i = 0; i < n; i++) if (random.nextInt(20) == 0) low[i] = high[i] = b((planted >>> i) & 1);
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int row = 0; row < n + random.nextInt(n); row++) {
                var terms = new LinkedHashMap<Integer, BigInteger>(); long upper = 0;
                for (int i = 0; i < n; i++) {
                    int coefficient = random.nextInt(101) - 50;
                    if (coefficient != 0) terms.put(i, b(coefficient));
                    upper += coefficient * ((planted >>> i) & 1);
                }
                upper += model % 2 == 0 ? random.nextInt(12) : random.nextInt(15) - 9;
                rows.add(new ExactLinearProgram.Constraint(terms, b(upper)));
            }
            boolean feasible = false;
            for (int mask = 0; mask < (1 << n); mask++) { assignments++; feasible |= valid(rows, low, high, mask); }
            for (int variation = 0; variation < 4; variation++) {
                var order = new ArrayList<Integer>(); for (int i = 0; i < n; i++) order.add(i);
                if (variation != 0) Collections.shuffle(order, random);
                var changedLow = new BigInteger[n]; var changedHigh = new BigInteger[n];
                for (int i = 0; i < n; i++) { changedLow[order.get(i)] = low[i]; changedHigh[order.get(i)] = high[i]; }
                var changed = new ArrayList<ExactLinearProgram.Constraint>();
                for (var row : rows) {
                    var terms = new TreeMap<Integer, BigInteger>();
                    BigInteger scale = variation == 3 ? BigInteger.TEN.pow(random.nextInt(5) * 25) : BigInteger.ONE;
                    for (var term : row.terms().entrySet()) terms.put(order.get(term.getKey()), term.getValue().multiply(scale));
                    changed.add(new ExactLinearProgram.Constraint(terms, row.upper().multiply(scale)));
                }
                if (variation != 0) Collections.shuffle(changed, random);
                var budget = new PlanningBudget(0, 2_000_000, 64L << 20, () -> false, () -> 0L);
                try (var search = new CountLcg(changed, changedLow, changedHigh, budget, 1024, true).learnedRelaxation()) {
                    do {
                        while (!search.step()) {}
                        if (!search.paused()) break;
                        search.resume(variation == 1 ? 17 : variation == 2 ? 127 : 4096); resumes++;
                    } while (true);
                    if (search.counts() != null) {
                        CountBenchmark.verify(changed, changedLow, changedHigh, search.counts());
                        if (!feasible) throw new AssertionError("false SAT: " + model + "/" + variation);
                        sat++;
                    } else if (search.infeasible()) {
                        if (feasible) throw new AssertionError("false UNSAT: " + model + "/" + variation);
                        if (CountProof.verify(search.certificate(), 4_000_000) != CountProof.Verdict.VERIFIED)
                            throw new AssertionError("invalid certificate: " + model + "/" + variation);
                        unsat++; proofs++;
                    } else throw new AssertionError("unexpected UNKNOWN: " + model + "/" + variation);
                } finally {
                    if (budget.reservedBytes() != 0) throw new AssertionError("retained leak: " + budget.reservedBytes());
                }
                work += budget.nodes(); cases++;
            }
        }
        System.out.println("PERMUTATION models=500 cases=" + cases + " enumerated=" + assignments + " SAT=" + sat + " UNSAT=" + unsat + " proofs=" + proofs + " resumes=" + resumes + " work=" + work + " errors=0 leaks=0");
    }
}
