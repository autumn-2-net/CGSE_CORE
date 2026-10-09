// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Compare interrupted frontiers with exhaustive boxes and independent proof replay. */
public final class CountContinuationTest {
    private enum Engine { MITM, SEPARATOR, DIAGRAM, DOMAIN, BOOLEAN }
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}
    private record Result(BigInteger[] counts, boolean impossible, long work, int pauses) {}

    public static void main(String[] args) throws Exception {
        int cases = 0, pauses = 0, impossible = 0;
        for (Engine engine : Engine.values()) for (int seed = 0; seed < 96; seed++) {
            Model model = model(seed, engine == Engine.BOOLEAN);
            boolean feasible = enumerate(model, model.low.clone(), 0);
            if (!feasible) impossible++;
            for (int permutation = 0; permutation < 2; permutation++) {
                var rows = new ArrayList<>(model.rows);
                Collections.shuffle(rows, new Random(971L * seed + permutation));
                Model input = new Model(rows, model.low, model.high);
                Result sliced = run(engine, input, 2048, true);
                Result whole = run(engine, input, 500_000, false);
                check((sliced.counts != null) == feasible && sliced.impossible == !feasible,
                        engine + " disagrees with exhaustive oracle at " + seed + "/" + permutation);
                check(Arrays.equals(sliced.counts, whole.counts) && sliced.impossible == whole.impossible,
                        engine + " changed its search after a handoff");
                pauses += sliced.pauses;
                cases++;
            }
        }
        check(pauses > 100 && impossible > 50, "Campaign did not exercise enough pauses and contradictions");
        booleanFrontiers();
        lazyAndInterface();
        limits();
        scopeAndOwnership();
        sharedBudget();
        System.out.println("Continuation oracle: " + cases + " engine/order cases; pauses=" + pauses +
                "; infeasible boxes=" + impossible + "; independent proofs, ownership, cancellation and parallel budgets passed");
    }

    private static CountContinuation search(Engine engine, Model model, PlanningBudget budget, long quantum) {
        return switch (engine) {
            case MITM -> new CountMeetInMiddle(model.rows, model.low, model.high, budget, quantum).retained();
            case SEPARATOR -> new CountSeparator(model.rows, model.low, model.high, budget, quantum).retained();
            case DIAGRAM -> new CountDecisionDiagram(model.rows, model.low, model.high, budget, quantum).retained();
            case DOMAIN -> new CountDomainSearch(model.rows, model.low, model.high, budget, quantum).retained();
            case BOOLEAN -> new CountBoolean(model.rows, model.low, model.high, budget).retained();
        };
    }

    private static boolean impossible(CountContinuation search) {
        if (search instanceof CountMeetInMiddle value) return value.infeasible();
        if (search instanceof CountSeparator value) return value.infeasible();
        if (search instanceof CountDecisionDiagram value) return value.infeasible();
        if (search instanceof CountDomainSearch value) return value.infeasible();
        return ((CountBoolean) search).infeasible();
    }

    private static Result run(Engine engine, Model model, long quantum, boolean proof) throws Exception {
        var budget = new PlanningBudget(0, 16_000_000, 64L << 20, () -> false, System::nanoTime);
        var journal = new CountProof.Journal(32L << 20);
        if (proof) budget.proofJournal(journal);
        int pauses = 0;
        Result result;
        try (var search = search(engine, model, budget, quantum)) {
            for (;;) {
                while (!search.step()) {}
                if (!search.paused()) break;
                check(!impossible(search) && search.counts() == null, "Pause escaped as a conclusion");
                long work = budget.searchWork(), bytes = budget.reservedBytes();
                for (int i = 0; i < 3; i++) check(search.step(), "Paused frontier ran without a new quantum");
                check(work == budget.searchWork() && bytes == budget.reservedBytes(), "Paused polling charged or allocated");
                search.resume(quantum);
                check(++pauses < 8192, "Continuation made no progress");
            }
            if (search.counts() != null) check(valid(model, search.counts()), "Witness escaped original coordinates");
            result = new Result(search.counts(), impossible(search), budget.searchWork(), pauses);
        }
        check(budget.reservedBytes() == 0, "Workspace leaked after continuation");
        for (var entry : journal.entries()) check(CountProof.verify(entry, 10_000_000) == CountProof.Verdict.VERIFIED,
                "Resumed proof failed independent replay: " + engine + "/" + entry.scope());
        return result;
    }

    private static Model model(int seed, boolean binary) {
        Random random = new Random(seed + 82119);
        int n = 6;
        BigInteger[] low = new BigInteger[n], high = new BigInteger[n], planted = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            low[i] = binary ? BigInteger.ZERO : BigInteger.valueOf(random.nextInt(5) - 2);
            if (!binary && seed % 7 == 0) low[i] = low[i].add(BigInteger.ONE.shiftLeft(80));
            int width = binary ? 1 : 1 + random.nextInt(3);
            high[i] = low[i].add(BigInteger.valueOf(width));
            planted[i] = low[i].add(BigInteger.valueOf(random.nextInt(width + 1)));
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0; r < 6; r++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            BigInteger bound = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                var a = BigInteger.valueOf(random.nextInt(9) - 4);
                if (seed % 11 == 0) a = a.shiftLeft(65);
                if (a.signum() != 0) terms.put(i, a);
                bound = bound.add(a.multiply(planted[i]));
            }
            if (r >= 2 && seed % 3 == 0) bound = bound.subtract(BigInteger.ONE);
            rows.add(new ExactLinearProgram.Constraint(terms, bound));
            if (r < 2) {
                var reverse = new LinkedHashMap<Integer, BigInteger>();
                terms.forEach((id, a) -> reverse.put(id, a.negate()));
                rows.add(new ExactLinearProgram.Constraint(reverse, bound.negate()));
            }
        }
        return new Model(rows, low, high);
    }

    private static boolean valid(Model model, BigInteger[] values) {
        for (int i = 0; i < values.length; i++)
            if (values[i].compareTo(model.low[i]) < 0 || model.high[i] != null && values[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static boolean enumerate(Model model, BigInteger[] values, int at) {
        if (at == values.length) return valid(model, values);
        for (var v = model.low[at]; v.compareTo(model.high[at]) <= 0; v = v.add(BigInteger.ONE)) {
            values[at] = v;
            if (enumerate(model, values, at + 1)) return true;
        }
        return false;
    }

    private static Model chain(int n) {
        var low = new BigInteger[n];
        var high = low.clone();
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.valueOf(31));
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 1; i < n; i++) {
            rows.add(new ExactLinearProgram.Constraint(Map.of(i - 1, BigInteger.valueOf(3), i, BigInteger.valueOf(5)), BigInteger.valueOf(71)));
            rows.add(new ExactLinearProgram.Constraint(Map.of(i - 1, BigInteger.valueOf(-3), i, BigInteger.valueOf(-5)), BigInteger.valueOf(-63)));
        }
        return new Model(rows, low, high);
    }

    private static void booleanFrontiers() throws Exception {
        int continued = 0;
        for (int seed : new int[]{8, 17, 22, 32, 38}) {
            Random random = new Random(31241 + seed);
            int n = 64;
            BigInteger[] low = new BigInteger[n], high = new BigInteger[n], planted = new BigInteger[n];
            Arrays.fill(low, BigInteger.ZERO);
            Arrays.fill(high, BigInteger.ONE);
            for (int i = 0; i < n; i++) planted[i] = random.nextBoolean() ? BigInteger.ONE : BigInteger.ZERO;
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int clause = 0; clause < 270; clause++) {
                int[] ids = new int[3];
                boolean[] positive = new boolean[3];
                boolean satisfied = false;
                for (int i = 0; i < 3; i++) {
                    do { ids[i] = random.nextInt(n); }
                    while (i > 0 && ids[i] == ids[0] || i > 1 && ids[i] == ids[1]);
                    positive[i] = random.nextBoolean();
                    satisfied |= (planted[ids[i]].signum() > 0) == positive[i];
                }
                if (!satisfied) positive[0] = !positive[0];
                var terms = new LinkedHashMap<Integer, BigInteger>();
                int upper = -1;
                for (int i = 0; i < 3; i++) {
                    terms.put(ids[i], positive[i] ? BigInteger.ONE.negate() : BigInteger.ONE);
                    if (!positive[i]) upper++;
                }
                rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(upper)));
            }
            Model model = new Model(rows, low, high);
            check(valid(model, planted), "Invalid planted Boolean oracle");
            // These five first visits used to lose the trail and every learned clause.
            var budget = new PlanningBudget(0, 16_000_000, 64L << 20, () -> false, System::nanoTime);
            try (var original = new CountBoolean(rows, low, high, budget)) {
                while (!original.step()) {}
                check(original.counts() == null && !original.infeasible(), "Regression no longer reaches the Boolean cutoff");
            }
            check(budget.reservedBytes() == 0, "One-shot control leaked");
            for (int permutation = 0; permutation < 3; permutation++) {
                var shuffled = new ArrayList<>(rows);
                if (permutation > 0) Collections.shuffle(shuffled, new Random(71L * seed + permutation));
                Result result = run(Engine.BOOLEAN, new Model(shuffled, low, high), 8192, true);
                check(result.counts != null, "Retained Boolean lost a planted witness");
                if (result.pauses > 0) continued++;
            }
        }
        check(continued >= 5, "Boolean campaign never continued the old cutoffs");
    }

    private static void lazyAndInterface() throws Exception {
        var low = new BigInteger[128];
        var high = low.clone();
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.ONE);
        high[0] = null;
        var positive = new LinkedHashMap<Integer, BigInteger>();
        var negative = new LinkedHashMap<Integer, BigInteger>();
        for (int i = 0; i < low.length; i++) { positive.put(i, BigInteger.ONE); negative.put(i, BigInteger.ONE.negate()); }
        Model lazy = new Model(List.of(new ExactLinearProgram.Constraint(positive, BigInteger.valueOf(64)),
                new ExactLinearProgram.Constraint(negative, BigInteger.valueOf(-64))), low, high);
        Result result = run(Engine.DOMAIN, lazy, 2048, true);
        check(result.counts != null && result.pauses > 0, "Wrapper discarded its lazy integer frontier");

        // One narrow interface connects wide blocks. Pause the parent while a
        // local child is still live; preserve both the memo table and its trail.
        low = new BigInteger[25];
        high = low.clone();
        var witness = low.clone();
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.valueOf(50));
        high[0] = BigInteger.valueOf(3);
        witness[0] = BigInteger.TWO;
        Random random = new Random(38);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int block = 0; block < 3; block++) {
            for (int i = 0; i < 8; i++) witness[1 + 8 * block + i] = BigInteger.valueOf(random.nextInt(6));
            for (int equation = 0; equation < 2; equation++) {
                var terms = new LinkedHashMap<Integer, BigInteger>();
                terms.put(0, BigInteger.valueOf(1 + random.nextInt(7)));
                BigInteger rhs = terms.get(0).multiply(witness[0]);
                for (int i = 0; i < 8; i++) {
                    int id = 1 + 8 * block + i;
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
        Model model = new Model(rows, low, high);
        check(valid(model, witness), "Invalid interface witness");
        result = run(Engine.SEPARATOR, model, 4096, true);
        check(result.counts != null && result.pauses > 0, "Parent cutoff discarded interface continuations");
    }

    private static void limits() {
        for (Engine engine : Engine.values()) for (long bytes : new long[]{1024, 16384, 65536, 1L << 20}) {
            var budget = new PlanningBudget(0, 1_000_000, bytes, () -> false, System::nanoTime);
            var search = search(engine, model(4, engine == Engine.BOOLEAN), budget, 2048);
            try {
                while (!search.step()) {}
            } catch (PlanningBudget.Exhausted expected) {
                check(expected.limit() == PlanningBudget.Limit.MEMORY_LIMIT, "Wrong memory outcome");
            } finally { search.close(); search.close(); }
            check(budget.reservedBytes() == 0, "Limited frontier leaked");
        }
        for (boolean cancelled : new boolean[]{false, true}) {
            var stop = new AtomicBoolean();
            var budget = new PlanningBudget(0, 4_000_000, 64L << 20, stop::get, System::nanoTime);
            Model model = chain(40);
            try (var search = search(Engine.SEPARATOR, model, budget, 2048)) {
                while (!search.step()) {}
                check(search.paused(), "Expected a retained table");
                if (cancelled) stop.set(true); else budget.charge(budget.remainingWork());
                try { search.resume(Long.MAX_VALUE); throw new AssertionError("Exhausted/cancelled resume succeeded"); }
                catch (CancellationException expected) { check(cancelled, "Unexpected cancellation"); }
                catch (PlanningBudget.Exhausted expected) { check(!cancelled, "Cancellation became budget exhaustion"); }
            }
            check(budget.reservedBytes() == 0, "Stopped table leaked");
        }
        var budget = new PlanningBudget(0, Long.MAX_VALUE, 64L << 20, () -> false, System::nanoTime);
        check(CountContinuation.deadline(Long.MAX_VALUE - 2, Long.MAX_VALUE, budget) == Long.MAX_VALUE,
                "Continuation deadline overflowed");
    }

    private static void scopeAndOwnership() {
        Model model = chain(40);
        var budget = new PlanningBudget(0, 16_000_000, 64L << 20, () -> false, System::nanoTime);
        try (var reduction = new CountReduction(model.rows, model.low, model.high, budget)) {
            while (!reduction.step()) {}
            try (var models = CountModelViews.create(model.rows, model.low, model.high, budget);
                 var views = new CountViewSearch(models, budget)) {
                models.addReduced(reduction);
                var solver = new CountSeparator(reduction.rows(), reduction.lower(), reduction.upper(), budget, 2048).retained();
                while (!solver.step()) {}
                check(solver.paused(), "Scope test needs a paused table");
                var different = reduction.upper().clone();
                different[0] = different[0].add(BigInteger.ONE);
                check(!solver.matches(reduction.rows(), reduction.lower(), different), "Changed bounds reused a frontier");
                check(views.retain(reduction, solver), "Existing table was not adopted");
                check(!views.retain(reduction, solver), "Same table transferred twice");
                int turns = 0;
                do {
                    views.resume(65536);
                    while (!views.step()) {}
                } while (views.counts() == null && !views.infeasible() && views.retained() && ++turns < 64);
                check(views.counts() != null && valid(model, views.counts()), "Adopted frontier did not produce a valid original witness");
            }
        }
        check(budget.reservedBytes() == 0, "Transferred frontier leaked or released another owner's memory");
    }

    private static void sharedBudget() throws Exception {
        for (boolean expanded : new boolean[]{false, true}) {
            var budget = new PlanningBudget(0, PlanningBudget.parallelWorkLimit(4_000_000, 4, expanded),
                    64L << 20, () -> false, System::nanoTime);
            var workers = Executors.newFixedThreadPool(4);
            var tasks = new ArrayList<Future<?>>();
            try {
                for (int seed = 0; seed < 16; seed++) {
                    int fixed = seed;
                    tasks.add(workers.submit(() -> {
                        Model model = chain(16 + fixed);
                        try (var search = search(Engine.SEPARATOR, model, budget, 2048)) {
                            do {
                                if (search.paused()) search.resume(2048);
                                while (!search.step()) {}
                            } while (search.paused());
                            check(search.counts() != null && valid(model, search.counts()), "Parallel continuation escaped its scope");
                        }
                    }));
                }
                for (var task : tasks) task.get(60, TimeUnit.SECONDS);
            } finally { workers.shutdownNow(); }
            check(budget.reservedBytes() == 0, "Parallel continuations leaked");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
