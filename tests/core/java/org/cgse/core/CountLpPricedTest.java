// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent finite oracles for aggregate-guided candidate neighborhoods. */
public final class CountLpPricedTest {
    public static void main(String[] args) throws Exception {
        for (int sample = 0; sample < 600; sample++) model(sample);
        for (int sample = 0; sample < 200; sample++) subsets(sample);
        radii();
        widePrices();
        scope();
        limits();
        handoffs();
        System.out.println("Priced LP neighborhoods: 600 exact model oracles in two orders, 200 exhaustive subset oracles, " +
                "four/five changes, wide prices, scope, queue/work/memory limits, cancellation and worker handoffs");
    }

    private static void model(int sample) {
        var random = new Random(121250000L + sample);
        int n = 4 + random.nextInt(7), plant = random.nextInt(1 << n);
        var low = filled(n, 0); var high = filled(n, 1); var point = new double[n];
        var terms = new TreeMap<Integer, BigInteger>();
        long rhs = random.nextInt(5);
        var scale = BigInteger.ONE.shiftLeft(sample % 7 == 0 ? 120 : 0);
        int fractionals = 0;
        for (int i = 0; i < n; i++) {
            int a = random.nextInt(35) - 17;
            terms.put(i, BigInteger.valueOf(a).multiply(scale));
            if ((plant & (1 << i)) != 0) rhs += a;
            point[i] = random.nextInt(4) == 0 && fractionals < 8 ? .5 : a < 0 ? 1 : 0;
            if (point[i] == .5) fractionals++;
            if (sample % 3 == 0 && random.nextInt(5) == 0) low[i] = high[i] = BigInteger.valueOf((plant >>> i) & 1);
        }
        var preference = new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(rhs).multiply(scale));
        var rows = new ArrayList<ExactLinearProgram.Constraint>(); rows.add(preference);
        for (int r = 0, m = 1 + random.nextInt(4); r < m; r++) {
            terms = new TreeMap<>(); rhs = 0;
            for (int i = 0; i < n; i++) {
                int a = random.nextInt(19) - 9;
                terms.put(i, BigInteger.valueOf(a));
                if ((plant & (1 << i)) != 0) rhs += a;
            }
            rhs += random.nextInt(5) - (sample % 2 == 0 ? 3 : 0);
            rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(rhs)));
            if (sample % 4 == 0 && r == 0) {
                var opposite = new TreeMap<Integer, BigInteger>(); terms.forEach((i, a) -> opposite.put(i, a.negate()));
                rows.add(new ExactLinearProgram.Constraint(opposite, BigInteger.valueOf(-rhs)));
            }
        }
        boolean expected = false;
        for (int bits = 0; bits < 1 << n; bits++) {
            var value = decode(bits, n);
            expected |= neighborhood(value, low, high, point, 5) && valid(rows, value);
        }
        for (int order = 0; order < 2; order++) {
            if (order != 0) Collections.shuffle(rows, random);
            var budget = budget(40_000_000, 64L << 20);
            try (var search = CountLpProximity.createPriced(rows, low, high, point, preference, budget)) {
                check(search != null, "Unexpected priced admission refusal " + sample);
                while (!search.step()) {}
                var value = search.counts();
                check((value != null) == expected, "Priced finite oracle mismatch " + sample + " " + budget.diagnostics());
                if (value != null) check(valid(rows, value) && neighborhood(value, low, high, point, 5), "Invalid priced witness");
            }
            check(budget.reservedBytes() == 0, "Priced model leaked");
        }
    }

    private static void subsets(int sample) {
        var random = new Random(121270000L + sample);
        int n = 2 + random.nextInt(13);
        long[] prices = new long[n];
        for (int i = 0; i < n; i++) prices[i] = random.nextInt(20) * (sample % 3 == 0 ? 1L << 40 : 1);
        Arrays.sort(prices);
        long capacity = prices[n - 1] * random.nextInt(5);
        var expected = new HashSet<Integer>();
        for (int mask = 0; mask < 1 << n; mask++) {
            if (Integer.bitCount(mask) > 4) continue;
            long sum = 0;
            for (int i = 0; i < n; i++) if ((mask & (1 << i)) != 0) sum += prices[i];
            if (sum <= capacity) expected.add(mask);
        }
        var budget = budget(40_000_000, 64L << 20);
        var actual = new HashSet<Integer>(); long previous = -1;
        try (var queue = new CountLpPriceQueue(prices, capacity, budget, () -> {})) {
            while (!queue.complete()) {
                int mask = 0, last = -1; long sum = 0;
                for (int k = 0; k < queue.length(); k++) {
                    int index = queue.at(k);
                    check(index > last, "Non-increasing subset"); last = index;
                    mask |= 1 << index; sum += prices[index];
                }
                check(sum >= previous && actual.add(mask), "Repeated or out-of-price-order subset"); previous = sum;
                for (int i = 0; i < n; i++)
                    check(queue.permits(i) == ((mask & (1 << i)) == 0 && sum + prices[i] <= capacity), "Bad extra-flip price/overlap filter");
                queue.advance();
            }
            check(!queue.limited() && actual.equals(expected), "Incomplete bounded subset enumeration " + sample);
        }
        check(budget.reservedBytes() == 0, "Price queue leaked");
    }

    private static void radii() {
        int n = 8;
        var preference = sumRow(n, 1, n);
        for (int required = 4; required <= 6; required++) {
            var rows = List.of(sumRow(n, -1, -required));
            var budget = budget(40_000_000, 64L << 20);
            try (var shallow = CountLpProximity.create(rows, filled(n, 0), filled(n, 1), new double[n], preference, budget)) {
                while (!shallow.step()) {}
                check(shallow.counts() == null, "Shallow radius unexpectedly included four changes");
            }
            try (var deep = CountLpProximity.createPriced(rows, filled(n, 0), filled(n, 1), new double[n], preference, budget)) {
                while (!deep.step()) {}
                check((deep.counts() != null) == (required <= 5), "Five-change radius mismatch");
                if (required == 6) check(budget.diagnostics().contains("neighborhood_exhausted"), "Local exhaustion was lost");
            }
            check(valid(rows, filled(n, 1)), "Restricted failure is not an original infeasibility oracle");
            check(budget.reservedBytes() == 0, "Radius run leaked");
        }
    }

    private static void widePrices() {
        int n = 8;
        var weights = new TreeMap<Integer, BigInteger>();
        var large = BigInteger.ONE.shiftLeft(200);
        for (int i = 0; i < n; i++) weights.put(i, large.add(BigInteger.valueOf(i * 17L + 3)));
        var row = new ExactLinearProgram.Constraint(weights,
                weights.values().stream().limit(5).reduce(BigInteger.ZERO, BigInteger::add));
        var rows = List.of(sumRow(n, -1, -5));
        var budget = budget(40_000_000, 64L << 20);
        try (var search = CountLpProximity.createPriced(rows, filled(n, 0), filled(n, 1), new double[n], row, budget)) {
            while (!search.step()) {}
            check(search.counts() != null && valid(rows, search.counts()), "Wide/fractionally scaled price lost a five-change candidate");
        }
        check(budget.reservedBytes() == 0, "Wide price run leaked");
    }

    private static void scope() {
        int n = 8;
        var low = filled(n, 0); var high = filled(n, 1);
        var rows = List.of(sumRow(n, -1, -4));
        var budget = budget(40_000_000, 64L << 20);
        for (int capacity : new int[] {0, 8, 0, 8}) {
            try (var search = CountLpProximity.createPriced(rows, low, high, new double[n], sumRow(n, 1, capacity), budget)) {
                while (!search.step()) {}
                check((search.counts() != null) == (capacity == 8), "A previous price restriction escaped its scope");
            }
            check(Arrays.equals(high, filled(n, 1)) && Arrays.equals(low, filled(n, 0)), "Price fixings mutated caller domains");
        }
        check(CountLpProximity.createPriced(rows, low, high, new double[n], null, budget) == null, "No aggregate should decline");
        var wrongDirection = new double[n]; Arrays.fill(wrongDirection, 1);
        check(CountLpProximity.createPriced(rows, low, high, wrongDirection, sumRow(n, 1, 8), budget) == null, "Misaligned price cone admitted");
        check(budget.reservedBytes() == 0, "Scope run leaked");
    }

    private static void limits() {
        for (long bytes : new long[] {1, 100, 10000, 1L << 20}) {
            var budget = budget(40_000_000, bytes);
            try (var queue = new CountLpPriceQueue(new long[128], 0, budget, () -> {})) {
                while (!queue.complete()) queue.advance();
                check(queue.limited(), "Unbounded queue grew past its memory/state allowance");
            }
            check(budget.reservedBytes() == 0, "Limited queue leaked");
        }
        var rows = List.of(sumRow(20, -1, -6));
        var preference = sumRow(20, 1, 20);
        for (int cancel = 1; cancel < 6000; cancel += 41) {
            int until = cancel; var checks = new AtomicInteger();
            var budget = new PlanningBudget(0, 40_000_000, 64L << 20, () -> checks.incrementAndGet() >= until, System::nanoTime);
            try (var search = CountLpProximity.createPriced(rows, filled(20, 0), filled(20, 1), new double[20], preference, budget)) {
                if (search != null) while (!search.step()) {}
            } catch (CancellationException expected) {}
            check(budget.reservedBytes() == 0, "Partial priced construction/query leaked on cancellation");
        }
        var budget = budget(40_000_000, 64L << 20);
        try (var search = CountLpProximity.createPriced(List.of(sumRow(128, -1, -6)), filled(128, 0), filled(128, 1),
                new double[128], sumRow(128, 1, 128), budget)) {
            while (!search.step()) {}
            check(search.counts() == null && budget.nodes() < 530000, "Priced neighborhood exhausted the request");
        }
        check(budget.reservedBytes() == 0, "Priced work stop leaked");
    }

    private static void handoffs() throws Exception {
        int n = 14;
        var rows = List.of(sumRow(n, -1, -5));
        var preference = sumRow(n, 1, n);
        var worker = Executors.newSingleThreadExecutor();
        BigInteger[] expected = null; long expectedWork = 0;
        try {
            for (int mode = 0; mode < 2; mode++) {
                var budget = budget(40_000_000, 64L << 20); long unrelated = 0;
                try (var search = CountLpProximity.createPriced(rows, filled(n, 0), filled(n, 1), new double[n], preference, budget)) {
                    int visits = 0;
                    while (true) {
                        boolean done = mode == 1 && (visits++ & 1) == 0 ? worker.submit(search::step).get() : search.step();
                        if (done) break;
                        if (mode == 1) { budget.charge(17); unrelated += 17; }
                    }
                    if (mode == 0) { expected = search.counts(); expectedWork = budget.nodes(); }
                    else {
                        check(expected != null && Arrays.equals(expected, search.counts()), "Priced worker handoff changed the witness");
                        check(Math.abs(expectedWork - (budget.nodes() - unrelated)) <= 2, "Priced handoff double-counted work");
                    }
                }
                check(budget.reservedBytes() == 0, "Priced worker handoff leaked");
            }
        } finally { worker.shutdownNow(); }
    }

    private static boolean neighborhood(BigInteger[] value, BigInteger[] low, BigInteger[] high, double[] point, int radius) {
        int changes = 0;
        for (int i = 0; i < value.length; i++) {
            if (value[i].compareTo(low[i]) < 0 || value[i].compareTo(high[i]) > 0) return false;
            if (low[i].equals(high[i]) || point[i] > 1e-6 && point[i] < 1 - 1e-6) continue;
            if (value[i].intValue() != (point[i] >= .5 ? 1 : 0)) changes++;
        }
        return changes <= radius;
    }

    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] value) {
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(value[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static ExactLinearProgram.Constraint sumRow(int n, int coefficient, int rhs) {
        var terms = new TreeMap<Integer, BigInteger>();
        for (int i = 0; i < n; i++) terms.put(i, BigInteger.valueOf(coefficient));
        return new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(rhs));
    }
    private static BigInteger[] filled(int n, long value) { var result = new BigInteger[n]; Arrays.fill(result, BigInteger.valueOf(value)); return result; }
    private static BigInteger[] decode(int bits, int n) { var result = new BigInteger[n]; for (int i = 0; i < n; i++) result[i] = BigInteger.valueOf((bits >>> i) & 1); return result; }
    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
