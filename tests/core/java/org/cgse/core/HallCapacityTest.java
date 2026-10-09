// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Independent finite assignments and original-premise replay for inferred capacities. */
public final class HallCapacityTest {
    record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}

    public static void main(String[] args) throws Exception {
        for (int size = 4; size <= 13; size++) for (int order = 0; order < 3; order++) {
            Model blocked = coloring(size, size - 1, false, order);
            var cuts = solve(blocked, 64L << 20, true);
            check(cuts.stream().anyMatch(r -> r.terms().isEmpty() && r.upper().signum() < 0),
                    "Pigeonhole deficit not detected: " + size + "/" + order);
            Model relaxed = coloring(size, size - 1, true, order);
            // The first two groups share resource zero; all others use distinct resources.
            BigInteger[] witness = new BigInteger[size * (size - 1)];
            Arrays.fill(witness, BigInteger.ZERO);
            for (int g = 0; g < size; g++) witness[g * (size - 1) + Math.max(0, g - 1)] = BigInteger.ONE;
            check(fits(relaxed.rows, witness), "Invalid independent neighbor witness");
            check(fits(solve(relaxed, 64L << 20, true), witness), "Missing pair was promoted to a clique");
        }
        exhaustive();
        boundaries();
        System.out.println("Hall capacities: 30 deficits, 30 feasible missing-edge neighbors, 240 exhaustive models; original-premise certificates, serialization, low memory and cancellation passed");
    }

    static Model coloring(int groups, int resources, boolean missing, int order) {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int g = 0; g < groups; g++) {
            var positive = new LinkedHashMap<Integer, BigInteger>();
            var negative = new LinkedHashMap<Integer, BigInteger>();
            for (int r = 0; r < resources; r++) {
                positive.put(g * resources + r, BigInteger.ONE);
                negative.put(g * resources + r, BigInteger.ONE.negate());
            }
            rows.add(new ExactLinearProgram.Constraint(positive, BigInteger.ONE));
            rows.add(new ExactLinearProgram.Constraint(negative, BigInteger.ONE.negate()));
        }
        for (int a = 0; a < groups; a++) for (int b = a + 1; b < groups; b++) for (int r = 0; r < resources; r++) {
            if (missing && a == 0 && b == 1 && r == 0) continue;
            rows.add(new ExactLinearProgram.Constraint(Map.of(a * resources + r, BigInteger.ONE,
                    b * resources + r, BigInteger.ONE), BigInteger.ONE));
        }
        Collections.shuffle(rows, new Random(8911 + order));
        var low = new BigInteger[groups * resources];
        var high = low.clone();
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.ONE);
        return new Model(rows, low, high);
    }

    private static void exhaustive() throws Exception {
        var random = new Random(775109);
        for (int sample = 0; sample < 240; sample++) {
            Model initial = coloring(3, 3, false, sample);
            var rows = new ArrayList<>(initial.rows);
            // Arbitrary missing edges and extra overlapping unit capacities
            // prevent an incomplete component from silently becoming a clique.
            rows.removeIf(r -> r.terms().size() == 2 && random.nextInt(4) == 0);
            for (int i = 0; i < 3; i++) {
                int a = random.nextInt(9), b = random.nextInt(9);
                if (a != b) rows.add(new ExactLinearProgram.Constraint(Map.of(a, BigInteger.ONE, b, BigInteger.ONE), BigInteger.ONE));
            }
            var low = initial.low.clone();
            var high = initial.high.clone();
            if (sample % 3 == 0) high[random.nextInt(9)] = BigInteger.ZERO;
            if (sample % 5 == 0) low[random.nextInt(9)] = BigInteger.ONE;
            for (int i = 0; i < 9; i++) if (low[i].compareTo(high[i]) > 0) low[i] = high[i];
            var model = new Model(rows, low, high);
            var cuts = solve(model, sample % 7 == 0 ? 20000 : 64L << 20, sample % 11 == 0);
            for (int mask = 0; mask < 512; mask++) {
                var point = new BigInteger[9];
                boolean domain = true;
                for (int i = 0; i < 9; i++) {
                    point[i] = BigInteger.valueOf((mask >>> i) & 1);
                    domain &= point[i].compareTo(low[i]) >= 0 && point[i].compareTo(high[i]) <= 0;
                }
                if (domain && fits(rows, point)) check(fits(cuts, point), "Capacity removed an original feasible assignment: " + sample);
            }
        }
    }

    private static List<ExactLinearProgram.Constraint> solve(Model model, long memory, boolean archive) throws Exception {
        var budget = budget(memory);
        var journal = new CountProof.Journal(32L << 20);
        budget.proofJournal(journal);
        List<ExactLinearProgram.Constraint> cuts;
        try (var hall = new CountHall(model.rows, model.low, model.high, budget)) {
            while (!hall.step()) {}
            cuts = hall.cuts();
        }
        check(budget.reservedBytes() == 0, "Hall workspace leaked");
        check(!journal.truncated(), "Truncated capacity proof");
        if (archive) {
            Path path = Files.createTempFile("cgse-hall-capacity", ".proof");
            try { journal.write(path); journal = CountProof.read(path); }
            finally { Files.delete(path); }
        }
        var established = new HashSet<>(model.rows.stream().map(CountProof::row).toList());
        for (int i = 0; i < model.low.length; i++) {
            established.add(new CountProof.Row(Map.of(i, BigInteger.ONE.negate()), model.low[i].negate()));
            established.add(new CountProof.Row(Map.of(i, BigInteger.ONE.negate()), BigInteger.ZERO));
            established.add(new CountProof.Row(Map.of(i, BigInteger.ONE), model.high[i]));
            established.add(new CountProof.Row(Map.of(i, BigInteger.ONE), BigInteger.ONE));
        }
        for (var proof : journal.cliques()) {
            check(established.containsAll(proof.axioms()), "Clique introduced an unsupported premise");
            check(CountProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "Clique certificate rejected");
            established.add(proof.consequence());
        }
        for (var proof : journal.derivations()) {
            check(established.containsAll(proof.axioms()), "Hall proof introduced an unsupported capacity");
            check(CountProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "Hall certificate rejected");
            proof.steps().forEach(step -> established.add(step.consequence()));
        }
        for (var cut : cuts) check(established.contains(CountProof.row(cut)), "Returned cut lacks a checked derivation");
        return cuts;
    }

    private static void boundaries() throws Exception {
        Model model = coloring(9, 8, false, 3);
        for (long bytes : new long[]{512, 65536, 131072}) solve(model, bytes, true);
        var budget = budget(64L << 20);
        var hall = new CountHall(model.rows, model.low, model.high, budget);
        try {
            budget.cancel();
            try { hall.step(); throw new AssertionError("Cancelled Hall search continued"); }
            catch (CancellationException expected) {}
        } finally { hall.close(); hall.close(); }
        check(budget.reservedBytes() == 0, "Cancelled capacity search leaked");
        // Interrupt inside adjacency construction, matching and certificate
        // checking, including nested optional reservations.
        for (int threshold : new int[]{800, 1400, 2200, 4500}) {
            PlanningBudget[] owner = {null};
            owner[0] = new PlanningBudget(0, 20_000_000, 64L << 20,
                    () -> owner[0] != null && owner[0].nodes() >= threshold, System::nanoTime);
            try (var interrupted = new CountHall(model.rows, model.low, model.high, owner[0])) {
                try { while (!interrupted.step()) {} throw new AssertionError("Expected in-flight cancellation"); }
                catch (CancellationException expected) {}
            }
            check(owner[0].reservedBytes() == 0, "In-flight cancellation leaked: " + threshold);
            var shared = budget(64L << 20);
            try (var limited = new CountHall(model.rows, model.low, model.high, shared)) {
                // Another arm can consume the request after local admission.
                shared.charge(20_000_000 - threshold);
                try { while (!limited.step()) {} throw new AssertionError("Expected shared-work cutoff"); }
                catch (PlanningBudget.Exhausted expected) {}
            }
            check(shared.reservedBytes() == 0, "In-flight work cutoff leaked: " + threshold);
        }
    }

    private static boolean fits(List<ExactLinearProgram.Constraint> rows, BigInteger[] values) {
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static PlanningBudget budget(long memory) { return new PlanningBudget(0, 20_000_000, memory, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
