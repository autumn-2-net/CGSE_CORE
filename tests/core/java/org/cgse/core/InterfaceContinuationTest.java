// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Planted wide-domain blocks that previously lost every locally paused frontier. */
public final class InterfaceContinuationTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                         BigInteger[] witness) {}
    private static int solved, unresolved;

    public static void main(String[] args) throws Exception {
        Random order = new Random(71029);
        for (int seed = 0; seed < 64; seed++) {
            Model model = planted(seed, 8, 3);
            check(valid(model, model.witness), "Planted witness does not satisfy the independent rows");
            for (int permutation = 0; permutation < 3; permutation++) {
                Model input = permutation == 0 ? model : shuffle(model, order);
                var budget = budget(64L << 20, new AtomicBoolean());
                try (var search = new CountInterfaceSearch(input.rows, input.low, input.high, budget, 200_000)) {
                    while (!search.step()) {}
                    var result = search.counts();
                    if (result != null) {
                        check(valid(input, result), "Resumed component escaped its model or interface scope");
                        solved++;
                    } else unresolved++;
                    if (permutation == 0 && Set.of(13, 38, 39, 49).contains(seed)) {
                        check(result != null, "Recorded interface witness was lost: " + seed + " " + budget.diagnostics());
                        if (seed != 13) check(search.resumptions() > 0, "Recorded case did not exercise continuation");
                    }
                }
                check(budget.reservedBytes() == 0, "Conditional search leaked after close");
            }
        }
        cancellation();
        memoryPressure();
        proofs();
        sparseStars();
        sharedParallelBudget();
        System.out.println("Interface continuation: 192 planted/order cases, witnesses=" + solved +
                ", unresolved=" + unresolved + "; four retained witness regressions; scoped proofs, cancellation and eviction passed");
    }

    private static void sharedParallelBudget() throws Exception {
        for (boolean expanded : new boolean[]{false, true}) {
            var budget = new PlanningBudget(0, PlanningBudget.parallelWorkLimit(4_000_000, 4, expanded),
                    64L << 20, () -> false, System::nanoTime);
            var workers = Executors.newFixedThreadPool(4);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            try {
                for (int seed : new int[]{13, 38, 39, 49}) tasks.add(workers.submit(() -> {
                    Model model = planted(seed, 8, 3);
                    start.await();
                    try (var search = new CountInterfaceSearch(model.rows, model.low, model.high, budget, 200_000)) {
                        while (!search.step()) {}
                        check(search.counts() != null && valid(model, search.counts()), "Parallel state or quota escaped its model");
                    }
                    return null;
                }));
                start.countDown();
                for (var task : tasks) task.get(60, TimeUnit.SECONDS);
            } finally { workers.shutdownNow(); }
            check(budget.reservedBytes() == 0, "Shared parallel request leaked retained state");
        }
    }

    private static void sparseStars() {
        // Many eligible Boolean vertices around one linking variable, with
        // wide internal counts. Trial deletion of every vertex exhausted the
        // same cap before the largest model reached a block.
        for (int blocks : new int[]{8, 16, 32, 60}) {
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            var low = new BigInteger[blocks * 4 + 1];
            var high = low.clone();
            var witness = low.clone();
            Arrays.fill(low, BigInteger.ZERO);
            Arrays.fill(high, BigInteger.ONE);
            Arrays.fill(witness, BigInteger.ZERO);
            for (int block = 0; block < blocks; block++) {
                int first = 1 + block * 4;
                high[first] = BigInteger.valueOf(100);
                witness[first + 1] = BigInteger.ONE;
                Map<Integer, BigInteger> terms = new LinkedHashMap<>();
                terms.put(0, BigInteger.ONE.negate());
                terms.put(first, BigInteger.TEN);
                for (int i = 1; i < 4; i++) terms.put(first + i, BigInteger.ONE);
                rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.ONE));
                var reverse = new LinkedHashMap<Integer, BigInteger>();
                terms.forEach((id, a) -> reverse.put(id, a.negate()));
                rows.add(new ExactLinearProgram.Constraint(reverse, BigInteger.ONE.negate()));
            }
            Model model = new Model(rows, low, high, witness);
            check(valid(model, witness), "Invalid star oracle");
            var budget = budget(64L << 20, new AtomicBoolean());
            try (var search = new CountInterfaceSearch(rows, low, high, budget, 20_000)) {
                while (!search.step()) {}
                check(search.counts() != null && valid(model, search.counts()),
                        "Sparse separator exceeded its fixed work cap: " + blocks + " " + budget.diagnostics());
            }
            check(budget.reservedBytes() == 0, "Sparse separator leaked");
        }
    }

    private static PlanningBudget budget(long memory, AtomicBoolean cancelled) {
        return new PlanningBudget(0, 4_000_000, memory, cancelled::get, System::nanoTime);
    }

    private static void cancellation() {
        Model model = planted(38, 8, 3);
        AtomicBoolean cancelled = new AtomicBoolean();
        var budget = budget(64L << 20, cancelled);
        var search = new CountInterfaceSearch(model.rows, model.low, model.high, budget, 200_000);
        try {
            while (search.resumptions() == 0 && !search.step()) {}
            check(search.resumptions() > 0, "No suspended state reached cancellation check");
            cancelled.set(true);
            try {
                search.step();
                throw new AssertionError("Cancellation was swallowed by the conditional solver");
            } catch (CancellationException expected) {
                // Cancellation is not UNSAT or a cached missing witness.
            }
        } finally {
            search.close();
            search.close();
        }
        check(budget.reservedBytes() == 0, "Cancellation leaked active or parked children");
    }

    private static void memoryPressure() {
        Model model = planted(38, 8, 31);
        long evictions = 0;
        for (long memory : new long[]{512, 32768, 65536, 131072, 262144, 1048576}) {
            var budget = budget(memory, new AtomicBoolean());
            try (var search = new CountInterfaceSearch(model.rows, model.low, model.high, budget, 200_000)) {
                try {
                    while (!search.step()) {}
                    if (search.counts() != null) check(valid(model, search.counts()), "Eviction produced an invalid witness");
                    evictions += search.evictions();
                } catch (PlanningBudget.Exhausted limit) {
                    check(limit.limit() == PlanningBudget.Limit.MEMORY_LIMIT, "Unexpected pressure limit");
                }
            }
            check(budget.reservedBytes() == 0, "Memory pressure leaked conditional state: " + memory);
        }
        check(evictions > 0, "Memory campaign never exercised paused-state eviction");
    }

    private static void proofs() throws Exception {
        for (int seed : new int[]{38, 39}) {
            Model model = planted(seed, 8, 3);
            var budget = budget(64L << 20, new AtomicBoolean());
            var journal = new CountProof.Journal(32L << 20);
            budget.proofJournal(journal);
            try (var search = new CountInterfaceSearch(model.rows, model.low, model.high, budget, 200_000)) {
                while (!search.step()) {}
                check(search.resumptions() > 0, "Proof run did not resume a scoped frontier");
                if (search.counts() != null) check(valid(model, search.counts()), "Proof mode changed witness scope");
            }
            check(!journal.entries().isEmpty(), "Missing conditional proof journal");
            for (var proof : journal.entries()) check(CountProof.verify(proof, 10_000_000) == CountProof.Verdict.VERIFIED,
                    "Conditional proof failed independent replay: " + proof.scope());
            check(budget.reservedBytes() == 0, "Proof mode leaked conditional state");
        }
    }

    private static Model planted(int seed, int count, int interfaceHigh) {
        Random random = new Random(seed);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        var low = new BigInteger[3 * count + 1];
        var high = low.clone();
        var witness = low.clone();
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.valueOf(50));
        high[0] = BigInteger.valueOf(interfaceHigh);
        witness[0] = BigInteger.TWO;
        for (int block = 0; block < 3; block++) {
            for (int i = 0; i < count; i++) witness[1 + block * count + i] = BigInteger.valueOf(random.nextInt(6));
            for (int equation = 0; equation < 2; equation++) {
                Map<Integer, BigInteger> terms = new LinkedHashMap<>();
                terms.put(0, BigInteger.valueOf(1 + random.nextInt(7)));
                BigInteger rhs = terms.get(0).multiply(witness[0]);
                for (int i = 0; i < count; i++) {
                    int id = 1 + block * count + i;
                    var a = BigInteger.valueOf(1 + random.nextInt(50));
                    terms.put(id, a);
                    rhs = rhs.add(a.multiply(witness[id]));
                }
                rows.add(new ExactLinearProgram.Constraint(terms, rhs));
                var reverse = new LinkedHashMap<Integer, BigInteger>();
                terms.forEach((id, a) -> reverse.put(id, a.negate()));
                rows.add(new ExactLinearProgram.Constraint(reverse, rhs.negate()));
            }
        }
        return new Model(rows, low, high, witness);
    }

    private static Model shuffle(Model model, Random random) {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < model.low.length; i++) ids.add(i);
        Collections.shuffle(ids, random);
        var low = model.low.clone();
        var high = low.clone();
        var witness = low.clone();
        for (int i = 0; i < ids.size(); i++) {
            low[ids.get(i)] = model.low[i];
            high[ids.get(i)] = model.high[i];
            witness[ids.get(i)] = model.witness[i];
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : model.rows) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            row.terms().forEach((id, a) -> terms.put(ids.get(id), a));
            rows.add(new ExactLinearProgram.Constraint(terms, row.upper()));
        }
        Collections.shuffle(rows, random);
        return new Model(rows, low, high, witness);
    }

    private static boolean valid(Model model, BigInteger[] values) {
        if (values.length != model.low.length) return false;
        for (int i = 0; i < values.length; i++) if (values[i].compareTo(model.low[i]) < 0 || values[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
