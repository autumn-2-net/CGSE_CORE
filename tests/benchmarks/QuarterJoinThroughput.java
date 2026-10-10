// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.util.*;

/** Exact original-mask validation; no test generator or checker in the measured interval. */
public final class QuarterJoinThroughput {
    private record Model(String name, long[] coefficients, long target) {}
    private record Observation(String result, long nanos, long allocated, long work, long peak, long mask) {}

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) {
        int cases = Integer.parseInt(args[0]), warmup = Integer.parseInt(args[1]), samples = Integer.parseInt(args[2]);
        long cap = Long.parseLong(args[3]);
        if (!THREADS.isThreadAllocatedMemorySupported()) throw new AssertionError("Allocation counter unavailable");
        THREADS.setThreadAllocatedMemoryEnabled(true);
        String family = args.length > 4 ? args[4] : "repeated";
        if (!Set.of("repeated", "distinct", "all").contains(family)) throw new IllegalArgumentException("Invalid family");
        List<Model> models = new ArrayList<>();
        if (!family.equals("distinct")) models.addAll(models(cases, false));
        if (!family.equals("repeated")) models.addAll(models(cases, true));
        System.out.println("sample,case,result,nanos,allocated_bytes,work,peak_reserved_bytes,mask");
        for (int sample = -warmup; sample < samples; sample++) for (Model model : models) {
            Observation observation = run(model, cap);
            if (sample >= 0) System.out.println(sample + "," + model.name + "," + observation.result + "," +
                    observation.nanos + "," + observation.allocated + "," + observation.work + "," +
                    observation.peak + "," + observation.mask);
        }
    }

    private static List<Model> models(int cases, boolean distinct) {
        var models = new ArrayList<Model>();
        for (int seed = 0; seed < cases; seed++) {
            Random random = new Random(170611L + seed);
            int n = 41 + seed % 12;
            int types = distinct ? n : 3 + seed % 6;
            long[] weights = new long[types], values = new long[n];
            for (int i = 0; i < types; i++) weights[i] = (1L << 39) + random.nextLong(1L << 39);
            long target = 0;
            for (int i = 0; i < n; i++) {
                values[i] = weights[i % types] * (seed % 2 == 0 || i % 5 != 0 ? 1 : -1);
                if (random.nextBoolean()) target += values[i];
            }
            for (int order = 0; order < 3; order++) {
                if (order != 0) for (int i = n - 1; i > 0; i--) {
                    int j = random.nextInt(i + 1);
                    long swap = values[i]; values[i] = values[j]; values[j] = swap;
                }
                models.add(new Model((distinct ? "distinct" : "repeated") + "-n" + n + "-t" + types + "-s" + seed + "-o" + order,
                        values.clone(), target));
            }
        }
        return models;
    }

    private static Observation run(Model model, long cap) {
        long span = 0;
        for (long value : model.coefficients) span = Math.addExact(span, Math.abs(value));
        var shape = CountResidueJoin.Shape.create(model.coefficients.length, span);
        var budget = new PlanningBudget(0, cap, 64L << 20, () -> false, System::nanoTime);
        long thread = Thread.currentThread().getId();
        long startAllocated = THREADS.getThreadAllocatedBytes(thread), start = System.nanoTime();
        String result = "UNKNOWN";
        long mask = -1, peak = 0;
        try (var search = new CountResidueJoin(model.coefficients, model.target, budget, shape)) {
            peak = budget.reservedBytes();
            while (!search.step()) {}
            mask = search.candidate();
            if (mask >= 0) result = "WITNESS";
        } catch (PlanningBudget.Exhausted exhausted) {
            if (exhausted.limit() != PlanningBudget.Limit.SEARCH_LIMIT) throw exhausted;
        }
        long nanos = System.nanoTime() - start, allocated = THREADS.getThreadAllocatedBytes(thread) - startAllocated;
        if (allocated < 0 || budget.reservedBytes() != 0) throw new AssertionError("Invalid memory accounting");
        if (mask >= 0) {
            if ((mask >>> model.coefficients.length) != 0) throw new AssertionError("Mask outside original domain");
            BigInteger sum = BigInteger.ZERO;
            for (int i = 0; i < model.coefficients.length; i++) if ((mask & (1L << i)) != 0)
                sum = sum.add(BigInteger.valueOf(model.coefficients[i]));
            if (!sum.equals(BigInteger.valueOf(model.target))) throw new AssertionError("Invalid original witness");
        }
        return new Observation(result, nanos, allocated, budget.searchWork(), peak, mask);
    }
}
