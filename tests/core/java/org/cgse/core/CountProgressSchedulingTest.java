// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Real numeric frontiers plus controlled scheduler observations, never synthetic proofs. */
public final class CountProgressSchedulingTest {
    public static void main(String[] args) throws Exception {
        eliminationEvidence();
        lpContinuation();
        largeCeilings();
        numericSelection();
        System.out.println("Progress scheduling: exact/redundant/fixed/trial elimination, LP continuation/plateau, " +
                "mandatory visits, work ceilings, proof eligibility, cancellation and cleanup passed");
    }

    private static void eliminationEvidence() {
        var independent = new ArrayList<ExactLinearProgram.Constraint>();
        equation(independent, 5, 2, 3, 0, 0);
        equation(independent, 7, 0, 2, 5, 0);
        checkLattice(independent, filled(4, 0), filled(4, 2), true);

        // Two syntactically different weighted rows, but only one rank gain.
        var duplicate = new ArrayList<ExactLinearProgram.Constraint>();
        equation(duplicate, 5, 2, 3, 0, 0);
        equation(duplicate, 10, 4, 6, 0, 0);
        checkLattice(duplicate, filled(4, 0), filled(4, 2), false);

        // Independent in ambient coordinates, dependent in the actual free box.
        var fixed = new ArrayList<ExactLinearProgram.Constraint>();
        equation(fixed, 10, 2, 3, 5, 0);
        equation(fixed, 17, 4, 6, 7, 0);
        var low = filled(4, 0);
        var high = filled(4, 2);
        low[2] = high[2] = BigInteger.ONE;
        low[3] = high[3] = BigInteger.ONE;
        checkLattice(fixed, low, high, false);

        // Exact rank is one. Trial faces may reduce the basis, but are not
        // evidence of a second exact balance and must not earn this preference.
        var trial = new ArrayList<ExactLinearProgram.Constraint>();
        equation(trial, 2, 1, 1, 0, 0);
        equation(trial, 3, 1, 1, 0, 1);
        trial.add(row(-2, -2, 0, -3, 0));
        trial.add(row(-3, 0, -3, -2, 0));
        low = filled(4, 0);
        high = filled(4, 2);
        low[3] = high[3] = BigInteger.ONE;
        checkLattice(trial, low, high, false);
    }

    private static void checkLattice(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                                     boolean expectedEvidence) {
        // Exhaust the tiny original box independently, before running the tool.
        boolean satisfiable = false;
        for (int encoded = 0; encoded < 81; encoded++) {
            var point = new BigInteger[4];
            int value = encoded;
            for (int i = 0; i < 4; i++, value /= 3) point[i] = BigInteger.valueOf(value % 3);
            satisfiable |= valid(rows, low, high, point);
        }
        check(satisfiable, "Bad planted rank fixture");
        var budget = budget(2_000_000);
        boolean observed = false;
        try (var search = new CountAffineLattice(rows, low, high, budget, 1024).retained()) {
            while (true) {
                boolean done = search.step();
                observed |= search.hasCoupledElimination();
                if (!expectedEvidence) check(!observed, "Redundant/fixed/trial rows earned exact-rank preference");
                if (!done) continue;
                if (!search.paused()) break;
                search.resume(17);
            }
            check(observed == expectedEvidence, "Lost real exact elimination");
            if (search.counts() != null) check(valid(rows, low, high, search.counts()), "Invalid lattice witness");
        }
        check(budget.reservedBytes() == 0, "Lattice evidence leaked");
    }

    private static void lpContinuation() throws Exception {
        var rows = weighted();
        var low = filled(32, 0);
        var high = filled(32, 1);
        var budget = budget(20_000_000);
        try (var reduction = new CountReduction(rows, low, high, budget)) {
            while (!reduction.step()) {}
            try (var search = CountLpSearch.create(reduction, 32, budget)) {
                check(search != null, "Missing weighted LP");
                long ceiling = number(search, "progressLimit");
                while (!search.step()) {}
                check(search.retained(), "Fixture needs an undecided retained LP");
                var lcg = (CountLcg) get(search, "search");
                check(lcg.cutProgress() > 0, "Fixture did not produce certified cuts");
                check(search.continueOnCuts(), "Certified cuts lost their continuation");
                long before = budget.nodes();
                while (!search.step()) {}
                check(budget.nodes() > before, "LP continuation did no charged work");
                if (search.counts() != null) check(valid(rows, low, high, search.counts()), "Invalid LP witness");
                check(search.retained(), "Fixture completed before plateau test");

                // Controlled completed-sample observation: no new cuts across
                // the last four samples. No row, certificate or domain is changed.
                set(search, "lastCuts", lcg.cutProgress());
                set(search, "idleProgressTurns", 3);
                check(!search.continueOnCuts() && search.retained(), "Plateau discarded or monopolized the frontier");
                search.resume(4096);
                while (!search.step()) {}
                check(!search.continueOnCuts(), "Ordinary resume renewed the spent preference");
                check(number(search, "progressLimit") == ceiling, "Parent resume replenished the work ceiling");

                // Isolate the cumulative ceiling independently of cut progress.
                set(search, "progressFinished", false);
                set(search, "lastCuts", 0L);
                set(search, "work", ceiling);
                check(!search.continueOnCuts(), "Productive cuts bypassed the fixed ceiling");
            }
        }
        check(budget.reservedBytes() == 0, "LP preference leaked");
    }

