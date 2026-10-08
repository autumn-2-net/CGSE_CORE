import org.cgse.core.*;

import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.util.*;

/** Local-only stress runner. Uses the unchanged production core from the supplied JAR. */
public final class ComplexCycleStress {
    static final long HUGE = 100_000_017L;
    static final com.sun.management.ThreadMXBean MEMORY =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static long assertions;
    record Case(String name, List<GraphRecipe<String>> recipes, String target, long amount,
                Map<String, Long> stock, Map<String, Long> seeds, PlanStep witness, boolean possible) {
        GraphPlan<String> known() {
            var ids = new LinkedHashMap<String, GraphRecipe<String>>();
            recipes.forEach(r -> ids.put(r.id(), r));
            return new GraphPlan<>(target, amount, true, witness, ids, stock, seeds, Map.of(),
                    GraphPlan.Result.FEASIBLE, 0, 0);
        }
    }
    record Measured(GraphPlan<String> plan, long nanos, long cpu, long allocated, PlanningBudget budget) {}

    public static void main(String[] args) {
        String mode = args.length == 0 ? "scan" : args[0];
        String filter = args.length > 1 ? args[1] : "";
        long timeout = args.length > 2 ? Long.parseLong(args[2]) : 3000;
        System.out.printf(Locale.ROOT, "ENV java=%s vm=%s mode=%s timeout_ms=%d nodes=10000000 memory_budget_mib=128 heap_max_mib=%d%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name").replace(' ', '_'), mode,
                timeout, Runtime.getRuntime().maxMemory() >> 20);
        for (int i = 0; i < 25; i++) run(ring(8, 100, 4), null, 1000);
        if (mode.equals("execute")) {
            executeSuite(filter);
        } else {
            var cases = cases(mode);
            for (Case c : cases) if (c.name.contains(filter)) {
                if (c.possible) validate(c, c.known(), true);
                if (mode.equals("bench")) {
                    for (boolean hot : new boolean[]{false, true}) benchmark(c, hot, timeout);
                } else {
                    System.out.printf("BEGIN name=%s recipes=%d amount=%d possible=%s%n", c.name, c.recipes.size(), c.amount, c.possible);
                    try {
                        print(c, "scan", run(c, null, timeout));
                    } catch (Throwable e) {
                        System.out.printf("ERROR name=%s error=%s message=%s%n", c.name, e.getClass().getSimpleName(), String.valueOf(e.getMessage()).replace(' ', '_'));
                        e.printStackTrace(System.out);
                    }
                }
            }
        }
        System.out.printf("END assertions=%d%n", assertions);
    }

    static List<Case> cases(String mode) {
        var out = new ArrayList<Case>();
        if (mode.equals("bench")) {
            for (int size : new int[]{8, 32, 128, 512, 1024}) out.add(ring(size, HUGE, 8));
            for (int size : new int[]{4, 16, 64, 256, 1024, 4096}) out.add(independent(size, HUGE, 4));
            for (int size : new int[]{4, 16, 64, 256, 1024, 4096}) out.add(chain(size, HUGE, 4));
            for (int size : new int[]{2, 4, 8, 16}) out.add(hub(size, HUGE, 4));
            for (int size : new int[]{1, 2, 4, 8}) out.add(nested(size, 1, HUGE, 1));
            return out;
        }
        for (int size : new int[]{3, 6, 8, 32, 128, 512, 1024}) out.add(ring(size, HUGE, 8));
        for (int size : new int[]{4, 16, 64, 256, 1024, 4096}) out.add(independent(size, HUGE, 4));
        for (int size : new int[]{4, 16, 64, 256, 1024, 4096}) out.add(chain(size, HUGE, 4));
        for (int size : new int[]{2, 4, 8, 16, 64}) out.add(hub(size, HUGE, 4));
        for (int size : new int[]{1, 2, 4, 8, 32}) out.add(braid(size, HUGE, 4));
        for (int depth : new int[]{1, 2, 3, 4, 6, 8})
            for (int ratio : new int[]{1, 2, 7}) out.add(nested(depth, ratio, HUGE, 1));
        for (long amount : new long[]{1, 1000, 1_000_000_000_000L}) {
            out.add(ring(128, amount, 4));
            out.add(independent(16, amount, 4));
            out.add(chain(16, amount, 4));
            out.add(nested(2, 2, amount, 1));
        }
        var missing = ring(32, 100, 1);
        out.add(new Case("missing-seed-ring32", missing.recipes, "P", 100,
                amounts("R", 100), Map.of(), missing.witness, false));
        out.add(new Case("nonproductive-loop", List.of(recipe("ab", amounts("A", 1), amounts("B", 1)),
                recipe("ba", amounts("B", 1), amounts("A", 1))), "A", 10,
                amounts("A", 1), Map.of(), new PlanStep.Sequence(List.of()), false));
        return out;
    }

    static Measured run(Case c, GraphCompiler<String> compiler, long timeout) {
        var budget = new PlanningBudget(timeout, 10_000_000, 128L << 20, () -> false, System::nanoTime);
        budget.enableMetrics();
        long allocated = MEMORY.getCurrentThreadAllocatedBytes(), cpu = MEMORY.getCurrentThreadCpuTime(), start = System.nanoTime();
        GraphPlan<String> p;
        try (var scope = budget.work(PlanningBudget.Phase.BUILD)) {
            if (compiler == null) compiler = new GraphCompiler<>(c.recipes);
            var work = new GraphPlanningWork<>(compiler, c.target, c.amount, c.stock, true, true, budget)
                    .catalysts(new CatalystPolicy(8, 0));
            while (!work.step()) {}
            p = work.result();
        }
        long nanos = System.nanoTime() - start;
        long used = MEMORY.getCurrentThreadAllocatedBytes() - allocated;
        long usedCpu = MEMORY.getCurrentThreadCpuTime() - cpu;
        if (p.feasible()) validate(c, p, false);
        return new Measured(p, nanos, usedCpu, used, budget);
    }

    static void print(Case c, String mode, Measured m) {
        var p = m.plan;
        var phases = m.budget.metrics().activeNanos();
        System.out.printf(Locale.ROOT, "PLAN name=%s amount=%d recipes=%d cache=%s result=%s wall_ms=%.3f cpu_ms=%.3f alloc_mib=%.3f budget_peak_mib=%.3f search=%d ast=%d build_ms=%.3f analyse_ms=%.3f solve_ms=%.3f verify_ms=%.3f missing_types=%d%n",
                c.name, c.amount, c.recipes.size(), mode, p.result(), m.nanos / 1e6, m.cpu / 1e6, m.allocated / 1048576.0,
                m.budget.peakBytes() / 1048576.0, m.budget.nodes(), nodes(p.steps()),
                phases.get(PlanningBudget.Phase.BUILD) / 1e6, phases.get(PlanningBudget.Phase.ANALYSE) / 1e6,
                phases.get(PlanningBudget.Phase.SOLVE) / 1e6, phases.get(PlanningBudget.Phase.VERIFY) / 1e6, p.missing().size());
    }

    static void benchmark(Case c, boolean hot, long timeout) {
        GraphCompiler<String> compiler = hot ? new GraphCompiler<>(c.recipes) : null;
        Measured first = run(c, compiler, timeout);
        if (!first.plan.feasible() || first.nanos > 500_000_000L) { print(c, hot ? "hot-slow" : "cold-slow", first); return; }
        for (int i = 0; i < 6; i++) run(c, compiler, timeout);
        var samples = new ArrayList<Measured>();
        for (int i = 0; i < 13; i++) samples.add(run(c, compiler, timeout));
        samples.sort(Comparator.comparingLong(Measured::nanos));
        print(c, hot ? "hot-median" : "cold-median", samples.get(6));
        System.out.printf(Locale.ROOT, "TAIL name=%s amount=%d cache=%s n=%d p95_ms=%.3f%n",
                c.name, c.amount, hot ? "hot" : "cold", samples.size(), samples.get(12).nanos / 1e6);
    }

    static Case ring(int size, long n, int seeds) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        var body = new ArrayList<PlanStep>();
        for (int i = 0; i < size; i++) {
            var in = amounts("C" + i, 1);
            if (i == 0) in.put("R", 1L);
            var out = amounts("C" + ((i + 1) % size), 1);
            if (i + 1 == size) out.put("P", 1L);
            recipes.add(recipe("r" + i, in, out)); body.add(batch("r" + i));
        }
        return new Case("ring" + size + "-s" + seeds, recipes, "P", n, amounts("R", n, "C0", seeds), amounts("C0", seeds), repeat(seq(body), n), true);
    }

    static Case independent(int size, long n, int seeds) {
        var recipes = new ArrayList<GraphRecipe<String>>(); var body = new ArrayList<PlanStep>();
        var stock = amounts("R", Math.multiplyExact(n, size)); var reserve = new TreeMap<String, Long>();
        var join = new TreeMap<String, Long>();
        for (int i = 0; i < size; i++) {
            recipes.add(recipe("a" + i, amounts("C" + i, 1, "R", 1), amounts("I" + i, 1)));
            recipes.add(recipe("b" + i, amounts("I" + i, 1), amounts("C" + i, 1, "Q" + i, 1)));
            stock.put("C" + i, (long) seeds); reserve.put("C" + i, (long) seeds); join.put("Q" + i, 1L);
            body.add(repeat(seq(List.of(batch("a" + i), batch("b" + i))), n));
        }
        joinTree(recipes, body, join, n);
        return new Case("independent" + size, recipes, "P", n, stock, reserve, seq(body), true);
    }

    static Case chain(int depth, long n, int seeds) {
        var recipes = new ArrayList<GraphRecipe<String>>(); var body = new ArrayList<PlanStep>();
        var stock = amounts("R", n); var reserve = new TreeMap<String, Long>();
        for (int i = 0; i < depth; i++) {
            recipes.add(recipe("a" + i, amounts("C" + i, 1, i == 0 ? "R" : "Q" + (i - 1), 1), amounts("I" + i, 1)));
            recipes.add(recipe("b" + i, amounts("I" + i, 1), amounts("C" + i, 1, "Q" + i, 1)));
            stock.put("C" + i, (long) seeds); reserve.put("C" + i, (long) seeds);
            body.add(repeat(seq(List.of(batch("a" + i), batch("b" + i))), n));
        }
        return new Case("chain-of-rings" + depth, recipes, "Q" + (depth - 1), n, stock, reserve, seq(body), true);
    }

    static Case hub(int size, long n, int seeds) {
        var recipes = new ArrayList<GraphRecipe<String>>(); var body = new ArrayList<PlanStep>(); var join = new TreeMap<String, Long>();
        for (int i = 0; i < size; i++) {
            recipes.add(recipe("a" + i, amounts("C", 1, "R", 1), amounts("I" + i, 1)));
            recipes.add(recipe("b" + i, amounts("I" + i, 1), amounts("C", 1, "Q" + i, 1)));
            join.put("Q" + i, 1L);
            body.add(repeat(seq(List.of(batch("a" + i), batch("b" + i))), n));
        }
        joinTree(recipes, body, join, n);
        return new Case("shared-hub" + size, recipes, "P", n, amounts("R", Math.multiplyExact(n, size), "C", seeds), amounts("C", seeds), seq(body), true);
    }

    static Case braid(int size, long n, int seeds) {
        var recipes = new ArrayList<GraphRecipe<String>>(); var body = new ArrayList<PlanStep>();
        for (int i = 0; i < size; i++) {
            recipes.add(recipe("a" + i, amounts("C" + i, 1, "R", 1), amounts("A" + i, 1, "B" + i, 1)));
            recipes.add(recipe("b" + i, amounts("A" + i, 1), amounts("D" + i, 1)));
            recipes.add(recipe("c" + i, amounts("B" + i, 1), amounts("E" + i, 1)));
            var output = amounts("C" + ((i + 1) % size), 1);
            if (i == size - 1) output.put("P", 1L);
            recipes.add(recipe("d" + i, amounts("D" + i, 1, "E" + i, 1), output));
            for (String id : new String[]{"a", "b", "c", "d"}) body.add(batch(id + i));
        }
        return new Case("braid" + size, recipes, "P", n, amounts("R", Math.multiplyExact(n, size), "C0", seeds), amounts("C0", seeds), repeat(seq(body), n), true);
    }

    static void joinTree(List<GraphRecipe<String>> recipes, List<PlanStep> body, Map<String, Long> inputs, long n) {
        List<String> keys = new ArrayList<>(inputs.keySet()); int level = 0;
        while (keys.size() > 8) {
            List<String> next = new ArrayList<>();
            for (int start = 0; start < keys.size(); start += 8) {
                Map<String, Long> group = new TreeMap<>();
                for (int k = start; k < Math.min(start + 8, keys.size()); k++) group.put(keys.get(k), 1L);
                String output = "J" + level + "x" + start, id = "join_" + output;
                recipes.add(recipe(id, group, amounts(output, 1))); body.add(new PlanStep.Batch(id, n)); next.add(output);
            }
            keys = next; level++;
        }
        Map<String, Long> last = new TreeMap<>(); keys.forEach(k -> last.put(k, 1L));
        recipes.add(recipe("join", last, amounts("P", 1))); body.add(new PlanStep.Batch("join", n));
    }

    static Case nested(int depth, int ratio, long n, int seeds) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < depth; i++) {
            recipes.add(recipe("open" + i, amounts("C" + i, 1, "R", 1), amounts("C" + (i + 1), 1)));
            recipes.add(recipe("close" + i, amounts("C" + (i + 1), 1, "Q" + (i + 1), ratio), amounts("C" + i, 1, "Q" + i, 1)));
        }
        recipes.add(recipe("inner", amounts("C" + depth, 1, "R", 1), amounts("C" + depth, 1, "Q" + depth, 1)));
        PlanStep witness = batch("inner"); long raw = 1;
        for (int i = depth - 1; i >= 0; i--) {
            witness = seq(List.of(batch("open" + i), repeat(witness, ratio), batch("close" + i)));
            raw = Math.addExact(1, Math.multiplyExact(raw, ratio));
        }
        return new Case("nested" + depth + "-ratio" + ratio, recipes, "Q0", n,
                amounts("R", Math.multiplyExact(raw, n), "C0", seeds), amounts("C0", seeds), repeat(witness, n), true);
    }

    static void validate(Case c, GraphPlan<String> p, boolean known) {
        check(c.possible, "Impossible case accepted: " + c.name);
        PlanVerifier.verify(p);
        for (var e : p.initial().entrySet()) check(e.getValue() <= c.stock.getOrDefault(e.getKey(), 0L), "Invented initial resource " + e);
        var inventory = new TreeMap<String, BigInteger>(); p.initial().forEach((k, v) -> inventory.put(k, BigInteger.valueOf(v)));
        var availableRecipes = new HashMap<String, GraphRecipe<String>>(); c.recipes.forEach(r -> availableRecipes.put(r.id(), r));
        p.patternTimes().forEach((id, count) -> {
            var r = availableRecipes.get(id);
            check(r != null && r.equals(p.recipes().get(id)), "Foreign/rewritten recipe " + id);
            r.inputs().forEach((k, v) -> inventory.merge(k, BigInteger.valueOf(v).multiply(BigInteger.valueOf(count)).negate(), BigInteger::add));
            r.outputs().forEach((k, v) -> inventory.merge(k, BigInteger.valueOf(v).multiply(BigInteger.valueOf(count)), BigInteger::add));
        });
        inventory.forEach((k, v) -> check(v.signum() >= 0, "Negative final balance " + k));
        check(inventory.getOrDefault(c.target, BigInteger.ZERO).compareTo(BigInteger.valueOf(c.amount)) >= 0, "Missing target");
        p.seeds().forEach((k, v) -> check(inventory.getOrDefault(k, BigInteger.ZERO).compareTo(BigInteger.valueOf(v)) >= 0, "Lost recovery seed " + k));
        long operations = 0;
        for (long count : p.patternTimes().values()) { if (count > 100_000 || operations > 100_000 - count) { operations = 100_001; break; } operations += count; }
        if (operations <= 100_000) {
            var physical = new TreeMap<>(p.initial());
            interpret(p.steps(), p.recipes(), physical);
            check(physical.getOrDefault(p.target(), 0L) >= p.amount(), "Serial witness target");
            p.seeds().forEach((k, v) -> check(physical.getOrDefault(k, 0L) >= v, "Serial witness seed"));
        }
    }

    static void interpret(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, Long> inv) {
        if (step instanceof PlanStep.Batch b) {
            var r = recipes.get(b.recipe());
            // Batch is a compressed repetition, not a promise all runs dispatch
            // simultaneously; returned catalysts may be reused each operation.
            for (long i = 0; i < b.runs(); i++) {
                r.inputs().forEach((k, v) -> debit(inv, k, v));
                r.outputs().forEach((k, v) -> inv.merge(k, v, Math::addExact));
            }
        } else if (step instanceof PlanStep.Repeat r) { for (long i = 0; i < r.times(); i++) interpret(r.body(), recipes, inv); }
        else for (var s : ((PlanStep.Sequence) step).children()) interpret(s, recipes, inv);
    }

    static void executeSuite(String filter) {
        var cases = List.of(ring(8, 31, 4), ring(128, 17, 4), independent(4, 31, 4), independent(64, 9, 4),
                chain(4, 31, 4), chain(32, 17, 4), hub(4, 31, 4), hub(32, 17, 4), braid(4, 17, 4),
                nested(2, 2, 17, 1), nested(4, 2, 7, 1), nested(6, 2, 3, 1),
                nested(32, 1, 9, 1), hub(64, 9, 4), braid(16, 9, 4));
        for (Case c : cases) if (c.name.contains(filter)) {
            Measured result = run(c, null, 1500);
            if (result.plan.feasible()) execute(c, result.plan, "solver");
            else System.out.printf("EXEC_SKIP name=%s source=solver result=%s%n", c.name, result.plan.result());
            // A mathematically known legal nested witness tests execution independently of solver completeness.
            execute(c, c.known(), "known-witness");
        }
    }

    static void execute(Case c, GraphPlan<String> p, String source) {
        validate(c, p, source.equals("known-witness"));
        for (int reload : new int[]{0, 97}) {
            var m = new Machines(p, reload);
            long start = System.nanoTime();
            for (int i = 0; i < 250_000 && !m.runtime.finished(); i++) m.step();
            boolean done = m.runtime.state() == GraphJobRuntime.State.COMPLETED;
            if (done) {
                check(p.patternTimes().equals(m.accepted), "Exactly-once recipe acceptance");
                check(m.delivered.equals(Map.of(p.target(), p.amount())), "Exact output delivery");
                check(m.physical.isEmpty(), "No material stranded in completed CPU");
                check(m.flights.isEmpty(), "No in-flight output after completion");
                p.seeds().forEach((k, v) -> check(m.refunded.getOrDefault(k, 0L) >= v, "Catalyst was not returned"));
            }
            System.out.printf(Locale.ROOT, "EXEC name=%s source=%s reload_every=%d result=%s reason=%s ticks=%d pushes=%d max_inflight=%d overlaps=%d active_ms=%.3f max_tick_ms=%.3f wall_ms=%.3f%n",
                    c.name, source, reload, m.runtime.state(), m.runtime.reason(), m.tick, m.pushes, m.maxFlight, m.overlaps, m.nanos / 1e6, m.maxNanos / 1e6, (System.nanoTime() - start) / 1e6);
            if (!done) System.out.printf("EXEC_PENDING name=%s owned=%s expected=%s pending=%s%n", c.name, m.runtime.owned(), m.runtime.expected(), m.runtime.pendingRuns());
        }
    }

    record Flight(long due, String recipe, Map<String, Long> output) {}
    static final class Machines implements GraphJobRuntime.Adapter<String> {
        GraphJobRuntime<String> runtime;
        final Map<String, Long> physical = new TreeMap<>(), accepted = new TreeMap<>(), delivered = new TreeMap<>(), refunded = new TreeMap<>();
        final List<Flight> flights = new ArrayList<>();
        final Random random = new Random(49367);
        final int reload;
        long tick, pushes, overlaps, nanos, maxNanos, maxFlight;
        Machines(GraphPlan<String> p, int reload) { runtime = new GraphJobRuntime<>(p, p.initial(), Map.of()); physical.putAll(p.initial()); this.reload = reload; }
        void step() {
            Collections.shuffle(flights, random);
            for (var it = flights.iterator(); it.hasNext();) {
                var f = it.next(); if (f.due > tick) continue;
                f.output.forEach((k, v) -> {
                    long got = runtime.accept(k, v, false); check(got == v, "Dropped or double-counted physical output"); physical.merge(k, got, Math::addExact);
                }); it.remove();
            }
            long start = System.nanoTime(); runtime.tick(this, tick++, 8); long elapsed = System.nanoTime() - start;
            nanos += elapsed; maxNanos = Math.max(maxNanos, elapsed);
            physical.values().removeIf(v -> v == 0); check(physical.equals(runtime.owned()), "Physical ownership differs");
            maxFlight = Math.max(maxFlight, flights.size());
            if (reload > 0 && tick % reload == 0 && !runtime.finished()) runtime = new GraphJobRuntime<>(runtime.snapshot());
        }
        public long capacity(GraphRecipe<String> r, long requested) {
            if (tick < 41 && r.id().equals("a1")) return 0;
            return Math.min(requested, 1);
        }
        public GraphJobRuntime.Outcome push(GraphRecipe<String> r, long runs, Map<String, Long> input) {
            input.forEach((k, v) -> debit(physical, k, v));
            if (flights.stream().anyMatch(f -> !f.recipe.equals(r.id()))) overlaps++;
            long ordinal = accepted.merge(r.id(), runs, Math::addExact);
            long delay = 1 + Math.floorMod(r.id().hashCode() + ordinal * 7, 13);
            // Return byproducts and main outputs separately and out of order.
            int offset = 0;
            for (var e : r.outputs().entrySet()) flights.add(new Flight(tick + delay + (offset++ * 3), r.id(), Map.of(e.getKey(), Math.multiplyExact(e.getValue(), runs))));
            pushes++; return GraphJobRuntime.Outcome.ACCEPTED;
        }
        public long deliver(String key, long n) { debit(physical, key, n); delivered.merge(key, n, Math::addExact); return n; }
        public long refund(String key, long n) { debit(physical, key, n); refunded.merge(key, n, Math::addExact); return n; }
    }

    static void debit(Map<String, Long> map, String k, long n) {
        long before = map.getOrDefault(k, 0L); check(before >= n, "Spending unowned material " + k); map.put(k, before - n);
    }
    static Map<String, Long> amounts(Object... pairs) {
        var map = new TreeMap<String, Long>(); for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], ((Number) pairs[i + 1]).longValue()); return map;
    }
    static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    static PlanStep batch(String id) { return new PlanStep.Batch(id, 1); }
    static PlanStep seq(List<PlanStep> steps) { return new PlanStep.Sequence(steps); }
    static PlanStep repeat(PlanStep s, long n) { return new PlanStep.Repeat(s, n); }
    static int nodes(PlanStep s) {
        if (s instanceof PlanStep.Batch) return 1;
        if (s instanceof PlanStep.Repeat r) return 1 + nodes(r.body());
        int n = 1; for (var c : ((PlanStep.Sequence) s).children()) n += nodes(c); return n;
    }
    static void check(boolean c, String text) { assertions++; if (!c) throw new AssertionError(text); }
}
