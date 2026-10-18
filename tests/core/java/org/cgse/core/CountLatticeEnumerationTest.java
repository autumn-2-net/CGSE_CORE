// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Original-coordinate oracles for candidate-only sphere/box search and retained ownership. */
public final class CountLatticeEnumerationTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}
    private record Result(List<BigInteger> counts, long work, long peak) {}

    public static void main(String[] args) throws Exception {
        smallOracles();
        jointBoxOracles();
        var original = residual(false);
        var whole = solve(original, 262144, null);
        var sliced = solve(original, 1, null);
        check(whole.counts.equals(sliced.counts), "Slicing changed the residual witness");
        check(whole.work < 1_000_000 && sliced.work < 1_500_000, "Residual box enumeration lost its bounded witness");
        var worker = Executors.newSingleThreadExecutor();
        try {
            check(solve(original, 127, null).counts.equals(solve(original, 127, worker).counts), "Worker handoff changed the witness");
        } finally { worker.shutdownNow(); }
        solve(residual(true), 113, null);
        limits(original);
        System.out.println("Lattice box enumeration: 48 exhaustive small oracles, large integer offsets/rows, residual witness, tiny grants, worker handoff and resource controls passed");
    }

    private static void smallOracles() {
        var basis = List.of(new BigInteger[]{bi(2), bi(-1), bi(0)}, new BigInteger[]{bi(1), bi(0), bi(-1)});
        var mu = new ExactRational[][]{{ExactRational.ONE, ExactRational.ZERO}, {q(2,5), ExactRational.ONE}};
        var norms = new ExactRational[]{q(5,1), q(6,5)};
        int feasible = 0, empty = 0;
        for (int seed = 0; seed < 48; seed++) {
            var random = new Random(719923 + seed);
            var low = new BigInteger[3]; var high = new BigInteger[3]; var point = new BigInteger[3];
            for (int i = 0; i < 3; i++) {
                var shift = seed % 3 == 0 ? BigInteger.ONE.shiftLeft(70).multiply(bi(i % 2 == 0 ? 1 : -1)) : BigInteger.ZERO;
                point[i] = shift;
                low[i] = shift.add(bi(random.nextInt(7) - 3)); high[i] = low[i].add(bi(random.nextInt(4)));
            }
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            var normal = Map.of(0, bi(1), 1, bi(2), 2, bi(1));
            var rhs = activity(normal, point);
            rows.add(new ExactLinearProgram.Constraint(normal, rhs));
            rows.add(new ExactLinearProgram.Constraint(Map.of(0, bi(-1), 1, bi(-2), 2, bi(-1)), rhs.negate()));
            // A valid lattice point can still violate an original side row.
            var scale = seed >= 24 ? BigInteger.ONE.shiftLeft(80) : BigInteger.ONE;
            rows.add(new ExactLinearProgram.Constraint(Map.of(0, scale), point[0].multiply(scale)));
            var model = new Model(rows, low, high);
            boolean expected = false;
            for (var a = low[0]; a.compareTo(high[0]) <= 0; a = a.add(BigInteger.ONE))
                for (var b = low[1]; b.compareTo(high[1]) <= 0; b = b.add(BigInteger.ONE))
                    for (var c = low[2]; c.compareTo(high[2]) <= 0; c = c.add(BigInteger.ONE))
                        expected |= valid(model, new BigInteger[]{a,b,c});
            var budget = budget(() -> false);
            try (var search = new CountLatticeEnumeration(rows, low, high, point, basis, mu, norms, budget)) {
                while (!search.step()) {}
                var found = search.counts();
                check((found != null) == expected, "Small exhaustive oracle mismatch " + seed);
                if (found != null) { check(valid(model, found), "Numerical hint escaped integer verification"); feasible++; }
                else empty++;
            }
            check(budget.reservedBytes() == 0, "Small enumeration retained memory");
        }
        check(feasible > 5 && empty > 5, "Small oracles lack positive or empty boxes");
    }

    private static Result solve(Model model, long quantum, ExecutorService worker) throws Exception {
        var budget = budget(() -> false); long peak = 0;
        try (var search = new CountAffineLattice(model.rows, model.low, model.high, budget, 32768).retained().enumerateBox()) {
            for (int visits = 0; ; visits++) {
                check(visits < 1_000_000, "Retained enumeration failed to advance");
                boolean done = worker == null ? search.step() : worker.submit(search::step).get(30, TimeUnit.SECONDS);
                peak = Math.max(peak, budget.reservedBytes());
                if (!done) continue;
                if (!search.paused()) break;
                search.resume(quantum);
            }
            check(search.counts() != null && valid(model, search.counts()), "Residual witness missing or invalid");
            check(budget.diagnostics().contains("count_affine_enum"), "New enumeration route not exercised");
            return new Result(List.of(search.counts().clone()), budget.nodes(), peak);
        } finally { check(budget.reservedBytes() == 0, "Residual enumeration or kernel leaked memory"); }
    }

    private static void jointBoxOracles() {
        int feasible = 0, empty = 0;
        for (int seed = 0; seed < 96; seed++) {
            int d = 2 + seed % 4, n = d + 1;
            var basis = new ArrayList<BigInteger[]>();
            var mu = new ExactRational[d][d]; var norms = new ExactRational[d];
            // b_j = e_j - e_last spans every integer vector with zero sum.
            // Its closed-form Gram factors do not use the production reducer.
            for (int j = 0; j < d; j++) {
                var vector = new BigInteger[n]; Arrays.fill(vector, BigInteger.ZERO);
                vector[j] = BigInteger.ONE; vector[n - 1] = BigInteger.ONE.negate(); basis.add(vector);
                Arrays.fill(mu[j], ExactRational.ZERO); mu[j][j] = ExactRational.ONE;
                for (int k = 0; k < j; k++) mu[j][k] = q(1, k + 2);
                norms[j] = q(j + 2, j + 1);
            }
            var random = new Random(3417781 + seed);
            var low = new BigInteger[n]; var high = new BigInteger[n]; var point = new BigInteger[n];
            var terms = new TreeMap<Integer, BigInteger>(); var reverse = new TreeMap<Integer, BigInteger>();
            for (int i = 0; i < n; i++) {
                point[i] = seed % 3 == 0 ? BigInteger.ONE.shiftLeft(70).multiply(bi(i % 2 == 0 ? 1 : -1)) : bi(0);
                low[i] = point[i].add(bi(random.nextInt(7) - 3)); high[i] = low[i].add(bi(random.nextInt(4)));
                terms.put(i, bi(1)); reverse.put(i, bi(-1));
            }
            var rhs = activity(terms, point);
            var model = new Model(List.of(new ExactLinearProgram.Constraint(terms, rhs),
                    new ExactLinearProgram.Constraint(reverse, rhs.negate()),
                    new ExactLinearProgram.Constraint(Map.of(0, bi(1), n - 1, bi(-2)), point[0].subtract(point[n - 1].multiply(bi(2))))), low, high);
            boolean expected = exhaustive(model, low.clone(), 0);
            var budget = budget(() -> false);
            try (var search = new CountLatticeEnumeration(model.rows, low, high, point, basis, mu, norms, budget)) {
                while (!search.step()) {}
                check((search.counts() != null) == expected, "Joint box oracle mismatch " + seed);
                if (search.counts() == null) empty++;
                else { feasible++; check(valid(model, search.counts()), "Joint box invalid witness"); }
            }
            check(budget.reservedBytes() == 0, "Joint box workspace leaked");
        }
        check(feasible > 10 && empty > 10, "Joint boxes lack feasible or empty scopes");
        System.out.println("Joint box distance: 96 original-coordinate exhaustive oracles in 3 through 6 dimensions passed");
    }

    private static boolean exhaustive(Model model, BigInteger[] values, int index) {
        if (index == values.length) return valid(model, values);
        for (var v = model.low[index]; v.compareTo(model.high[index]) <= 0; v = v.add(BigInteger.ONE)) {
            values[index] = v; if (exhaustive(model, values, index + 1)) return true;
        }
        return false;
    }

    private static void limits(Model model) {
        var cancelled = new AtomicBoolean(); var budget = budget(cancelled::get);
        try (var search = new CountAffineLattice(model.rows, model.low, model.high, budget, 32768).retained().enumerateBox()) {
            while (!budget.diagnostics().contains("count_affine_enum")) {
                if (!search.step()) continue;
                check(search.paused(), "Enumeration not reached before cancellation"); search.resume(4096);
            }
            cancelled.set(true); boolean stopped = false;
            try { search.step(); } catch (CancellationException expected) { stopped = true; }
            check(stopped, "Enumeration ignored request cancellation");
        }
        check(budget.reservedBytes() == 0, "Cancellation leaked borrowed basis memory");
        var refused = budget(() -> false); long held = refused.availableBytes(); check(refused.tryReserve(held), "Guard reservation");
        var basis = List.of(new BigInteger[]{bi(1),bi(0)}, new BigInteger[]{bi(0),bi(1)});
        try (var search = new CountLatticeEnumeration(List.of(), new BigInteger[]{bi(0),bi(0)}, new BigInteger[]{bi(1),bi(1)},
                new BigInteger[]{bi(0),bi(0)}, basis, new ExactRational[][]{{q(1,1),q(0,1)},{q(0,1),q(1,1)}},
                new ExactRational[]{q(1,1),q(1,1)}, refused)) {
            check(search.step() && search.counts() == null, "Memory refusal invented a witness");
        } finally { refused.release(held); }
        check(refused.reservedBytes() == 0, "Memory refusal leaked");
        // A valid integer basis can have factors outside double range. A
        // declined numerical hint must remain an absent candidate, even when
        // the original box contains feasible lattice points.
        var huge = BigInteger.ONE.shiftLeft(1023); var norm = huge.multiply(huge).add(BigInteger.ONE);
        var numeric = budget(() -> false);
        var hugeBasis = List.of(new BigInteger[]{huge,bi(1)},new BigInteger[]{bi(1),bi(0)});
        try (var search = new CountLatticeEnumeration(List.of(),new BigInteger[]{bi(0),bi(0)},new BigInteger[]{bi(1),bi(1)},
                new BigInteger[]{bi(0),bi(0)},hugeBasis,
                new ExactRational[][]{{q(1,1),q(0,1)},{new ExactRational(huge,norm),q(1,1)}},
                new ExactRational[]{ExactRational.of(norm),new ExactRational(BigInteger.ONE,norm)},numeric)) {
            while (!search.step()) {}
            check(search.counts() == null,"Nonfinite basis should decline optional enumeration");
        }
        check(numeric.reservedBytes() == 0,"Numerical refusal leaked");
    }

    // Residual of the maintained manual_small_n40_c3_d3_a1_s43 allocation.
    // These are synthetic public test coefficients, not player evidence.
    private static Model residual(boolean shifted) {
        int[] highValues = {0,1,3,1,1,3,3,0,0,0,1,3,0,1,1,1,1,1,3,1,0,2,2,1,3,0,3,0,2,3,2,2,0,1,1,1,0,2,3,0};
        int[][] weights = {
                {144,165,171,181,191,205,206,219,229,256,266,267,307,337,354,377,454,470,474,511,527,540,589,593,626,688,730,753,803,806,811,819,858,860,866,877,895,921,936,990},
                {183,525,130,60,551,243,386,629,11,7,960,609,741,321,193,746,602,514,430,143,443,653,187,354,813,716,35,18,397,663,475,903,664,170,563,834,681,798,544,425},
                {705,855,869,872,183,45,436,954,748,112,773,601,493,268,796,191,39,63,305,118,591,50,386,270,748,812,96,792,716,921,187,654,814,304,625,599,749,747,191,617}};
        int[] targets = {16276,14467,13544};
        var low = new BigInteger[40]; var high = new BigInteger[40]; var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < 40; i++) {
            low[i] = shifted ? BigInteger.ONE.shiftLeft(70).multiply(bi(i % 2 == 0 ? 1 : -1)) : BigInteger.ZERO;
            high[i] = low[i].add(bi(highValues[i]));
        }
        for (int d = 0; d < weights.length; d++) {
            var terms = new TreeMap<Integer,BigInteger>(); var reverse = new TreeMap<Integer,BigInteger>();
            for (int i = 0; i < 40; i++) { terms.put(i,bi(weights[d][i])); reverse.put(i,bi(-weights[d][i])); }
            var rhs = bi(targets[d]).add(activity(terms, low));
            rows.add(new ExactLinearProgram.Constraint(terms,rhs)); rows.add(new ExactLinearProgram.Constraint(reverse,rhs.negate()));
        }
        return new Model(rows,low,high);
    }
    private static boolean valid(Model model, BigInteger[] values) {
        for (int i = 0; i < values.length; i++) if (values[i].compareTo(model.low[i]) < 0 || values[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) if (activity(row.terms(), values).compareTo(row.upper()) > 0) return false;
        return true;
    }
    private static BigInteger activity(Map<Integer,BigInteger> terms, BigInteger[] values) {
        var total = BigInteger.ZERO;
        for (var term : terms.entrySet()) total = total.add(term.getValue().multiply(values[term.getKey()]));
        return total;
    }
    private static PlanningBudget budget(java.util.function.BooleanSupplier cancelled) { return new PlanningBudget(0,8_000_000,64L<<20,cancelled,System::nanoTime); }
    private static BigInteger bi(long value) { return BigInteger.valueOf(value); }
    private static ExactRational q(long a, long b) { return new ExactRational(bi(a),bi(b)); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