    private static void numericSelection() throws Exception {
        var rows = weighted();
        var low = filled(32, 0);
        var high = filled(32, 1);
        var budget = budget(20_000_000);
        try (var reduction = new CountReduction(rows, low, high, budget);
             var models = CountModelViews.create(rows, low, high, budget)) {
            while (!reduction.step()) {}
            check(models != null, "No model views");
            models.compileLight();
            try (var numeric = new CountDiophantine(reduction.rows(), reduction.lower(), reduction.upper(), budget, 2048).retained();
                 var search = new CountViewSearch(models, budget)) {
                do {
                    while (!numeric.step()) {}
                    check(numeric.paused(), "Numeric fixture finished before the retained checkpoint");
                    if (numeric.hasCoupledElimination()) break;
                    numeric.resume(512);
                } while (true);
                check(search.retain(reduction, numeric), "Failed to adopt the actual numeric frontier");
                search.resume(32768);
                var policy = (CountPortfolioPolicy) get(search, "policy");
                var arms = (List<?>) get(search, "searches");
                var numericArm = arms.stream().filter(a -> uncheckedGet(a, "specialist") == numeric).findFirst().orElseThrow();
                check(select(search) != numericArm, "Numeric preference suppressed mandatory first visits");
                for (Object arm : arms) {
                    var scheduling = (CountPortfolioPolicy.Arm) get(arm, "scheduling");
                    if (scheduling.eligible && policy.selections(scheduling) == 0) policy.selected(scheduling);
                }
                check(select(search) == numericArm, "Coupled exact elimination got no bounded continuation");
                long ceiling = number(numericArm, "continuationLimit");
                long checkpoint = number(numericArm, "continuationCheckpoint");
                var other = arms.stream().filter(a -> a != numericArm).findFirst().orElseThrow();
                ((CountPortfolioPolicy.Arm) get(other, "scheduling")).waiting = Integer.MAX_VALUE;
                // Inject accounting observations only, not algorithm state.
                set(numericArm, "work", checkpoint + 262144);
                check(select(search) == other, "Charged-work stagnation did not return to ordinary selection");
                set(numericArm, "progress", number(numericArm, "progress") + 1);
                check(select(search) == numericArm, "Real progress could not end the dry interval");
                set(numericArm, "work", ceiling);
                set(numericArm, "progress", number(numericArm, "progress") + 1);
                check(select(search) == other && number(numericArm, "continuationLimit") == ceiling,
                        "Numeric progress replenished its one-time ceiling");
                search.mode(CountPortfolioPolicy.Mode.PROOF);
                check(select(search) != numericArm, "Candidate-only numeric tool selected as a proof engine");
                budget.cancel();
                boolean cancelled = false;
                try { while (!search.step()) {} } catch (CancellationException expected) { cancelled = true; }
                check(cancelled, "Retained view ignored cancellation");
            }
        }
        check(budget.reservedBytes() == 0, "Numeric preference leaked");
    }

    private static void largeCeilings() throws Exception {
        for (long cap : new long[] { Integer.MAX_VALUE, Long.MAX_VALUE }) {
            var budget = budget(cap);
            try (var reduction = new CountReduction(weighted(), filled(32, 0), filled(32, 1), budget)) {
                while (!reduction.step()) {}
                try (var search = CountLpSearch.create(reduction, 32, budget)) {
                    check(search != null, "Large work cap disabled LP");
                    long ceiling = number(search, "progressLimit");
                    check(ceiling > 0 && ceiling <= cap / 2, "LP ceiling overflowed or grew its reserved share");
                    search.resume(Long.MAX_VALUE);
                    check(number(search, "progressLimit") == ceiling && number(search, "until") > 0,
                            "Large parent resume overflowed/replenished the grant");
                }
            }
            check(budget.reservedBytes() == 0, "Large-cap LP leaked");
        }
    }

    private static List<ExactLinearProgram.Constraint> weighted() {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        var random = new Random(17);
        for (int d = 0; d < 2; d++) {
            int[] coefficients = new int[32];
            int rhs = 0;
            for (int i = 0; i < coefficients.length; i++) {
                coefficients[i] = 1 + random.nextInt(1000);
                if (i % 3 == 0) rhs += coefficients[i];
            }
            equation(rows, rhs, coefficients);
        }
        return rows;
    }

    private static ExactLinearProgram.Constraint row(long bound, int... coefficients) {
        var terms = new LinkedHashMap<Integer, BigInteger>();
        for (int i = 0; i < coefficients.length; i++) if (coefficients[i] != 0) terms.put(i, BigInteger.valueOf(coefficients[i]));
        return new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(bound));
    }
    private static void equation(List<ExactLinearProgram.Constraint> rows, int bound, int... coefficients) {
        rows.add(row(bound, coefficients));
        rows.add(row(-bound, Arrays.stream(coefficients).map(v -> -v).toArray()));
    }
    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, BigInteger[] point) {
        for (int i = 0; i < point.length; i++) if (point[i].compareTo(low[i]) < 0 || point[i].compareTo(high[i]) > 0) return false;
        for (var row : rows) {
            var sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static Object select(CountViewSearch search) throws Exception {
        var method = CountViewSearch.class.getDeclaredMethod("select");
        method.setAccessible(true);
        return method.invoke(search);
    }
    private static Field field(Object object, String name) throws Exception { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field; }
    private static Object get(Object object, String name) throws Exception { return field(object, name).get(object); }
    private static Object uncheckedGet(Object object, String name) { try { return get(object, name); } catch (Exception failure) { throw new AssertionError(failure); } }
    private static void set(Object object, String name, Object value) throws Exception { field(object, name).set(object, value); }
    private static long number(Object object, String name) throws Exception { return ((Number) get(object, name)).longValue(); }
    private static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime); }
    private static BigInteger[] filled(int n, int value) { var result = new BigInteger[n]; Arrays.fill(result, BigInteger.valueOf(value)); return result; }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
