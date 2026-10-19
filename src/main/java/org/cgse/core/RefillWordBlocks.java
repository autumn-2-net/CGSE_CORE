// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.ArrayList;
import java.util.List;

/** Exact adjacent tandem blocks in a completed reverse word; no recipe algebra. */
final class RefillWordBlocks implements AutoCloseable {
    private enum Phase { FIND, EXTEND, BODY, EMIT, OUTPUT, DONE }
    static final int MAX_PERIOD = 16;
    private final List<PlanStep> reverse, groups = new ArrayList<>(), output = new ArrayList<>();
    private final PlanningBudget budget;
    private final long started, allowance;
    private List<PlanStep> body;
    private PlanStep result;
    private Phase phase = Phase.FIND;
    private int position, period = 2, offset, copies, next;
    private long memory;
    private boolean changed, allocated;

    RefillWordBlocks(List<PlanStep> reverse, PlanningBudget budget, long allowance) {
        this.reverse = reverse; this.budget = budget; this.allowance = allowance; started = budget.nodes();
    }

    boolean step() {
        budget.checkpoint();
        if (phase == Phase.DONE) return true;
        if (!allocated) { reserve(256); allocated = true; }
        for (int i = 0; i < 64; i++) {
            if (budget.nodes() - started >= allowance) { phase = Phase.DONE; return true; }
            budget.check();
            switch (phase) {
                case FIND -> {
                    if (position == reverse.size()) {
                        if (!changed) { phase = Phase.DONE; return true; }
                        next = groups.size(); phase = Phase.OUTPUT;
                    } else if (period > MAX_PERIOD || period > (reverse.size() - position) / 2) {
                        reserve(32); groups.add(reverse.get(position++)); period = 2; offset = 0;
                    } else if (!same(reverse.get(position + offset), reverse.get(position + period + offset))) {
                        period++; offset = 0;
                    } else if (++offset == period) { copies = 2; offset = 0; phase = Phase.EXTEND; }
                }
                case EXTEND -> {
                    // Division bounds avoid overflow even for an enormous list.
                    if (copies == (reverse.size() - position) / period ||
                            !same(reverse.get(position + offset), reverse.get(position + copies * period + offset))) {
                        reserve(64); body = new ArrayList<>(); next = period; phase = Phase.BODY;
                    } else if (++offset == period) { copies++; offset = 0; }
                }
                case BODY -> {
                    if (next > 0) { reserve(32); body.add(reverse.get(position + --next)); }
                    else phase = Phase.EMIT;
                }
                case EMIT -> {
                    reserve(160);
                    // Keep each original batch boundary, including batch counts.
                    // Never collapse Repeat(Batch(n), m) into Batch(n*m).
                    groups.add(new PlanStep.Repeat(FrozenPlanSteps.owned(body), copies)); body = null;
                    position += period * copies; period = 2; offset = 0; changed = true; phase = Phase.FIND;
                }
                case OUTPUT -> {
                    if (next > 0) { reserve(32); output.add(groups.get(--next)); }
                    else { reserve(64); result = FrozenPlanSteps.owned(output); phase = Phase.DONE; return true; }
                }
                default -> throw new IllegalStateException("Unexpected block phase " + phase);
            }
        }
        return false;
    }

    private static boolean same(PlanStep a, PlanStep b) {
        // Record equals/hashCode recurse through composite words. Composite
        // identity is sufficient, but each occurrence is still counted.
        return a == b || a instanceof PlanStep.Batch x && b instanceof PlanStep.Batch y &&
                x.runs() == y.runs() && x.recipe().equals(y.recipe());
    }
    PlanStep result() { return result; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    @Override public void close() { budget.release(memory); memory = 0; }
}
