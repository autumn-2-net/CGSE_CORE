// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent original-domain oracles for the candidate-only centered lattice. */
public final class CountSubsetLatticeTest {
    private static final long[] HARD = {
            218070523642963L, 183324461373421L, 148477166057853L, 277609229104315L,
            163615691447639L, 253386431694429L, 245209433222029L, 264639386825093L,
            210084901943791L, 151337279679009L, 231078228889267L, 149520027229331L,
            261107102531521L, 263542608642737L, 177398810327369L, 207047903535925L,
            268586630702953L, 141084374799157L, 171797998364669L, 212245240792111L,
            247540991340113L, 184575498875423L, 178872000951569L, 207271981202285L,
            189384606253817L, 193645488682467L, 275327447217873L, 196658160676481L,
            188521650679731L, 238952429513639L, 176838550622719L, 179130113404667L,
            271313575640333L, 172019415539893L, 218070700712677L, 259778069448767L,
            264064252184917L, 208723917190469L, 150229285172043L, 211882853244197L,
            177717807659557L, 249025096528789L, 230655890830551L, 260191820084351L,
            219921011344781L, 175531203154013L, 161764133701467L, 143945138503981L
    };
    private static final long TARGET = 4523914075680311L;

    public static void main(String[] args) throws Exception {
        String selection = args.length == 0 ? "all" : args[0];
        if (selection.equals("all") || selection.equals("oracle")) finiteOracles();
        if (selection.equals("all") || selection.equals("marker")) halfTotal();
        if (selection.equals("all") || selection.equals("hard")) retainedOriginalCoordinates();
        if (selection.equals("all") || selection.equals("limits")) limitsAndCancellation();
        if (selection.equals("all") || selection.equals("factors")) retainedFactorOracle();
        System.out.println("Subset lattice: independent finite oracles, half-total rank, signed large-offset " +
                "restoration, order/slice invariance, refusal and cancellation passed");
    }

    private static void finiteOracles() {
        Random random = new Random(111024);
        int witnesses = 0, impossible = 0;
        for (int sample = 0; sample < 48; sample++) {
            int n = 7 + sample % 5;
            long[] coefficients = new long[n];
            for (int i = 0; i < n; i++) coefficients[i] = (random.nextInt(2001) - 1000L) << (sample % 3 * 16);
            Set<BigInteger> possible = new HashSet<>();
            for (long mask = 0; mask < 1L << n; mask++) possible.add(sum(coefficients, mask));
            long planted = sum(coefficients, random.nextLong(1L << n)).longValueExact();
            for (long target : new long[] {planted, planted + 1}) {
                PlanningBudget budget = budget(2_000_000, 64L << 20);
                try (var search = new CountSubsetLattice(coefficients, target, budget, 1_000_000)) {
                    while (!search.step()) {}
                    long answer = search.candidate();
                    if (answer >= 0) {
                        check(answer < 1L << n && sum(coefficients, answer).equals(BigInteger.valueOf(target)),
                                "Lattice returned an invalid original-domain witness");
                        witnesses++;
                    }
                    if (!possible.contains(BigInteger.valueOf(target))) { impossible++; check(answer < 0, "False SAT"); }
                }
                check(budget.reservedBytes() == 0, "Finite lattice workspace leaked");
            }
        }
        check(witnesses >= 40 && impossible >= 25, "Finite SAT/UNSAT oracle coverage missing");
    }

    private static void halfTotal() {
        long[] values = {100000000001L, 230000000003L, 310000000007L, 470000000009L,
                100000000001L, 230000000003L, 310000000007L, 470000000009L};
        long target = Arrays.stream(values).sum() / 2;
        var budget = budget(1_000_000, 64L << 20);
        try (var search = new CountSubsetLattice(values, target, budget, 500_000)) {
            while (!search.step()) {}
            check(search.candidate() >= 0 && sum(values, search.candidate()).equals(BigInteger.valueOf(target)),
                    "Half-total embedding lost rank or the valid subset");
        }
        check(budget.reservedBytes() == 0, "Half-total workspace leaked");
    }

