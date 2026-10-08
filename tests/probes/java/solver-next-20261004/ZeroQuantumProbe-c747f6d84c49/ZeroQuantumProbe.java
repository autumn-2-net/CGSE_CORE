package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public class ZeroQuantumProbe {
    static long checks;
    static BigInteger z(long x) { return BigInteger.valueOf(x); }
    static void ok(boolean b, String message) { checks++; if (!b) throw new AssertionError(message); }
    static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    static List<ExactLinearProgram.Constraint> rows(int holes) {
        List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
        for (int p = 0; p <= holes; p++) {
            Map<Integer, BigInteger> m = new LinkedHashMap<>();
            for (int h = 0; h < holes; h++) m.put(p * holes + h, z(-1));
            rows.add(new ExactLinearProgram.Constraint(m, z(-1)));
        }
        for (int h = 0; h < holes; h++) for (int p = 0; p <= holes; p++) for (int q = p + 1; q <= holes; q++)
            rows.add(new ExactLinearProgram.Constraint(Map.of(p * holes + h, z(1), q * holes + h, z(1)), z(1)));
        return rows;
    }
    static String run(long remainder, boolean worker, boolean cancel, boolean lastCheck) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        PlanningBudget budget = new PlanningBudget(0, 2_000_000, 128L << 20, cancelled::get, System::nanoTime);
        String result = "none";
        try (var models = CountModelViews.create(rows(7), low(56), high(56), budget);
             var search = new CountViewSearch(models, budget)) {
            search.resume(300_000);
            boolean paused = false;
            for (int iteration = 0; iteration < 1_000_000; iteration++) {
                boolean complete = search.step();
                if (field(search, "active") == null) {
                    for (Object arm : (List<?>) field(search, "searches")) {
                        CountLcg solver = (CountLcg) field(arm, "solver");
                        if (solver != null && solver.paused()) paused = true;
                    }
                }
                if (paused) break;
                ok(!complete, "model finished before retained pause: " + budget.diagnostics());
            }
            ok(paused, "never reached paused retained arm");
            ok((long) field(search, "work") < (long) field(search, "until"), "parent exhausted");
            ok(((List<?>) field(search, "searches")).size() == 1, "expected one nonbinary LCG arm");
            long target = remainder + (lastCheck ? 1 : 0);
            Runnable consume = () -> budget.charge(budget.remainingWork() - target);
            if (worker) { Thread t = new Thread(consume); t.start(); t.join(); }
            else consume.run();
            if (lastCheck) budget.check();
            ok(budget.remainingWork() == remainder, "not at requested remaining quota");
            cancelled.set(cancel);
            try {
                for (int iteration = 0; iteration < 10_000; iteration++) if (search.step()) break;
                result = "returned";
            } catch (PlanningBudget.Exhausted expected) { result = "exhausted:" + expected.limit(); }
            catch (CancellationException expected) { result = "cancelled"; }
            catch (IllegalStateException failure) { result = "illegal:" + failure.getMessage(); }
            ok(search.counts() == null && !search.infeasible(), "budget cutoff became proof");
        }
        ok(budget.reservedBytes() == 0, "retained search leaked memory");
        System.out.println("remaining=" + remainder + " worker=" + worker + " cancel=" + cancel + " lastCheck=" + lastCheck + " outcome=" + result);
        return result;
    }
    static BigInteger[] low(int n) { BigInteger[] x = new BigInteger[n]; Arrays.fill(x, z(0)); return x; }
    static BigInteger[] high(int n) { BigInteger[] x = new BigInteger[n]; Arrays.fill(x, z(2)); return x; }
    public static void main(String[] args) throws Exception {
        boolean old = args.length > 0 && args[0].equals("old");
        for (long r : new long[]{0, 1, 2, 8, 1023}) {
            String result = run(r, false, false, true);
            ok(r == 0 && old ? result.startsWith("illegal:") : result.startsWith("exhausted:"), "unexpected remainder outcome");
        }
        String worker = run(0, true, false, false);
        ok(old ? worker.startsWith("illegal:") : worker.startsWith("exhausted:"), "worker exhaustion outcome");
        String cancel = run(0, false, true, true);
        ok(old ? cancel.startsWith("illegal:") : cancel.equals("cancelled"), "cancellation outcome");
        System.out.println("PASS checks=" + checks);
    }
}
