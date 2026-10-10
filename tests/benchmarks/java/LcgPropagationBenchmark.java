// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

/** Warmed, sequential original-model LCG/PB slices; allocation is not retained heap or RSS. */
public final class LcgPropagationBenchmark {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}

    public static void main(String[] args) throws Exception {
        var models = new ArrayList<Model>();
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[0]))))) {
            for (int left = input.readInt(); left > 0; left--) {
                GraphFixtureRegression.string(input);
                GraphFixtureRegression.string(input);
                int size = input.readInt();
                var low = new BigInteger[size];
                var high = new BigInteger[size];
                for (int i = 0; i < size; i++) { low[i] = CountModelRegression.integer(input); high[i] = CountModelRegression.integer(input); }
                var rows = new ArrayList<ExactLinearProgram.Constraint>();
                for (int count = input.readInt(); count > 0; count--) {
                    BigInteger rhs = CountModelRegression.integer(input);
                    var terms = new LinkedHashMap<Integer, BigInteger>();
                    for (int entries = input.readInt(); entries > 0; entries--)
                        terms.put(input.readInt(), CountModelRegression.integer(input));
                    rows.add(new ExactLinearProgram.Constraint(terms, rhs));
                }
                models.add(new Model(rows, low, high));
            }
            if (input.read() != -1) throw new AssertionError("Trailing model bytes");
        }
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unsupported");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        int warmup = Integer.parseInt(args[1]), samples = Integer.parseInt(args[2]);
        String engine = args.length > 3 ? args[3] : "lcg";
        if (!Set.of("lcg", "pb").contains(engine)) throw new IllegalArgumentException("Unknown benchmark engine: " + engine);
        for (int iteration = -warmup; iteration < samples; iteration++) {
            long nanos = 0, allocated = 0, work = 0, signature = 1;
            for (Model model : models) {
                var budget = new PlanningBudget(0, 8000000, 128L << 20, () -> false, System::nanoTime);
                BigInteger[] point;
                boolean impossible;
                long before = bean.getThreadAllocatedBytes(thread), started = System.nanoTime();
                if (engine.equals("pb")) {
                    try (var search = new CountCdcl(model.rows, model.low, model.high, budget, 262144)) {
                        while (!search.step()) {}
                        point = search.counts();
                        impossible = search.infeasible();
                    }
                } else {
                    try (var search = new CountLcg(model.rows, model.low, model.high, budget, 262144)) {
                        while (!search.step()) {}
                        point = search.counts();
                        impossible = search.infeasible();
                    }
                }
                nanos += System.nanoTime() - started;
                allocated += bean.getThreadAllocatedBytes(thread) - before;
                require(budget.reservedBytes() == 0, "unreleased budget memory");
                work += budget.nodes();
                signature = signature * 31 + budget.nodes();
                signature = signature * 31 + Arrays.hashCode(point);
                signature = signature * 31 + (impossible ? 1 : 0);
                if (point != null) {
                    for (int i = 0; i < point.length; i++) require(point[i].compareTo(model.low[i]) >= 0 &&
                            (model.high[i] == null || point[i].compareTo(model.high[i]) <= 0), "domain violation");
                    for (var row : model.rows) {
                        BigInteger value = BigInteger.ZERO;
                        for (var term : row.terms().entrySet()) value = value.add(term.getValue().multiply(point[term.getKey()]));
                        require(value.compareTo(row.upper()) <= 0, "row violation");
                    }
                }
            }
            if (iteration >= 0) System.out.println(iteration + "\t" + models.size() + "\t" + nanos + "\t" + allocated + "\t" + work + "\t" + signature);
        }
    }

    private static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
