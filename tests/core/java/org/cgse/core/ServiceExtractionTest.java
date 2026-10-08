package org.cgse.core;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Host-free checks for request cancellation, retry coordination and request orchestration. */
public final class ServiceExtractionTest {

    public static void main(String[] args) throws Exception {
        requestLifecycle();
        inventory();
        retryPolicy();
        replanLifecycle();
        requestPlanning();
        System.out.println("ServiceExtractionTest: PASS");
    }

    private static void requestLifecycle() {
        var clock = new AtomicLong(1);
        var budget = new PlanningBudget(0, 100, 1024, () -> false, clock::get);
        var request = new PlanningRequest<String, String>(budget, clock::get);
        var keys = new HashSet<>(Set.of("ore"));
        request.dependencies(keys);
        keys.add("later");
        check(request.dependencies().equals(Set.of("ore")), "dependency snapshot must not alias its source");
        clock.addAndGet(999_000_000);
        check(!request.noticeDue(1000), "notice must wait for threshold");
        clock.addAndGet(1_000_000);
        check(request.noticeDue(1000), "notice due at threshold");
        clock.addAndGet(999_999_999);
        check(!request.noticeDue(1000), "notice throttle must retain one-second period");
        clock.incrementAndGet();
        check(request.noticeDue(1000), "notice due after exactly one second");
        request.cancel(true);
        var lateWorker = new CompletableFuture<String>();
        request.attach(lateWorker);
        check(lateWorker.isCancelled(), "cancel before attach must cancel the late worker");
        expect(CancellationException.class, budget::check);
        check(!request.noticeDue(0), "cancelled requests do not publish progress notices");

        var pending = new PlanningRequest<String, String>(budget());
        var attached = new CompletableFuture<String>();
        pending.attach(attached);
        pending.cancel(false);
        check(attached.isCancelled(), "cancel after attach must cancel worker");
        var complete = new PlanningRequest<String, String>(budget());
        complete.complete("ready");
        check(!complete.cancel(false), "completed request cannot be cancelled");
        complete.budget().check();
    }

    private static void inventory() {
        Map<String, Long> network = Map.of("infinite", Long.MAX_VALUE, "ore", 7L);
        var merged = PlanningInventory.availability(network, Map.of("infinite", 9L, "ore", 2L, "seed", 1L));
        check(merged.equals(Map.of("infinite", Long.MAX_VALUE, "ore", 9L, "seed", 1L)), "availability must saturate, not overflow");
        check(network.get("ore") == 7, "availability must not mutate network stock");
        expect(IllegalArgumentException.class, () -> PlanningInventory.availability(Map.of(), Map.of("ore", -1L)));
        var requirements = PlanningInventory.requirements(Map.of("ore", 10L, "seed", 1L, "external", 3L),
                Map.of("ore", 4L, "seed", 2L), Map.of("ore", 2L, "external", 9L));
        check(requirements.needed().equals(Map.of("ore", 4L)), "only unfunded input must be physically extracted");
        check(requirements.emitted().equals(Map.of("ore", 2L, "external", 3L)), "external supply must be limited to deficit");
    }

    private static void retryPolicy() {
        var retry = new ReplanRetry();
        long tick = 100;
        for (int delay : new int[] { 40, 80, 160, 320, 600, 600 }) {
            check(retry.failed(tick) == delay, "retry delay changed");
            check(!retry.ready(tick + delay - 1) && retry.ready(tick + delay), "retry boundary changed");
            tick += delay;
        }
        check(retry.shouldLog(100, "a"), "first failure must log");
        check(!retry.shouldLog(699, "a"), "duplicate failures must remain throttled");
        check(retry.shouldLog(700, "a"), "duplicate failure log deadline");
        check(retry.shouldLog(701, "b"), "different failure must log immediately");
        retry.reset();
        check(retry.failures() == 0 && retry.ready(0), "reset must clear retry state");
    }

