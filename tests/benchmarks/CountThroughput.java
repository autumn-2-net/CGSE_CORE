// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.util.*;

/** Fixed-search throughput: model construction and independent validation are outside the timer. */
public final class CountThroughput {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                         BigInteger[] objective) {}
    private record Observation(long nanos, long allocated, long work, String result) {}
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final long THREAD = Thread.currentThread().getId();

    public static void main(String[] args) {
        int cases = Integer.parseInt(args[0]), warmup = Integer.parseInt(args[1]), samples = Integer.parseInt(args[2]);
        if (!THREADS.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unavailable");
        THREADS.setThreadAllocatedMemoryEnabled(true);
        System.out.println("engine,sample,cases,nanos,allocated_bytes,work,results");
        for (String engine : args[3].split(",")) {
            var models = new ArrayList<Model>();
            for (int seed = 0; seed < cases; seed++) models.add(model(engine, seed));
            for (int sample = -warmup; sample < samples; sample++) {
                long nanos = 0, allocated = 0, work = 0;
                var signatures = new StringJoiner(";");
                for (Model model : models) {
                    Observation result = run(engine, model);
                    nanos += result.nanos;
                    allocated += result.allocated;
                    work += result.work;
                    signatures.add(result.work + ":" + result.result);
                }
                if (sample >= 0) System.out.println(engine + "," + sample + "," + cases + "," + nanos + "," +
                        allocated + "," + work + "," + signatures);
            }
        }
    }

    private static Observation run(String engine, Model model) {
        long allocated = THREADS.getThreadAllocatedBytes(THREAD), start = System.nanoTime();
        var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
        BigInteger[] counts = null;
        ExactRational[] point = null;
        String result;
        if (engine.startsWith("lp-")) {
            try (var search = new ExactLinearProgram(model.low.length, model.rows, model.objective, budget)) {
                while (!search.step()) {}
                point = search.point();
                result = search.result().name();
            }
        } else {
            try (var search = new CountLcg(model.rows, model.low, model.high, budget, 200_000)) {
                while (!search.step()) {}
                counts = search.counts();
                result = counts != null ? "WITNESS" : search.infeasible() ? "INFEASIBLE" : "UNKNOWN";
            }
        }
        long nanos = System.nanoTime() - start;
        allocated = THREADS.getThreadAllocatedBytes(THREAD) - allocated;
        if (allocated < 0) throw new AssertionError("Allocation counter unavailable");
        if (result.equals("INFEASIBLE")) throw new AssertionError("Planted feasible model rejected");
        if (budget.reservedBytes() != 0) throw new AssertionError("Leaked solver workspace");
        if (counts != null) {
            for (int i = 0; i < counts.length; i++)
                if (counts[i].compareTo(model.low[i]) < 0 || counts[i].compareTo(model.high[i]) > 0)
                    throw new AssertionError("Invalid domain");
            for (var row : model.rows) {
                BigInteger sum = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(counts[term.getKey()]));
                if (sum.compareTo(row.upper()) > 0) throw new AssertionError("Invalid integer witness");
            }
        }
        if (point != null) for (var row : model.rows) {
            // Independent common-denominator arithmetic, outside measured work.
            BigInteger denominator = BigInteger.ONE;
            for (var value : point) denominator = denominator.multiply(value.denominator());
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                var value = point[term.getKey()];
                sum = sum.add(term.getValue().multiply(value.numerator()).multiply(denominator.divide(value.denominator())));
            }
            if (sum.compareTo(row.upper().multiply(denominator)) > 0) throw new AssertionError("Invalid rational witness");
        }
        // Compare the full canonical witness, not a lossy hash; no commas in CSV.
        result += ":" + (engine.startsWith("lp-") ? Arrays.toString(point) : Arrays.toString(counts)).replace(',', '|');
        return new Observation(nanos, allocated, budget.searchWork(), result);
    }

    private static Model model(String engine, int seed) {
        boolean binary = engine.equals("lcg-binary"), lp = engine.startsWith("lp-");
        int n = binary ? 64 : lp ? 12 : 8;
        var low = new BigInteger[n];
        var high = new BigInteger[n];
        var witness = new BigInteger[n];
        var objective = new BigInteger[n];
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        Random random = new Random(31997 + seed);
        BigInteger offset = engine.equals("lcg-offset") ? BigInteger.ONE.shiftLeft(80).negate() : BigInteger.ZERO;
        int width = binary ? 1 : lp ? 31 : 15;
        for (int i = 0; i < n; i++) {
            low[i] = offset;
            high[i] = offset.add(BigInteger.valueOf(width));
            witness[i] = offset.add(BigInteger.valueOf(random.nextInt(width + 1)));
            objective[i] = BigInteger.valueOf(random.nextInt(15) - 7);
        }
        if (binary) {
            for (int r = 0; r < 275; r++) {
                var terms = new TreeMap<Integer, BigInteger>();
                int positives = 0;
                boolean satisfied = false;
                while (terms.size() < 3) {
                    int id = random.nextInt(n);
                    if (terms.containsKey(id)) continue;
                    boolean positive = random.nextBoolean();
                    if (positive) positives++;
                    terms.put(id, positive ? BigInteger.ONE : BigInteger.ONE.negate());
                    satisfied |= positive != witness[id].equals(BigInteger.ONE);
                }
                if (!satisfied) { r--; continue; }
                rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(positives - 1)));
            }
        } else {
            for (int r = 0; r < (lp ? 18 : 3); r++) {
                var terms = new LinkedHashMap<Integer, BigInteger>();
                var reverse = new LinkedHashMap<Integer, BigInteger>();
                BigInteger rhs = BigInteger.ZERO;
                for (int i = 0; i < n; i++) {
                    BigInteger a = BigInteger.valueOf(random.nextInt(31) - 15);
                    if (engine.equals("lp-unit")) a = BigInteger.valueOf(a.signum());
                    if (a.signum() == 0) continue;
                    terms.put(i, a);
                    reverse.put(i, a.negate());
                    rhs = rhs.add(a.multiply(witness[i]));
                }
                rows.add(new ExactLinearProgram.Constraint(terms, rhs.add(BigInteger.valueOf(lp ? random.nextInt(12) : 0))));
                if (!lp) rows.add(new ExactLinearProgram.Constraint(reverse, rhs.negate()));
            }
            if (lp) for (int i = 0; i < n; i++) rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), high[i]));
        }
        Collections.shuffle(rows, new Random(271L * seed + 5));
        return new Model(rows, low, high, objective);
    }
}
