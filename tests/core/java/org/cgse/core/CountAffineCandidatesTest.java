// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Alternative witnesses reuse an exact basis, without exporting a partial rejection as a cut. */
public final class CountAffineCandidatesTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}
    private record Result(List<List<BigInteger>> candidates, long work) {}

    public static void main(String[] args) throws Exception {
        int distinct = 0;
        for (int seed = 0; seed < 6; seed++) {
            var model = model(seed);
            var whole = solve(model, 262144, null);
            var sliced = solve(model, seed == 0 ? 1 : 127, null);
            check(whole.equals(sliced), "Worker grants changed the candidate stream or work");
            int count = new HashSet<>(whole.candidates).size();
            check(count >= 4, "Basis did not generate distinct alternatives: " + count);
            distinct += count;
        }
        var executor = Executors.newSingleThreadExecutor();
        try {
            check(solve(model(2), 127, null).equals(solve(model(2), 127, executor)), "Worker handoff lost the basis");
        } finally { executor.shutdownNow(); }
        limits();
        for (int seed : new int[] {0, 1}) {
            var whole = solve(model(seed), 262144, null, true);
            var sliced = solve(model(seed), 1, null, true);
            check(whole.equals(sliced), "Widened targets renewed the grant or lost a basis");
            check(whole.candidates.size() > 33, "Widened original-coordinate targets not exercised");
        }
        System.out.println("Affine alternatives: 6 whole/sliced streams, " + distinct + " distinct original-row witnesses; handoff, cancellation and memory refusal passed");
    }

    private static Model model(int seed) {
        int n = 10;
        var random = new Random(37187 + seed);
        var ids = new ArrayList<Integer>(); for (int i = 0; i < n; i++) ids.add(i); Collections.shuffle(ids, random);
        var low = new BigInteger[n]; var high = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            low[i] = BigInteger.valueOf(i % 3 - 1);
            if ((seed & 1) != 0) low[i] = low[i].add(BigInteger.ONE.shiftLeft(70).multiply(BigInteger.valueOf(i % 2 == 0 ? 1 : -1)));
            high[i] = low[i].add(BigInteger.valueOf(4));
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int dimension = 0; dimension < 2; dimension++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            BigInteger rhs = BigInteger.ZERO;
            for (int i : ids) {
                var a = BigInteger.valueOf(dimension == 0 ? 2 : i % 2 + 2);
                if (seed >= 4) a = a.shiftLeft(80);
                terms.put(i, a); rhs = rhs.add(a.multiply(low[i].add(BigInteger.TWO)));
            }
            rows.add(new ExactLinearProgram.Constraint(terms, rhs));
            var opposite = new LinkedHashMap<Integer, BigInteger>(); terms.forEach((i, a) -> opposite.put(i, a.negate()));
            rows.add(new ExactLinearProgram.Constraint(opposite, rhs.negate()));
        }
        Collections.shuffle(rows, random);
        return new Model(rows, low, high);
    }

    private static Result solve(Model model, long quantum, ExecutorService executor) throws Exception {
        return solve(model, quantum, executor, false);
    }

    private static Result solve(Model model, long quantum, ExecutorService executor, boolean widened) throws Exception {
        var budget = new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime);
        var candidates = new ArrayList<List<BigInteger>>();
        try (var search = new CountAffineLattice(model.rows, model.low, model.high, budget, Math.max(32768, quantum)).retained()) {
            check(!search.nextCandidate(), "Unsolved search accepted a rejection");
            for (int visits = 0; ; visits++) {
                check(visits < 1_000_000, "Candidate enumeration stalled");
                boolean done = executor == null ? search.step() : executor.submit(search::step).get(30, TimeUnit.SECONDS);
                if (!done) continue;
                if (search.paused()) {
                    if (widened) search.widenAlternativeTargets();
                    search.resume(quantum); continue;
                }
                var counts = search.counts();
                if (counts == null) break;
                check(valid(model, counts), "Original-row or domain violation");
                candidates.add(List.of(counts.clone()));
                check(candidates.size() <= (widened ? 257 : 33), "Alternative bound was renewed");
                if (widened) search.widenAlternativeTargets();
                if (!search.nextCandidate()) break;
            }
            check(budget.diagnostics().contains("basis_reused"), "Reuse was not exercised");
        }
        check(budget.reservedBytes() == 0, "Candidate basis retained memory");
        return new Result(candidates, budget.nodes());
    }

    private static void limits() {
        var model = model(1);
        var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 8_000_000, 64L << 20, cancel::get, System::nanoTime);
        try (var search = new CountAffineLattice(model.rows, model.low, model.high, budget, 262144).retained()) {
            while (true) {
                if (!search.step()) continue;
                if (!search.paused()) break;
                search.resume(262144);
            }
            check(search.counts() != null, "Cancellation baseline witness");
            check(search.nextCandidate(), "Cancellation reuse admission");
            cancel.set(true);
            boolean stopped = false;
            try { search.step(); } catch (CancellationException expected) { stopped = true; }
            check(stopped, "Retained basis ignored cancellation");
        }
        check(budget.reservedBytes() == 0, "Cancellation retained the basis");
        var refused = new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime);
        long held = refused.availableBytes(); check(refused.tryReserve(held), "Guard allocation");
        var search = new CountAffineLattice(model.rows, model.low, model.high, refused, 262144).retained();
        try {
            while (!search.step()) {}
            check(search.counts() == null && !search.nextCandidate(), "Refused preparation invented a candidate");
        } finally { search.close(); refused.release(held); }
        check(!search.nextCandidate(), "Closed basis was reopened");
        check(refused.reservedBytes() == 0, "Memory refusal leaked");
    }

    private static boolean valid(Model model, BigInteger[] values) {
        for (int i = 0; i < values.length; i++)
            if (values[i].compareTo(model.low[i]) < 0 || values[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
