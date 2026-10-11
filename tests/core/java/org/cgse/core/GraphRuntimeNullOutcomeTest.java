package org.cgse.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An invalid host return cannot certify that an escrow handoff had no side effects. */
public final class GraphRuntimeNullOutcomeTest {
    private static int checks;

    public static void main(String[] args) {
        for (boolean synchronous : List.of(false, true)) {
            ambiguous(synchronous, false);
            ambiguous(synchronous, true);
            cancellationDuringPush(synchronous);
        }
        validOutcomes();
        headroom();
        System.out.println("GraphRuntimeNullOutcomeTest: PASS assertions=" + checks);
    }

    private static void ambiguous(boolean synchronous, boolean throwsAfterTransfer) {
        var host = new Host(plan(4, false));
        host.synchronous = synchronous;
        host.throwsAfterTransfer = throwsAfterTransfer;
        host.runtime.suspend(true);
        host.runtime.tick(host, 0, 16);
        check(host.pushes == 0, "suspended tasks do not dispatch");
        host.runtime.suspend(false);
        check(host.runtime.tick(host, 1, 1) == 0, "uncertain outcome is not reported as accepted dispatch");
        var saved = host.runtime.snapshot();
        check(saved.state() == GraphJobRuntime.State.NEEDS_ATTENTION, "invalid outcome stops at attention state");
        check(saved.reason().equals("DISPATCH_IN_DOUBT"), "handoff preparation completed and has stable failure reason");
        check(saved.uncertainInputs().equals(Map.of("R", 1L)), "possibly transferred escrow remains evidence, not held material");
        check(saved.owned().getOrDefault("R", 0L) == 3, "uncertain transferred input is never restored");
        check(saved.owned().getOrDefault("P", 0L) == (synchronous ? 1 : 0), "only actual synchronous output becomes owned");
        check(saved.expected().getOrDefault("P", 0L) == (synchronous ? 0 : 1), "pending physical output remains explicit");
        check(saved.acceptedRuns().isEmpty() && host.runtime.dispatches() == 0 && host.runtime.rejections() == 0,
                "uncertainty is neither acceptance nor safe rejection");
        check(host.duringPush.uncertainInputs().equals(saved.uncertainInputs()), "synchronous save retains the same transfer evidence");
        for (int reload = 0; reload < 12; reload++) {
            host.runtime = new GraphJobRuntime<>(host.runtime.snapshot());
            host.runtime.suspend(true);
            host.runtime.suspend(false);
            host.runtime.tick(host, 10 + reload, 64);
        }
        check(host.pushes == 1, "reload and pause/resume never redispatch uncertain work");
        check(host.runtime.state() == GraphJobRuntime.State.NEEDS_ATTENTION, "attention survives reload");
        if (!synchronous) check(host.runtime.accept("P", 1, false) == 1, "late actual output can settle its obligation");
        host.runtime.cancel();
        check(host.runtime.state() == GraphJobRuntime.State.CANCELLING, "prepared handoff fields were cleared so cancellation takes effect");
        host.refundLimit = 0;
        host.runtime.tick(host, 100, 64);
        host.runtime = new GraphJobRuntime<>(host.runtime.snapshot());
        host.refundLimit = Long.MAX_VALUE;
        for (int tick = 101; tick < 110 && !host.runtime.finished(); tick++) host.runtime.tick(host, tick, 64);
        check(host.runtime.state() == GraphJobRuntime.State.CANCELLED, "cancellation settles across blocked refund and reload");
        check(host.refunded.equals(Map.of("R", 3L, "P", 1L)), "refund only true held material, never uncertain escrow");
        check(host.transferred == 1 && host.refunded.get("R") + host.transferred == 4, "original input ownership is conserved");
        check(host.runtime.accept("P", 1, false) == 0, "late output bypasses cancelled task");
    }

    private static void cancellationDuringPush(boolean synchronous) {
        var host = new Host(plan(4, false));
        host.synchronous = synchronous;
        host.cancelDuringPush = true;
        host.runtime.tick(host, 0, 1);
        check(host.runtime.state() == GraphJobRuntime.State.CANCELLING, "in-handoff cancel applies after null outcome is classified");
        for (int tick = 1; tick < 10 && !host.runtime.finished(); tick++) host.runtime.tick(host, tick, 64);
        check(host.runtime.state() == GraphJobRuntime.State.CANCELLED && host.pushes == 1, "cancelled handoff is never retried");
        check(host.refunded.getOrDefault("R", 0L) == 3, "cancel during invalid push cannot refund transferred input");
        check(host.refunded.getOrDefault("P", 0L) == (synchronous ? 1 : 0), "cancel refunds only observed callback outputs");
    }