    private static void replanLifecycle() {
        var runtime = runtime();
        var coordinator = new ReplanCoordinator<String, String>();
        var request = new PlanningRequest<String, String>(budget());
        coordinator.begin(runtime, checkpoint -> request);
        var host = new TestHost();
        check(coordinator.update(runtime, 10, host) == ReplanCoordinator.Update.WAITING, "pending request must not block or install");
        check(host.installs == 0, "pending request touched host installation");
        request.dependencies(Set.of("new-suffix"));
        request.complete("missing");
        check(coordinator.update(runtime, 10, host) == ReplanCoordinator.Update.FAILED, "missing suffix must wait for dependency");
        check(host.watched.equals(Set.of("new-suffix", "product", "seed")), "watch must retain roots but exclude retired recipe inputs");
        check(host.failure.delay() == 40 && host.failure.attempts() == 1, "first failure retry accounting");
        check(coordinator.update(runtime, 100, host) == ReplanCoordinator.Update.WAITING, "elapsed cooldown alone must not retry");
        host.changed.run();
        check(coordinator.update(runtime, 49, host) == ReplanCoordinator.Update.WAITING, "storage event cannot shorten quiet period");
        runtime.suspend(true);
        check(coordinator.update(runtime, 50, host) == ReplanCoordinator.Update.WAITING, "suspension must keep event latched");
        runtime.suspend(false);
        check(coordinator.update(runtime, 50, host) == ReplanCoordinator.Update.RETRY, "one latched event must suffice after cooldown");
        check(coordinator.checkpoint() == null, "retry must release old checkpoint");
        var second = new PlanningRequest<String, String>(budget());
        coordinator.begin(runtime, checkpoint -> second);
        second.completeExceptionally(new IllegalStateException("capture failed"));
        check(coordinator.update(runtime, 51, host) == ReplanCoordinator.Update.FAILED, "capture failure must remain retryable");
        check(host.watched.equals(Set.of("ore", "seed", "product")), "failed capture must watch conservative pending recipe dependencies");
        check(host.failure.attempts() == 2 && host.failure.delay() == 80, "retry restart must preserve accumulated failures");
        check(runtime.reason().equals("REPLAN_IllegalStateException: capture failed"), "failure diagnostic mapping changed");

        var staleRuntime = runtime();
        var stale = new ReplanCoordinator<String, String>();
        var oldRequest = new PlanningRequest<String, String>(budget());
        stale.begin(staleRuntime, checkpoint -> oldRequest);
        staleRuntime.abortReplan(stale.checkpoint().epoch(), "replaced");
        staleRuntime.beginReplan();
        oldRequest.complete("ready");
        int installs = host.installs;
        check(stale.update(staleRuntime, 0, host) == ReplanCoordinator.Update.STALE, "obsolete epoch must be rejected");
        check(host.installs == installs, "stale result must never enter host installation");

        var cancelledRuntime = runtime();
        var cancelled = new ReplanCoordinator<String, String>();
        var cancelledRequest = new PlanningRequest<String, String>(budget());
        cancelled.begin(cancelledRuntime, checkpoint -> cancelledRequest);
        cancelled.clear(true);
        check(cancelledRequest.isCancelled(), "clearing coordinator must cancel pending work");

        var successfulRuntime = runtime();
        var successful = new ReplanCoordinator<String, String>();
        var successfulRequest = new PlanningRequest<String, String>(budget());
        successful.begin(successfulRuntime, checkpoint -> successfulRequest);
        successfulRequest.complete("ready");
        host.succeed = true;
        check(successful.update(successfulRuntime, 0, host) == ReplanCoordinator.Update.INSTALLED, "successful installation must finish request");
        check(host.thread == Thread.currentThread(), "host installation must run on polling thread");
    }

