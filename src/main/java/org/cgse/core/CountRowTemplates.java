// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Catalog-owned derivations, not cached inventory-dependent right-hand sides or conflicts. */
final class CountRowTemplates {

    private static final int MAX_PARENTS = 16, MAX_TERMS = 128, MAX_BITS = 1024;
    private record Parent(Object key, Map<String, BigInteger> supply, BigInteger weight) {}
    private record Template(List<Parent> parents, BigInteger divisor, long bytes) {}
    private final Deque<Template> templates = new ArrayDeque<>();
    private long hits, misses, evictions, memory;

    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    private static final class Work {
        final PlanningBudget budget;
        final long started, allowance;
        Work(PlanningBudget budget) {
            this.budget = budget;
            started = budget.threadWork();
            allowance = Math.min(65536, budget.remainingWork() / 32);
        }
        long remaining() { return Math.max(0, allowance - (budget.threadWork() - started)); }
        void check() {
            if (remaining() == 0) throw new Stop();
            budget.check();
        }
        BigInteger product(BigInteger a, BigInteger b) {
            check();
            if (a.bitLength() + b.bitLength() > 2 * MAX_BITS) throw new Stop();
            budget.operation(PlanningBudget.Operation.INTEGER, Math.max(a.bitLength(), b.bitLength()));
            return a.multiply(b);
        }
    }

    synchronized GraphCompiler.CacheEntry cacheMetrics() {
        return new GraphCompiler.CacheEntry(templates.size(), memory / 256, memory, hits, misses, evictions);
    }

    /** Only original material rows are accepted. Branch bounds and cutoffs have no material key. */
    synchronized void remember(RecipeCountModel<?> model, List<ExactLinearProgram.Constraint> source,
                               CountLpLearning.Cut cut, PlanningBudget budget) {
        if (cut.parents().isEmpty() || cut.parents().size() > MAX_PARENTS || cut.divisor().signum() <= 0 ||
                cut.divisor().bitLength() > MAX_BITS || cut.row().terms().size() > MAX_TERMS) return;
        var work = new Work(budget);
        if (work.allowance < 1024) return;
        long scratch = 262144;
        if (scratch > budget.availableBytes() / 8 || !budget.tryReserve(scratch)) return;
        try {
            List<Parent> parents = new ArrayList<>();
            Map<Integer, BigInteger> indices = new TreeMap<>();
            for (var term : cut.parents().entrySet()) {
                work.check();
                int id = term.getKey();
                if (term.getValue().signum() <= 0 || term.getValue().bitLength() > MAX_BITS ||
                        id < 0 || id >= model.rowKeys.size() || id >= source.size() || !source.get(id).equals(model.constraints.get(id))) return;
                var supply = signature(model, model.constraints.get(id), work);
                if (supply == null) return;
                parents.add(new Parent(model.rowKeys.get(id), supply, term.getValue()));
                indices.put(id, term.getValue());
            }
            var reconstructed = rebuild(model, indices, cut.divisor(), work, false);
            if (reconstructed == null || !reconstructed.row().equals(cut.row())) return;
            var immutable = List.copyOf(parents);
            for (var old : templates) if (old.parents.equals(immutable) && old.divisor.equals(cut.divisor())) return;
            long bytes = 512L + 256L * parents.size();
            for (var parent : parents) for (var term : parent.supply.entrySet())
                bytes += 256L + 2L * term.getKey().length() + (term.getValue().bitLength() + 7L) / 8;
            if (bytes > 262144) return;
            while (!templates.isEmpty() && (templates.size() >= 32 || memory + bytes > (2L << 20))) {
                memory -= templates.removeLast().bytes;
                evictions++;
            }
            templates.addFirst(new Template(immutable, cut.divisor(), bytes));
            memory += bytes;
            budget.note("count_row_template", "retained; parents=" + parents.size() + "; original_material_rows");
        } catch (Stop ignored) {
            // Template admission is optional, independently bounded, and never a search conclusion.
        } finally { budget.release(scratch); }
    }

