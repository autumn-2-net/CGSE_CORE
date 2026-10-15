package org.cgse.core;

import java.util.*;
import java.util.function.BiConsumer;

/** Physical state receipts must never become synthesized inventory or replayed input transfers. */
public final class StateReturnRuntimeTest {
    private static int checks;

    public static void main(String[] args) {
        changedReturnAndReplacement();
        exactAndPartialReturns();
        synchronousAndRejected();
        olderReturnDuringRejection();
        concurrentDomains();
        equivalentParallelReturns();
        externalAndMalformedContracts();
        returnHeadroomAndMultipleSlots();
        cancellationAndUnknown();
        obligationBoundaries();
        GraphRuntimeTest.run();
        System.out.println("StateReturnRuntimeTest: " + checks + " assertions passed");
    }

    private static GraphRecipe<String> recipe(String id, String tool, String next, String material, String product) {
        return new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>(tool, 1, 0), new GraphRecipe.Slot<>(material, 1, 1)),
                ordered(product, 1, next, 1));
    }

    private static Map<String, Long> ordered(Object... entries) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], ((Number) entries[i + 1]).longValue());
        return result;
    }

    private static GraphPlan<String> plan(String target, long amount, Map<String, Long> stock,
                                           List<GraphRecipe<String>> recipes, PlanStep steps) {
        Map<String, GraphRecipe<String>> index = new LinkedHashMap<>();
        recipes.forEach(r -> index.put(r.id(), r));
        return new GraphPlan<>(target, amount, false, steps, index, stock, Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }

    private static PlanStep sequence(String... recipes) {
        return new PlanStep.Sequence(Arrays.stream(recipes).map(id -> (PlanStep) new PlanStep.Batch(id, 1)).toList());
    }

    private static Fake chain() {
        var a = recipe("a", "T0", "T1", "R", "P");
        var b = recipe("b", "T1", "T2", "R", "P");
        Fake f = new Fake(plan("P", 2, ordered("T0", 1, "R", 2), List.of(a, b), sequence("a", "b")));
        f.choices.put("a", List.of(choice("T1", 1, "T0", "T1")));
        f.choices.put("b", List.of(choice("T2", 1, "T1", "T2")));
        return f;
    }

    private static OutputObligations.ReturnChoice<String> choice(String predicted, long amount, String... allowed) {
        return new OutputObligations.ReturnChoice<>(0, predicted, new LinkedHashSet<>(List.of(allowed)), amount);
    }

    private static void changedReturnAndReplacement() {
        Fake f = chain();
        eq(1, f.tick(0, 1), "first dispatch");
        eq(ordered("P", 1, "T1", 1), f.runtime.expected(), "exact predicted account");
        eq(Set.of("P", "T0", "T1"), f.runtime.receivableKeys(), "receipt subscription includes actual alternatives");
        var saved = f.runtime.snapshot();
        eq(1L, f.runtime.accept("T0", 1, true), "alternative simulation");
        eq(saved, f.runtime.snapshot(), "simulation does not retire tickets");
        eq(0L, f.runtime.accept("T2", 1, false), "unlisted state refused");
        f.runtime.accept("P", 1, false);
        eq(0, f.tick(20, 100), "primary output does not establish missing tool return");
        rejects(f.runtime::beginReplan, "unresolved random return cannot fund replan");
        eq(1L, f.runtime.accept("T0", 1, false), "real better state accepted");
        eq(ordered("R", 1, "P", 1, "T0", 1), f.runtime.owned(), "only real state credited");
        eq(Map.of(), f.runtime.expected(), "predicted state retired");
        eq(true, f.runtime.stateReplanReady(), "changed state requests suffix replacement");
        eq(0, f.tick(100, 100), "old suffix frozen");
        f.reload();
        eq(true, f.runtime.needsStateReplan(), "freeze survives save");
        eq(0, f.tick(101, 100), "load cannot resume old state path");
        var checkpoint = f.runtime.beginReplan();
        var a = f.runtime.plan().recipes().get("a");
        var replacement = plan("P", 2, f.runtime.forecastInventory(), List.of(a), sequence("a"));
        eq(true, f.runtime.replaceSuffix(checkpoint.epoch(), replacement, Map.of(), Map.of()), "funded suffix installed");
        eq(false, f.runtime.needsStateReplan(), "replacement clears state barrier");
        eq(1, f.tick(102, 1), "replacement dispatches once");
        f.runtime.accept("T1", 1, false);
        f.runtime.accept("P", 1, false);
        f.finish();
        eq(Map.of("P", 2L), f.delivered, "complete target delivered");
        eq(Map.of("T1", 1L), f.refunded, "refund physical returned tool");
        eq(Map.of("a", 2L), f.runtime.committedRuns(), "no replay of accepted prefix");
    }

    private static void exactAndPartialReturns() {
        Fake f = chain();
        f.tick(0, 1);
        f.runtime.accept("T1", 1, false);
        eq(false, f.runtime.needsStateReplan(), "predicted return keeps witness valid");
        eq(1, f.tick(1, 1), "precise returned tool immediately reusable");
        f.runtime.accept("T2", 1, false);
        f.runtime.accept("P", 2, false);
        f.finish();
        eq(Map.of("T2", 1L), f.refunded, "exact terminal state refunded");

        var a = recipe("a", "T0", "T1", "R", "P");
        f = new Fake(plan("P", 2, ordered("T0", 2, "R", 2), List.of(a), new PlanStep.Batch("a", 2)));
        f.limit = 2;
        f.choices.put("a", List.of(choice("T1", 2, "T0", "T1")));
        f.tick(0, 1);
        f.runtime.accept("T0", 1, false);
        eq(1L, f.runtime.waiting("T1"), "partial alternative retires only one predicted unit");
        eq(false, f.runtime.stateReplanReady(), "wait for all state receipts");
        f.reload();
        eq(1L, f.runtime.snapshot().obligations().flights().get(0).choices().get(0).amount(), "remaining choice count persisted");
        f.runtime.accept("T1", 1, false);
        eq(true, f.runtime.stateReplanReady(), "mixed actual results resolve one batch");
        eq(ordered("T0", 1, "T1", 1), f.runtime.owned(), "distinct physical states retained");
    }

    private static void synchronousAndRejected() {
        Fake f = chain();
        f.during = (host, r) -> {
            eq(Set.of("P", "T0", "T1"), host.runtime.receivableKeys(), "subscribe before synchronous provider callback");
            host.runtime.accept("P", 1, false);
            host.runtime.accept("T0", 1, false);
            host.handoff = host.runtime.snapshot();
        };
        f.tick(0, 100);
        eq(1, f.pushes, "changed synchronous return freezes same tick");
        eq(true, f.runtime.stateReplanReady(), "successful synchronous receipt ready to replan");
        var recovered = new GraphJobRuntime<>(f.handoff);
        eq(GraphJobRuntime.State.NEEDS_ATTENTION, recovered.state(), "save inside push remains ambiguous");
        eq(ordered("R", 1, "P", 1, "T0", 1), recovered.owned(), "handoff save never duplicates escrow");
        eq(Map.of(), recovered.expected(), "handoff receipt already accounted");

        f = chain();
        f.outcome = GraphJobRuntime.Outcome.REJECTED;
        f.tick(0, 1);
        eq(ordered("T0", 1, "R", 2), f.runtime.owned(), "clean rejection refunds inputs");
        eq(Set.of(), f.runtime.receivableKeys(), "rejected return owner unregistered");
        eq(false, f.runtime.pendingStateReturns(), "rejected choice not retained");
        f.outcome = GraphJobRuntime.Outcome.ACCEPTED;
        eq(1, f.tick(10, 1), "rejected choice does not prevent retry");

        f = chain();
        f.outcome = GraphJobRuntime.Outcome.REJECTED;
        f.during = (host, r) -> host.runtime.accept("T0", 1, false);
        f.tick(0, 1);
        eq(GraphJobRuntime.State.NEEDS_ATTENTION, f.runtime.state(), "receipt contradicts rejection");
        eq(ordered("R", 1, "T0", 1), f.runtime.owned(), "ambiguous receipt not combined with input refund");
        eq(Map.of("P", 1L), f.runtime.expected(), "known output obligation survives ambiguous return");
        f.reload();
        eq(0, f.tick(100, 100), "ambiguous restore never retries");
    }

    private static Fake parallel(boolean overlap) {
        var a = recipe("a", "A0", "A1", "RA", "I");
        var b = recipe("b", "B0", "B1", "RB", "J");
        var end = new GraphRecipe<>("end", "end", List.of(new GraphRecipe.Slot<>("I", 1), new GraphRecipe.Slot<>("J", 1)), Map.of("P", 1L));
        Fake f = new Fake(plan("P", 1, ordered("A0", 1, "RA", 1, "B0", 1, "RB", 1), List.of(a, b, end), sequence("a", "b", "end")));
        f.choices.put("a", List.of(choice("A1", 1, "A0", "A1", "X")));
        f.choices.put("b", List.of(choice("B1", 1, "B0", "B1", overlap ? "X" : "Y")));
        return f;
    }

    private static void olderReturnDuringRejection() {
        Fake f = parallel(false);
        f.permitted = Set.of("a");
        f.tick(0, 1);
        f.permitted = Set.of("b");
        f.outcome = GraphJobRuntime.Outcome.REJECTED;
        f.during = (host, r) -> {
            host.runtime.accept("A0", 1, false);
            host.runtime.accept("I", 1, false);
            host.handoff = host.runtime.snapshot();
        };
        f.tick(10, 1);
        eq(GraphJobRuntime.State.RUNNING, f.runtime.state(), "older receipt does not make unrelated rejection ambiguous");
        eq(ordered("B0", 1, "RB", 1, "A0", 1, "I", 1), f.runtime.owned(), "old actual return and rejected inputs distinct");
        eq(Map.of(), f.runtime.expected(), "old obligations retired; rejected outputs removed");
        eq(true, f.runtime.stateReplanReady(), "old state change retained despite unrelated rejected push");
        f.reload();
        eq(true, f.runtime.stateReplanReady(), "mixed event boundary restores cleanly");
        var inPush = new GraphJobRuntime<>(f.handoff);
        eq(GraphJobRuntime.State.NEEDS_ATTENTION, inPush.state(), "in-progress rejected push not known rejected at save");
        eq(Set.of("J", "B0", "B1", "Y"), inPush.receivableKeys(), "snapshot retains exact pending return contract");
    }

    private static void concurrentDomains() {
        Fake f = parallel(true);
        f.tick(0, 10);
        eq(1, f.pushes, "overlapping random domains serialized across recipes");
        eq(1L, f.runtime.pendingRuns().get("b"), "blocked return domain remains undispatched");
        f.runtime.accept("A1", 1, false);
        f.tick(10, 10);
        eq(2, f.pushes, "next domain dispatches after predecessor receipt");

        f = parallel(false);
        f.tick(0, 10);
        eq(2, f.pushes, "disjoint random domains keep parallelism");
        f.runtime.accept("A0", 1, false);
        eq(false, f.runtime.stateReplanReady(), "other variable owner must resolve first");
        rejects(f.runtime::beginReplan, "no forecast of unresolved independent variable");
        f.reload();
        f.runtime.accept("B1", 1, false);
        eq(true, f.runtime.stateReplanReady(), "all variable returns settled even while fixed outputs are in flight");
        eq(ordered("I", 1, "J", 1), f.runtime.expected(), "fixed output forecast retained");
        eq(ordered("A0", 1, "B1", 1, "I", 1, "J", 1), f.runtime.forecastInventory(), "forecast contains no phantom alternative");
    }

    private static void equivalentParallelReturns() {
        var a = recipe("a", "T0", "T1", "R", "P");
        Fake f = new Fake(plan("P", 4, ordered("T0", 4, "R", 4), List.of(a), new PlanStep.Batch("a", 4)));
        f.choices.put("a", List.of(choice("T1", 1, "T0", "T1")));
        f.tick(0, 10);
        eq(4, f.pushes, "four physical tools with equal return contracts dispatch in parallel");
        eq(4L, f.runtime.receivable("T0"), "alternative receipts aggregate all physical owners");
        f.runtime.accept("P", 4, false);
        f.runtime.accept("T1", 1, false);
        f.runtime.accept("T0", 1, false);
        f.reload();
        eq(2L, f.runtime.receivable("T1"), "partial parallel owners survive reload");
        eq(false, f.runtime.stateReplanReady(), "unreceived tool keeps barrier");
        f.runtime.accept("T0", 2, false);
        eq(Map.of(), f.runtime.expected(), "all interchangeable obligations retire exactly once");
        eq(ordered("P", 4, "T1", 1, "T0", 3), f.runtime.owned(), "four actual tools retained without predicted duplicates");
        f.runtime.cancel();
        f.finish();
        eq(ordered("P", 4, "T1", 1, "T0", 3), f.refunded, "cancel refunds outputs and only the four actual tool states");

        var ledger = new OutputObligations<String>(Map.of());
        var sameSlot = List.of(choice("T1", 1, "T0", "T1"), choice("T1", 1, "T0", "T1"));
        ledger.dispatch("mixed", 2, ordered("P", 2, "T1", 2), false, sameSlot);
        ledger = new OutputObligations<>(ledger.snapshot());
        ledger.receive("T0", 1, 0);
        ledger.receive("T1", 1, 0);
        eq(false, ledger.hasChoices(), "equal slots in one batch resolve independently");
        eq(Map.of("P", 2L), ledger.all(), "same-batch returns do not retire main output");
        ledger.dispatch("disjoint", 1, ordered("A1", 1, "B1", 1), false,
                List.of(choice("A1", 1, "A0", "A1"), choice("B1", 1, "B0", "B1")));
        ledger.receive("B0", 1, 0);
        ledger.receive("A1", 1, 0);
        eq(false, ledger.hasChoices(), "same input slot can contain distinct disjoint states");

        ledger = new OutputObligations<>(Map.of());
        var contract = List.of(choice("T1", 1, "T0", "T1"));
        ledger.dispatch("older", 1, ordered("P", 1, "T1", 1), false, contract);
        long preparing = ledger.dispatch("preparing", 1, ordered("P", 1, "T1", 1), true, contract);
        eq(0L, ledger.receive("T0", 1, preparing).preparingAmount(), "older equivalent return does not taint new handoff");
        ledger.reject(preparing);
        eq(false, ledger.hasChoices(), "reject removes only new owner after older return");
        eq(Map.of("P", 1L), ledger.all(), "older fixed output still owed after rejection");
        ledger.dispatch("next", 1, ordered("Q", 1, "T1", 1), false, contract);
        ledger.receive("T1", 1, 0);
        eq(false, ledger.hasChoices(), "rejected group can be registered and settled again");
    }

    private static void externalAndMalformedContracts() {
        var a = recipe("a", "T0", "T1", "R", "P");
        var p = plan("P", 1, ordered("T0", 1, "R", 1, "X", 1), List.of(a), sequence("a"));
        Fake f = new Fake(p);
        f.runtime = new GraphJobRuntime<>(p, ordered("T0", 1, "R", 1), Map.of("X", 1L));
        f.choices.put("a", List.of(choice("T1", 1, "T1", "X")));
        f.tick(0, 1);
        eq(0, f.pushes, "external wait cannot be stolen by a return choice");
        f.runtime.accept("X", 1, false);
        f.tick(10, 1);
        eq(1, f.pushes, "consumed external obligation unlocks return domain");

        f = chain();
        f.choices.put("a", List.of(choice("not-an-output", 1, "not-an-output")));
        f.tick(0, 1);
        eq(GraphJobRuntime.State.NEEDS_ATTENTION, f.runtime.state(), "malformed host contract rejected");
        eq(ordered("T0", 1, "R", 2), f.runtime.owned(), "invalid contract cannot transfer inputs");
        eq(0, f.pushes, "invalid contract stopped before provider push");
    }

    private static void cancellationAndUnknown() {
        Fake f = chain();
        f.tick(0, 1);
        f.runtime.accept("T0", 1, false);
        f.runtime.cancel();
        eq(Set.of(), f.runtime.receivableKeys(), "cancel drops all exact and alternate subscriptions");
        eq(0L, f.runtime.accept("P", 1, false), "late outputs return to network after cancel");
        f.reload();
        f.finish();
        eq(ordered("R", 1, "T0", 1), f.refunded, "cancel refunds actual state only");

        f = chain();
        f.outcome = GraphJobRuntime.Outcome.IN_DOUBT;
        f.tick(0, 1);
        f.reload();
        eq(Set.of("P", "T0", "T1"), f.runtime.receivableKeys(), "in-doubt batch retains possible real receipts");
        eq(1L, f.runtime.accept("T0", 1, false), "real return accepted while dispatch is uncertain");
        eq(GraphJobRuntime.State.NEEDS_ATTENTION, f.runtime.state(), "receipt does not authorize replay after uncertainty");
        f.runtime.cancel();
        f.finish();
        eq(ordered("R", 1, "T0", 1), f.refunded, "in-doubt input not refunded twice");
    }

    private static void returnHeadroomAndMultipleSlots() {
        var a = recipe("a", "T0", "T1", "R", "P");
        Fake f = new Fake(plan("P", 1, ordered("T0", 1, "R", 1, "X", Long.MAX_VALUE), List.of(a), sequence("a")));
        f.choices.put("a", List.of(choice("T1", 1, "T1", "X")));
        f.tick(0, 1);
        eq(0, f.pushes, "reserve space for every legal actual return");
        eq(1L, f.runtime.owned().get("T0"), "headroom refusal does not spend tool");
        eq("WAIT_RETURN_CAPACITY", f.runtime.reason(), "headroom wait explicit");

        var both = new GraphRecipe<>("both", "both", List.of(new GraphRecipe.Slot<>("A0", 1, 0),
                new GraphRecipe.Slot<>("B0", 1, 1)), ordered("A1", 1, "B1", 1, "P", 1));
        f = new Fake(plan("P", 1, ordered("A0", 1, "B0", 1), List.of(both), sequence("both")));
        f.choices.put("both", List.of(choice("A1", 1, "A0", "A1"),
                new OutputObligations.ReturnChoice<>(1, "B1", Set.of("B0", "B1"), 1)));
        f.tick(0, 1);
        f.runtime.accept("A0", 1, false);
        f.reload();
        eq(Set.of("P", "B0", "B1"), f.runtime.receivableKeys(), "one slot receipt leaves other slot subscribed");
        eq(false, f.runtime.stateReplanReady(), "one unresolved slot blocks state forecast");
        f.runtime.accept("B0", 1, false);
        eq(true, f.runtime.stateReplanReady(), "multiple actual slots resolve independently");
        eq(ordered("A0", 1, "B0", 1), f.runtime.owned(), "both physical input states owned");
        eq(Map.of("P", 1L), f.runtime.expected(), "no assumed damaged tool enters forecast");
    }

    private static void obligationBoundaries() {
        var ledger = new OutputObligations<String>(Map.of());
        ledger.dispatch("a", 1, ordered("P", 1, "T1", 1), false, List.of(choice("T1", 1, "T0", "T1")));
        eq(false, ledger.compatible(Map.of("T0", 1L), List.of()), "fixed output conflicts with variable alternative");
        rejects(() -> ledger.addExternal(Map.of("T0", 1L)), "late external overlap refused");
        rejects(() -> new OutputObligations<>(new OutputObligations.Snapshot<>(Map.of("T0", 1L), ledger.snapshot().flights(), 2)), "overlapping persisted scopes rejected");
        rejects(() -> choice("T1", 1, "T0"), "predicted key must be legal");
        rejects(() -> choice("T1", 0, "T1"), "zero or optional mandatory return disallowed");
        var allowed = new HashSet<>(Set.of("A", "B"));
        var copied = new OutputObligations.ReturnChoice<>(0, "A", allowed, 1);
        allowed.clear();
        eq(Set.of("A", "B"), copied.allowed(), "host collection cannot mutate captured contract");
    }

    private static final class Fake implements GraphJobRuntime.Adapter<String> {
        GraphJobRuntime<String> runtime;
        Map<String, List<OutputObligations.ReturnChoice<String>>> choices = new HashMap<>();
        Set<String> permitted;
        long limit = 1;
        int pushes;
        GraphJobRuntime.Outcome outcome = GraphJobRuntime.Outcome.ACCEPTED;
        BiConsumer<Fake, GraphRecipe<String>> during;
        GraphJobRuntime.Snapshot<String> handoff;
        Map<String, Long> delivered = new LinkedHashMap<>(), refunded = new LinkedHashMap<>();

        Fake(GraphPlan<String> plan) { runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of()); }
        public long capacity(GraphRecipe<String> recipe, long requested) { return permitted == null || permitted.contains(recipe.id()) ? Math.min(limit, requested) : 0; }
        public List<OutputObligations.ReturnChoice<String>> returnChoices(GraphRecipe<String> recipe, long runs) { return choices.getOrDefault(recipe.id(), List.of()); }
        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
            pushes++;
            if (during != null) during.accept(this, recipe);
            return outcome;
        }
        public long deliver(String key, long amount) { delivered.merge(key, amount, Long::sum); return amount; }
        public long refund(String key, long amount) { refunded.merge(key, amount, Long::sum); return amount; }
        int tick(long tick, int work) { return runtime.tick(this, tick, work, () -> 0); }
        void reload() { runtime = new GraphJobRuntime<>(runtime.snapshot()); }
        void finish() {
            for (int tick = 100; tick < 110 && !runtime.finished(); tick++) tick(tick, 100);
            eq(true, runtime.finished(), "task settles");
        }
    }

    private static void eq(Object expected, Object actual, String message) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": " + expected + " != " + actual);
    }

    private static void rejects(Runnable action, String message) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
}