    private static void requestPlanning() throws Exception {
        var compiler = new GraphCompiler<>(List.of(new GraphRecipe<>("smelt", "smelt",
                List.of(new GraphRecipe.Slot<>("ore", 1)), Map.of("product", 1L))));
        var less = work(compiler, 10, Map.of("ore", 4L), Set.of(), null, true, false, false, budget());
        GraphPlan<String> reduced = run(less);
        check(reduced.feasible() && reduced.amount() == 4, "craft-less must retain largest feasible probe");
        var report = work(compiler, 10, Map.of("ore", 4L), Set.of(), null, false, false, false, budget());
        GraphPlan<String> missing = run(report);
        check(!missing.feasible() && missing.amount() == 10 && !missing.missingExact().isEmpty(), "report-missing must keep full order");
        var direct = work(new GraphCompiler<>(List.of()), 5, Map.of("product", 99L), Set.of("product"), null, false, false, false, budget());
        GraphPlan<String> emitted = run(direct);
        check(emitted.feasible() && emitted.initial().get("product") == 5 && !direct.work().availability().containsKey("product"), "direct emission cannot consume cached target stock");
        var checkpoint = new GraphJobRuntime.ReplanCheckpoint<>(1, "product", 4, Map.<String, Long>of(), Map.of("ore", 3L));
        check(run(work(compiler, 4, Map.of("ore", 1L), Set.of(), checkpoint, false, false, false, budget())).feasible(), "replan solve must include owned/in-flight forecast");
        var unavailable = work(new GraphCompiler<>(List.of()), 5, Map.of(), Set.of(), null, true, false, false, budget());
        check(run(unavailable).amount() == 5, "unavailable target must not trigger meaningless craft-less probes");

        var quickBudget = budget();
        var fallback = work(compiler, 4, Map.of("ore", 4L), Set.of(), null, false, true, false, quickBudget);
        GraphPlan<String> recovered = fallback.work().limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT));
        check(recovered.feasible() && fallback.work().fallbackMode(), "bounded ordinary fallback must survive main search limit");
        expect(CancellationException.class, quickBudget::check);
        check(less.work().limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT)).result() == GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL,
                "limited search must retain feasible result with reduced proof status");
        var bounded = work(compiler, 10, Map.of("ore", 4L), Set.of(), null, false, false, true, budget());
        try {
            run(bounded);
            throw new AssertionError("bounded alternatives cannot publish an unproven missing preview");
        } catch (java.util.concurrent.ExecutionException expected) {
            check(expected.getCause() instanceof PlanningBudget.Exhausted exhausted && exhausted.limit() == PlanningBudget.Limit.SEARCH_LIMIT,
                    "bounded alternatives must signal search limit");
        }
    }

    private record TestWork(RequestPlanningWork<String> work, PlanningBudget budget) {}

    private static TestWork work(GraphCompiler<String> compiler, long amount, Map<String, Long> stock,
                                                    Set<String> emitable, GraphJobRuntime.ReplanCheckpoint<String> checkpoint,
                                                    boolean craftLess, boolean fallback, boolean bounded, PlanningBudget budget) {
        return new TestWork(new RequestPlanningWork<>(compiler, "product", amount, stock, emitable, checkpoint, false, craftLess,
                craftLess ? "CRAFT_LESS" : "REPORT_MISSING_ITEMS", CatalystPolicy.MINIMAL, fallback, bounded, budget, () -> false, report -> {}), budget);
    }

    private static GraphPlan<String> run(TestWork work) throws Exception {
        try (var scheduler = new PlanningScheduler(1, 4, 4096, 2_000_000)) {
            return scheduler.submit(work.work(), work.budget()).get(10, TimeUnit.SECONDS);
        }
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 1_000_000, () -> false);
    }

    private static GraphJobRuntime<String> runtime() {
        var recipe = new GraphRecipe<>("make", "make", List.of(new GraphRecipe.Slot<>("ore", 1), new GraphRecipe.Slot<>("seed", 1)),
                Map.of("product", 1L, "seed", 1L));
        var initial = Map.of("ore", 1L, "seed", 1L);
        var plan = new GraphPlan<>("product", 1, true, new PlanStep.Batch("make", 1), Map.of("make", recipe), initial,
                Map.of("seed", 1L), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        return new GraphJobRuntime<>(plan, initial, Map.of());
    }

    private static final class TestHost implements ReplanCoordinator.Host<String, String> {
        private int installs;
        private boolean succeed;
        private Set<String> watched;
        private Runnable changed;
        private ReplanCoordinator.Failure failure;
        private Thread thread;

        @Override
        public String install(String plan) {
            installs++;
            thread = Thread.currentThread();
            return succeed ? null : "REPLAN_MISSING_INPUT";
        }

        @Override
        public void watch(Set<String> dependencies, Runnable changed) {
            watched = Set.copyOf(dependencies);
            this.changed = changed;
        }

        @Override
        public void failed(String diagnostic, ReplanCoordinator.Failure failure) {
            this.failure = failure;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable operation) {
        try {
            operation.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
