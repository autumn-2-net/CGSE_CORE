// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;

/** Primitive weighted rows against forced BigInteger execution and finite original-model oracles. */
public final class CountCdclFiniteTest {
    private static final Field ROWS = field(CountCdcl.class, "rows"), VALUES = field(CountCdcl.class, "values");
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}
    private static int models, primitive, arbitrary, resumes;
    private static long conflicts, cuts;

    public static void main(String[] args) throws Exception {
        var random = new Random(2026101102L);
        for (int sample = 0; sample < 150; sample++) {
            Model model = model(random, sample);
            boolean expected = enumerable(model);
            for (var branching : CountCdcl.Branching.values()) run(model, expected, branching);
        }
        require(primitive > 1000 && arbitrary > 1000 && resumes > 0 && conflicts > 100 && cuts > 0,
                "missing arithmetic/conflict/continuation coverage");
        System.out.println("Finite PB: " + models + " paired original-model oracles; primitive rows=" + primitive +
                ", arbitrary rows=" + arbitrary + ", resumptions=" + resumes + ", conflicts=" + conflicts +
                ", derived rows=" + cuts + "; identical work, trail, reasons and independently replayed proofs");
    }

    private static Model model(Random random, int sample) {
        int n = 12;
        BigInteger origin = switch (sample % 4) {
            case 1 -> BigInteger.valueOf(Long.MIN_VALUE);
            case 2 -> BigInteger.ONE.shiftLeft(100);
            case 3 -> BigInteger.valueOf(-4);
            default -> BigInteger.ZERO;
        };
        BigInteger[] low = new BigInteger[n], high = new BigInteger[n], planted = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            low[i] = origin;
            high[i] = i < 10 ? origin.add(BigInteger.ONE) : origin;
            planted[i] = origin.add(i < 10 && random.nextBoolean() ? BigInteger.ONE : BigInteger.ZERO);
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        BigInteger[] magnitudes = {BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(7),
                BigInteger.valueOf(Long.MAX_VALUE), BigInteger.ONE.shiftLeft(63), BigInteger.ONE.shiftLeft(90)};
        for (int r = 0; r < 16; r++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            BigInteger rhs = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                if (random.nextInt(3) == 0) continue;
                BigInteger a = sample % 3 == 0 ? magnitudes[random.nextInt(magnitudes.length)] :
                        BigInteger.valueOf(1 + random.nextInt(19));
                if (random.nextBoolean()) a = a.negate();
                terms.put(i, a);
                rhs = rhs.add(a.multiply(planted[i]));
            }
            rhs = rhs.add(BigInteger.valueOf(sample % 2 == 0 ? random.nextInt(4) : random.nextInt(19) - 14));
            rows.add(new ExactLinearProgram.Constraint(terms, rhs));
        }
        Collections.shuffle(rows, random);
        return new Model(rows, low, high);
    }

    private static void run(Model model, boolean expected, CountCdcl.Branching branching) throws Exception {
        var first = budget();
        var second = budget();
        var journal = new CountProof.Journal(8L << 20);
        first.proofJournal(journal);
        second.proofJournal(new CountProof.Journal(8L << 20));
        try (var fast = new CountCdcl(model.rows, model.low, model.high, first, 1024, branching).retained();
             var exact = new CountCdcl(model.rows, model.low, model.high, second, 1024, branching).retained()) {
            while (true) {
                disable(exact);
                boolean done = fast.step();
                require(done == exact.step(), "different yield");
                require(first.nodes() == second.nodes(), "different work");
                require(Arrays.equals((int[]) VALUES.get(fast), (int[]) VALUES.get(exact)), "different assignment");
                for (String name : List.of("trail", "conflicts", "decisions", "restarts", "weightedRows", "weightedSteps", "learned"))
                    require(Objects.equals(field(CountCdcl.class, name).get(fast), field(CountCdcl.class, name).get(exact)),
                            "different search state: " + name);
                require(Arrays.deepEquals((int[][]) field(CountCdcl.class, "reasons").get(fast),
                        (int[][]) field(CountCdcl.class, "reasons").get(exact)), "different materialized reasons");
                inspect(fast, model);
                if (!done) continue;
                require(fast.paused() == exact.paused(), "different pause");
                if (!fast.paused()) break;
                fast.resume(37);
                exact.resume(37);
                resumes++;
            }
            require((fast.counts() != null) == expected && fast.infeasible() != expected, "finite oracle mismatch");
            require(Arrays.equals(fast.counts(), exact.counts()), "different final witness");
            if (fast.counts() != null) require(valid(model.rows, fast.counts()), "original witness invalid");
            conflicts += field(CountCdcl.class, "conflicts").getInt(fast);
            cuts += ((List<?>) field(CountCdcl.class, "weightedRows").get(fast)).size();
        }
        require(first.reservedBytes() == 0 && second.reservedBytes() == 0, "unreleased reservation");
        require(!journal.entries().isEmpty(), "missing proof");
        for (var entry : journal.entries())
            require(CountProof.verify(entry, 2_000_000) == CountProof.Verdict.VERIFIED, "proof replay");
        models++;
    }

    private static void disable(CountCdcl search) throws Exception {
        for (Object row : (List<?>) ROWS.get(search)) {
            Field flag = field(row.getClass(), "finiteLong");
            if (flag.getBoolean(row)) {
                field(row.getClass(), "spent").set(row, BigInteger.valueOf(field(row.getClass(), "spentLong").getLong(row)));
                flag.setBoolean(row, false);
            }
        }
    }

    private static void inspect(CountCdcl search, Model model) throws Exception {
        int[] values = (int[]) VALUES.get(search);
        for (Object row : (List<?>) ROWS.get(search)) {
            var source = (ExactLinearProgram.Constraint) field(row.getClass(), "source").get(row);
            BigInteger minimum = BigInteger.ZERO;
            for (var term : source.terms().entrySet()) {
                int i = term.getKey();
                BigInteger x = values[i] < 0 ? (term.getValue().signum() > 0 ? model.low[i] : model.high[i]) :
                        model.low[i].add(BigInteger.valueOf(values[i]));
                minimum = minimum.add(term.getValue().multiply(x));
            }
            boolean finite = field(row.getClass(), "finiteLong").getBoolean(row);
            BigInteger spent = finite ? BigInteger.valueOf(field(row.getClass(), "spentLong").getLong(row)) :
                    (BigInteger) field(row.getClass(), "spent").get(row);
            BigInteger capacity = (BigInteger) field(row.getClass(), "capacity").get(row);
            require(source.upper().subtract(minimum).equals(capacity.subtract(spent)), "original-coordinate activity mismatch");
            require(spent.signum() >= 0, "negative weighted subset");
            if (finite) primitive++;
            else arbitrary++;
        }
    }

    private static boolean enumerable(Model model) {
        for (int mask = 0; mask < 1024; mask++) {
            BigInteger[] point = model.low.clone();
            for (int i = 0; i < 10; i++) if ((mask & (1 << i)) != 0) point[i] = point[i].add(BigInteger.ONE);
            if (valid(model.rows, point)) return true;
        }
        return false;
    }
    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] point) {
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 20000000, 64L << 20, () -> false, System::nanoTime); }
    private static Field field(Class<?> type, String name) {
        try { var result = type.getDeclaredField(name); result.setAccessible(true); return result; }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
