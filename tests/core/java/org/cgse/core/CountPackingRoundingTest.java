// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Literal neighborhood oracles plus original-model continuation and ownership checks. */
public final class CountPackingRoundingTest {
    private static final CountPackingNeighborhood.Mode[] MODES = {
            CountPackingNeighborhood.Mode.EXCHANGE_ROUNDING_WIDE,
            CountPackingNeighborhood.Mode.EXCHANGE_ROUNDING,
            CountPackingNeighborhood.Mode.EXCHANGE_ROUNDING_TAIL
    };
    private static final int[] LEFT = {2, 3, 4}, RIGHT = {2, 3, 2};
    private static int checks;

    public static void main(String[] args) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int trial = 0; trial < 216; trial++) {
                var random = new Random(7811329L + trial);
                int n = 2 + trial % 13, m = 1 + trial % 8;
                long seed = random.nextInt(1 << n), flexible = random.nextInt(1 << n);
                long[][] rows = new long[m][n]; long[] bounds = new long[m];
                long planted = random.nextInt(1 << n);
                for (int r = 0; r < m; r++) {
                    for (int i = 0; i < n; i++) {
                        rows[r][i] = random.nextInt(29) - 14;
                        if ((planted & (1L << i)) != 0) bounds[r] += rows[r][i];
                    }
                    bounds[r] += random.nextInt(7) - 3;
                }
                long left = (1L << (n / 2)) - 1;
                for (int mode = 0; mode < MODES.length; mode++) {
                    boolean expected = false;
                    for (int x = 0; x < 1 << n; x++) if (inside(x, seed, flexible, left, mode) && valid(rows, bounds, x)) {
                        expected = true; break;
                    }
                    Long actual = solve(rows, bounds, seed, flexible, mode, trial < 3 ? worker : null);
                    check((actual != null) == expected, "Literal oracle " + trial + "/" + mode);
                    if (actual != null) check(inside(actual, seed, flexible, left, mode) && valid(rows, bounds, actual), "Invalid local witness");
                }
            }
            for (int mode = 0; mode < MODES.length; mode++) boundaries(mode);
            var view = fixture();
            BigInteger[] one = continuation(view, 32768, null), sliced = continuation(view, 73, worker);
            check(Arrays.equals(one, sliced), "Handoff changed the candidate trajectory");
            lifecycle(view);
            optionalRefusal(view);
        } finally { worker.shutdownNow(); }
        System.out.println("Fractional packing: 648 exhaustive neighborhoods, exact original rows, 64-bit boundaries, scoped misses, handoffs and lifecycle passed; checks=" + checks);
    }

    private static boolean inside(long value, long seed, long flexible, long left, int mode) {
        long paid = (value ^ seed) & ~flexible;
        return Long.bitCount(paid & left) <= LEFT[mode] && Long.bitCount(paid & ~left) <= RIGHT[mode];
    }

    private static boolean valid(long[][] rows, long[] bounds, long value) {
        for (int r = 0; r < rows.length; r++) {
            long sum = 0;
            for (int i = 0; i < rows[r].length; i++) if ((value & (1L << i)) != 0) sum += rows[r][i];
            if (sum > bounds[r]) return false;
        }
        return true;
    }

    private static Long solve(long[][] rows, long[] bounds, long seed, long flexible, int mode, ExecutorService worker) throws Exception {
        var budget = new PlanningBudget(0, 20_000_000, 64L << 20, () -> false, System::nanoTime);
        Long value;
        try (var search = new CountPackingNeighborhood(rows, bounds, seed, budget, MODES[mode], flexible)) {
            while (!(worker == null ? search.step() : worker.submit(search::step).get())) {}
            value = search.feasible() ? search.assignment() : null;
            check(!search.provedInfeasible(), "Restricted domain escaped as a full-face proof");
        }
        check(budget.reservedBytes() == 0, "Exchange storage leaked");
        return value;
    }

    private static void boundaries(int mode) throws Exception {
        long[][] rows = new long[35][64]; long[] bounds = new long[35];
        rows[34][63] = -(1L << 52); bounds[34] = -(1L << 52);
        check(Objects.equals(solve(rows, bounds, 0, 0, mode, null), Long.MIN_VALUE), "Sign bit or scaled row lost");
        rows = new long[1][16]; bounds = new long[] {-5}; Arrays.fill(rows[0], 0, 8, -1);
        check(solve(rows, bounds, 0, 0, mode, null) == null && valid(rows, bounds, 31), "Restricted miss is not global infeasibility");
        var budget = new PlanningBudget(0, 20_000_000, 1, () -> false, System::nanoTime);
        try (var search = new CountPackingNeighborhood(rows, bounds, 0, budget, MODES[mode], 0)) {
            check(search.step() && !search.feasible() && !search.provedInfeasible(), "Memory refusal changed truth");
        }
        check(budget.reservedBytes() == 0, "Memory refusal leaked");
    }

    private static CountModelViews.View fixture() throws Exception {
        String input = String.join("\n", Files.readAllLines(Path.of("tests/core/data/packing-rounding-100.txt")).stream().filter(s -> !s.startsWith("#")).toList());
        try (var scanner = new Scanner(input)) {
            int n = scanner.nextInt(), m = scanner.nextInt();
            var low = new BigInteger[n]; var high = new BigInteger[n];
            Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int r = 0; r < m; r++) {
                BigInteger rhs = scanner.nextBigInteger(); var terms = new LinkedHashMap<Integer, BigInteger>();
                for (int i = 0; i < n; i++) { BigInteger a = scanner.nextBigInteger(); if (a.signum() != 0) terms.put(i, a); }
                rows.add(new ExactLinearProgram.Constraint(terms, rhs));
            }
            var witness = new BigInteger[n]; for (int i = 0; i < n; i++) witness[i] = scanner.nextBigInteger();
            check(!scanner.hasNext(), "Extra fixture data"); verify(rows, low, high, witness);
            return new CountModelViews.View("fractional-regression", rows, low, high,
                    new CountModelViews.Shape(n, rows.stream().mapToLong(r -> r.terms().size()).sum(), 32, n, false),
                    null, CountModelViews.Semantics.EQUIVALENT, null);
        }
    }

    private static BigInteger[] continuation(CountModelViews.View view, long quantum, ExecutorService worker) throws Exception {
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime);
        BigInteger[] value; boolean expanded = false;
        try (var search = CountPackingCore.create(view, new CountModelViews.Domains(view.lower(), view.upper(), 0), budget, 4096)) {
            for (int turns = 0;; turns++) {
                check(turns < 500000, "Unbounded continuation quantum=" + quantum + "; work=" + search.work() + "; progress=" + search.progress() + "; budget=" + budget.nodes() + "; expanded=" + search.roundingContinuationWork());
                if (search.paused()) {
                    long before = budget.nodes(); check(search.step() && budget.nodes() == before, "Paused work was renewed"); search.resume(quantum);
                }
                boolean done = worker == null || turns % 2 == 0 ? advance(search) : worker.submit(() -> advance(search)).get();
                expanded |= budget.diagnostics().contains("count_packing_roundings");
                if (done && !search.paused()) break;
            }
            value = search.counts(); check(expanded && value != null, "Expanded candidate was not solved");
            verify(view.rows(), view.lower(), view.upper(), value);
            check(budget.nodes() < 7_000_000, "Unexpected direct completion cost " + budget.nodes());
        }
        check(budget.reservedBytes() == 0, "Retained candidate leaked"); return value;
    }

    private static boolean advance(CountPackingCore search) {
        while (!search.step()) {}
        return true;
    }

    private static void lifecycle(CountModelViews.View view) {
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancelled::get, System::nanoTime);
        try (var search = CountPackingCore.create(view, new CountModelViews.Domains(view.lower(), view.upper(), 0), budget, 32768)) {
            while (!budget.diagnostics().contains("count_packing_roundings")) {
                if (search.paused()) search.resume(32768);
                advance(search); check(search.paused(), "No expanded frontier");
            }
            if (search.paused()) search.resume(32768);
            check(search.matches(view.rows(), view.lower(), view.upper()), "Scope missing");
            var high = view.upper().clone(); high[0] = BigInteger.ZERO;
            check(!search.matches(view.rows(), view.lower(), high), "Changed bounds reused");
            cancelled.set(true);
            try { search.step(); throw new AssertionError("Ignored cancellation"); } catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancelled expanded search leaked");
    }

    private static void optionalRefusal(CountModelViews.View view) {
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime);
        long withheld = 0; boolean expanded = false, restored = false;
        try (var search = CountPackingCore.create(view, new CountModelViews.Domains(view.lower(), view.upper(), 0), budget, 32768)) {
            while (search.work() < 131072) {
                if (search.paused()) search.resume(32768);
                advance(search); check(search.paused(), "Fixture ended before memory refusal");
            }
            withheld = budget.availableBytes() - 16384;
            check(withheld > 0 && budget.tryReserve(withheld), "Could not restrict optional workspace");
            for (int turns = 0;; turns++) {
                check(turns < 10000, "Refused workspace loop");
                if (search.paused()) search.resume(32768);
                advance(search);
                expanded |= budget.diagnostics().contains("count_packing_roundings");
                restored |= expanded && budget.diagnostics().contains("resume_original");
                if (!search.paused()) break;
            }
            check(expanded && restored, "Refused rounding lost the original frontier");
            if (search.counts() != null) verify(view.rows(), view.lower(), view.upper(), search.counts());
        } finally { budget.release(withheld); }
        check(budget.reservedBytes() == 0, "Refused expanded workspace leaked");
    }

    private static void verify(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, BigInteger[] values) {
        for (int i = 0; i < values.length; i++) check(values[i].compareTo(low[i]) >= 0 && values[i].compareTo(high[i]) <= 0, "Original domain");
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            check(sum.compareTo(row.upper()) <= 0, "Original row");
        }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
