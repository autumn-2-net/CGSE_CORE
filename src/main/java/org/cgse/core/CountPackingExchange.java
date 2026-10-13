// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

/** Retained sparse-exchange matching inside a proposed Boolean face.
 * Each half may change at most three seed coordinates. Exhausting that
 * restricted neighborhood is never a proof about the original count model.
 * A retained left table is queried by exact integer boxes, without allocating
 * a vector or traversing every left entry for each right-hand combination. */
final class CountPackingExchange implements AutoCloseable {
    private final PlanningBudget budget;
    private final long[] slack, seedChange, query;
    private final long seed;
    private final int size, dimensions, split, points;
    private final long[] vectors, masks, minimum, maximum, spread;
    private final int[] order, from, to, left, right, building, querying;
    private final Subsets subsets;
    private int cursor, phase, nodes = 1, builds, queries;
    private long memory, assignment, rightMask;
    private boolean complete, feasible;
    CountPackingExchange(long[][] a, long[] b, long seed, PlanningBudget budget) {
        if (a.length == 0) throw new IllegalArgumentException("Empty exchange face");
        size = a[0].length;
        dimensions = b.length;
        split = size / 2;
        if (size < 2 || size > 64 || dimensions < 1 || dimensions > 35 || a.length != dimensions)             throw new IllegalArgumentException("Exchange shape");
        this.budget = budget;
        this.seed = seed;
        points = 1 + split + split * (split - 1)/ 2 + split * (split - 1) * (split - 2)/ 6;
        // Retain only lower node bounds for one-sided queries. Upper activities
        // are build scratch; root spreads guide partitioning without tree storage.
        long bytes = 8192L + points * (128L + 24L * dimensions) + 32L * size * dimensions;
        if (!budget.tryReserve(bytes)) {
            complete = true;
            slack = seedChange = query = vectors = masks = minimum = maximum = spread = null;
            order = from = to = left = right = building = querying = null;
            subsets = null;
            return;
        }

        memory = bytes;
        try {
            slack = b.clone();
            query = new long[dimensions];
            seedChange = new long[dimensions * size];
            for (int r = 0; r < dimensions; r++) {
                long magnitude = Math.abs(b[r]);
                if (a[r].length != size || b[r] <=  - (1L << 60) || b[r] >= 1L << 60) throw new IllegalArgumentException("Exchange row range");
                for (int i = 0; i < size; i++) {
                    budget.check();
                    long v = a[r][i];
                    if (v <=  - (1L << 60) || v >= 1L << 60 || Math.abs(v) >= (1L << 60) - magnitude) throw new IllegalArgumentException("Exchange activity range");
                    magnitude += Math.abs(v);
                    boolean selected = (seed & (1L << i)) != 0;
                    if (selected)slack[r] -= v;
                    seedChange[r * size + i] = selected ?  - v : v;
                }

            }

            vectors = new long[points * dimensions];
            masks = new long[points];
            order = new int[points];
            minimum = new long[2 * points * dimensions];
            maximum = new long[dimensions];
            spread = new long[dimensions];
            from = new int[2 * points];
            to = new int[2 * points];
            left = new int[2 * points];
            right = new int[2 * points];
            building = new int[64];
            querying = new int[64];
            subsets = new Subsets(0, split);
        }
 catch (RuntimeException failure) {
            close();
            throw failure;
        }

    }
    boolean step() {
        if (complete) return true;
        budget.checkpoint();
        if (phase == 0) {
            long mask = subsets.next();
            if (mask == -1L) {
                phase = 1;
                from[0] = 0;
                to[0] = cursor;
                building[builds++] = 0;
                return false;
            }

            masks[cursor] = mask;
            order[cursor] = cursor;
            for (int r = 0; r < dimensions; r++) {
                long value = 0, bits = mask;
                budget.operations(PlanningBudget.Operation.INTEGER, 64, Long.bitCount(mask) + 1);
                while (bits != 0) {
                    int i = Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    value += seedChange[r * size + i];
                }

                vectors[cursor * dimensions + r] = value;
            }

            cursor++;
            return false;
        }

        if (phase == 1) {
            if (builds == 0) {
                phase = 2;
                subsets.reset(split, size);
                return false;
            }

            build(building[--builds]);
            return false;
        }

        if (queries == 0) {
            rightMask = subsets.next();
            if (rightMask == -1L) {
                complete = true;
                return true;
            }

            for (int r = 0; r < dimensions; r++) {
                long value = slack[r], bits = rightMask;
                budget.operations(PlanningBudget.Operation.INTEGER, 64, Long.bitCount(bits) + 1);
                while (bits != 0) {
                    int i = Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    value -= seedChange[r * size + i];
                }

                query[r] = value;
            }

            querying[queries++] = 0;
        }

        int node = querying[--queries];
        for (int r = dimensions - 1; r >= 0; r--) {
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            if (minimum[node * dimensions + r] > query[r]) return false;
        }

        if (left[node] != 0) {
            querying[queries++] = right[node];
            querying[queries++] = left[node];
            return false;
        }

        for (int p = from[node]; p < to[node]; p++) {
            int id = order[p];
            boolean valid = true;
            for (int r = dimensions - 1; r >= 0; r--) {
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                if (vectors[id * dimensions + r] > query[r]) {
                    valid = false;
                    break;
                }

            }

            if (valid) {
                assignment = seed ^ masks[id] ^ rightMask;
                feasible = complete = true;
                return true;
            }
        }

        return false;
    }

