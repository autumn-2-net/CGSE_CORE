// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

/**
 * Candidate-only four-list matching over one certified integer equality.
 * Pair sums are joined one residue class at a time, retaining all quarter lists.
 * A stopped, truncated or exhausted search never asserts infeasibility.
 */
final class CountResidueJoin implements AutoCloseable {

    record Shape(int variables, int split, int first, int third, int residueBits,
                 int capacity, long bytes, long work) {
        static Shape create(int variables, long span) {
            if (variables < 4 || variables > 52 || span <= 0 || span > Long.MAX_VALUE / 4) return null;
            int split = (variables + 1) / 2, first = split / 2, third = (variables - split) / 2;
            int largest = Math.max(split - first, variables - split - third);
            int bits = Math.max(1, Math.max(split - 20,
                    Math.min(largest, variables - (64 - Long.numberOfLeadingZeros(span)) - 3)));
            int left = 1 << (split - bits), right = 1 << (variables - split - bits);
            int capacity = left * 2;
            long lists = (1L << first) + (1L << (split - first)) +
                    (1L << third) + (1L << (variables - split - third));
            long bytes = 1024 + 8L * variables + 16L * capacity + 8L * lists +
                    4L * ((1L << first) + (1L << third)) + (8L << bits);
            // The density estimate chooses a strategy, not a completeness bound.
            // Skewed residues still charge each actual pair and hash inspection.
            long work = 4 * (left + (long) right) + 4 * lists;
            return new Shape(variables, split, first, third, bits, capacity, bytes, work);
        }
    }

    private final PlanningBudget budget;
    private final Shape shape;
    private final long target;
    private long[] coefficients, keys;
    private int[] codes, epochs;
    private long[][] sums;
    private int[][] heads, links;
    private final int[] starts, sizes;
    private PairCursor left, right;
    private long memory, work, progress, candidate = -1, value, key;
    private int phase, group, cursor, residue, slot, joinedCode, distinct, truncated;
    private boolean pending, done;

