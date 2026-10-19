// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

/** A compiled allocation must reach its owned opportunity across outer handoffs. */
public final class CountCompletionServiceTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                         BigInteger[] witness) {}

    public static void main(String[] args) throws Exception {
        observations();
        handoff(false);
        handoff(true);
        System.out.println("Structural completion service: real outer handoff, fixed opportunity, mandatory visits, " +
                "proof eligibility, shared tail, worker move, cancellation and cleanup passed");
    }

    private static void observations() throws Exception {
        var model = model();
        var budget = budget();
        try (var models = CountModelViews.create(model.rows, model.low, model.high, budget);
             var search = new CountViewSearch(models, budget)) {
            long work = budget.nodes(), bytes = budget.reservedBytes();
            check(search.allocationCompletionQuantum() == 0 && budget.nodes() == work && budget.reservedBytes() == bytes,
                    "Observation admitted an unowned search");
            search.resume(4096);
            check(search.allocationCompletionQuantum() == 0, "Structural preference suppressed mandatory first visits");
            while (search.allocationCompletionQuantum() == 0) {
                if (!search.step()) continue;
                check(search.counts() == null && !search.infeasible(), "Fixture completed before its opportunity");
                search.resume(4096);
            }
            var arms = (List<?>) get(search, "searches");
            Object allocation = null;
            for (Object arm : arms) {
                if (get(arm, "specialist") instanceof CountVectorPartition) allocation = arm;
                var state = (CountPortfolioPolicy.Arm) get(arm, "scheduling");
                if (state.eligible && !state.retired) check(state.selections > 0, "An eligible engine lost its first visit");
            }
            check(allocation != null, "Missing real allocation frontier");
            long ceiling = number(allocation, "continuationLimit"), spent = number(allocation, "work");
            long quantum = search.allocationCompletionQuantum();
            work = budget.nodes(); bytes = budget.reservedBytes();
            check(quantum > 0 && quantum <= 262144 && quantum <= ceiling - spent, "Unbounded completion quantum");
            for (int i = 0; i < 20; i++) check(search.allocationCompletionQuantum() == quantum, "Observation renewed service");
            check(budget.nodes() == work && budget.reservedBytes() == bytes, "Observation rebuilt or advanced a frontier");

            search.mode(CountPortfolioPolicy.Mode.PROOF);
            check(search.allocationCompletionQuantum() == 0, "Candidate-only completion entered proof mode");
            search.mode(CountPortfolioPolicy.Mode.FIRST_WITNESS);
            check(search.allocationCompletionQuantum() == quantum, "Goal change lost or renewed the opportunity");
            // Change completed-work observations only. Original domains and
            // the actual mathematical frontier retain their exact scope.
            set(allocation, "work", ceiling - 7);
            check(search.allocationCompletionQuantum() == 7, "Last completion units were rounded up");
            set(allocation, "work", ceiling);
            check(search.allocationCompletionQuantum() == 0, "Spent opportunity was replenished");
            search.resume(17);
            check(search.allocationCompletionQuantum() == 0 && number(allocation, "continuationLimit") == ceiling,
                    "Owner resume renewed a spent ceiling");
            // Simulate a newly observed checked-prefix/residual milestone,
            // without changing any row, count or mathematical frontier.
            var vector = (CountVectorPartition) get(allocation, "specialist");
            set(vector, "interiorCompletionDepth", 1);
            search.mode(CountPortfolioPolicy.Mode.PROOF);
            check(search.allocationCompletionQuantum() == 0 && number(allocation, "allocationCompletionObserved") == 0,
                    "A residual milestone bypassed proof eligibility");
            search.mode(CountPortfolioPolicy.Mode.FIRST_WITNESS);
            long tail = vector.remainingAllowance();
            quantum = search.allocationCompletionQuantum();
            long extended = number(allocation, "continuationLimit");
            check(quantum > 0 && quantum <= 262144 && extended > ceiling &&
                    extended - ceiling <= Math.min(tail, Math.min(2_097_152, budget.remainingWork() / 8)),
                    "Residual preference grew its request/local share");
            work = budget.nodes(); bytes = budget.reservedBytes();
            for (int i = 0; i < 20; i++) check(search.allocationCompletionQuantum() == quantum &&
                    number(allocation, "continuationLimit") == extended, "Repeated residual observation renewed service");
            check(budget.nodes() == work && budget.reservedBytes() == bytes, "Residual observation rebuilt a frontier");
            set(allocation, "work", extended);
            check(search.allocationCompletionQuantum() == 0, "Same prefix received another opportunity");
            search.resume(17);
            check(search.allocationCompletionQuantum() == 0, "Parent resume renewed a residual preference");
            long vectorWork = number(vector, "work");
            set(vector, "work", number(vector, "maximumWork") - 7);
            set(vector, "interiorCompletionDepth", 2);
            check(search.allocationCompletionQuantum() == 7, "New depth bypassed the allocation's local tail");
            set(allocation, "work", number(allocation, "continuationLimit"));
            check(search.allocationCompletionQuantum() == 0, "Exhausted residual tail was renewed");
            set(vector, "work", vectorWork);
            set(allocation, "work", spent);
            budget.charge(budget.remainingWork() - 7);
            check(search.allocationCompletionQuantum() == 7, "Shared request tail was enlarged");
        }
        check(budget.reservedBytes() == 0, "Observation controls leaked memory");
    }

    private static void handoff(boolean cancel) throws Exception {
        var input = model();
        var embedded = new CountModelReplay.Embedding(input.rows, input.low, input.high);
        var budget = budget();
        var worker = Executors.newSingleThreadExecutor();
        try (var search = new IntegerCountSearch<>(new GraphCompiler<>(embedded.recipes), "target", 1,
                embedded.stock, Map.of(), Set.of(), Set.of(), false, true, budget, System.nanoTime())) {
            @SuppressWarnings("unchecked")
            var branch = ((Deque<IntegerCountBranch<String>>) get(search, "pending")).getFirst();
            while (branch.viewStage != 2) {
                check(branch.state == IntegerCountBranch.State.OPEN, "Fixture closed before compiled service");
                branch.run(4096, List.of(), List.of(), List.of(), null, () -> false);
            }
            var views = branch.viewSearch;
            long firstDeadline = number(views, "until");
            while (views.work() < firstDeadline) {
                check(branch.viewStage == 2, "Fixture left its first compiled sample early");
                branch.run(4096, List.of(), List.of(), List.of(), null, () -> false);
            }
            // Cross the actual outer deadline on another worker. The original
            // branch must keep its compiled portfolio and avoid starting an
            // unrelated whole-model scout while completion is still eligible.
            worker.submit(() -> branch.run(1, List.of(), List.of(), List.of(), null, () -> false)).get();
            check(branch.viewSearch == views && branch.viewStage == 2 && branch.congruence == null &&
                    number(views, "until") > firstDeadline, "Outer sample stranded the structural frontier");
            if (cancel) {
                budget.cancel();
                try { branch.run(1, List.of(), List.of(), List.of(), null, () -> false); throw new AssertionError("Ignored cancellation"); }
                catch (CancellationException expected) { /* All live children remain owned until close. */ }
            } else {
                while (branch.state == IntegerCountBranch.State.OPEN)
                    branch.run(4096, List.of(), List.of(), List.of(), null, () -> false);
                check(branch.plan != null && branch.plan.feasible(), "Continued structural search lost its executable witness");
                var primitives = new HashMap<String, GraphRecipe<String>>();
                embedded.recipes.forEach(recipe -> primitives.put(recipe.id(), recipe));
                var summary = GraphFixtureRegression.summary(branch.plan.steps(), primitives, new IdentityHashMap<>());
                for (var need : summary.need().entrySet()) check(need.getValue().compareTo(
                        BigInteger.valueOf(embedded.stock.getOrDefault(need.getKey(), 0L))) <= 0, "Unfunded primitive prefix");
                check(summary.delta().getOrDefault("target", BigInteger.ZERO).signum() > 0, "Missing target delivery");
                var counts = new BigInteger[input.low.length];
                for (int i = 0; i < counts.length; i++) counts[i] = input.low[i].add(
                        branch.plan.patternTimesExact().getOrDefault("var" + i, BigInteger.ZERO));
                check(valid(input, counts), "Restored count witness violated original rows");
                check(budget.nodes() < 4_000_000, "Structural handoff repeated expensive startup");
            }
        } finally { worker.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Outer completion handoff leaked memory");
    }

    private static Model model() {
        int groups = 22, bins = 3;
        var random = new Random(920176);
        var low = new BigInteger[groups * bins]; var high = low.clone(); var witness = low.clone();
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int g = 0; g < groups; g++) {
            int capacity = 1 + random.nextInt(3), left = capacity;
            var terms = new TreeMap<Integer, BigInteger>();
            for (int b = 0; b < bins; b++) {
                int id = g * bins + b;
                low[id] = BigInteger.valueOf((g + b) % 5 - 2);
                high[id] = low[id].add(BigInteger.valueOf(capacity));
                int units = b == bins - 1 ? left : random.nextInt(left + 1); left -= units;
                witness[id] = low[id].add(BigInteger.valueOf(units));
                terms.put(id, BigInteger.ONE);
            }
            rows.add(new ExactLinearProgram.Constraint(terms, activity(terms, witness)));
        }
        for (int d = 0; d < 2; d++) {
            var weight = new int[groups]; for (int g = 0; g < groups; g++) weight[g] = 1 + random.nextInt(997);
            for (int b = 0; b < bins; b++) {
                var terms = new TreeMap<Integer, BigInteger>();
                for (int g = 0; g < groups; g++) terms.put(g * bins + b, BigInteger.valueOf(-weight[g]));
                rows.add(new ExactLinearProgram.Constraint(terms, activity(terms, witness)));
            }
        }
        var model = new Model(rows, low, high, witness);
        check(valid(model, witness), "Invalid constructive oracle");
        return model;
    }
    private static BigInteger activity(Map<Integer, BigInteger> terms, BigInteger[] point) {
        var sum = BigInteger.ZERO;
        for (var term : terms.entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
        return sum;
    }
    private static boolean valid(Model model, BigInteger[] point) {
        for (int i = 0; i < point.length; i++) if (point[i].compareTo(model.low[i]) < 0 || point[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) if (activity(row.terms(), point).compareTo(row.upper()) > 0) return false;
        return true;
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 16_000_000, 128L << 20, () -> false, System::nanoTime); }
    private static Field field(Object owner, String name) throws Exception { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field; }
    private static Object get(Object owner, String name) throws Exception { return field(owner, name).get(owner); }
    private static void set(Object owner, String name, Object value) throws Exception { field(owner, name).set(owner, value); }
    private static long number(Object owner, String name) throws Exception { return ((Number) get(owner, name)).longValue(); }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
