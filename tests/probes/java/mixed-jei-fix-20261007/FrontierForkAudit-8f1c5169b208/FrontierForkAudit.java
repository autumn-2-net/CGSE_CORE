package org.cgse.core;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;

public class FrontierForkAudit {
    static int checks, forks, witnesses, cancelled, declined, creationStops;
    static Field field(Class<?> type, String name) throws Exception { var f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    static Object get(Object object, String name) throws Exception { return field(object.getClass(), name).get(object); }
    static long number(Object object, String name) throws Exception { return field(object.getClass(), name).getLong(object); }
    static void set(Object object, String name, Object value) throws Exception { field(object.getClass(), name).set(object, value); }
    static void ok(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    record Input(MixedSweep.Data data, String target, long amount) {}
    static Input read(String file) throws Exception {
        Path path = Path.of(file); var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("request");
        return new Input(MixedSweep.read(path), json.get("target").getAsString(), json.get("amount").getAsLong());
    }

    static void fairness(Input input, boolean oldControl) throws Exception {
        var data = input.data(); var budget = new PlanningBudget(0, 100_000_000, 256L << 20, () -> false, System::nanoTime);
        budget.reserve(128);
        var compiler = new GraphCompiler<>(data.recipes(), data.producers());
        var policy = new CatalystPolicy(4096, 64);
        var portfolio = new GraphStockViewPortfolio<>(compiler, input.target(), input.amount(), data.stock(), data.external(),
                Map.of(), Set.of(), true, true, policy, budget, System.nanoTime());
        var counts = new IntegerCountSearch<>(compiler, input.target(), input.amount(), data.stock(), Map.of(), data.external(),
                Set.of(), true, true, budget, System.nanoTime());
        GraphPlanningWork<String> parent = null;
        try {
            set(portfolio, "until", 1L);
            while (!portfolio.step()) {}
            ok(portfolio.paused(), "stock frontier did not really pause");
            long stockPaused = budget.nodes();
            counts.scout(1);
            while (!counts.step()) {}
            ok(counts.paused(), "integer frontier did not really pause");
            long countPaused = budget.nodes();
            parent = new GraphPlanningWork<>(compiler, input.target(), input.amount(), data.stock(), data.external(),
                    Map.of(), true, true, budget).catalysts(policy);
            ((Deque<?>) get(parent, "pending")).clear();
            set(parent, "phase", 9);
            set(parent, "stockView", portfolio);
            set(parent, "stockViewTried", true);
            set(parent, "stockPausedAt", stockPaused);
            set(parent, "stockSpareMemory", 0L);
            set(parent, "parkedCounts", counts);
            set(parent, "countPausedAt", countPaused);
            set(parent, "countAttempted", true);
            set(parent, "allocationAttempted", true);
            var turns = new ArrayList<String>();
            for (int iteration = 0; iteration < 10000 && turns.size() < 6; iteration++) {
                int previousPhase = (int) get(parent, "phase");
                long previousStock = number(portfolio, "work"), previousCount = number(counts, "work");
                ok(!parent.step(), "frontier unexpectedly completed the whole order");
                int nextPhase = (int) get(parent, "phase");
                if (previousPhase == 9) {
                    if (nextPhase == 19) {
                        turns.add("stock");
                        ok(number(portfolio, "work") > previousStock, "stock received no real work");
                        set(portfolio, "until", number(portfolio, "work") + 1);
                    } else if (nextPhase == 14) {
                        turns.add("counts");
                        ok(number(counts, "work") > previousCount, "integer frontier received no real work");
                        set(counts, "allowance", number(counts, "work") + 1);
                    } else throw new AssertionError("unexpected terminal handoff phase " + nextPhase);
                }
                if ((previousPhase == 19 || previousPhase == 14) && nextPhase == 9)
                    ok(get(parent, "stockView") == portfolio && get(parent, "parkedCounts") == counts, "lost a retained frontier");
            }
            String sequence = String.join(",", turns);
            System.out.println("terminal turns=" + sequence + "; stock_work=" + number(portfolio, "work") + "; count_work=" + number(counts, "work"));
            if (oldControl) ok(turns.size() == 6 && !turns.contains("counts"), "old control did not reproduce starvation");
            else ok(turns.equals(List.of("stock", "counts", "stock", "counts", "stock", "counts")), "terminal handoff was unfair: " + sequence);
        } finally {
            if (parent != null) { parent.close(); parent.close(); }
            else { portfolio.close(); counts.close(); }
            ok(budget.reservedBytes() == 128, "fairness lease leak " + budget.reservedBytes());
            budget.release(128);
        }
    }

    static void fork(Input input, long bytes, int mode) throws Exception {
        var data = input.data(); var budget = new PlanningBudget(0, 20_000_000, bytes + 128, () -> false, System::nanoTime);
        budget.reserve(128); budget.failureDetail("retained");
        var compiler = new GraphCompiler<>(data.recipes(), data.producers());
        @SuppressWarnings("unchecked") GraphSourceRanking<String>[] ranking = new GraphSourceRanking[1];
        var parent = new GraphStockViewWork<>(compiler, input.target(), input.amount(), data.stock(), data.external(), Map.of(), Set.of(),
                true, true, new CatalystPolicy(4096, 64), budget, System.nanoTime(), 2, () -> {
                    if (ranking[0] == null) ranking[0] = GraphSourceRanking.create(compiler, data.stock(), data.external(), input.target(), true, budget);
                    return ranking[0];
                });
        GraphStockViewWork<String> child = null;
        long pressure = 0;
        try {
            for (int step = 0; step < 2_000_000; step++) {
                boolean finished = parent.step();
                @SuppressWarnings("unchecked") var held = (GraphStockViewWork<String>) get(parent, "fork");
                if (held != null) {
                    forks++;
                    ok((int) get(held, "variant") == 6, "wrong fork strategy");
                    ok(get(held, "repairCandidate") != null && get(held, "graph") != null, "fork repeated prefix instead of retaining it");
                    ok(get(held, "leaves") != get(parent, "leaves") && get(held, "excluded") != get(parent, "excluded"), "mutable repair state shared");
                    if (mode == 0) break; // Parent still owns child.
                    child = parent.takeFork();
                    ok(parent.takeFork() == null, "fork ownership transferred twice");
                    if (mode == 1) parent.close();
                    if (mode == 2) { child.close(); child.close(); child = null; while (!parent.step()) {} break; }
                    if (mode == 3) { budget.cancel(); cancelled++; }
                    if (mode == 4) { pressure = Math.max(0, budget.availableBytes() - 1); budget.reserve(pressure); }
                    for (int next = 0; next < 2_000_000; next++) if (child.step()) break;
                    if (child.result() != null) {
                        PlanVerifier.verify(child.result()); PlanVerifier.verifyRuntimeInventory(child.result()); witnesses++;
                    }
                    break;
                }
                if (finished) { declined++; break; }
            }
        } catch (CancellationException expected) {
            ok(mode == 3, "unexpected cancel");
        } finally {
            parent.close(); parent.close();
            if (child != null) { child.close(); child.close(); }
            if (ranking[0] != null) ranking[0].close();
            budget.release(pressure);
            ok(budget.reservedBytes() == 128, "fork lease leak bytes=" + bytes + " mode=" + mode + " balance=" + budget.reservedBytes());
            ok(budget.failureDetail().equals("retained"), "optional view changed failure state");
            budget.release(128);
        }
    }

    static void stopDuringForkCreation(Input input, int checkpoint, boolean cancel) throws Exception {
        var data = input.data();
        PlanningBudget[] holder = new PlanningBudget[1];
        int[] visited = {0}; long[] pressure = {0}; boolean[] injected = {false};
        var budget = new PlanningBudget(60_000, 20_000_000, (128L << 20) + 128, () -> false, () -> {
            if (holder[0] != null && !injected[0] && Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getClassName().endsWith("GraphStockViewWork") && frame.getMethodName().equals("forkConsumerRepair")) && ++visited[0] == checkpoint) {
                injected[0] = true;
                if (cancel) holder[0].cancel();
                else { pressure[0] = Math.max(0, holder[0].availableBytes() - 256); holder[0].reserve(pressure[0]); }
            }
            return 0;
        });
        holder[0] = budget;
        budget.reserve(128); budget.failureDetail("retained");
        var compiler = new GraphCompiler<>(data.recipes(), data.producers());
        @SuppressWarnings("unchecked") GraphSourceRanking<String>[] ranking = new GraphSourceRanking[1];
        var parent = new GraphStockViewWork<>(compiler, input.target(), input.amount(), data.stock(), data.external(), Map.of(), Set.of(),
                true, true, new CatalystPolicy(4096, 64), budget, System.nanoTime(), 2, () -> {
                    if (ranking[0] == null) ranking[0] = GraphSourceRanking.create(compiler, data.stock(), data.external(), input.target(), true, budget);
                    return ranking[0];
                });
        try {
            while (!parent.step()) {}
            ok(!cancel, "mid-construction cancellation ignored");
        } catch (CancellationException expected) {
            ok(cancel, "unexpected construction cancellation");
        } finally {
            parent.close(); parent.close();
            if (ranking[0] != null) ranking[0].close();
            budget.release(pressure[0]);
            ok(injected[0], "fork creation checkpoint was not reached: " + checkpoint);
            ok(budget.reservedBytes() == 128, "partial fork construction leaked " + budget.reservedBytes());
            ok(budget.failureDetail().equals("retained"), "fork admission damaged failure state");
            budget.release(128);
            creationStops++;
        }
    }

    public static void main(String[] args) throws Exception {
        fairness(read(args[0]), args.length > 2);
        if (args.length <= 2) {
            Input forge = read(args[1]);
            for (int mode = 0; mode < 5; mode++) fork(forge, 128L << 20, mode);
            for (long bytes : new long[]{1024, 16384, 262144, 1048576, 4194304}) fork(forge, bytes, 1);
            for (int checkpoint : new int[]{1, 2, 3}) for (boolean cancel : List.of(false, true)) stopDuringForkCreation(forge, checkpoint, cancel);
            ok(forks >= 5 && cancelled == 1, "fork paths not exercised");
        }
        System.out.println("checks=" + checks + "; forks=" + forks + "; child_witnesses=" + witnesses + "; cancellations=" + cancelled + "; declines=" + declined + "; construction_stops=" + creationStops);
    }
}
