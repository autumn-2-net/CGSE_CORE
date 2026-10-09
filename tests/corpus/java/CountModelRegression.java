package org.cgse.core;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

/** Standalone exact count-model corpus; all returned witnesses checked independently. */
public final class CountModelRegression {
    static BigInteger integer(DataInputStream input) throws IOException {
        String value = GraphFixtureRegression.string(input);
        return value.isEmpty() ? null : new BigInteger(value);
    }

    public static void main(String[] args) throws Exception {
        String engine = args.length > 3 ? args[3] : "lcg-first";
        if (!Set.of("lcg-first", "lcg-retained", "views", "main-counts").contains(engine))
            throw new IllegalArgumentException("Unknown model engine: " + engine);
        System.out.println("ENGINE " + engine + " milliseconds=" + args[1] + " work=" + args[2] + " memory_bytes=" + (256L << 20));
        int solved = 0, unresolved = 0, unsupported = 0, failed = 0;
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[0]))))) {
            for (int left = input.readInt(); left > 0; left--) {
                String id = GraphFixtureRegression.string(input), expected = GraphFixtureRegression.string(input);
                int size = input.readInt();
                BigInteger[] lower = new BigInteger[size], upper = new BigInteger[size];
                for (int i = 0; i < size; i++) { lower[i] = integer(input); upper[i] = integer(input); }
                var rows = new ArrayList<ExactLinearProgram.Constraint>();
                for (int remaining = input.readInt(); remaining > 0; remaining--) {
                    BigInteger bound = integer(input);
                    var terms = new LinkedHashMap<Integer, BigInteger>();
                    for (int term = input.readInt(); term > 0; term--) terms.put(input.readInt(), integer(input));
                    rows.add(new ExactLinearProgram.Constraint(terms, bound));
                }
                var budget = new PlanningBudget(Long.parseLong(args[1]), Long.parseLong(args[2]), 256L << 20, () -> false, System::nanoTime);
                try {
                    var result = CountModelReplay.solve(engine, rows, lower, upper, budget, Long.parseLong(args[2]));
                    BigInteger[] witness = result.counts();
                    boolean infeasible = result.infeasible();
                    if (budget.reservedBytes() != 0) throw new AssertionError("Unreleased solver memory: " + budget.reservedBytes());
                    if (witness != null) {
                        for (int i = 0; i < size; i++)
                            if (witness[i].compareTo(lower[i]) < 0 || upper[i] != null && witness[i].compareTo(upper[i]) > 0)
                                throw new AssertionError("Variable bound violated: " + i);
                        for (var row : rows) {
                            BigInteger sum = BigInteger.ZERO;
                            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(witness[term.getKey()]));
                            if (sum.compareTo(row.upper()) > 0) throw new AssertionError("Constraint violated");
                        }
                    }
                    if (expected.equals("SAT") && infeasible || expected.equals("UNSAT") && witness != null)
                        throw new AssertionError("Contradicted external oracle " + expected);
                    if (witness == null && !infeasible) { unresolved++; System.out.println(id + "\tUNRESOLVED\twork=" + budget.nodes()); }
                    else { solved++; System.out.println(id + "\tPASS\t" + (witness != null ? "SAT" : "UNSAT") + "\twork=" + budget.nodes()); }
                } catch (PlanningBudget.Exhausted limit) {
                    unresolved++; System.out.println(id + "\tUNRESOLVED\twork=" + budget.nodes() + "\t" + limit);
                } catch (CountModelReplay.Unsupported limitation) {
                    unsupported++; System.out.println(id + "\tUNSUPPORTED\t" + limitation.getMessage());
                } catch (Throwable failure) {
                    failed++; System.out.println(id + "\tFAIL\t" + failure); failure.printStackTrace(System.err);
                } finally {
                    if (args.length > 4 && Boolean.parseBoolean(args[4])) System.out.println("DIAGNOSTICS " + id + " " + budget.diagnostics());
                    if (budget.reservedBytes() != 0) throw new AssertionError("Unreleased solver memory after exit: " + budget.reservedBytes());
                }
            }
            if (input.read() != -1) throw new AssertionError("Trailing count-model bytes");
        }
        System.out.println("SUMMARY solved=" + solved + " unresolved=" + unresolved + " unsupported=" + unsupported + " failed=" + failed);
        if (failed != 0) System.exit(1);
        if (unresolved != 0 || unsupported != 0) System.exit(2);
    }
}