    private static void validOutcomes() {
        for (var outcome : List.of(GraphJobRuntime.Outcome.REJECTED, GraphJobRuntime.Outcome.BLOCKED)) {
            var host = new Host(plan(4, false));
            host.outcome = outcome;
            host.runtime.tick(host, 0, 1);
            check(host.runtime.owned().equals(Map.of("R", 4L)) && host.runtime.expected().isEmpty(), "safe refusal restores escrow atomically");
            check(host.runtime.snapshot().uncertainInputs().isEmpty(), "safe refusal does not create an uncertain transfer");
            host.runtime.tick(host, 1, 8);
            check(host.pushes == 1, "safe refusal keeps bounded retry delay");
            host.runtime.cancel();
            host.runtime.tick(host, 10, 64);
            check(host.refunded.equals(Map.of("R", 4L)), "safe refusal cancellation refunds original material");
        }
        var accepted = new Host(plan(4, false));
        accepted.outcome = GraphJobRuntime.Outcome.ACCEPTED;
        accepted.synchronous = true;
        for (int tick = 0; tick < 30 && !accepted.runtime.finished(); tick++) accepted.runtime.tick(accepted, tick, 16);
        check(accepted.runtime.state() == GraphJobRuntime.State.COMPLETED, "accepted synchronous dispatch still completes");
        check(accepted.delivered == 4 && accepted.transferred == 4, "accepted flow conserves input/output");
    }

    private static void headroom() {
        var host = new Host(plan(1, true));
        host.synchronous = true;
        host.runtime.tick(host, 0, 1);
        check(host.runtime.held("R") == Long.MAX_VALUE, "actual synchronous returned catalyst exactly fills long headroom");
        check(host.runtime.snapshot().uncertainInputs().equals(Map.of("R", 1L)), "returned catalyst does not authorize refunding escrow again");
        host.runtime.cancel();
        for (int tick = 1; tick < 10 && !host.runtime.finished(); tick++) host.runtime.tick(host, tick, 64);
        check(host.refunded.get("R") == Long.MAX_VALUE, "long boundary refund contains one physical copy only");
    }

    private static GraphPlan<String> plan(long amount, boolean returnedCatalyst) {
        var recipe = new GraphRecipe<>("make", "make", List.of(new GraphRecipe.Slot<>("R", 1L, 0)), returnedCatalyst ? Map.of("R", 1L, "P", 1L) : Map.of("P", 1L));
        return new GraphPlan<>("P", amount, false, new PlanStep.Batch("make", amount), Map.of("make", recipe),
                Map.of("R", returnedCatalyst ? Long.MAX_VALUE : amount), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }

    private static final class Host implements GraphJobRuntime.Adapter<String> {
        GraphJobRuntime<String> runtime;
        GraphJobRuntime.Snapshot<String> duringPush;
        GraphJobRuntime.Outcome outcome;
        boolean synchronous, throwsAfterTransfer, cancelDuringPush;
        long pushes, transferred, delivered, refundLimit = Long.MAX_VALUE;
        final Map<String, Long> refunded = new LinkedHashMap<>();
        Host(GraphPlan<String> plan) { runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of()); }
        public long capacity(GraphRecipe<String> recipe, long requested) { return Math.min(1, requested); }
        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
            pushes++;
            if (outcome != GraphJobRuntime.Outcome.REJECTED && outcome != GraphJobRuntime.Outcome.BLOCKED) transferred += inputs.get("R");
            if (synchronous) recipe.executionOutputs().forEach((key, count) -> check(runtime.accept(key, count * runs, false) == count * runs, "physical synchronous return accepted"));
            duringPush = runtime.snapshot();
            if (cancelDuringPush) runtime.cancel();
            if (throwsAfterTransfer) throw new IllegalStateException("host transferred then failed");
            return outcome;
        }
        public long deliver(String key, long amount) { delivered += amount; return amount; }
        public long refund(String key, long amount) {
            long accepted = Math.min(amount, refundLimit);
            if (accepted > 0) refunded.merge(key, accepted, Math::addExact);
            return accepted;
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
