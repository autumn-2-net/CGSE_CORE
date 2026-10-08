package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent truth-table and lifecycle checks; UNKNOWN is allowed for this heuristic. */
public final class NeighborhoodIndependentSafety {
    static long checks, assignments, models, oracleSat, witnesses, proofs, restrictedProofs;
    static long cancelled, exhausted, declined, handoffs, retained, paused, maxStep, wideWins;
    static BigInteger b(long x) { return BigInteger.valueOf(x); }
    static void check(boolean x, String message) { checks++; if (!x) throw new AssertionError(message); }
    static Object get(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, ExactRational[] point) {}
    static boolean valid(Model m, BigInteger[] x) {
        if (x.length != m.low.length) return false;
        for (int i = 0; i < x.length; i++) if (x[i] == null || x[i].compareTo(m.low[i]) < 0 ||
                m.high[i] != null && x[i].compareTo(m.high[i]) > 0) return false;
        for (var row : m.rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(x[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    static BigInteger[] enumerate(Model m, int id, BigInteger[] x) {
        if (id == x.length) { assignments++; return valid(m, x) ? x.clone() : null; }
        BigInteger[] found = null;
        for (BigInteger v = m.low[id]; v.compareTo(m.high[id]) <= 0; v = v.add(BigInteger.ONE)) {
            x[id] = v; BigInteger[] hit = enumerate(m, id + 1, x); if (hit != null) found = hit;
        }
        return found;
    }
    static void verifyJournal(CountProof.Journal journal) {
        for (var proof : journal.entries()) {
            check(CountProof.verify(proof, 8_000_000) == CountProof.Verdict.VERIFIED, "invalid neighborhood proof");
            proofs++;
            if (proof.closed()) restrictedProofs++;
        }
    }
    static void random(Random r, int test) throws Exception {
        int n = 2 + r.nextInt(4);
        BigInteger[] lo = new BigInteger[n], hi = new BigInteger[n], plant = new BigInteger[n];
        ExactRational[] point = new ExactRational[n];
        BigInteger shift = test % 31 == 0 ? BigInteger.TEN.pow(40) : b(r.nextInt(3));
        BigInteger scale = test % 17 == 0 ? BigInteger.TEN.pow(35) : BigInteger.ONE;
        for (int i = 0; i < n; i++) {
            lo[i] = shift.add(b(r.nextInt(3))); int span = r.nextInt(4); hi[i] = lo[i].add(b(span));
            plant[i] = lo[i].add(b(r.nextInt(span + 1)));
            int denominator = 2 + r.nextInt(2), numerator = r.nextInt(span * denominator + 1);
            point[i] = ExactRational.of(lo[i]).add(new ExactRational(b(numerator), b(denominator)));
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int k = 0; k < n + 2; k++) {
            var terms = new LinkedHashMap<Integer, BigInteger>(); BigInteger rhs = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                int coefficient = r.nextInt(15) - 7;
                if (coefficient != 0) {
                    BigInteger c = b(coefficient).multiply(scale); terms.put(i, c); rhs = rhs.add(c.multiply(plant[i]));
                }
            }
            rhs = rhs.add(b(test % 3 == 0 ? r.nextInt(4) : r.nextInt(9) - 4).multiply(scale));
            rows.add(new ExactLinearProgram.Constraint(terms, rhs));
        }
        if (test % 7 == 0) Collections.shuffle(rows, r);
        Model m = new Model(rows, lo, hi, point); BigInteger[] oracle = enumerate(m, 0, new BigInteger[n]);
        models++; if (oracle != null) oracleSat++;
        var budget = new PlanningBudget(0, 4_000_000, 48L << 20, () -> false, System::nanoTime);
        var journal = test % 7 == 0 ? new CountProof.Journal(8L << 20) : null;
        if (journal != null) budget.proofJournal(journal);
        try (var candidate = new CountNeighborhood(rows, lo, hi, point, budget).pump(test % 2 == 0)) {
            if (oracle != null && test % 11 == 0) {
                BigInteger[] costs = new BigInteger[n]; Arrays.fill(costs, BigInteger.ONE); candidate.incumbent(oracle, costs);
            }
            int steps = 0; while (!candidate.step()) check(++steps < 500000, "nonterminating neighborhood");
            BigInteger[] x = candidate.counts();
            if (x != null) {
                witnesses++; check(oracle != null && valid(m, x), "false/invalid SAT test=" + test);
                BigInteger original = x[0]; x[0] = b(-12345);
                check(candidate.counts()[0].equals(original), "counts returned mutable storage");
            }
            check(candidate.step() && candidate.step(), "completed neighborhood reopened");
            candidate.close(); candidate.close();
        }
        check(budget.reservedBytes() == 0, "random reservation leak");
        if (journal != null) verifyJournal(journal);
    }
    static Model equality() {
        var p = new ExactLinearProgram.Constraint(Map.of(0, b(2), 1, b(3)), b(7));
        var n = new ExactLinearProgram.Constraint(Map.of(0, b(-2), 1, b(-3)), b(-7));
        return new Model(List.of(p, n), new BigInteger[]{b(0), b(0)}, new BigInteger[]{b(4), b(4)},
                new ExactRational[]{new ExactRational(b(1), b(2)), ExactRational.of(b(2))});
    }
    static Model coloring(int colors) {
        int vertices = colors + 1, n = vertices * colors + 1, escape = n - 1;
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        BigInteger[] lo = new BigInteger[n], hi = new BigInteger[n]; ExactRational[] point = new ExactRational[n];
        Arrays.fill(lo, b(0)); Arrays.fill(hi, b(1)); Arrays.fill(point, new ExactRational(b(1), b(colors)));
        hi[escape] = b(2); point[escape] = ExactRational.ZERO;
        for (int v = 0; v < vertices; v++) {
            var up = new LinkedHashMap<Integer, BigInteger>(); var down = new LinkedHashMap<Integer, BigInteger>();
            for (int c = 0; c < colors; c++) { up.put(v * colors + c, b(1)); down.put(v * colors + c, b(-1)); }
            rows.add(new ExactLinearProgram.Constraint(up, b(1))); rows.add(new ExactLinearProgram.Constraint(down, b(-1)));
        }
        for (int v = 0; v < vertices; v++) for (int w = v + 1; w < vertices; w++) for (int c = 0; c < colors; c++)
            rows.add(new ExactLinearProgram.Constraint(Map.of(v * colors + c, b(2), w * colors + c, b(2), escape, b(-1)), b(2)));
        return new Model(rows, lo, hi, point);
    }
    static void fairness(int colors, boolean pump) throws Exception {
        Model m = colors == 0 ? equality() : coloring(colors);
        var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
        var journal = new CountProof.Journal(16L << 20); budget.proofJournal(journal);
        try (var candidate = new CountNeighborhood(m.rows, m.low, m.high, m.point, budget).pump(pump)) {
            Object lastArm = null, lastBinary = null, lastWide = null; boolean ended = false; long lastWork = 0;
            int steps = 0;
            while (!ended) {
                Object oldArm = get(candidate, "activeArm"); long before = budget.threadWork();
                ended = candidate.step(); maxStep = Math.max(maxStep, budget.threadWork() - before);
                Object arm = get(candidate, "activeArm"), binary = get(candidate, "search"), wide = get(candidate, "relaxedSearch");
                if (oldArm != null && arm == null) handoffs++;
                if (arm != null && lastArm != null && arm != lastArm &&
                        (binary != null && binary == lastBinary || wide != null && wide == lastWide)) retained++;
                if (wide instanceof CountLcg lcg && lcg.paused()) { paused++; check(!lcg.infeasible(), "pause marked infeasible"); }
                if (binary instanceof CountCdcl cdcl && cdcl.paused()) check(!cdcl.infeasible(), "PB pause marked infeasible");
                if (arm != null) lastArm = arm; lastBinary = binary; lastWide = wide;
                long work = (long) get(candidate, "work"); check(work >= lastWork, "local budget refunded"); lastWork = work;
                check(++steps < 500000, "fairness never completes");
            }
            var binary = (CountPortfolioPolicy.Arm) get(candidate, "binaryArm");
            var wide = (CountPortfolioPolicy.Arm) get(candidate, "relaxedArm");
            check(binary.selections > 0 && wide.selections > 0, "wide arm starved");
            check(binary.work > 0 && wide.work > 0, "unaccounted arm effort");
            if (candidate.counts() != null) { check(valid(m, candidate.counts()), "invalid fairness witness"); wideWins++; }
            if (colors <= 4) check(candidate.counts() != null, "wide reachable fixture missed");
            if (colors == 0) {
                check(binary.bestProgress > 0, "restricted UNSAT did not complete");
                check(wide.bestProgress > 0, "wide success did not complete");
                check(journal.entries().stream().anyMatch(CountProof.Certificate::closed), "restricted proof not exercised");
            }
            System.out.println("FAIR colors=" + colors + " pump=" + pump + " found=" + (candidate.counts() != null) +
                    " binarySlices=" + binary.selections + " wideSlices=" + wide.selections + " work=" + budget.nodes());
        }
        check(budget.reservedBytes() == 0, "fairness reservation leak"); verifyJournal(journal);
    }
    static void lifecycle(int cancellation, long bytes, long remaining) throws Exception {
        Model m = coloring(4); AtomicInteger polls = new AtomicInteger();
        var budget = new PlanningBudget(0, 4_000_000, bytes, () -> cancellation > 0 && polls.incrementAndGet() >= cancellation, System::nanoTime);
        try (var candidate = new CountNeighborhood(m.rows, m.low, m.high, m.point, budget)) {
            if (remaining >= 0) budget.charge(Math.max(0, budget.remainingWork() - remaining));
            while (!candidate.step()) {}
            if (candidate.counts() != null) check(valid(m, candidate.counts()), "lifecycle false witness"); else declined++;
            candidate.close();
        } catch (CancellationException expected) { cancelled++; }
        catch (PlanningBudget.Exhausted expected) { exhausted++; }
        check(budget.reservedBytes() == 0, "lifecycle leak stop=" + cancellation + " bytes=" + bytes);
    }
    public static void main(String[] args) throws Exception {
        Random random = new Random(720261003L);
        for (int test = 0; test < 1500; test++) random(random, test);
        fairness(0, false); fairness(4, false); fairness(6, false); fairness(6, true);
        for (int stop = 1; stop < 160; stop += 2) lifecycle(stop, 64L << 20, -1);
        for (int stop = 512; stop <= 32768; stop += 512) lifecycle(stop, 64L << 20, -1);
        for (long bytes : new long[]{0, 1024, 4096, 8192, 16384, 32768, 65536, 131072, 262144, 1048576}) lifecycle(0, bytes, -1);
        for (long quota : new long[]{0, 1, 7, 127, 1024, 4096, 8192, 16384, 32768}) lifecycle(0, 64L << 20, quota);
        check(cancelled > 0 && exhausted > 0 && handoffs > 0 && retained > 0 && wideWins >= 2, "missing lifecycle coverage");
        System.out.println("PASS models=" + models + " assignments=" + assignments + " oracleSat=" + oracleSat + " witnesses=" + witnesses +
                " proofs=" + proofs + " restrictedProofs=" + restrictedProofs + " cancelled=" + cancelled + " exhausted=" + exhausted +
                " declined=" + declined + " handoffs=" + handoffs + " retained=" + retained + " paused=" + paused + " wideWins=" + wideWins +
                " maxAtomicWork=" + maxStep + " assertions=" + checks + " leaks=0");
    }
}
