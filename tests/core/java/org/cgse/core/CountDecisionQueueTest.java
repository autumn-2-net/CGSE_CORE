// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Linear-selection and exhaustive-model oracles, including retained undo/rekey. */
public final class CountDecisionQueueTest {
    private static final Field VALUES = field("values"), ACTIVITY = field("activity"), RATE = field("learningRate"),
            DECISIONS = field("decisions"), CONFLICTS = field("conflicts"), RESTARTS = field("restarts"),
            TRAIL = field("trail"), QUEUE = field("decisionQueue"), ATTEMPTED = field("queueAttempted"),
            INCREMENT = field("increment"), WORK = field("work");
    private static int selections, resumptions, models, queued, rescaled, imported;
    private static long conflicts, restarts, weightedCuts;
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}

    public static void main(String[] args) throws Exception {
        queueOracle();
        var journal = new CountProof.Journal(32L << 20);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var random = new Random(2026101101L);
            for (int sample = 0; sample < 160; sample++) {
                Model model = model(random, sample, 10, 64 + sample % 5);
                boolean expected = enumerable(model, 10);
                for (var branching : CountCdcl.Branching.values()) {
                    boolean found = solve(model, branching, sample % 3 == 0, sample % 5 == 0,
                            sample % 7 == 0, journal, worker);
                    check(found == expected, "Boolean answer disagrees with finite enumeration");
                }
            }
            // Larger planted models drive repeated conflicts, restarts and
            // both activity/learning-rate updates while the queue is live.
            for (int sample = 0; sample < 18; sample++) {
                Model model = model(random, 2 * sample, 80, 96);
                for (var branching : CountCdcl.Branching.values())
                    check(solve(model, branching, true, true, true, journal, worker), "Lost planted Boolean witness");
            }
            for (int sample = 0; sample < 48; sample++) {
                Model model = weightedModel(random, sample);
                boolean expected = enumerable(model, 12);
                for (var branching : CountCdcl.Branching.values())
                    check(solve(model, branching, true, true, false, journal, worker) == expected,
                            "Weighted Boolean answer disagrees with enumeration");
            }
        } finally { worker.shutdownNow(); }
        var archive = Files.createTempFile("cgse-decision-queue-", ".bin");
        try {
            journal.write(archive);
            for (var entry : CountProof.read(archive).entries())
                check(CountProof.verify(entry, 20_000_000) == CountProof.Verdict.VERIFIED, "Queue search proof did not replay");
        } finally { Files.delete(archive); }
        refusalsAndLimits();
        selectionSavings();
        check(queued > 100 && selections > 1000 && resumptions > 0 && conflicts > 50 && restarts > 0 && rescaled > 0 && imported > 0 && weightedCuts > 0,
                "Missing retained/conflict/restart/rescale/import coverage");
        System.out.println("Decision queue: 50,000 independent priority operations; models=" + models + "; queued=" + queued +
                "; linear-oracle decisions=" + selections + "; conflicts=" + conflicts + "; restarts=" + restarts +
                "; resumptions=" + resumptions + "; rescalings=" + rescaled + "; imports=" + imported +
                "; weighted_cuts=" + weightedCuts +
                "; proofs, memory refusal, cancellation, work limits and cleanup passed");
    }

    private static void queueOracle() {
        var random = new Random(7719801);
        var keys = new HashMap<Integer, Double>();
        long[] charges = {0};
        var queue = new CountDecisionQueue(257, () -> charges[0]++);
        for (int operation = 0; operation < 50000; operation++) {
            if (random.nextInt(3) != 0) {
                int id = random.nextInt(257);
                double value = random.nextInt(31) * Math.pow(10, random.nextInt(5) - 2);
                queue.update(id, value);
                keys.put(id, value);
            } else {
                int expected = -1;
                for (var entry : keys.entrySet()) if (expected < 0 || entry.getValue() > keys.get(expected) ||
                        entry.getValue().equals(keys.get(expected)) && entry.getKey() < expected) expected = entry.getKey();
                check(queue.take() == expected, "Indexed queue lost a changed priority or id tie");
                keys.remove(expected);
            }
        }
        check(charges[0] > 50000, "Queue comparisons were not charged");
    }

    private static Model model(Random random, int sample, int core, int n) {
        var low = new BigInteger[n];
        var high = new BigInteger[n];
        var plant = new BigInteger[n];
        BigInteger offset = sample % 9 == 0 ? BigInteger.ONE.shiftLeft(90) : BigInteger.ZERO;
        Arrays.fill(low, offset);
        for (int i = 0; i < n; i++) {
            high[i] = i >= core && i % 3 == 0 ? offset : offset.add(BigInteger.ONE);
            plant[i] = low[i].add(random.nextBoolean() && !high[i].equals(low[i]) ? BigInteger.ONE : BigInteger.ZERO);
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0; r < 4 * core; r++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            int negatives = 0;
            while (terms.size() < 3) {
                int id = random.nextInt(core);
                if (terms.containsKey(id)) continue;
                int a = random.nextBoolean() ? 1 : -1;
                terms.put(id, BigInteger.valueOf(a));
                if (a < 0) negatives++;
            }
            BigInteger bound = BigInteger.valueOf(2 - negatives);
            for (var a : terms.values()) bound = bound.add(a.multiply(offset));
            BigInteger value = BigInteger.ZERO;
            for (var entry : terms.entrySet()) value = value.add(entry.getValue().multiply(plant[entry.getKey()]));
            if (sample % 2 == 0 && value.compareTo(bound) > 0) { r--; continue; }
            rows.add(new ExactLinearProgram.Constraint(terms, bound));
        }
        return new Model(rows, low, high);
    }

    private static boolean solve(Model model, CountCdcl.Branching branching, boolean sliced, boolean rescale,
                                 boolean importClause, CountProof.Journal journal, ExecutorService worker) throws Exception {
        var budget = new PlanningBudget(0, 20_000_000, 64L << 20, () -> false, System::nanoTime);
        budget.proofJournal(journal);
        boolean queuedHere = false, injected = false, importedHere = false, rescaledHere = false;
        try (var search = new CountCdcl(model.rows, model.low, model.high, budget, sliced ? 1024 : 2_000_000, branching).retained()) {
            for (;;) {
                int expected = linear(search, model, branching), before = DECISIONS.getInt(search);
                boolean done = search.step();
                if (DECISIONS.getInt(search) > before) {
                    List<?> trail = (List<?>) TRAIL.get(search);
                    check((int) trail.get(trail.size() - 1) / 2 == expected, "Decision differs from original linear selector");
                    selections++;
                }
                if (QUEUE.get(search) != null) {
                    queuedHere = true;
                    if (rescale && !injected) { INCREMENT.setDouble(search, 1e81); injected = true; }
                    else if (injected && INCREMENT.getDouble(search) < 1e80) rescaledHere = true;
                    if (importClause && !importedHere) {
                        var assumptions = new ArrayList<ExactLinearProgram.Constraint>();
                        for (var term : model.rows.get(0).terms().entrySet()) {
                            int id = term.getKey();
                            boolean positive = term.getValue().signum() > 0;
                            assumptions.add(new ExactLinearProgram.Constraint(Map.of(id, positive ? BigInteger.ONE.negate() : BigInteger.ONE),
                                    positive ? model.high[id].negate() : model.low[id]));
                        }
                        check(search.learn(new CountConflict(assumptions)), "Could not import original clause");
                        importedHere = true;
                        imported++;
                    }
                }
                if (!done) continue;
                if (search.paused()) {
                    // The queue is request-owned, independent of the thread
                    // which resumes the existing search object.
                    worker.submit(() -> search.resume(1024)).get(10, TimeUnit.SECONDS);
                    resumptions++;
                    continue;
                }
                BigInteger[] witness = search.counts();
                check(witness != null || search.infeasible(), "Small Boolean model unexpectedly unfinished");
                if (witness != null) {
                    check(satisfies(model.rows, witness), "Queue witness violates original rows");
                    for (int i = 0; i < witness.length; i++)
                        check(witness[i].compareTo(model.low[i]) >= 0 && witness[i].compareTo(model.high[i]) <= 0, "Original domain violation");
                }
                conflicts += CONFLICTS.getInt(search);
                restarts += RESTARTS.getInt(search);
                weightedCuts += ((List<?>) field("weightedRows").get(search)).size();
                models++;
                if (queuedHere) queued++;
                if (rescaledHere) rescaled++;
                return witness != null;
            }
        } finally { check(budget.reservedBytes() == 0, "Queue solve leaked memory"); }
    }

    private static int linear(CountCdcl search, Model model, CountCdcl.Branching branching) throws Exception {
        int[] values = (int[]) VALUES.get(search);
        double[] activity = (double[]) ACTIVITY.get(search), rate = (double[]) RATE.get(search);
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < values.length; i++) {
            double score = branching == CountCdcl.Branching.ACTIVITY ? activity[i] : rate[i] + 1e-8 * Math.min(activity[i], 1000);
            if (values[i] < 0 && !model.low[i].equals(model.high[i]) && (best < 0 || score > bestScore)) { best = i; bestScore = score; }
        }
        return best;
    }

    private static Model weightedModel(Random random, int sample) {
        int core = 12, n = 96;
        var low = new BigInteger[n]; var high = new BigInteger[n]; var plant = new BigInteger[n];
        BigInteger offset = sample % 5 == 0 ? BigInteger.ONE.shiftLeft(90) : BigInteger.ZERO;
        Arrays.fill(low, offset); Arrays.fill(high, offset.add(BigInteger.ONE));
        for (int i = 0; i < n; i++) plant[i] = low[i].add(random.nextBoolean() ? BigInteger.ONE : BigInteger.ZERO);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0; r < 24; r++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            while (terms.size() < 5) {
                int id = random.nextInt(core), coefficient = random.nextInt(17) - 8;
                if (coefficient != 0) terms.put(id, BigInteger.valueOf(coefficient));
            }
            BigInteger bound = BigInteger.valueOf(random.nextInt(5) - (sample % 2 == 0 ? 0 : 3));
            for (var entry : terms.entrySet()) bound = bound.add(entry.getValue().multiply(plant[entry.getKey()]));
            rows.add(new ExactLinearProgram.Constraint(terms, bound));
        }
        return new Model(rows, low, high);
    }

    private static void refusalsAndLimits() throws Exception {
        BigInteger[] low = new BigInteger[128], high = new BigInteger[128];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
        var budget = new PlanningBudget(0, 1_000_000, 4L << 20, () -> false, System::nanoTime);
        try (var search = new CountCdcl(List.of(), low, high, budget, 100_000).retained()) {
            long lease = budget.availableBytes();
            check(budget.tryReserve(lease), "Optional queue refusal setup");
            try { while (!search.step()) {} }
            finally { budget.release(lease); }
            check(ATTEMPTED.getBoolean(search) && QUEUE.get(search) == null && search.counts() != null,
                    "Optional queue refusal killed the underlying search");
        }
        check(budget.reservedBytes() == 0, "Refused queue leaked memory");
        for (boolean cancel : new boolean[] {false, true}) {
            var cancelled = new AtomicBoolean();
            var limited = new PlanningBudget(0, 100000, 4L << 20, cancelled::get, System::nanoTime);
            boolean stopped = false;
            try (var search = new CountCdcl(List.of(), low, high, limited, 100000).retained()) {
                for (;;) {
                    if (QUEUE.get(search) != null) {
                        if (cancel) cancelled.set(true);
                        else limited.charge(limited.remainingWork());
                    }
                    if (search.step()) { check(search.paused(), "Limited search produced a final answer"); search.resume(1024); }
                }
            } catch (CancellationException failure) { check(cancel, "Unexpected cancellation"); stopped = true; }
            catch (PlanningBudget.Exhausted failure) { check(!cancel && failure.limit() == PlanningBudget.Limit.SEARCH_LIMIT,
                    "Unexpected work boundary"); stopped = true; }
            check(stopped && limited.reservedBytes() == 0, "Limited/cancelled queue was not closed");
        }
    }

    private static void selectionSavings() throws Exception {
        long[] work = new long[2];
        for (int reference = 0; reference < 2; reference++) {
            var low = new BigInteger[1024]; var high = new BigInteger[1024];
            Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
            var budget = new PlanningBudget(0, 20_000_000, 64L << 20, () -> false, System::nanoTime);
            try (var search = new CountCdcl(List.of(), low, high, budget, 2_000_000).retained()) {
                if (reference == 1) ATTEMPTED.setBoolean(search, true);
                while (!search.step()) {}
                check(search.counts() != null && Arrays.stream(search.counts()).allMatch(BigInteger.ONE::equals), "Variable/tie ordering changed");
                work[reference] = WORK.getLong(search);
            }
            check(budget.reservedBytes() == 0, "Selection benchmark leaked memory");
        }
        check(work[0] * 10 < work[1], "Repeated full selection scans remain");
    }

    private static boolean enumerable(Model model, int variables) {
        var point = model.low.clone();
        for (int mask = 0; mask < 1 << variables; mask++) {
            for (int i = 0; i < variables; i++) point[i] = model.low[i].add(BigInteger.valueOf(mask >>> i & 1));
            if (satisfies(model.rows, point)) return true;
        }
        return false;
    }

    private static boolean satisfies(List<ExactLinearProgram.Constraint> rows, BigInteger[] point) {
        for (var row : rows) {
            BigInteger value = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) value = value.add(term.getValue().multiply(point[term.getKey()]));
            if (value.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static Field field(String name) {
        try { Field field = CountCdcl.class.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
