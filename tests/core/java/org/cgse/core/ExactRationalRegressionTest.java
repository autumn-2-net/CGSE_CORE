// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.Random;
import java.util.function.Supplier;

/** Independent full-product fraction oracle, including the precision boundary. */
public final class ExactRationalRegressionTest {
    private record Fraction(BigInteger n, BigInteger d) {
        Fraction {
            if (d.signum() == 0) throw new ArithmeticException();
            if (d.signum() < 0) { n = n.negate(); d = d.negate(); }
            BigInteger gcd = n.gcd(d);
            n = n.divide(gcd);
            d = d.divide(gcd);
        }

        Fraction add(Fraction other, boolean subtract) {
            BigInteger a = n.multiply(other.d), b = other.n.multiply(d);
            return new Fraction(subtract ? a.subtract(b) : a.add(b), d.multiply(other.d));
        }

        Fraction multiply(Fraction other) {
            return new Fraction(n.multiply(other.n), d.multiply(other.d));
        }

        Fraction divide(Fraction other) {
            return new Fraction(n.multiply(other.d), d.multiply(other.n));
        }

        boolean fits() { return n.bitLength() <= 2048 && d.bitLength() <= 2048; }
    }

    public static void main(String[] args) {
        int cases = 0;
        for (int a = -5; a <= 5; a++) for (int b = -5; b <= 5; b++) if (b != 0)
            for (int c = -5; c <= 5; c++) for (int d = -5; d <= 5; d++) if (d != 0) {
                pair(BigInteger.valueOf(a), BigInteger.valueOf(b), BigInteger.valueOf(c), BigInteger.valueOf(d));
                cases++;
            }
        Random random = new Random(810_083);
        int[] widths = { 1, 31, 63, 127, 257, 511, 1024, 2047 };
        for (int i = 0; i < 1200; i++) {
            int bits = widths[i % widths.length];
            BigInteger a = signed(random, bits), b = signed(random, bits).abs().add(BigInteger.ONE);
            BigInteger c = signed(random, bits), d = signed(random, bits).abs().add(BigInteger.ONE);
            // Include equal and strongly overlapping denominators, exact
            // cancellation, integers and negative denominators, not only
            // almost-coprime random fractions.
            switch (i % 6) {
                case 0 -> d = b;
                case 1 -> { b = b.shiftRight(bits / 2).shiftLeft(bits / 2); d = d.shiftRight(bits / 2).shiftLeft(bits / 2); }
                case 2 -> { c = a.negate(); d = b; }
                case 3 -> b = BigInteger.ONE;
                case 4 -> { b = b.negate(); d = d.negate(); }
                default -> {}
            }
            if (b.signum() == 0) b = BigInteger.ONE;
            if (d.signum() == 0) d = BigInteger.ONE;
            pair(a, b, c, d);
            cases++;
        }
        BigInteger boundary = BigInteger.ONE.shiftLeft(2048);
        pair(boundary.subtract(BigInteger.ONE), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE);
        pair(boundary.negate(), BigInteger.ONE, BigInteger.ONE.negate(), BigInteger.ONE);
        pair(boundary.negate(), BigInteger.ONE, boundary.negate(), BigInteger.ONE);
        check(() -> new ExactRational(boundary.shiftLeft(100), boundary.shiftLeft(100)), new Fraction(BigInteger.ONE, BigInteger.ONE));
        check(() -> new ExactRational(BigInteger.ZERO, boundary.shiftLeft(100)), new Fraction(BigInteger.ZERO, BigInteger.ONE));
        zeroDivision(() -> new ExactRational(BigInteger.ZERO, BigInteger.ZERO));
        zeroDivision(() -> ExactRational.ZERO.divide(ExactRational.ZERO));
        zeroDivision(() -> ExactRational.ONE.divide(ExactRational.ZERO));
        preparedProducts();
        System.out.println("Exact rationals: " + cases + " independent pairs, signs, cancellation and precision boundaries passed");
    }