    private void build(int node) {
        int begin = from[node], end = to[node];
        int offset = node * dimensions, first = order[begin] * dimensions;
        budget.operations(PlanningBudget.Operation.SCAN, 0, 2 * dimensions);
        System.arraycopy(vectors, first, minimum, offset, dimensions);
        System.arraycopy(vectors, first, maximum, 0, dimensions);
        // Every stored point contributes to the box; no sampled bound can prune.
        for (int p = begin + 1; p < end; p++) {
            int id = order[p] * dimensions;
            budget.operations(PlanningBudget.Operation.INTEGER, 64, 2 * dimensions);
            for (int r = 0; r < dimensions; r++) {
                long v = vectors[id + r];
                minimum[offset + r] = Math.min(minimum[offset + r], v);
                maximum[r] = Math.max(maximum[r], v);
            }
        }

        if (node == 0) {
            budget.operations(PlanningBudget.Operation.INTEGER, 64, dimensions);
            for (int r = 0; r < dimensions; r++) spread[r] = maximum[r] - minimum[r];
        }
        if (end - begin <= 8) return;
        // Relative spread chooses a partition only. Exclusions above compare
        // exact long activities whose complete range was checked on admission.
        int axis = 0;
        double widest = -1;
        for (int r = 0; r < dimensions; r++) {
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            double relative = (double) (maximum[r] - minimum[offset + r]) / Math.max(1, spread[r]);
            if (relative > widest) {
                widest = relative;
                axis = r;
            }
        }

        int middle = (begin + end) >>> 1;
        partition(begin, end, middle, axis);
        int a = nodes++, b = nodes++;
        left[node] = a;
        right[node] = b;
        from[a] = begin;
        to[a] = middle;
        from[b] = middle;
        to[b] = end;
        building[builds++] = b;
        building[builds++] = a;
    }

    private void partition(int begin, int end, int middle, int axis) {
        int low = begin, high = end - 1;
        while (low < high) {
            int pivot = order[(low + high) >>> 1], i = low, j = high;
            while (i <= j) {
                while (compare(order[i], pivot, axis) < 0) i++;
                while (compare(order[j], pivot, axis) > 0) j--;
                if (i <= j) {
                    int t = order[i];
                    order[i++] = order[j];
                    order[j--] = t;
                }

            }

            if (middle <= j) high = j;
            else if (middle >= i) low = i;
            else break;
        }

    }
    private int compare(int a, int b, int axis) {
        budget.operation(PlanningBudget.Operation.SCAN, 0);
        int c = Long.compare(vectors[a * dimensions + axis], vectors[b * dimensions + axis]);
        return c != 0 ? c : Integer.compare(a, b);
    }

    boolean feasible() {
        return feasible;
    }

    long assignment() {
        if (!feasible) throw new IllegalStateException("No exchange witness");
        return assignment;
    }

    @Override public void close() {
        budget.release(memory);
        memory = 0;
    }

    private static final class Subsets {
        int start, end, count;
        int[] chosen = new int[3];
        boolean first = true, done;
        Subsets(int start, int end) {
            reset(start, end);
        }

        void reset(int start, int end) {
            this.start = start;
            this.end = end;
            count = 0;
            first = true;
            done = false;
        }

        long next() {
            if (done) return -1L;
            if (first) {
                first = false;
                return 0;
            }

            int i = count - 1;
            while (i >= 0 && chosen[i] == end - count + i) i--;
            if (i < 0) {
                if (++count > Math.min(3, end - start)) {
                    done = true;
                    return -1L;
                }

                for (i = 0; i < count; i++) chosen[i] = start + i;
            } else {
                chosen[i]++;
                for (int j = i + 1; j < count; j++) chosen[j] = chosen[j - 1] + 1;
            }

            long mask = 0;
            for (int j = 0; j < count; j++) mask |= 1L << chosen[j];
            return mask;
        }

    }
}
