// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Original-domain enumeration checks every imported clause and restored DFS. */
public final class CountPackingConflictTest {
    private static int snapshots, clauses, continuations;
    private static long forced;

    public static void main(String[] args) throws Exception {
        try (var workers = new Worker()) {
            for (int trial = 0; trial < 256; trial++) exhaustive(trial, workers.executor);
            unitAndWordBoundary(workers.executor);
        }
        binarySnapshot();
        snapshotBoundary();
        branchBacktrack();
        scopeAndRefusal();
        ownerHandoff();
        spentFrontier();
        interruption(false);
        interruption(true);
        check(clauses > 100 && snapshots > 30 && forced > 0, "Did not exercise learned propagation");
        System.out.println("Packing conflict handoff: " + snapshots + " snapshots, " + clauses +
                " independently checked clauses, " + continuations + " continuations, " + forced +
                " implications; scope, word boundary, residual frontier and lifecycle passed");
    }

    private static final class Worker implements AutoCloseable {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        @Override public void close() { executor.shutdownNow(); }
    }

    private static void exhaustive(int trial, ExecutorService worker) throws Exception {
        var random = new Random(2026102093L + trial);
        int n = 7 + trial % 6, m = 3 + trial % 7;
        var a = new long[m][n]; var b = new long[m];
        long planted = random.nextInt(1 << n), seed = random.nextInt(1 << n);
        for (int r = 0; r < m; r++) {
            for (int i = 0; i < n; i++) {
                a[r][i] = random.nextInt(19) - 9;
                if ((planted & (1L << i)) != 0) b[r] += a[r][i];
            }
            b[r] += trial % 3 == 0 ? -random.nextInt(7) : random.nextInt(4);
        }
        var witnesses = new ArrayList<Long>();
        for (long value = 0; value < 1L << n; value++) if (valid(a, b, value)) witnesses.add(value);
        var budget = budget(() -> false);
        var journal = trial % 32 == 0 ? new CountProof.Journal(8L << 20) : null;
        if (journal != null) budget.proofJournal(journal);
        try (var learned = cdcl(a, b, budget);
             var original = new CountPackingSearch(a, b, seed, budget);
             var resumed = new CountPackingSearch(a, b, seed, budget)) {
            learned.preferPhases(seed);
            int steps = 0;
            while (!learned.step() && steps++ < 600) {}
            var masks = new long[64]; var ones = new long[64];
            int count = learned.copyBooleanConflicts(n, masks, ones);
            clauses += count;
            if (count != 0) snapshots++;
            for (int c = 0; c < count; c++) {
                check((ones[c] & ~masks[c]) == 0, "Malformed selected bits");
                for (long value : witnesses)
                    check((value & masks[c]) != ones[c], "Learned clause removed an original witness: " + trial);
            }
            for (int i = 0; i < trial % 29; i++) {
                boolean first = original.step(), second = resumed.step();
                check(first == second, "Different original prefix");
                if (first) break;
            }
            Object oldChoices = get(resumed, "choices"), oldSlacks = get(resumed, "slacks");
            int depth = (int) get(resumed, "depth");
            resumed.reuse(learned);
            check(get(resumed, "choices") == oldChoices && get(resumed, "slacks") == oldSlacks &&
                    (int) get(resumed, "depth") == depth, "Import rebuilt the retained DFS");
            Object imported = get(resumed, "conflicts");
            if (imported != null) {
                long bytes = budget.reservedBytes();
                resumed.reuse(learned);
                check(get(resumed, "conflicts") == imported && budget.reservedBytes() == bytes, "Repeated import renewed storage");
            }
            while (!original.step()) {}
            for (int turns = 0; ; turns++) {
                check(turns < 200000, "Continuation did not terminate");
                boolean done = trial % 32 == 0 ? worker.submit(resumed::step).get() : resumed.step();
                if (done) break;
            }
            check(original.feasible() == !witnesses.isEmpty() && resumed.feasible() == original.feasible(), "Changed exhaustive SAT status");
            if (resumed.feasible()) {
                check(valid(a, b, resumed.assignment()), "Invalid restored witness");
                check(resumed.assignment() == original.assignment(), "Changed first feasible Boolean assignment");
            }
            if (imported != null) forced += (long) get(imported, "forced");
            continuations++;
            if (journal != null) for (var entry : journal.entries())
                check(CountProof.verify(entry, 2_000_000) == CountProof.Verdict.VERIFIED, "Invalid source proof");
        }
        check(budget.reservedBytes() == 0, "Exhaustive search leaked");
    }