    private static void preparedProducts() {
        BigInteger power = BigInteger.ONE.shiftLeft(1400);
        Fraction huge = new Fraction(power, BigInteger.ONE), tiny = new Fraction(BigInteger.ONE, power);
        // Reassociation must preserve an oversized OLD intermediate product,
        // while a too-wide optional b/c must not reject a valid a*b/c.
        products(huge, huge, huge);
        products(tiny, huge, tiny);
        Fraction edge = new Fraction(BigInteger.ONE.shiftLeft(2048).negate(), BigInteger.ONE);
        products(edge, new Fraction(BigInteger.ONE.negate(), BigInteger.ONE), new Fraction(BigInteger.ONE.negate(), BigInteger.ONE));
        Random random = new Random(792_437);
        int[] widths = { 1, 31, 63, 127, 511, 1023, 1024, 1400, 2047 };
        for (int i = 0; i < 1200; i++) {
            int bits = widths[i % widths.length];
            Fraction a = new Fraction(signed(random, bits), signed(random, bits).abs().add(BigInteger.ONE));
            Fraction b = new Fraction(signed(random, bits), i % 3 == 0 ? BigInteger.ONE : signed(random, bits).abs().add(BigInteger.ONE));
            Fraction c = i % 5 == 0 ? b : new Fraction(signed(random, bits), signed(random, bits).abs().add(BigInteger.ONE));
            if (c.n.signum() == 0) c = new Fraction(BigInteger.ONE, BigInteger.ONE);
            products(a, b, c);
        }
        System.out.println("Prepared products: 1203 shared-factor contexts, including old-intermediate and optional-factor precision cutoffs passed");
    }

    private static void products(Fraction first, Fraction b, Fraction c) {
        var prepared = new ExactRational.ProductQuotient(new ExactRational(b.n, b.d), new ExactRational(c.n, c.d));
        // Reuse after a failed application as well as after a successful one.
        for (Fraction a : new Fraction[]{first, new Fraction(BigInteger.ZERO, BigInteger.ONE), new Fraction(BigInteger.ONE, BigInteger.ONE)}) {
            Fraction product = a.multiply(b), result = product.divide(c);
            boolean fits = product.fits() && result.fits();
            try {
                var actual = prepared.apply(new ExactRational(a.n, a.d));
                require(fits, "prepared ratio bypassed an original precision cutoff");
                require(actual.numerator().equals(result.n) && actual.denominator().equals(result.d), "incorrect prepared ratio");
            } catch (ExactRational.PrecisionLimit limited) {
                require(!fits, "optional prepared ratio narrowed valid arithmetic");
            }
        }
    }

    private static BigInteger signed(Random random, int bits) {
        BigInteger result = new BigInteger(bits, random);
        return random.nextBoolean() ? result : result.negate();
    }

    private static void pair(BigInteger a, BigInteger b, BigInteger c, BigInteger d) {
        Fraction x = new Fraction(a, b), y = new Fraction(c, d);
        if (!x.fits() || !y.fits()) return;
        ExactRational left = new ExactRational(a, b), right = new ExactRational(c, d);
        check(() -> left, x);
        check(() -> right, y);
        check(() -> left.add(right), x.add(y, false));
        check(() -> left.subtract(right), x.add(y, true));
        check(() -> left.multiply(right), x.multiply(y));
        if (c.signum() != 0) check(() -> left.divide(right), x.divide(y));
        else zeroDivision(() -> left.divide(right));
        check(() -> left.negate(), new Fraction(x.n.negate(), x.d));
        require(Integer.signum(left.compareTo(right)) == Integer.signum(x.n.multiply(y.d).compareTo(y.n.multiply(x.d))), "comparison");
        require(left.equals(right) == x.equals(y), "equality");
        require(left.hashCode() == 31 * x.n.hashCode() + x.d.hashCode(), "stable canonical hash");
        require(left.integral() == x.d.equals(BigInteger.ONE), "integrality");
        BigInteger floor = left.floor(), ceil = left.ceil();
        require(floor.multiply(x.d).compareTo(x.n) <= 0 && floor.add(BigInteger.ONE).multiply(x.d).compareTo(x.n) > 0, "floor");
        require(ceil.multiply(x.d).compareTo(x.n) >= 0 && ceil.subtract(BigInteger.ONE).multiply(x.d).compareTo(x.n) < 0, "ceil");
    }

    private static void check(Supplier<ExactRational> operation, Fraction expected) {
        try {
            ExactRational result = operation.get();
            require(expected.fits(), "missing precision limit");
            require(result.numerator().equals(expected.n) && result.denominator().equals(expected.d), "incorrect reduced fraction");
            require(result.numerator().gcd(result.denominator()).equals(BigInteger.ONE), "not coprime");
            require(result.denominator().signum() > 0, "denominator sign");
        } catch (ExactRational.PrecisionLimit limit) {
            require(!expected.fits(), "avoidable precision limit");
        }
    }

    private static void zeroDivision(Supplier<ExactRational> operation) {
        try { operation.get(); }
        catch (ArithmeticException expected) { return; }
        throw new AssertionError("Missing division by zero");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
