// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent full-row checks, bounded slices and request-private lifetimes. */
public final class CountMessageSearchTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    private static void pair(List<ExactLinearProgram.Constraint> rows, Map<Integer, BigInteger> terms, BigInteger rhs) {
        rows.add(new ExactLinearProgram.Constraint(terms, rhs));
        var reverse = new LinkedHashMap<Integer, BigInteger>(); terms.forEach((i, a) -> reverse.put(i, a.negate()));
        rows.add(new ExactLinearProgram.Constraint(reverse, rhs.negate()));
    }

    private static Model model(int seed, boolean translated) {
        int n = 12; var random = new Random(614961L + seed);
        var lo = new BigInteger[n]; var hi = new BigInteger[n]; var witness = new BigInteger[n];
        BigInteger scale = translated ? BigInteger.ONE.shiftLeft(160).add(BigInteger.valueOf(13)) : BigInteger.ONE;
        for (int i = 0; i < n; i++) {
            lo[i] = translated ? BigInteger.ONE.shiftLeft(240).multiply(BigInteger.valueOf(i % 2 == 0 ? -1 : 1)) : BigInteger.valueOf(-2);
            hi[i] = lo[i].add(BigInteger.valueOf(7)); witness[i] = lo[i].add(BigInteger.valueOf(random.nextInt(8)));
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        // Every small block has a unique integer point; its adjacent equation
        // supports interact, including opposite signs and arbitrary offsets.
        for (int i = 0; i < n; i += 2) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            terms.put(i, scale.multiply(BigInteger.valueOf(3))); terms.put(i + 1, scale.multiply(BigInteger.valueOf(-5)));
            pair(rows, terms, terms.get(i).multiply(witness[i]).add(terms.get(i + 1).multiply(witness[i + 1])));
            terms = new LinkedHashMap<>(); terms.put(i, scale.multiply(BigInteger.valueOf(2))); terms.put(i + 1, scale.multiply(BigInteger.valueOf(7)));
            pair(rows, terms, terms.get(i).multiply(witness[i]).add(terms.get(i + 1).multiply(witness[i + 1])));
        }
        if (seed % 2 != 0) Collections.shuffle(rows, random);
        return new Model(rows, lo, hi);
    }

    private static BigInteger[] solve(Model model, long quantum, boolean handoff) throws Exception {
        var budget = new PlanningBudget(0, 60_000_000, 64L << 20, () -> false, System::nanoTime);
        ExecutorService worker = handoff ? Executors.newSingleThreadExecutor() : null;
        BigInteger[] counts;
        try (var search = CountMessageSearch.create(model.rows, model.low, model.high, budget, quantum)) {
            check(search != null && search.paused(), "Missing retained private candidate");
            check(search.matches(model.rows, model.low, model.high), "Original model did not match");
            var changed = model.low.clone(); changed[0] = changed[0].subtract(BigInteger.ONE);
            check(!search.matches(model.rows, changed, model.high), "Different box reused a frontier");
            long owned = search.work(); budget.charge(1234);
            check(search.work() == owned, "Competing portfolio work advanced private search");
            long previous = search.work(); int turns = 0;
            while (true) {
                boolean done;
                if (handoff && turns++ % 7 == 0) done = worker.submit(() -> {
                    if (search.paused()) search.resume(quantum); return search.step();
                }).get(30, TimeUnit.SECONDS);
                else { if (search.paused()) search.resume(quantum); done = search.step(); }
                check(search.work() >= previous, "Worker handoff lost work"); previous = search.work();
                if (done && !search.paused()) break;
            }
            counts = search.counts(); check(counts != null, "Small constructive equations were lost"); verify(model, counts);
            BigInteger original = counts[0]; counts[0] = counts[0].subtract(BigInteger.ONE);
            check(search.counts()[0].equals(original), "Witness aliased caller array"); counts = search.counts();
            long paid = budget.nodes(); check(search.step() && budget.nodes() == paid, "Completed search restarted");
            check(search.remainingAllowance() == 0, "Completed frontier advertised more work");
            try { search.resume(quantum); throw new AssertionError("Resumed completed frontier"); }
            catch (IllegalStateException expected) { checks++; }
        } finally { if (worker != null) worker.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Normal completion leaked memory"); return counts;
    }

    private static void verify(Model m, BigInteger[] values) {
        for (int i = 0; i < values.length; i++) check(values[i].compareTo(m.low[i]) >= 0 && values[i].compareTo(m.high[i]) <= 0, "Original bound");
        for (var row : m.rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var t : row.terms().entrySet()) sum = sum.add(t.getValue().multiply(values[t.getKey()]));
            check(sum.compareTo(row.upper()) <= 0, "Original row");
        }
    }

    private static void refused(Model m) {
        var budget = new PlanningBudget(0, 60_000_000, 64L << 20, () -> false, System::nanoTime);
        try (var result = CountMessageSearch.create(m.rows, m.low, m.high, budget, 4096)) { check(result == null, "Unsupported model admitted"); }
        check(budget.reservedBytes() == 0, "Admission refusal leaked");
    }

    private static void limits() {
        Model m = model(0, false);
        var hi = m.high.clone(); hi[0] = m.low[0].add(BigInteger.valueOf(16)); refused(new Model(m.rows, m.low, hi));
        hi[0] = null; refused(new Model(m.rows, m.low, hi));
        var unpaired = new ArrayList<>(m.rows); unpaired.remove(0); refused(new Model(unpaired, m.low, m.high));
        var oversized = new ArrayList<ExactLinearProgram.Constraint>();
        pair(oversized, Map.of(0, BigInteger.valueOf(Integer.MIN_VALUE), 1, BigInteger.valueOf(3)), BigInteger.ZERO);
        refused(new Model(oversized, m.low, m.high));
        var budget = new PlanningBudget(0, 60_000_000, 1, () -> false, System::nanoTime);
        check(CountMessageSearch.create(m.rows, m.low, m.high, budget, 4096) == null && budget.reservedBytes() == 0, "Low-memory admission leaked");
        budget = new PlanningBudget(0, 60_000_000, 64L << 20, () -> false, System::nanoTime);
        budget.proofJournal(new CountProof.Journal(1L << 20));
        check(CountMessageSearch.create(m.rows, m.low, m.high, budget, 4096) == null && budget.reservedBytes() == 0, "Private messages entered proof export");
        var cancelled = new AtomicBoolean();
        budget = new PlanningBudget(0, 60_000_000, 64L << 20, cancelled::get, System::nanoTime);
        try (var search = CountMessageSearch.create(m.rows, m.low, m.high, budget, 4096)) {
            search.resume(4096); search.step(); cancelled.set(true);
            try { search.step(); throw new AssertionError("Ignored cancellation"); }
            catch (CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancellation leaked");
        for (long work : new long[]{1, 128, 8192}) {
            budget = new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime);
            try (var search = CountMessageSearch.create(m.rows, m.low, m.high, budget, 1)) {
                if (search != null) while (true) { if (search.paused()) search.resume(1); if (search.step() && !search.paused()) break; }
            } catch (PlanningBudget.Exhausted expected) { checks++; }
            check(budget.reservedBytes() == 0, "Work cutoff leaked");
        }
    }

    private static void infeasibleIsOnlyNoWitness() {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        pair(rows, Map.of(0, BigInteger.TWO, 1, BigInteger.valueOf(3)), BigInteger.ONE);
        BigInteger[] lo = {BigInteger.ZERO, BigInteger.ZERO}, hi = {BigInteger.valueOf(3), BigInteger.valueOf(3)};
        var budget = new PlanningBudget(0, 1_000_000, 64L << 20, () -> false, System::nanoTime);
        try (var search = CountMessageSearch.create(rows, lo, hi, budget, 1)) {
            check(search != null, "Unreachable finite sum was not exercised");
            while (true) { if (search.paused()) search.resume(1); if (search.step() && !search.paused()) break; }
            check(search.counts() == null, "Fabricated solution for impossible finite sum");
        }
        check(budget.reservedBytes() == 0, "Empty finite search leaked");
    }

    private static void inconsistentTriangle() {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        pair(rows, Map.of(0, BigInteger.TWO, 1, BigInteger.TWO), BigInteger.TWO);
        pair(rows, Map.of(1, BigInteger.TWO, 2, BigInteger.TWO), BigInteger.TWO);
        pair(rows, Map.of(0, BigInteger.TWO, 2, BigInteger.TWO), BigInteger.TWO);
        // Independent exhaustive oracle: each pair requires opposite Boolean
        // values, which cannot simultaneously hold around this odd cycle.
        for (int tuple = 0; tuple < 8; tuple++) {
            int x = tuple & 1, y = (tuple >>> 1) & 1, z = (tuple >>> 2) & 1;
            check(x + y != 1 || y + z != 1 || x + z != 1, "Unexpected triangle witness");
        }
        for (long quantum : new long[]{1, 32768}) {
            BigInteger[] lo = {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
            BigInteger[] hi = {BigInteger.ONE, BigInteger.ONE, BigInteger.ONE};
            var budget = new PlanningBudget(0, 1_000_000, 64L << 20, () -> false, System::nanoTime);
            try (var search = CountMessageSearch.create(rows, lo, hi, budget, quantum)) {
                check(search != null, "Triangle did not enter private search");
                while (true) { if (search.paused()) search.resume(quantum); if (search.step() && !search.paused()) break; }
                check(search.counts() == null, "Fabricated triangle witness");
                check(search.work() < CountMessageSearch.LIMIT, "Triangle stopped at a local cutoff");
                var branches = java.util.regex.Pattern.compile("branches=(\\d+); failures=(\\d+)").matcher(budget.diagnostics());
                check(branches.find() && Long.parseLong(branches.group(1)) >= 2 && Long.parseLong(branches.group(2)) >= 2,
                        "Both sides of the inconsistent search were not exercised");
                long paid = budget.nodes(); check(search.step() && budget.nodes() == paid, "Exhausted triangle search restarted");
            }
            check(budget.reservedBytes() == 0, "Backtracking to an empty stack leaked");
        }
    }

    public static void main(String[] args) throws Exception {
        for (int seed = 0; seed < 4; seed++) {
            Model model = model(seed, seed >= 2);
            BigInteger[] small = solve(model, 1, seed == 0), large = solve(model, 32768, false);
            check(Arrays.equals(small, large), "Slice sizes changed deterministic witness");
        }
        // Both root marginals tie at 1/2. Independently rounding them to zero
        // violates the row; exact private branching must finish the proposal.
        var symmetric = new ArrayList<ExactLinearProgram.Constraint>();
        pair(symmetric, Map.of(0, BigInteger.TWO, 1, BigInteger.TWO), BigInteger.TWO);
        var m = new Model(symmetric, new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO},
                new BigInteger[]{BigInteger.ONE, BigInteger.ONE});
        check(Arrays.equals(solve(m, 1, true), solve(m, 32768, false)), "Decimation changed with handoff/slices");
        limits(); infeasibleIsOnlyNoWitness(); inconsistentTriangle();
        System.out.println("Equality messages: " + checks + " checks; original integers, translated/scaled rows, slices, handoff, admission, cancellation and limits passed");
    }
}
