// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Same-cap retained matching; input construction and independent validation are outside the timer. */
public final class SubsetMatchingThroughput {
    private record Model(String id, List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper) {}
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        List<Model> models = new ArrayList<>();
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[0]))))) {
            for (int remaining = input.readInt(); remaining > 0; remaining--) {
                String id = string(input), expected = string(input);
                if (!expected.equals("SAT")) throw new IllegalArgumentException("Expected known SAT inputs");
                int n = input.readInt();
                BigInteger[] lower = new BigInteger[n], upper = new BigInteger[n];
                for (int i = 0; i < n; i++) { lower[i] = new BigInteger(string(input)); upper[i] = new BigInteger(string(input)); }
                List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
                for (int r = input.readInt(); r > 0; r--) {
                    BigInteger bound = new BigInteger(string(input));
                    Map<Integer, BigInteger> terms = new LinkedHashMap<>();
                    for (int j = input.readInt(); j > 0; j--) terms.put(input.readInt(), new BigInteger(string(input)));
                    rows.add(new ExactLinearProgram.Constraint(terms, bound));
                }
                models.add(new Model(id, rows, lower, upper));
            }
            if (input.read() != -1) throw new AssertionError("Trailing input bytes");
        }
        int warmup = Integer.parseInt(args[1]), repetitions = Integer.parseInt(args[2]);
        long cap = Long.parseLong(args[3]);
        if (!THREADS.isThreadAllocatedMemorySupported()) throw new AssertionError("Allocation counter unavailable");
        THREADS.setThreadAllocatedMemoryEnabled(true);
        System.out.println("sample,case,nanos,allocated_bytes,work,peak_reserved_bytes,witness");
        for (int sample = -warmup; sample < repetitions; sample++) for (Model model : models) {
            var budget = new PlanningBudget(0, cap, 256L << 20, () -> false, System::nanoTime);
            long allocated = THREADS.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
            BigInteger[] counts;
            try (var search = new CountMeetInMiddle(model.rows, model.lower, model.upper, budget, 262144).retained()) {
                for (;;) {
                    long before = budget.searchWork();
                    while (!search.step()) {}
                    if (!search.paused() || search.counts() != null || search.infeasible()) break;
                    if (budget.searchWork() <= before) throw new AssertionError("Paused without progress");
                    search.resume(Math.min(262144, budget.remainingWork()));
                }
                counts = search.counts();
            }
            long elapsed = System.nanoTime() - start;
            allocated = THREADS.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated;
            if (counts == null || allocated < 0 || budget.reservedBytes() != 0) throw new AssertionError("Missing witness/accounting");
            for (int i = 0; i < counts.length; i++)
                if (counts[i].compareTo(model.lower[i]) < 0 || counts[i].compareTo(model.upper[i]) > 0)
                    throw new AssertionError("Invalid original bound");
            for (var row : model.rows) {
                BigInteger sum = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(counts[term.getKey()]));
                if (sum.compareTo(row.upper()) > 0) throw new AssertionError("Invalid original row");
            }
            if (sample >= 0) System.out.println(sample + "," + model.id + "," + elapsed + "," + allocated + "," +
                    budget.searchWork() + "," + budget.peakBytes() + "," + Arrays.toString(counts).replace(',', '|'));
        }
    }

    private static String string(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > 1_000_000) throw new IllegalArgumentException("Invalid string size");
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new EOFException();
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
