// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Original-coordinate proof checks for small matching kernels in mixed, large models. */
public final class HallSupportTest {
    record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, int[] ids) {}
    record Result(List<ExactLinearProgram.Constraint> cuts, CountProof.Journal journal, long work) {}
    private static long checked;

    public static void main(String[] args) throws Exception {
        int deficits = 0;
        for (int n : new int[]{32, 600, 4096}) for (int order = 0; order < 6; order++) {
            var blocked = embed(HallCapacityTest.coloring(6, 5, false, order), n, order, false);
            var result = solve(blocked, 40_000_000, 128L << 20, true);
            check(result.cuts.stream().anyMatch(HallSupportTest::contradiction), "Small Hall kernel declined: " + n + "/" + order);
            check(!result.journal.cliques().isEmpty(), "Pair capacities not proved on actual support");
            deficits++;
            var relaxed = embed(HallCapacityTest.coloring(6, 5, true, order), n, order, false);
            var point = relaxed.low.clone();
            for (int g = 0; g < 6; g++) point[relaxed.ids[g * 5 + Math.max(0, g - 1)]] = BigInteger.ONE;
            check(fits(relaxed.rows, point), "Invalid planted missing-edge neighbor");
            check(fits(solve(relaxed, 40_000_000, 128L << 20, true).cuts, point), "Discarded feasible neighbor");
        }
        exhaustive();
        unrelatedCapacities();
        crossing();
        boundaries();
        System.out.println("Hall support: " + deficits + " padded deficits, 18 feasible neighbors; " + checked +
                " independent finite assignments; complete boundary rows, original-coordinate archives and resource limits passed");
    }

    private static void unrelatedCapacities() throws Exception {
        var base = HallCapacityTest.coloring(6, 5, false, 0);
        var low = new BigInteger[700]; var high = new BigInteger[700];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        // These 660 Boolean coordinates have capacities but no demands.
        // They must not crowd the actual 30-coordinate conflict out of a
        // 512-coordinate analysis simply because they occur first.
        for (int i = 30; i < 690; i += 2)
            rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE, i + 1, BigInteger.ONE), BigInteger.ONE));
        rows.addAll(base.rows());
        var model = new Model(rows, low, high, null);
        check(solve(model, 40_000_000, 64L << 20, true).cuts.stream().anyMatch(HallSupportTest::contradiction),
                "Unrelated unit capacities consumed the matching support cap");
    }

    static Model embed(HallCapacityTest.Model base, int n, int seed, boolean signed) {
        var random = new Random(2026101119L + seed);
        var order = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) order.add(i);
        Collections.shuffle(order, random);
        var low = new BigInteger[n];
        var high = new BigInteger[n];
        Arrays.fill(low, signed ? BigInteger.valueOf(-3) : BigInteger.ZERO);
        Arrays.fill(high, BigInteger.valueOf(1000));
        int[] ids = new int[base.low().length];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = order.get(i);
            low[ids[i]] = base.low()[i]; high[ids[i]] = base.high()[i];
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : base.rows()) {
            var terms = new TreeMap<Integer, BigInteger>();
            row.terms().forEach((id, value) -> terms.put(ids[id], value));
            rows.add(new ExactLinearProgram.Constraint(terms, row.upper()));
        }
        // Thousands of irrelevant rows must not determine the matching size.
        // Include an unbounded coordinate; nothing substitutes zero for it.
        for (int at = ids.length; at < n; at++) {
            int id = order.get(at);
            rows.add(new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE.negate()), low[id].negate()));
            if (at % 7 == 0) high[id] = null;
            else rows.add(new ExactLinearProgram.Constraint(Map.of(id, BigInteger.TWO), BigInteger.valueOf(2000)));
        }
        Collections.shuffle(rows, random);
        return new Model(List.copyOf(rows), low, high, ids);
    }

    private static void exhaustive() throws Exception {
        var random = new Random(920417);
        for (int seed = 0; seed < 96; seed++) {
            var base = HallCapacityTest.coloring(3, 3, false, seed);
            var rows = new ArrayList<>(base.rows());
            rows.removeIf(row -> row.terms().size() == 2 && random.nextInt(3) == 0);
            var lo = base.low().clone(); var hi = base.high().clone();
            if (seed % 3 == 0) hi[random.nextInt(9)] = BigInteger.ZERO;
            if (seed % 5 == 0) { int id = random.nextInt(9); lo[id] = hi[id]; }
            var model = embed(new HallCapacityTest.Model(rows, lo, hi), 600, seed, true);
            var result = solve(model, 40_000_000, 64L << 20, seed % 16 == 0);
            for (int mask = 0; mask < 512; mask++) {
                var point = model.low.clone(); boolean domain = true;
                for (int i = 0; i < 9; i++) {
                    point[model.ids[i]] = BigInteger.valueOf((mask >>> i) & 1);
                    domain &= point[model.ids[i]].compareTo(lo[i]) >= 0 && point[model.ids[i]].compareTo(hi[i]) <= 0;
                }
                if (domain && fits(model.rows, point)) check(fits(result.cuts, point), "Unsound local Hall consequence: " + seed);
                checked++;
            }
        }
    }

    private static void crossing() throws Exception {
        var base = HallCapacityTest.coloring(4, 3, false, 0);
        // Every apparent pair conflict is conditional on a wide or signed
        // boundary. Omitting that coordinate would fabricate a pigeonhole.
        for (int sign : new int[]{-1, 1}) {
            int outside = base.low().length;
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (var row : base.rows()) {
                var terms = new TreeMap<>(row.terms());
                if (terms.size() == 2) terms.put(outside, BigInteger.valueOf(sign));
                rows.add(new ExactLinearProgram.Constraint(terms, row.upper()));
            }
            var lo = Arrays.copyOf(base.low(), outside + 1);
            var hi = Arrays.copyOf(base.high(), outside + 1);
            lo[outside] = BigInteger.valueOf(-2); hi[outside] = BigInteger.TWO;
            var model = new Model(rows, lo, hi, null);
            var point = lo.clone();
            for (int g = 0; g < 4; g++) point[g * 3] = BigInteger.ONE;
            point[outside] = BigInteger.valueOf(-sign);
            check(fits(rows, point), "Invalid crossing-row witness");
            check(fits(solve(model, 40_000_000, 64L << 20, true).cuts, point), "Omitted crossing variable treated as zero");
        }
        // Proof verifier must include all variables of the cited pair row,
        // including a boundary that does not occur in the clique itself.
        var low = List.of(BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(-1));
        var high = Arrays.asList(BigInteger.ONE, BigInteger.ONE, (BigInteger) null);
        var axioms = new ArrayList<CountProof.Row>();
        axioms.add(new CountProof.Row(Map.of(0, BigInteger.ONE, 1, BigInteger.ONE, 2, BigInteger.ONE), BigInteger.ONE));
        for (int id = 0; id < 2; id++) {
            axioms.add(new CountProof.Row(Map.of(id, BigInteger.ONE.negate()), BigInteger.ZERO));
            axioms.add(new CountProof.Row(Map.of(id, BigInteger.ONE), BigInteger.ONE));
        }
        var cut = new CountProof.Row(Map.of(0, BigInteger.ONE, 1, BigInteger.ONE), BigInteger.ONE);
        var missingBoundary = new CountProof.Clique("missing_boundary", 3, axioms, low, high, List.of(1, 3), List.of(0), cut);
        check(CountProof.verify(missingBoundary, 1000) == CountProof.Verdict.INVALID, "Missing boundary bound accepted");
        axioms.add(new CountProof.Row(Map.of(2, BigInteger.ONE.negate()), BigInteger.ONE));
        var feasiblePair = new CountProof.Clique("feasible_pair", 3, axioms, low, high, List.of(1, 3), List.of(0), cut);
        check(CountProof.verify(feasiblePair, 1000) == CountProof.Verdict.INVALID, "Signed boundary lost in pair proof");
    }

    private static void boundaries() throws Exception {
        var model = embed(HallCapacityTest.coloring(6, 5, false, 0), 600, 13, false);
        for (long work : new long[]{1, 64, 4096, 262144, 1_000_000}) for (long memory : new long[]{1024, 65536, 16L << 20})
            solve(model, work, memory, false);
        for (long threshold : new long[]{0, 100, 700, 1400, 2000, 5000}) {
            PlanningBudget[] owner = {null};
            owner[0] = new PlanningBudget(0, 40_000_000, 64L << 20,
                    () -> owner[0] != null && owner[0].nodes() >= threshold, System::nanoTime);
            try (var hall = new CountHall(model.rows, model.low, model.high, owner[0])) {
                while (!hall.step()) {}
            } catch (CancellationException expected) {}
            check(owner[0].reservedBytes() == 0, "Cancellation leaked: " + threshold);
        }
        // Discovery can stop midway through a row. Only complete previously
        // admitted rows are ever used, including when the support cap is hit.
        for (long grant : new long[]{0, 1, 5, 31, 200, 1000, 8192}) {
            var budget = budget(40_000_000, 64L << 20);
            var selected = CountHallSupport.select(model.rows, model.low, model.high, budget, grant);
            check(selected.work() <= grant && model.rows.containsAll(selected.rows()), "Discovery exceeded grant or projected a row");
        }
    }

    private static Result solve(Model model, long work, long bytes, boolean archive) throws Exception {
        var budget = budget(work, bytes);
        var journal = new CountProof.Journal(32L << 20);
        budget.proofJournal(journal);
        List<ExactLinearProgram.Constraint> cuts = List.of();
        try (var hall = new CountHall(model.rows, model.low, model.high, budget)) {
            while (!hall.step()) {}
            cuts = hall.cuts();
        } catch (PlanningBudget.Exhausted expected) {}
        check(budget.reservedBytes() == 0, "Hall support workspace leaked");
        check(!journal.truncated(), "Local proof archive truncated");
        if (archive) {
            var path = Files.createTempFile("cgse-hall-support", ".proof");
            try { journal.write(path); journal = CountProof.read(path); }
            finally { Files.deleteIfExists(path); }
        }
        var known = new HashSet<CountProof.Row>();
        for (var row : model.rows) known.add(CountProof.row(row));
        for (int i = 0; i < model.low.length; i++) {
            known.add(new CountProof.Row(Map.of(i, BigInteger.ONE.negate()), model.low[i].negate()));
            if (model.low[i].signum() >= 0) known.add(new CountProof.Row(Map.of(i, BigInteger.ONE.negate()), BigInteger.ZERO));
            if (model.high[i] != null) {
                known.add(new CountProof.Row(Map.of(i, BigInteger.ONE), model.high[i]));
                if (model.high[i].compareTo(BigInteger.ONE) <= 0) known.add(new CountProof.Row(Map.of(i, BigInteger.ONE), BigInteger.ONE));
            }
        }
        for (var proof : journal.cliques()) {
            check(known.containsAll(proof.axioms()), "Invented clique premise or coordinate");
            check(CountProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "Invalid clique proof");
            known.add(proof.consequence());
        }
        for (var proof : journal.derivations()) {
            check(known.containsAll(proof.axioms()), "Invented Hall premise or coordinate");
            check(CountProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "Invalid Hall proof");
            proof.steps().forEach(step -> known.add(step.consequence()));
        }
        for (var cut : cuts) check(known.contains(CountProof.row(cut)), "Cut has no independent proof");
        return new Result(cuts, journal, budget.nodes());
    }

    private static boolean contradiction(ExactLinearProgram.Constraint row) { return row.terms().isEmpty() && row.upper().signum() < 0; }
    private static boolean fits(List<ExactLinearProgram.Constraint> rows, BigInteger[] values) {
        for (var row : rows) {
            var sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