    CountResidueJoin(long[] coefficients, long target, PlanningBudget budget, Shape shape) {
        this.budget = budget;
        this.shape = shape;
        this.target = target;
        starts = new int[] {0, shape.first, shape.split, shape.split + shape.third};
        sizes = new int[] {1 << shape.first, 1 << (shape.split - shape.first),
                1 << shape.third, 1 << (shape.variables - shape.split - shape.third)};
        if (!budget.tryReserve(shape.bytes)) {
            done = true;
            return;
        }
        memory = shape.bytes;
        try {
            this.coefficients = coefficients.clone();
            keys = new long[shape.capacity];
            codes = new int[shape.capacity];
            epochs = new int[shape.capacity];
            sums = new long[4][];
            for (int i = 0; i < 4; i++) sums[i] = new long[sizes[i]];
            heads = new int[][] {new int[1 << shape.residueBits], new int[1 << shape.residueBits]};
            links = new int[][] {new int[sizes[0]], new int[sizes[2]]};
            left = new PairCursor(0);
            right = new PairCursor(2);
            budget.note("count_residue_join", "quarters=" + sizes[0] + "," + sizes[1] + "," + sizes[2] + "," + sizes[3] +
                    "; residue_bits=" + shape.residueBits + "; bytes=" + shape.bytes + "; positive_only");
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        if (done) return true;
        budget.checkpoint();
        for (int quantum = 0; quantum < 16; quantum++) {
            if (phase == 0) {
                // Store by the option mask, so no separate list-code allocation
                // or sorting is required for exact witness reconstruction.
                charge();
                int code = cursor ^ (cursor >>> 1);
                if (cursor != 0) {
                    int bit = Integer.numberOfTrailingZeros(cursor);
                    long coefficient = coefficients[starts[group] + bit];
                    value += (code & (1 << bit)) == 0 ? -coefficient : coefficient;
                }
                sums[group][code] = value;
                progress++;
                if (++cursor == sizes[group]) {
                    cursor = 0;
                    value = 0;
                    if (++group == 4) { phase = 1; group = 0; }
                }
            } else if (phase == 1) {
                charge();
                int bucket = (int) sums[group * 2][cursor] & (heads[group].length - 1);
                links[group][cursor] = heads[group][bucket] - 1;
                heads[group][bucket] = cursor + 1;
                if (++cursor == sizes[group * 2]) {
                    cursor = 0;
                    if (++group == 2) phase = 2;
                }
            } else {
                PairCursor pairs = phase == 2 ? left : right;
                if (!pending) {
                    if (!pairs.next()) continue;
                    if (pairs.done) {
                        if (phase == 2) phase = 3;
                        else if (++residue == heads[0].length) return finish();
                        else {
                            // Epochs avoid clearing a large hash table at every
                            // residue. Quarter sums/buckets are never rebuilt.
                            phase = 2;
                            distinct = 0;
                            left.reset();
                            right.reset();
                        }
                        return false;
                    }
                    key = phase == 2 ? pairs.value : target - pairs.value;
                    joinedCode = pairs.code;
                    long hash = key;
                    hash = (hash ^ (hash >>> 33)) * 0xff51afd7ed558ccdL;
                    hash = (hash ^ (hash >>> 33)) * 0xc4ceb9fe1a85ec53L;
                    slot = (int) (hash ^ (hash >>> 33)) & (keys.length - 1);
                    pending = true;
                }
                charge();
                if (epochs[slot] != residue + 1) {
                    if (phase == 2) {
                        if (distinct >= keys.length / 4 * 3) {
                            // A skewed bucket can exceed its density estimate.
                            // Probe the retained partial table, then try another
                            // residue; this is still only a witness heuristic.
                            truncated++;
                            pending = false;
                            phase = 3;
                            return false;
                        }
                        keys[slot] = key;
                        codes[slot] = joinedCode;
                        epochs[slot] = residue + 1;
                        distinct++;
                    }
                } else if (keys[slot] == key) {
                    if (phase == 3) {
                        candidate = (long) codes[slot] | ((long) joinedCode << shape.split);
                        return finish();
                    }
                } else {
                    slot = (slot + 1) & (keys.length - 1);
                    continue;
                }
                pending = false;
                pairs.consume();
                progress++;
            }
        }
        return false;
    }

    private final class PairCursor {
        final int group;
        int outer, inner = -1, code;
        long value;
        boolean ready, done;

        PairCursor(int group) { this.group = group; }

        boolean next() {
            if (ready || done) return true;
            if (inner < 0) {
                if (outer == sizes[group + 1]) return done = true;
                charge();
                long wanted = group == 0 ? residue : target - residue;
                int bucket = (int) (wanted - sums[group + 1][outer]) & (heads[0].length - 1);
                inner = heads[group / 2][bucket] - 1;
                if (inner < 0) { outer++; return false; }
            }
            charge();
            value = sums[group][inner] + sums[group + 1][outer];
            code = inner | (outer << (group == 0 ? shape.first : shape.third));
            ready = true;
            return true;
        }

        void consume() {
            ready = false;
            inner = links[group / 2][inner];
            if (inner < 0) outer++;
        }

        void reset() { outer = 0; inner = -1; ready = done = false; }
    }

    private void charge() { budget.check(); work++; }

    private boolean finish() {
        done = true;
        budget.note("count_residue_join", "witness=" + (candidate >= 0) + "; residue=" + residue +
                "; truncated=" + truncated + "; work=" + work + "; positive_only");
        return true;
    }

    long work() { return work; }
    long progress() { return progress; }
    long candidate() { return candidate; }
    boolean probing() { return !done && phase == 3; }
    long continuationWork() { return done ? 0 : shape.work; }

    @Override
    public void close() {
        done = true;
        coefficients = keys = null;
        codes = epochs = null;
        sums = null;
        heads = links = null;
        left = right = null;
        budget.release(memory);
        memory = 0;
    }
}