    synchronized List<CountLpLearning.Cut> reuse(RecipeCountModel<?> model, PlanningBudget budget) {
        if (templates.isEmpty()) return List.of();
        var work = new Work(budget);
        if (work.allowance < 1024) return List.of();
        long scratch = 262144L + 96L * model.rowKeys.size();
        if (scratch > budget.availableBytes() / 8 || !budget.tryReserve(scratch)) return List.of();
        List<CountLpLearning.Cut> result = new ArrayList<>();
        try {
            Map<Object, Integer> positions = new HashMap<>();
            for (int i = 0; i < model.rowKeys.size(); i++) { work.check(); positions.put(model.rowKeys.get(i), i); }
            Map<Integer, Map<String, BigInteger>> signatures = new HashMap<>();
            for (var template : templates) {
                work.check();
                Map<Integer, BigInteger> parents = new TreeMap<>();
                boolean matched = true;
                for (var parent : template.parents) {
                    work.check();
                    Integer id = positions.get(parent.key);
                    if (id == null) { matched = false; break; }
                    var supply = signatures.get(id);
                    if (supply == null) {
                        supply = signature(model, model.constraints.get(id), work);
                        if (supply != null) signatures.put(id, supply);
                    }
                    // Compare the ENTIRE material row by recipe identity, not just
                    // the previously referenced columns. A new producer changes it.
                    if (!parent.supply.equals(supply)) { matched = false; break; }
                    parents.put(id, parent.weight);
                }
                if (!matched) { misses++; continue; }
                var cut = rebuild(model, parents, template.divisor, work, true);
                if (cut == null) { misses++; continue; }
                result.add(cut);
                hits++;
                if (result.size() == 16) break;
            }
        } catch (Stop ignored) {
            // Already completed exact consequences remain valid.
        } finally { budget.release(scratch); }
        if (!result.isEmpty()) budget.note("count_row_template", "replayed=" + result.size() + "; current_rhs; complete_supply_match");
        return List.copyOf(result);
    }

    private static Map<String, BigInteger> signature(RecipeCountModel<?> model, ExactLinearProgram.Constraint row, Work work) {
        if (row.terms().size() > MAX_TERMS) return null;
        Map<String, BigInteger> result = new TreeMap<>();
        for (var term : row.terms().entrySet()) {
            work.check();
            if (term.getValue().bitLength() > MAX_BITS || term.getKey() < 0 || term.getKey() >= model.recipes.size()) return null;
            String id = model.recipes.get(term.getKey()).id();
            if (result.put(id, term.getValue()) != null) return null;
        }
        return Map.copyOf(result);
    }

    /** Recompute RHS and independently verify the same exact integer derivation on current axioms. */
    private static CountLpLearning.Cut rebuild(RecipeCountModel<?> model, Map<Integer, BigInteger> parents,
                                              BigInteger divisor, Work work, boolean journal) {
        Map<Integer, BigInteger> sum = new TreeMap<>(), localParents = new TreeMap<>();
        List<CountProof.Row> axioms = new ArrayList<>();
        BigInteger upper = BigInteger.ZERO;
        for (var parent : parents.entrySet()) {
            var row = model.constraints.get(parent.getKey());
            if (row.upper().bitLength() > MAX_BITS) return null;
            localParents.put(axioms.size(), parent.getValue());
            axioms.add(CountProof.row(row));
            upper = upper.add(work.product(row.upper(), parent.getValue()));
            for (var term : row.terms().entrySet()) {
                var value = sum.getOrDefault(term.getKey(), BigInteger.ZERO).add(work.product(term.getValue(), parent.getValue()));
                if (value.signum() == 0) sum.remove(term.getKey());
                else sum.put(term.getKey(), value);
                if (sum.size() > MAX_TERMS || value.bitLength() > 2 * MAX_BITS) return null;
            }
            if (upper.bitLength() > 2 * MAX_BITS) return null;
        }
        for (var term : sum.entrySet()) {
            work.check();
            work.budget.operation(PlanningBudget.Operation.INTEGER, Math.max(term.getValue().bitLength(), divisor.bitLength()));
            var qr = term.getValue().divideAndRemainder(divisor);
            if (qr[1].signum() != 0) return null;
            term.setValue(qr[0]);
        }
        work.check();
        var qr = upper.divideAndRemainder(divisor);
        var bound = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
        var row = new ExactLinearProgram.Constraint(sum, bound);
        var proof = new CountProof.Derivation("current_inventory_template", model.recipes.size(), axioms,
                List.of(new CountProof.Combination(localParents, divisor, CountProof.row(row))));
        if (CountProof.verify(proof, work.remaining(), work.budget::charge) != CountProof.Verdict.VERIFIED) return null;
        if (journal && work.budget.proofJournal() != null) work.budget.proofJournal().add(proof);
        return new CountLpLearning.Cut(row, Map.copyOf(parents), divisor);
    }
}
