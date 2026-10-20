// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CountSupportedSearchTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, BigInteger[] witness) {}

    public static void main(String[] args) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (boolean domains : new boolean[] {false, true})
            for (int n : new int[] {8, 48, 144, 256}) for (int seed = 0; seed < 4; seed++) {
                Model m = model(n, seed);
                BigInteger[] whole = solve(m, 1_048_576, null, domains);
                BigInteger[] sliced = solve(m, 1, worker, domains);
                check(Arrays.equals(whole, m.witness) && Arrays.equals(whole, sliced), "Lost unique constructive witness");
                var reordered = new ArrayList<>(m.rows); Collections.shuffle(reordered, new Random(73100 + seed));
                check(Arrays.equals(solve(new Model(reordered, m.low, m.high, m.witness), 113, null, domains), whole), "Order changed unique solution");
                reordered.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), whole[0].subtract(BigInteger.ONE)));
                check(solve(new Model(reordered, m.low, m.high, null), 71, null, domains) == null, "Ignored original side row");
            }
        } finally { worker.shutdownNow(); }
        for (boolean domains : new boolean[] {false, true}) {
            limits(domains);
            boundedHardSearch(domains);
        }
        System.out.println("Supported search: 128 positive/negative continuations; hull/masks, original rows, offsets, orders, worker handoff and proof isolation passed");
    }

    private static BigInteger[] solve(Model m, long quantum, ExecutorService worker, boolean domains) throws Exception {
        var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
        BigInteger[] result;
        try (var search = CountSupportedSearch.create(m.rows, m.low, m.high, budget, 4096, domains)) {
            check(search != null && search.matches(m.rows, m.low, m.high), "Scope missing");
            var other = m.high.clone(); other[0] = other[0].add(BigInteger.ONE);
            check(!search.matches(m.rows, m.low, other), "Changed stock reused");
            while (true) {
                if (search.paused()) {
                    long work = budget.searchWork();
                    check(search.step() && budget.searchWork() == work, "Paused child consumed work");
                    search.resume(quantum);
                }
                boolean done = worker == null ? search.step() : worker.submit(search::step).get(30, TimeUnit.SECONDS);
                if (done && !search.paused()) break;
            }
            result = search.counts();
            check(search.work() <= 1_048_576 + 32768, "Local ceiling renewed by grants");
            if (result != null) {
                for (int i = 0; i < result.length; i++) check(result[i].compareTo(m.low[i]) >= 0 && result[i].compareTo(m.high[i]) <= 0, "Domain violation");
                for (var row : m.rows) {
                    BigInteger sum = BigInteger.ZERO;
                    for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(result[term.getKey()]));
                    check(sum.compareTo(row.upper()) <= 0, "Original row violation");
                }
            }
        }
        check(budget.reservedBytes() == 0, "Candidate workspace leaked");
        return result;
    }

    private static Model model(int n, int seed) {
        var random = new Random(2026102011L + seed);
        var low = new BigInteger[n]; var high = new BigInteger[n]; var witness = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            low[i] = (seed & 1) == 0 ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(85).multiply(BigInteger.valueOf(i % 2 == 0 ? 1 : -1));
            high[i] = low[i].add(BigInteger.valueOf(7));
            witness[i] = low[i].add(BigInteger.valueOf(1 + random.nextInt(6)));
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < n; i += 2) {
            BigInteger a = BigInteger.valueOf(7), b = BigInteger.valueOf(seed % 2 == 0 ? 11 : -11);
            rows.addAll(CountEqualitySupportTest.pair(Map.of(i, a, i + 1, b), a.multiply(witness[i]).add(b.multiply(witness[i + 1]))));
        }
        return new Model(rows, low, high, witness);
    }

    private static void limits(boolean domains) {
        Model m = model(144, 1);
        var budget = new PlanningBudget(0, 4_000_000, 1, () -> false, System::nanoTime);
        check(CountSupportedSearch.create(m.rows, m.low, m.high, budget, 4096, domains) == null && budget.reservedBytes() == 0, "Memory refusal leaked");
        budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
        var journal = new CountProof.Journal(1L << 20); budget.proofJournal(journal);
        check(CountSupportedSearch.create(m.rows, m.low, m.high, budget, 4096, domains) == null && budget.reservedBytes() == 0, "Private support entered proof export");
        var cancelled = new AtomicBoolean();
        budget = new PlanningBudget(0, 4_000_000, 64L << 20, cancelled::get, System::nanoTime);
        try (var search = CountSupportedSearch.create(m.rows, m.low, m.high, budget, 4096, domains)) {
            search.resume(4096); search.step(); cancelled.set(true);
            try { search.step(); throw new AssertionError("Ignored cancellation"); }
            catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancellation leaked");
    }

    private static void boundedHardSearch(boolean domains) throws Exception {
        int n = 144;
        var random = new Random(2026102023);
        var low = new BigInteger[n]; var high = new BigInteger[n]; var planted = new BigInteger[n];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.valueOf(7));
        for (int i = 0; i < n; i++) planted[i] = BigInteger.valueOf(random.nextInt(8));
        int[] weights = {-13, -7, -3, 2, 5, 11};
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0; r < n / 2; r++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            while (terms.size() < 9) terms.put(random.nextInt(n), BigInteger.valueOf(weights[random.nextInt(weights.length)]));
            BigInteger rhs = BigInteger.ZERO;
            for (var term : terms.entrySet()) rhs = rhs.add(term.getValue().multiply(planted[term.getKey()]));
            rows.addAll(CountEqualitySupportTest.pair(terms, rhs));
        }
        var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
        try (var search = CountSupportedSearch.create(rows, low, high, budget, 4096, domains)) {
            while (true) {
                if (search.paused()) search.resume(137);
                if (search.step() && !search.paused()) break;
            }
            // Constructive SAT is retained even when the local scout has no
            // witness. Repeated grants cannot buy another million work units.
            check(search.counts() == null && search.work() >= 1_048_576 && search.work() < 1_081_344, "Hard local cutoff not exercised");
            long before = budget.searchWork();
            check(search.step() && budget.searchWork() == before, "Completed scout restarted");
            try { search.resume(4096); throw new AssertionError("Renewed completed scout"); }
            catch (IllegalStateException expected) {}
        }
        check(budget.reservedBytes() == 0, "Local cutoff retained workspace");
        budget = new PlanningBudget(0, 20000, 64L << 20, () -> false, System::nanoTime);
        boolean exhausted = false;
        try (var search = CountSupportedSearch.create(rows, low, high, budget, 4096, domains)) {
            while (true) {
                if (search.paused()) search.resume(137);
                if (search.step() && !search.paused()) break;
            }
        } catch (PlanningBudget.Exhausted expected) { exhausted = true; }
        check(exhausted && budget.reservedBytes() == 0, "Request cutoff leaked or became an empty proof");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
