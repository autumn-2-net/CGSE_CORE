package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

public class ProofChainProbe {
    static long checks;
    static BigInteger b(long value) { return BigInteger.valueOf(value); }
    static CountProof.Row row(long coefficient, long rhs) { return new CountProof.Row(Map.of(0, b(coefficient)), b(rhs)); }
    static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    static CountProof.Certificate certificate(List<CountProof.Combination> steps) {
        return new CountProof.Certificate("root", 1, List.of(row(2, 1), row(-2, -1)), List.of(), List.of(), true, steps);
    }
    public static void main(String[] args) throws Exception {
        var first = new CountProof.Combination(Map.of(0, b(1)), b(2), row(1, 0));
        var second = new CountProof.Combination(Map.of(1, b(1)), b(2), row(-1, -1));
        var chained = new CountProof.Combination(Map.of(2, b(1), 3, b(1)), b(1), new CountProof.Row(Map.of(), b(-1)));
        var proof = certificate(List.of(first, second, chained));
        check(CountProof.verify(proof, 10000) == CountProof.Verdict.VERIFIED);
        check(CountProof.verify(proof, 1) == CountProof.Verdict.INCOMPLETE);
        for (var wrong : List.of(
                new CountProof.Combination(Map.of(0, b(-1)), b(2), row(-1, -1)),
                new CountProof.Combination(Map.of(2, b(1)), b(2), row(1, 0)),
                new CountProof.Combination(Map.of(0, b(1)), b(3), row(1, 0)),
                new CountProof.Combination(Map.of(0, b(1)), b(2), row(1, -1)),
                new CountProof.Combination(Map.of(0, b(1)), b(0), row(1, 0)))) {
            check(CountProof.verify(certificate(List.of(wrong)), 10000) == CountProof.Verdict.INVALID);
        }
        long[] charged = {0};
        check(CountProof.verify(proof, 10000, n -> charged[0] += n) == CountProof.Verdict.VERIFIED);
        check(charged[0] > 0 && charged[0] < 10000);
        Path folder = Path.of(args[0]);
        var journal = new CountProof.Journal(1L << 20);
        journal.add(proof);
        Path path = folder.resolve("chain43.proof"); journal.write(path);
        check(CountProof.read(path).entries().equals(List.of(proof)));
        check(CountProof.verify(CountProof.read(path).entries().get(0), 10000) == CountProof.Verdict.VERIFIED);
        var old = new CountProof.Certificate("old", 1, proof.axioms(), List.of(), List.of(), true);
        var oldJournal = new CountProof.Journal(1L << 20); oldJournal.add(old);
        Path oldPath = folder.resolve("old33.proof"); oldJournal.write(oldPath);
        check(CountProof.read(oldPath).entries().equals(List.of(old)));
        check(java.nio.ByteBuffer.wrap(Files.readAllBytes(oldPath)).getInt() == 0x43475033);
        check(java.nio.ByteBuffer.wrap(Files.readAllBytes(path)).getInt() == 0x43475043);
        Random random = new Random(872342);
        for (int trial = 0; trial < 3000; trial++) {
            long a = 1 + random.nextInt(10000), divisor = 1 + random.nextInt(100);
            long rhs = random.nextInt(10001) - 5000;
            var source = row(a * divisor, rhs);
            var consequence = row(a, Math.floorDiv(rhs, divisor));
            var step = new CountProof.Combination(Map.of(0, b(1)), b(divisor), consequence);
            var candidate = new CountProof.Certificate("rounding", 1, List.of(source), List.of(), List.of(), false, List.of(step));
            check(CountProof.verify(candidate, 10000) == CountProof.Verdict.VERIFIED);
            var invalid = new CountProof.Combination(step.parents(), step.divisor(), row(a, Math.floorDiv(rhs, divisor) - 1));
            check(CountProof.verify(new CountProof.Certificate("tampered", 1, List.of(source), List.of(), List.of(), false, List.of(invalid)), 10000) == CountProof.Verdict.INVALID);
        }
        System.out.println("PASS chain checks=" + checks);
    }
}
