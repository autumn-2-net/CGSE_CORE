// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Condition a small cutset, then solve the independent, possibly unbounded
 * integer blocks with LCG. Only count witnesses leave this neighborhood;
 * local failures never become global conflicts or execution-order proofs.
 * Component memo entries belong to this exact model and their interface values.
 */
final class CountInterfaceSearch implements AutoCloseable {

    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    private static final class Block {
        final int[] variables;
        final List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
        int[] interfaceVariables;
        final Map<List<BigInteger>, Answer> answers = new HashMap<>();

        Block(int[] variables) { this.variables = variables; }
    }

    private record Answer(BigInteger[] values) {} // null means proved locally infeasible, never UNKNOWN

    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final long allowance;
    private final List<Block> blocks = new ArrayList<>();
    private final List<ExactLinearProgram.Constraint> interfaceRows = new ArrayList<>();
    private BigInteger[] assignment, counts;
    private int[] cutset, domains;
    private CountLcg solving;
    private List<BigInteger> solvingKey;
    private int states = 1, state, cursor;
    private long memory, work, stepStarted, hits, calls, unknown, rejected;
    private boolean prepared, tupleStarted, complete;

    CountInterfaceSearch(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                         PlanningBudget budget, long maximumWork) {
        original = rows;
        this.lower = lower.clone();
        this.upper = upper.clone();
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork() / 16);
        long terms = rows.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 2048L + 384L * lower.length + 192L * terms + 128L * rows.size() + 8L * lower.length * lower.length;
        if (lower.length > 256 || rows.size() > 2048 || allowance < 4096 || !budget.tryReserve(bytes)) complete = true;
        else memory = bytes;
    }

    boolean step() {
        if (complete) return true;
        stepStarted = budget.threadSearchWork();
        try {
            charge();
            if (!prepared) {
                prepared = true;
                prepare();
                return complete;
            }
            if (solving != null) {
                if (!solving.step()) return false;
                var values = solving.counts();
                boolean impossible = solving.infeasible();
                solving.close();
                solving = null;
                var block = blocks.get(cursor);
                // UNKNOWN is deliberately absent from the answer table. Another
                // tuple with this local interface may retry it with fresh work.
                if (values != null || impossible) remember(block, solvingKey, values);
                if (values == null) {
                    if (impossible) rejected++; else unknown++;
                    nextTuple();
                } else {
                    apply(block, values);
                    cursor++;
                }
                return false;
            }
            if (state >= states) return finish("interfaces_exhausted");
            if (!tupleStarted) {
                assignment = lower.clone();
                int code = state;
                for (int i = 0; i < cutset.length; i++) {
                    charge();
                    assignment[cutset[i]] = lower[cutset[i]].add(BigInteger.valueOf(code % domains[i]));
                    code /= domains[i];
                }
                tupleStarted = true;
                if (!valid(interfaceRows, assignment)) {
                    rejected++;
                    nextTuple();
                    return false;
                }
            }
            if (cursor == blocks.size()) {
                if (!valid(original, assignment)) throw new IllegalStateException("Invalid interface composition");
                counts = assignment.clone();
                return finish("witness");
            }
            var block = blocks.get(cursor);
            solvingKey = Arrays.stream(block.interfaceVariables).mapToObj(i -> assignment[i]).toList();
            var answer = block.answers.get(solvingKey);
            if (answer != null) {
                hits++;
                if (answer.values == null) { rejected++; nextTuple(); }
                else { apply(block, answer.values); cursor++; }
                return false;
            }
            begin(block);
            return false;
        } catch (Stop stopped) {
            return finish("local_limit");
        } finally {
            work += budget.threadSearchWork() - stepStarted;
        }
    }

    private void prepare() {
        BitSet live = new BitSet();
        BitSet[] graph = new BitSet[lower.length];
        for (int i = 0; i < lower.length; i++) {
            charge();
            graph[i] = new BitSet();
            if (upper[i] != null && upper[i].compareTo(lower[i]) < 0) { finish("empty_domain"); return; }
            if (!lower[i].equals(upper[i])) live.set(i);
        }
        for (var row : original) {
            var support = new BitSet();
            for (var term : row.terms().entrySet()) {
                charge();
                if (live.get(term.getKey()) && term.getValue().signum() != 0) support.set(term.getKey());
            }
            for (int id = support.nextSetBit(0); id >= 0; id = support.nextSetBit(id + 1)) {
                charge();
                graph[id].or(support);
                graph[id].clear(id);
            }
        }
        int initialSize = live.cardinality();
        var removed = new ArrayList<Integer>();
        var parts = components(graph, live);
        while (!useful(parts, initialSize)) {
            if (removed.size() == 3) { finish("no_small_interface"); return; }
            int best = -1, bestLargest = Integer.MAX_VALUE, bestDegree = -1, bestDomain = 0;
            for (int id = live.nextSetBit(0); id >= 0; id = live.nextSetBit(id + 1)) {
                charge();
                if (upper[id] == null) continue;
                var width = upper[id].subtract(lower[id]);
                if (width.signum() < 0 || width.compareTo(BigInteger.valueOf(31)) > 0) continue;
                int domain = width.intValueExact() + 1;
                if (domain > 256 / states) continue;
                live.clear(id);
                var trial = components(graph, live);
                live.set(id);
                int largest = largest(trial), degree = graph[id].cardinality();
                if (largest < bestLargest || largest == bestLargest && (degree > bestDegree ||
                        degree == bestDegree && domain < bestDomain)) {
                    best = id;
                    bestLargest = largest;
                    bestDegree = degree;
                    bestDomain = domain;
                }
            }
            if (best < 0) { finish("no_small_interface"); return; }
            live.clear(best);
            removed.add(best);
            states *= bestDomain;
            parts = components(graph, live);
        }
        cutset = removed.stream().mapToInt(i -> i).toArray();
        domains = Arrays.stream(cutset).map(i -> upper[i].subtract(lower[i]).intValueExact() + 1).toArray();
        int[] owner = new int[lower.length];
        Arrays.fill(owner, -1);
        for (var part : parts) {
            for (int id : part) owner[id] = blocks.size();
            blocks.add(new Block(part));
        }
        for (var row : original) {
            int destination = -1;
            for (var term : row.terms().entrySet()) {
                charge();
                int next = owner[term.getKey()];
                if (term.getValue().signum() == 0 || next < 0) continue;
                if (destination >= 0 && destination != next) throw new IllegalStateException("Coupled interface blocks");
                destination = next;
            }
            if (destination < 0) interfaceRows.add(row);
            else blocks.get(destination).rows.add(row);
        }
        for (var block : blocks) {
            BitSet touched = new BitSet();
            for (var row : block.rows) for (var term : row.terms().entrySet()) {
                charge();
                if (term.getValue().signum() != 0) touched.set(term.getKey());
            }
            block.interfaceVariables = Arrays.stream(cutset).filter(touched::get).toArray();
        }
        budget.note("count_interface", "admitted; interface=" + cutset.length + "; states=" + states +
                "; blocks=" + blocks.size() + "; largest=" + largest(parts));
    }

    private boolean useful(List<int[]> parts, int initialSize) {
        return parts.size() >= 2 && largest(parts) * 4 <= initialSize * 3;
    }

    private static int largest(List<int[]> parts) {
        return parts.stream().mapToInt(part -> part.length).max().orElse(0);
    }

    private List<int[]> components(BitSet[] graph, BitSet live) {
        var unseen = (BitSet) live.clone();
        var result = new ArrayList<int[]>();
        while (!unseen.isEmpty()) {
            BitSet part = new BitSet(), pending = new BitSet();
            pending.set(unseen.nextSetBit(0));
            while (!pending.isEmpty()) {
                charge();
                int id = pending.nextSetBit(0);
                pending.clear(id);
                if (!unseen.get(id)) continue;
                unseen.clear(id);
                part.set(id);
                pending.or(graph[id]);
                pending.and(unseen);
            }
            result.add(part.stream().toArray());
        }
        return result;
    }

    private void begin(Block block) {
        int[] ids = new int[lower.length];
        Arrays.fill(ids, -1);
        BigInteger[] low = new BigInteger[block.variables.length], high = new BigInteger[low.length];
        for (int i = 0; i < low.length; i++) {
            charge();
            int id = block.variables[i];
            ids[id] = i;
            low[i] = lower[id];
            high[i] = upper[id];
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : block.rows) {
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            BigInteger bound = row.upper();
            for (var term : row.terms().entrySet()) {
                charge();
                int id = term.getKey();
                if (term.getValue().signum() == 0) continue;
                if (ids[id] >= 0) terms.put(ids[id], term.getValue());
                else {
                    integerCost(term.getValue(), assignment[id]);
                    bound = bound.subtract(term.getValue().multiply(assignment[id]));
                }
            }
            rows.add(new ExactLinearProgram.Constraint(terms, bound));
        }
        long remaining = allowance - used();
        long quantum = Math.min(remaining, Math.max(4096, remaining / Math.max(1, blocks.size() + states - state)));
        if (quantum < 1024) throw new Stop();
        solving = new CountLcg(rows, low, high, budget, quantum);
        calls++;
    }

    private void remember(Block block, List<BigInteger> key, BigInteger[] values) {
        long bytes = 128L + 64L * key.size() + (values == null ? 0 : 64L * values.length);
        if (!budget.tryReserve(bytes)) return;
        memory += bytes;
        block.answers.put(key, new Answer(values));
    }

    private void apply(Block block, BigInteger[] values) {
        for (int i = 0; i < values.length; i++) {
            charge();
            int id = block.variables[i];
            if (values[i].compareTo(lower[id]) < 0 || upper[id] != null && values[i].compareTo(upper[id]) > 0)
                throw new IllegalStateException("Invalid interface domain");
            assignment[id] = values[i];
        }
    }

    private boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] values) {
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                charge();
                integerCost(term.getValue(), values[term.getKey()]);
                sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            }
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private void nextTuple() { state++; cursor = 0; tupleStarted = false; }
    private long used() { return work + budget.threadSearchWork() - stepStarted; }
    private void charge() {
        budget.checkpoint();
        if (used() >= allowance) throw new Stop();
        budget.check();
    }
    private void integerCost(BigInteger a, BigInteger b) {
        budget.operation(PlanningBudget.Operation.INTEGER, Math.max(a.bitLength(), b.bitLength()));
    }
    private boolean finish(String reason) {
        complete = true;
        budget.note("count_interface", reason + "; calls=" + calls + "; hits=" + hits + "; rejected=" + rejected +
                "; unknown=" + unknown + "; work=" + used() + "; positive_only");
        return true;
    }
    BigInteger[] counts() { return counts == null ? null : counts.clone(); }
    long cacheHits() { return hits; }
    @Override public void close() {
        complete = true;
        if (solving != null) solving.close();
        solving = null;
        blocks.clear();
        interfaceRows.clear();
        budget.release(memory);
        memory = 0;
    }
}