    private static void retainedOriginalCoordinates() {
        // The canonical 48-bit corpus boundary; the planted witness is used
        // only to construct equivalent signed/shifted models, never by the tool.
        long known = 141601711113676L;
        check(sum(HARD, known).equals(BigInteger.valueOf(TARGET)), "Bad test witness");
        List<Integer> permutation = new ArrayList<>();
        for (int i = 0; i < HARD.length; i++) permutation.add(i);
        for (int order = 0; order < 3; order++) {
            if (order != 0) Collections.shuffle(permutation, new Random(611100 + order));
            BigInteger[] lower = new BigInteger[HARD.length], upper = new BigInteger[HARD.length];
            Map<Integer, BigInteger> positive = new LinkedHashMap<>(), negative = new LinkedHashMap<>();
            BigInteger target = BigInteger.ZERO;
            for (int i = 0; i < HARD.length; i++) {
                int old = permutation.get(i);
                boolean flip = order != 0 && i % 3 == 0;
                BigInteger weight = BigInteger.valueOf(HARD[old] * (flip ? -1 : 1));
                lower[i] = BigInteger.ONE.shiftLeft(100 + i % 3).negate().add(BigInteger.valueOf(i));
                upper[i] = lower[i].add(BigInteger.ONE);
                boolean one = ((known & (1L << old)) != 0) != flip;
                target = target.add(weight.multiply(one ? upper[i] : lower[i]));
                positive.put(i, weight); negative.put(i, weight.negate());
            }
            List<ExactLinearProgram.Constraint> rows = new ArrayList<>(List.of(
                    new ExactLinearProgram.Constraint(positive, target),
                    new ExactLinearProgram.Constraint(negative, target.negate())));
            if (order % 2 != 0) Collections.reverse(rows);
            BigInteger[] previous = null;
            long previousWork = -1;
            for (long quantum : new long[] {1_000_000, 37}) {
                var budget = budget(12_000_000, 64L << 20);
                try (var search = new CountMeetInMiddle(rows, lower, upper, budget, Math.max(1024, quantum)).retained()) {
                    int pauses = 0;
                    for (;;) {
                        while (!search.step()) {}
                        if (!search.paused()) break;
                        long charged = budget.searchWork(), bytes = budget.reservedBytes();
                        check(search.step() && budget.searchWork() == charged && budget.reservedBytes() == bytes,
                                "Paused lattice repeated paid work");
                        search.resume(quantum);
                        check(++pauses < 250_000, "Retained lattice stopped progressing");
                    }
                    BigInteger[] answer = search.counts();
                    check(answer != null && !search.infeasible(), "Hard subset did not reach a witness");
                    BigInteger actual = BigInteger.ZERO;
                    for (int i = 0; i < answer.length; i++) {
                        check(answer[i].equals(lower[i]) || answer[i].equals(upper[i]), "Restored value outside domain");
                        actual = actual.add(positive.get(i).multiply(answer[i]));
                    }
                    check(actual.equals(target), "Signed/shifted original equation failed");
                    check(budget.diagnostics().contains("count_subset_lattice"), "Hard case did not enter the lattice tool");
                    if (previous != null) check(Arrays.equals(previous, answer) && previousWork == budget.searchWork(),
                            "Scheduling changed the lattice answer or work");
                    previous = answer; previousWork = budget.searchWork();
                }
                check(budget.reservedBytes() == 0, "Retained lattice workspace leaked");
            }
        }
    }

    private static void limitsAndCancellation() {
        var refused = budget(1_000_000, 1024);
        try (var search = new CountSubsetLattice(HARD, TARGET, refused, 500_000)) {
            check(search.step() && search.candidate() < 0, "Optional memory refusal produced a conclusion");
        }
        check(refused.reservedBytes() == 0, "Refused lattice workspace leaked");
        var small = budget(1_000_000, 64L << 20);
        try (var search = new CountSubsetLattice(HARD, TARGET, small, 4096)) {
            while (!search.step()) {}
            check(search.candidate() < 0 && small.searchWork() >= 4096 && small.searchWork() < 10_000,
                    "Local limit was expanded or numeric work went uncharged");
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 10_000_000, 64L << 20, cancelled::get, System::nanoTime);
        try (var search = new CountSubsetLattice(HARD, TARGET, budget, 8_000_000)) {
            for (int i = 0; i < 2000; i++) check(!search.step(), "Cancellation fixture finished early");
            cancelled.set(true);
            try { search.step(); throw new AssertionError("Cancellation ignored"); }
            catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0 && small.reservedBytes() == 0, "Limited/cancelled lattice leaked");
        long[] repeated = HARD.clone(); repeated[1] = -repeated[0];
        check(!CountSubsetLattice.eligible(repeated, budget(10_000, 64L << 20)), "Repeated sums bypassed cheaper matching");
    }

    @SuppressWarnings("unchecked")
    private static void retainedFactorOracle() throws Exception {
        var budget = budget(8_000_000, 64L << 20);
        int checked = 0;
        try (var search = new CountSubsetLattice(HARD, TARGET, budget, 6_000_000)) {
            int previousStage = -1;
            while (!search.step()) {
                int phase = (int) field(search, "phase"), stage = (int) field(search, "insertStage");
                if (phase != 5 || stage == previousStage || stage == 0 || checked >= 9) continue;
                previousStage = stage;
                List<BigInteger[]> basis = (List<BigInteger[]>) field(search, "basis");
                CountIntegralGram factors = (CountIntegralGram) field(search, "gram");
                // Compare integer dot products with L*D*L^T. This oracle does
                // not use the production row-swap or addition recurrences.
                for (int i = 0; i < basis.size(); i++) for (int j = 0; j <= i; j++) {
                    BigInteger dot = BigInteger.ZERO;
                    for (int k = 0; k < basis.get(i).length; k++) dot = dot.add(basis.get(i)[k].multiply(basis.get(j)[k]));
                    ExactRational reconstructed = ExactRational.ZERO;
                    for (int k = 0; k <= j; k++) reconstructed = reconstructed.add(factors.norm(k)
                            .multiply(k == i ? ExactRational.ONE : factors.mu(i, k))
                            .multiply(k == j ? ExactRational.ONE : factors.mu(j, k)));
                    check(reconstructed.equals(ExactRational.of(dot)), "Stale factor during retained block insertion");
                }
                checked++;
            }
            check(search.candidate() >= 0 && checked == 9, "Retained block-update oracle did not cover all stages");
        }
        check(budget.reservedBytes() == 0, "Factor oracle workspace leaked");
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static PlanningBudget budget(long work, long bytes) {
        return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime);
    }
    private static BigInteger sum(long[] values, long mask) {
        BigInteger total = BigInteger.ZERO;
        for (int i = 0; i < values.length; i++) if ((mask & (1L << i)) != 0) total = total.add(BigInteger.valueOf(values[i]));
        return total;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