    private static long[][] unitRows(int n) {
        var a = new long[2][n];
        a[0][n - 1] = a[0][n - 2] = 1; // x + y <= 1
        a[1][n - 1] = 1; a[1][n - 2] = -1; // x <= y, hence x = 0
        return a;
    }

    private static void unitAndWordBoundary(ExecutorService worker) throws Exception {
        for (int n : new int[] {2, 31, 32, 63, 64}) {
            var a = unitRows(n); var b = new long[] {1, 0};
            var budget = budget(() -> false);
            try (var source = cdcl(a, b, budget);
                 var search = new CountPackingSearch(a, b, -1L, budget)) {
                source.preferPhases(-1L);
                while (!source.step()) {}
                search.reuse(source);
                var imported = get(search, "conflicts");
                check(imported != null, "Lost derived unit at width " + n);
                while (!worker.submit(search::step).get()) {}
                check(search.feasible() && valid(a, b, search.assignment()) &&
                        (search.assignment() & (1L << (n - 1))) == 0, "Lost high-bit implication");
                long implications = (long) get(imported, "forced");
                check(implications > 0, "Copied unit was never used");
                forced += implications;
            }
            check(budget.reservedBytes() == 0, "Word boundary leaked");
        }
    }

    private static void binarySnapshot() {
        var a = new long[][] {{-1, -1, 0}, {0, -1, -1}};
        var b = new long[] {-1, -1};
        var budget = budget(() -> false);
        try (var source = cdcl(a, b, budget)) {
            while (!source.step()) {}
            var masks = new long[64]; var ones = new long[64];
            int count = source.copyBooleanConflicts(3, masks, ones);
            boolean first = false, second = false;
            for (int c = 0; c < count; c++) {
                first |= masks[c] == 3 && ones[c] == 0;
                second |= masks[c] == 6 && ones[c] == 0;
                for (long value = 0; value < 8; value++)
                    if (valid(a, b, value)) check((value & masks[c]) != ones[c], "Invalid watched binary");
            }
            check(first && second, "Binary watch entries were omitted or reversed");
            check(source.copyBooleanConflicts(4, masks, ones) == 0, "Different coordinate width accepted");
        }
        check(budget.reservedBytes() == 0, "Binary snapshot leaked");
    }

    private static void branchBacktrack() {
        var a = new long[][] {{-1, -1}}; var b = new long[] {-1};
        var budget = budget(() -> false);
        try (var source = cdcl(a, b, budget)) {
            while (!source.step()) {}
            try (var copied = CountPackingConflicts.create(source, 2, 0, 1,
                    new long[] {3, 0, 0}, new long[3], new int[3], budget)) {
                check(copied != null, "Missing binary for sibling test");
                copied.branch(0, 0, true); // x=1 satisfies the disjunction.
                var forces = new long[2];
                check(copied.propagate(1, 2, forces) && forces[0] == 0 && forces[1] == 0,
                        "Satisfied clause imposed a sibling value");
                copied.branch(0, 0, false); // Returning to the same parent's x=0 branch.
                check(copied.propagate(1, 2, forces) && forces[0] == 0 && forces[1] == 2,
                        "Backtracking failed to restore the binary implication");
                copied.assign(1, 0, 2);
                Arrays.fill(forces, 0);
                check(copied.propagate(1, 0, forces), "Satisfied implication remained a conflict");
            }
        }
        check(budget.reservedBytes() == 0, "Sibling restoration leaked");
    }

