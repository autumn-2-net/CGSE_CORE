package org.gtlcore.gtlcore.integration.ae2.graph;

import org.cgse.core.*;

import java.util.*;

/** Delayed physical machines with an independent material ledger, including randomized return order. */
public final class GraphDependencyTest {

    private static int checks;

    public static void run() {
        dependencyCases();
        partialStages();
        reservePrefix();
        reserveOnlyRequiredAmount();
        prefixPeak();
        delayedSideOutput();
        independentBlockedBranch();
        cancellation();
        largeCompressedPlan();
        randomWitnesses();
        System.out.println("Graph pipeline: " + checks + " assertions passed");
    }

    /** Can also run against the previous production JAR to compare identical simulated machines. */
    public static void main(String[] args) {
        run();
    }

    private static void dependencyCases() {
        for (int trial = 0; trial < 60; trial++) {
            Random random = new Random(trial);
            int branches = 2 + random.nextInt(8), rounds = 20 + random.nextInt(60);
            var recipes = new ArrayList<GraphRecipe<String>>();
            var stock = new LinkedHashMap<String, Long>();
            var seeds = new LinkedHashMap<String, Long>();
            var outputs = new LinkedHashMap<String, Long>();
            var steps = new ArrayList<PlanStep>();
            stock.put("sharedRaw", (long)branches * rounds);
            for (int i = 0; i < branches; i++) {
                recipes.add(recipe("open"+i, Map.of("seed"+i,1L,"sharedRaw",1L), Map.of("mid"+i,1L)));
                recipes.add(recipe("close"+i, Map.of("mid"+i,1L), Map.of("seed"+i,1L,"out"+i,1L)));
                stock.put("seed"+i,1L); seeds.put("seed"+i,1L); outputs.put("out"+i,1L);
                steps.add(new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("open"+i,1),new PlanStep.Batch("close"+i,1))),rounds));
            }
            // Equal priority / witness ordering must not determine which independent branch can run.
            Collections.shuffle(steps,random);
            Collections.shuffle(recipes,random);
            String first = ((PlanStep.Batch)((PlanStep.Sequence)((PlanStep.Repeat)steps.get(0)).body()).children().get(0)).recipe();
            recipes.add(recipe("join",outputs,Map.of("P",1L)));
            steps.add(new PlanStep.Batch("join",rounds));
            var m = new Machines(plan(recipes,new PlanStep.Sequence(steps),rounds,stock,seeds),trial);
            m.blocked.add(first);
            for (int tick=0;tick<15;tick++) m.step();
            for (int i=0;i<branches;i++) if (!first.equals("open"+i))
                check(m.accepted.getOrDefault("close"+i,0L)>0,"Every unrelated cyclic branch runs while the first is blocked");
            eq(null,m.accepted.get("join"),"A join cannot consume a predicted output from the blocked branch");
            m.runtime=new GraphJobRuntime<>(m.runtime.snapshot());
            m.blocked.clear();
            m.finish();
            eq(seeds,m.refunded,"All independent physical seeds recovered after random order and repeated reloads");
        }
        headroomWakeup();
        sharedSeedConsumer();
        crossLaneWakeup();
        cancelledParallel();
    }

    private static void headroomWakeup() {
        var a=recipe("a",Map.of("C",1L),Map.of("I",1L,"R",9L));
        var b=recipe("b",Map.of("I",1L),Map.of("C",1L,"R",2L));
        var consumer=recipe("consume",Map.of("R",2L),Map.of("P",1L));
        var steps=new PlanStep.Sequence(List.of(new PlanStep.Batch("a",1),new PlanStep.Batch("b",1),new PlanStep.Batch("consume",1)));
        var m=new Machines(plan(List.of(a,b,consumer),steps,1,Map.of("C",1L,"I",1L,"R",Long.MAX_VALUE-10),Map.of("C",1L)),80);
        m.reload=false;
        m.blocked.addAll(Set.of("a","consume"));
        for(int i=0;i<20;i++)m.step();
        eq(null,m.accepted.get("b"),"Prefix output headroom initially protects the earlier producer");
        m.blocked.remove("consume");
        for(int i=0;i<20;i++)m.step();
        eq(1L,m.accepted.get("b"),"Consuming in another lane wakes the blocked prefix headroom reservation");
        m.blocked.clear();m.finish();
    }

    private static void sharedSeedConsumer() {
        var a=recipe("a",Map.of("C",1L,"R",1L),Map.of("I",1L));
        var b=recipe("b",Map.of("I",1L),Map.of("C",1L,"A",1L));
        var steal=recipe("seed-to-B",Map.of("C",1L),Map.of("B",1L));
        var independent=recipe("independent",Map.of("S",1L),Map.of("Q",1L));
        var join=recipe("join",Map.of("A",40L,"B",1L,"Q",1L),Map.of("P",1L));
        var loop=new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("a",1),new PlanStep.Batch("b",1))),40);
        var steps=new PlanStep.Sequence(List.of(loop,new PlanStep.Batch("seed-to-B",1),new PlanStep.Batch("independent",1),new PlanStep.Batch("join",1)));
        var m=new Machines(plan(List.of(a,b,steal,independent,join),steps,1,Map.of("C",1L,"R",40L,"S",1L),Map.of()),87);
        m.blocked.add("a");
        for(int i=0;i<30;i++)m.step();
        eq(null,m.accepted.get("seed-to-B"),"Downstream consumer outside the SCC must not steal its restart seed");
        eq(1L,m.accepted.get("independent"),"Independent raw input is not reserved by the cycle");
        m.blocked.clear();m.finish();
    }

    private static void crossLaneWakeup() {
        // Distinct lanes can depend on each other at different stages. Actual
        // returns must wake them without treating either entire lane as a barrier.
        var a=recipe("a",Map.of("C",1L),Map.of("I",1L,"A",1L));
        var b=recipe("b",Map.of("I",1L,"B",1L),Map.of("C",1L,"P",1L));
        var feed=recipe("feed",Map.of("A",1L),Map.of("B",1L));
        var steps=new PlanStep.Sequence(List.of(new PlanStep.Batch("a",1),new PlanStep.Batch("feed",1),new PlanStep.Batch("b",1)));
        var m=new Machines(plan(List.of(a,feed,b),steps,1,Map.of("C",1L),Map.of("C",1L)),14);
        m.finish();
    }

    private static void cancelledParallel() {
        var a=recipe("a",Map.of("C",1L,"R",1L),Map.of("I",1L));
        var b=recipe("b",Map.of("I",1L),Map.of("C",1L,"P",1L));
        var independent=recipe("independent",Map.of("S",1L),Map.of("Q",1L));
        var loop=new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("a",1),new PlanStep.Batch("b",1))),1000);
        var m=new Machines(plan(List.of(a,b,independent),new PlanStep.Sequence(List.of(loop,new PlanStep.Batch("independent",1000))),1000,
                Map.of("C",1L,"R",1000L,"S",1000L),Map.of("C",1L)),81);
        for(int i=0;i<5;i++)m.step();
        long pushes=m.pushes;
        var physical=new LinkedHashMap<>(m.physical);
        m.runtime.cancel();
        m.runtime=new GraphJobRuntime<>(m.runtime.snapshot());
        for(int i=0;i<100&&!m.runtime.finished();i++)m.step();
        eq(pushes,m.pushes,"Cancel does not redispatch any independent lane");
        eq(physical,m.refunded,"Cancel refunds only the physical holdings once");
        eq(GraphJobRuntime.State.CANCELLED,m.runtime.state(),"All independent flight owners settle on cancellation");
    }

    private static Machines ring(int seeds, int rounds) {
        var a = recipe("a", Map.of("C", 1L, "R", 1L), Map.of("I", 1L));
        var b = recipe("b", Map.of("I", 1L), Map.of("J", 1L));
        var c = recipe("c", Map.of("J", 1L), Map.of("C", 1L, "P", 1L));
        var body = new PlanStep.Sequence(List.of(new PlanStep.Batch("a", seeds), new PlanStep.Batch("b", seeds), new PlanStep.Batch("c", seeds)));
        return new Machines(plan(List.of(a, b, c), new PlanStep.Repeat(body, rounds), seeds * (long) rounds,
                Map.of("C", (long) seeds, "R", seeds * (long) rounds), Map.of("C", (long) seeds)), 741);
    }

    private static void partialStages() {
        var m = ring(4, 5);
        m.step();
        check(m.accepted.getOrDefault("a", 0L) > 0 && m.accepted.get("a") <= 4,
                "Real seeds bound the initial upstream dispatches, including a yielded work slice");
        for (int i = 0; i < 4; i++) m.step();
        check(m.accepted.getOrDefault("b", 0L) > 0, "Partial upstream output starts downstream before slow batches finish");
        check(m.flights.stream().anyMatch(f -> f.recipe.equals("a")), "Upstream is still processing when downstream starts");
        m.runtime = new GraphJobRuntime<>(m.runtime.snapshot());
        m.finish();
        eq(Map.of("P", 20L), m.delivered, "Pipeline delivers exactly the requested amount");
        eq(4L, m.refunded.get("C"), "All four physical restart seeds survive pipelining");
        check(m.overlaps > 0, "Different stages overlap");
    }

    private static void reservePrefix() {
        var a = recipe("a", Map.of("C", 1L), Map.of("I", 1L));
        var b = recipe("b", Map.of("I", 1L), Map.of("C", 1L));
        var c = recipe("take-seed", Map.of("C", 1L), Map.of("P", 1L));
        var m = new Machines(plan(List.of(a, b, c), new PlanStep.Sequence(List.of(
                new PlanStep.Batch("a", 1), new PlanStep.Batch("b", 1), new PlanStep.Batch("take-seed", 1))),
                1, Map.of("C", 1L), Map.of()), 92);
        m.blocked.add("a");
        for (int i = 0; i < 20; i++) m.step();
        eq(0L, m.pushes, "A later consumer must not spend the only seed needed by the earlier cycle");
        m.runtime = new GraphJobRuntime<>(m.runtime.snapshot());
        m.blocked.clear();
        m.finish();
        eq(Map.of("a", 1L, "b", 1L, "take-seed", 1L), m.accepted, "Unblocking the provider resumes the preserved witness exactly once");
    }

    private static void independentBlockedBranch() {
        var a = recipe("a", Map.of("C", 1L, "R", 1L), Map.of("C", 1L, "A", 1L));
        var b = recipe("b", Map.of("S", 1L), Map.of("B", 1L));
        var c = recipe("c", Map.of("A", 1L, "B", 1L), Map.of("P", 1L));
        var m = new Machines(plan(List.of(a, b, c), new PlanStep.Sequence(List.of(
                new PlanStep.Batch("a", 4), new PlanStep.Batch("b", 4), new PlanStep.Batch("c", 4))),
                4, Map.of("C", 2L, "R", 4L, "S", 4L), Map.of("C", 2L)), 3);
        m.blocked.add("a");
        m.step();
        eq(4L, m.accepted.get("b"), "An independent stage can run while a cyclic provider is blocked");
        eq(null, m.accepted.get("c"), "Predicted upstream output cannot feed the join");
        m.blocked.clear();
        m.finish();
    }

    private static void reserveOnlyRequiredAmount() {
        var a = recipe("a", Map.of("C", 2L), Map.of("I", 2L));
        var b = recipe("b", Map.of("I", 2L), Map.of("C", 2L));
        var c = recipe("surplus", Map.of("C", 1L), Map.of("P", 1L));
        var m = new Machines(plan(List.of(a, b, c), new PlanStep.Sequence(List.of(
                new PlanStep.Batch("a", 1), new PlanStep.Batch("b", 1), new PlanStep.Batch("surplus", 1))),
                1, Map.of("C", 3L), Map.of("C", 2L)), 93);
        m.blocked.add("a");
        m.step();
        eq(Map.of("surplus", 1L), m.accepted, "A later stage may consume the surplus above the protected prefix");
        eq(2L, m.runtime.held("C"), "Two seeds remain available to the earlier stage");
        m.blocked.clear();
        m.finish();
        eq(2L, m.refunded.get("C"), "Recovery contract survives early surplus use");
    }

    private static void prefixPeak() {
        var a = recipe("a", Map.of("C", 1L), Map.of("C", 1L, "R", 2L));
        var b = recipe("b", Map.of("R", 2L), Map.of("I", 1L));
        var c = recipe("c", Map.of("X", 1L), Map.of("R", 1L, "P", 1L));
        var m = new Machines(plan(List.of(a, b, c), new PlanStep.Sequence(List.of(
                new PlanStep.Batch("a", 1), new PlanStep.Batch("b", 1), new PlanStep.Batch("c", 1))),
                1, Map.of("C", 1L, "R", Long.MAX_VALUE - 2, "X", 1L), Map.of("C", 1L)), 11);
        m.blocked.addAll(Set.of("a", "b"));
        m.step();
        eq(0L, m.pushes, "A later byproduct must not consume the headroom needed by the earlier prefix");
        m.blocked.clear();
        m.finish();
        eq(Long.MAX_VALUE - 1, m.refunded.get("R"), "Reordered physical long-sized balances remain exact");
    }

    private static void delayedSideOutput() {
        var a = recipe("a", Map.of("R", 1L), Map.of("I", 1L, "X", 1L));
        var b = recipe("b", Map.of("I", 1L, "C", 1L), Map.of("C", 1L, "P", 1L));
        var m = new Machines(plan(List.of(a, b), new PlanStep.Sequence(List.of(new PlanStep.Batch("a", 4), new PlanStep.Batch("b", 4))),
                4, Map.of("R", 4L, "C", 1L), Map.of("C", 1L)), 40);
        m.extraDelay.put("X", 100L);
        for (int i = 0; i < 60; i++) m.step();
        eq(4L, m.accepted.get("b"), "A delayed unrelated side output does not block the funded cyclic successor");
        eq(4L, m.runtime.held("P"), "All final products can be produced before the side output returns");
        eq(4L, m.runtime.waiting("X"), "The full side output obligation is retained");
        eq(false, m.runtime.finished(), "Outstanding side output still prevents final settlement");
        m.finish();
        eq(4L, m.refunded.get("X"), "Delayed side output is received and refunded exactly once");
    }

    private static void cancellation() {
        var m = ring(4, 10);
        for (int i = 0; i < 6; i++) m.step();
        check(m.overlaps > 0, "Cancellation happens with overlapping physical stages");
        var actual = Map.copyOf(m.physical);
        long pushes = m.pushes;
        m.runtime.cancel();
        m.runtime = new GraphJobRuntime<>(m.runtime.snapshot());
        for (int i = 0; i < 100 && !m.runtime.finished(); i++) m.step();
        eq(pushes, m.pushes, "Cancellation never redispatches a prefetched step");
        eq(actual, m.refunded, "Cancellation refunds only independently recorded CPU-held material");
        eq(GraphJobRuntime.State.CANCELLED, m.runtime.state(), "Cancelled pipeline settles");
    }

    private static void largeCompressedPlan() {
        long amount = 1_000_000_000_000L;
        var r = recipe("ring", Map.of("C", 1L, "R", 1L), Map.of("C", 1L, "P", 1L));
        var m = new Machines(plan(List.of(r), new PlanStep.Repeat(new PlanStep.Batch("ring", 1), amount), amount,
                Map.of("C", 4L, "R", amount), Map.of("C", 4L)), 91);
        m.step();
        check(m.runtime.snapshot().pipeline().size() <= 32, "Trillion-run repetition retains only a bounded window");
        m.runtime = new GraphJobRuntime<>(m.runtime.snapshot());
        eq(amount - m.pushes, m.runtime.pendingRuns().get("ring"), "Compressed pending counts stay exact beyond int");
        m.runtime.cancel();
        m.step();
        eq(amount - m.pushes, m.refunded.get("R"), "Cancelling a trillion-run witness does not expand it");
    }

    private static void randomWitnesses() {
        Random random = new Random(731911);
        for (int trial = 0; trial < 200; trial++) {
            Map<String, Long> initial = Map.of("A", 8L, "B", 8L, "C", 8L);
            Map<String, Long> balance = new LinkedHashMap<>(initial);
            List<GraphRecipe<String>> recipes = new ArrayList<>();
            List<PlanStep> steps = new ArrayList<>();
            String[] keys = { "A", "B", "C" };
            for (int index = 0; index < 48; index++) {
                String input;
                do { input = keys[random.nextInt(keys.length)]; } while (balance.getOrDefault(input, 0L) == 0);
                long quantity = 1 + random.nextInt((int) Math.min(3, balance.get(input)));
                long runs = 1 + random.nextInt((int) Math.min(3, balance.get(input) / quantity));
                String output = keys[random.nextInt(keys.length)];
                long produced = 1 + random.nextInt(4);
                var recipe = recipe("r" + index, Map.of(input, quantity), Map.of(output, produced));
                recipes.add(recipe);
                steps.add(new PlanStep.Batch(recipe.id(), runs));
                balance.merge(input, -quantity * runs, Long::sum);
                balance.merge(output, produced * runs, Long::sum);
            }
            String input = balance.entrySet().stream().filter(e -> e.getValue() > 0).findFirst().orElseThrow().getKey();
            recipes.add(recipe("final", Map.of(input, 1L), Map.of("P", 1L)));
            steps.add(new PlanStep.Batch("final", 1));
            balance.merge(input, -1L, Long::sum);
            var m = new Machines(plan(recipes, new PlanStep.Sequence(steps), 1, initial, Map.of()), trial);
            m.finish();
            balance.values().removeIf(v -> v == 0);
            eq(balance, m.refunded, "Random witness independent final material balance " + trial);
        }
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    private static GraphPlan<String> plan(List<GraphRecipe<String>> recipes, PlanStep steps, long amount,
                                          Map<String, Long> initial, Map<String, Long> seeds) {
        Map<String, GraphRecipe<String>> indexed = new LinkedHashMap<>();
        recipes.forEach(recipe -> indexed.put(recipe.id(), recipe));
        return new GraphPlan<>("P", amount, !seeds.isEmpty(), steps, indexed, initial, seeds, Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }

    private record Flight(String recipe, long due, Map<String, Long> outputs) {}

    private static final class Machines implements GraphJobRuntime.Adapter<String> {

        GraphJobRuntime<String> runtime;
        final Map<String, Long> physical = new LinkedHashMap<>(), accepted = new LinkedHashMap<>();
        final Map<String, Long> delivered = new LinkedHashMap<>(), refunded = new LinkedHashMap<>();
        final List<Flight> flights = new ArrayList<>();
        final Set<String> blocked = new HashSet<>();
        final Map<String, Long> extraDelay = new HashMap<>();
        boolean reload = true;
        final Random random;
        long tick, pushes, overlaps, nanos;

        Machines(GraphPlan<String> plan, long seed) {
            runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            physical.putAll(plan.initial());
            random = new Random(seed);
        }

        void step() {
            Collections.shuffle(flights, random);
            for (var iterator = flights.iterator(); iterator.hasNext();) {
                var flight = iterator.next();
                if (flight.due > tick) continue;
                flight.outputs.forEach((key, count) -> {
                    long received = runtime.accept(key, count, false);
                    if (received != 0) physical.merge(key, received, Math::addExact);
                    if (runtime.state() != GraphJobRuntime.State.CANCELLING && runtime.state() != GraphJobRuntime.State.CANCELLED)
                        eq(count, received, "Every declared physical output is accepted once");
                });
                iterator.remove();
            }
            long start = System.nanoTime();
            runtime.tick(this, tick++, 8);
            nanos += System.nanoTime() - start;
            physical.values().removeIf(v -> v == 0);
            eq(physical, runtime.owned(), "Independent physical ledger agrees with runtime");
            if (reload && random.nextInt(7) == 0) runtime = new GraphJobRuntime<>(runtime.snapshot());
        }

        void finish() {
            for (int i = 0; i < 30_000 && !runtime.finished(); i++) step();
            check(runtime.finished(), "Delayed machines eventually finish: " + runtime.reason() + " " + runtime.pendingRuns());
            eq(runtime.plan().patternTimes(), accepted, "Every planned operation is dispatched exactly once");
            eq(Map.of("P", runtime.plan().amount()), delivered, "Exact target delivery");
            check(physical.isEmpty(), "Completed CPU has no unrefunded physical material");
        }

        @Override
        public long capacity(GraphRecipe<String> recipe, long requested) {
            return blocked.contains(recipe.id()) ? 0 : Math.min(1, requested);
        }

        @Override
        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
            inputs.forEach((key, count) -> debit(key, count));
            if (flights.stream().anyMatch(f -> !f.recipe.equals(recipe.id()))) overlaps++;
            long ordinal = accepted.merge(recipe.id(), runs, Math::addExact);
            long delay = recipe.id().equals("a") ? (ordinal % 2 == 0 ? 12 : 1) : 3;
            Map<String, Long> outputs = new LinkedHashMap<>();
            recipe.outputs().forEach((key, count) -> outputs.put(key, Math.multiplyExact(count, runs)));
            for (var delayed : extraDelay.entrySet()) {
                Long amount = outputs.remove(delayed.getKey());
                if (amount != null) flights.add(new Flight(recipe.id(), tick + delay + delayed.getValue(), Map.of(delayed.getKey(), amount)));
            }
            if (!outputs.isEmpty()) flights.add(new Flight(recipe.id(), tick + delay, outputs));
            pushes++;
            return GraphJobRuntime.Outcome.ACCEPTED;
        }

        private void debit(String key, long count) {
            check(physical.getOrDefault(key, 0L) >= count, "No spending predicted or already consumed input " + key);
            physical.merge(key, -count, Long::sum);
        }

        @Override
        public long deliver(String key, long amount) {
            debit(key, amount);
            delivered.merge(key, amount, Math::addExact);
            return amount;
        }

        @Override
        public long refund(String key, long amount) {
            debit(key, amount);
            refunded.merge(key, amount, Math::addExact);
            return amount;
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void eq(Object expected, Object actual, String message) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": " + expected + " != " + actual);
    }
}