    private static void snapshotBoundary() {
        int n = 64;
        var a = new long[n * (n - 1) / 2][n]; var b = new long[a.length];
        int row = 0;
        for (int x = 0; x < n; x++) for (int y = x + 1; y < n; y++) {
            a[row][x] = a[row][y] = 1; b[row++] = 1;
        }
        var budget = budget(() -> false);
        try (var source = cdcl(a, b, budget)) {
            while (!source.step()) {}
            var masks = new long[64]; var ones = new long[64];
            long before = budget.threadWork();
            int count = source.copyBooleanConflicts(n, masks, ones);
            check(count == 64 && budget.threadWork() - before <= 10240, "Snapshot exceeded its bounded selection opportunity");
            for (int c = 0; c < count; c++)
                check(Long.bitCount(masks[c]) == 2 && ones[c] == masks[c], "Large watch database changed a binary reason");
        }
        var lower = new BigInteger[] {BigInteger.TEN, BigInteger.ZERO};
        var upper = new BigInteger[] {BigInteger.valueOf(11), BigInteger.ONE};
        try (var offset = new CountCdcl(List.of(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), BigInteger.TEN)),
                lower, upper, budget, 65536).retained()) {
            check(offset.copyBooleanConflicts(2, new long[64], new long[64]) == 0, "Offset domain became Boolean bits");
        }
        check(budget.reservedBytes() == 0, "Bounded snapshot leaked");
    }

    private static void scopeAndRefusal() throws Exception {
        var a = unitRows(2); var b = new long[] {1, 0};
        var budget = budget(() -> false);
        try (var source = new CountPackingNeighborhood(a, b, -1, budget, true);
             var same = new CountPackingNeighborhood(a, b, -1, budget, false);
             var differentRows = new CountPackingNeighborhood(new long[][] {{-1, 0}, {0, -1}}, new long[] {-1, -1}, -1, budget, false);
             var copiedRows = new CountPackingNeighborhood(a.clone(), b, -1, budget, false);
             var copiedBounds = new CountPackingNeighborhood(a, b.clone(), -1, budget, false)) {
            while (!source.step()) {}
            for (var other : List.of(differentRows, copiedRows, copiedBounds)) {
                other.reuse(source);
                check(get(get(other, "propagation"), "conflicts") == null, "Cross-projection private clause escaped");
                while (!other.step()) {}
                check(other.feasible(), "Private clause removed an exterior witness");
            }
            check(differentRows.assignment() == 3, "Different face was affected by x=0");
            long occupied = budget.availableBytes();
            budget.reserve(occupied);
            same.reuse(source);
            check(get(get(same, "propagation"), "conflicts") == null, "Memory refusal changed the frontier");
            budget.release(occupied);
            same.reuse(source);
            check(get(get(same, "propagation"), "conflicts") != null, "Identical face refused its own clauses");
            while (!same.step()) {}
            check(same.feasible() && valid(a, b, same.assignment()), "Invalid same-face continuation");
        }
        check(budget.reservedBytes() == 0, "Scope/refusal leaked");
    }

    private static void spentFrontier() throws Exception {
        // x is coordinate zero here: the failed x=1 branch has already closed,
        // and only its x=0 sibling remains. All imported reasons concern x=1.
        var a = new long[][] {{1, 1}, {1, -1}}; var b = new long[] {1, 0};
        var budget = budget(() -> false);
        try (var source = cdcl(a, b, budget); var search = new CountPackingSearch(a, b, -1, budget)) {
            source.preferPhases(-1L);
            while (!source.step()) {}
            for (int turn = 0; ; turn++) {
                check(turn < 20, "Did not reach the unvisited sibling");
                search.step();
                int depth = (int) get(search, "depth");
                var choices = (int[]) get(search, "choices");
                if (depth == 1 && choices[0] == 2) break;
            }
            long memory = budget.reservedBytes();
            search.reuse(source);
            check(get(search, "conflicts") == null && budget.reservedBytes() == memory, "Stored clauses excluded from the remaining frontier");
            while (!search.step()) {}
            check(search.feasible() && valid(a, b, search.assignment()), "Discarded useful remaining subtree");
        }
        check(budget.reservedBytes() == 0, "Residual filtering leaked");
    }

    private static void ownerHandoff() throws Exception {
        int n = 64;
        var low = new BigInteger[n]; var high = new BigInteger[n];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
        var random = new Random(161017);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        var demand = new TreeMap<Integer, BigInteger>();
        var target = BigInteger.ZERO;
        for (int r = 0; r < 5; r++) {
            var terms = new TreeMap<Integer, BigInteger>(); var bound = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                var a = BigInteger.valueOf(1 + random.nextInt(100));
                terms.put(i, a); demand.merge(i, a.negate(), BigInteger::add);
                if (i % 2 == 0) bound = bound.add(a);
            }
            rows.add(new ExactLinearProgram.Constraint(terms, bound)); target = target.add(bound);
        }
        rows.add(new ExactLinearProgram.Constraint(demand, target.negate()));
        var view = new CountModelViews.View("conflict-handoff", rows, low, high,
                new CountModelViews.Shape(n, 6L * n, n, n), null, CountModelViews.Semantics.EQUIVALENT, null);
        var budget = budget(() -> false);
        try (var owner = CountPackingCore.create(view, new CountModelViews.Domains(low, high, 0), budget, Long.MAX_VALUE)) {
            check(owner != null, "Owner fixture was declined");
            while (get(owner, "search") == null) check(!owner.step(), "Owner closed before its retained frontier");
            Object original = get(owner, "search");
            long ceiling = (long) get(owner, "maximumWork");
            // Only the scheduling boundary is controlled. The repair itself
            // learns from the actual compiled face and original Boolean rows.
            var park = CountPackingCore.class.getDeclaredMethod("parkSearch"); park.setAccessible(true);
            set(owner, "enumerationBase", park.invoke(owner));
            set(owner, "phase", CountPackingCore.Phase.CONFLICT_REPAIR);
            var begin = CountPackingCore.class.getDeclaredMethod("beginCore"); begin.setAccessible(true); begin.invoke(owner);
            var repair = (CountPackingNeighborhood) get(owner, "search");
            for (int i = 0; i < 2000 && !repair.step(); i++) {}
            var resume = CountPackingCore.class.getDeclaredMethod("resumeEnumeration"); resume.setAccessible(true); resume.invoke(owner);
            check(get(owner, "search") == original && get(owner, "enumerationBase") == null &&
                    (long) get(owner, "maximumWork") == ceiling, "Owner replaced its stack or work ceiling");
            check(get(get(original, "propagation"), "conflicts") != null, "Owner discarded the paid repair's clauses");
            check(get(repair, "learning") == null, "Source repair remained live after copying its clauses");
        }
        check(budget.reservedBytes() == 0, "Owner handoff leaked");
    }

    private static void interruption(boolean workLimit) throws Exception {
        var stop = new AtomicBoolean();
        var budget = budget(stop::get);
        var a = unitRows(64); var b = new long[] {1, 0};
        try (var source = cdcl(a, b, budget); var search = new CountPackingSearch(a, b, -1, budget)) {
            source.preferPhases(-1L);
            while (!source.step()) {}
            if (workLimit) budget.charge(budget.remainingWork() - 4);
            else stop.set(true);
            try {
                search.reuse(source);
                throw new AssertionError("Import ignored interruption");
            } catch (PlanningBudget.Exhausted exhausted) {
                check(workLimit, "Unexpected work exhaustion");
            } catch (CancellationException cancelled) {
                check(!workLimit, "Unexpected cancellation");
            }
            check(get(search, "conflicts") == null, "Partial import published");
        }
        check(budget.reservedBytes() == 0, "Interrupted import leaked");
    }

    private static CountCdcl cdcl(long[][] a, long[] b, PlanningBudget budget) {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0; r < a.length; r++) {
            var terms = new TreeMap<Integer, BigInteger>();
            for (int i = 0; i < a[r].length; i++) if (a[r][i] != 0) terms.put(i, BigInteger.valueOf(a[r][i]));
            rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(b[r])));
        }
        var low = new BigInteger[a[0].length]; var high = low.clone();
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
        return new CountCdcl(rows, low, high, budget, 262144).retained();
    }

    private static boolean valid(long[][] a, long[] b, long mask) {
        for (int r = 0; r < a.length; r++) {
            long sum = 0;
            for (int i = 0; i < a[r].length; i++) if ((mask & (1L << i)) != 0) sum += a[r][i];
            if (sum > b[r]) return false;
        }
        return true;
    }
    private static PlanningBudget budget(java.util.function.BooleanSupplier stop) {
        return new PlanningBudget(0, 40_000_000, 64L << 20, stop, System::nanoTime);
    }
    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(object, value);
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
