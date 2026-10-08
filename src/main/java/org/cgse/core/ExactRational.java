// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;

/** Reduced rationals for bounded analyses; never round a long-sized deficit. */
final class ExactRational implements Comparable<ExactRational> {

    private final BigInteger numerator, denominator;

    static final ExactRational ZERO = new ExactRational(BigInteger.ZERO, BigInteger.ONE);
    static final ExactRational ONE = new ExactRational(BigInteger.ONE, BigInteger.ONE);

    ExactRational(BigInteger numerator, BigInteger denominator) {
        this(numerator, denominator, false);
    }

    /** Only arithmetic preserving coprimality may bypass normalization. */
    private ExactRational(BigInteger numerator, BigInteger denominator, boolean reduced) {
        if (denominator.signum() == 0) throw new ArithmeticException("Zero denominator");
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        if (numerator.signum() == 0) denominator = BigInteger.ONE;
        else if (!reduced && !denominator.equals(BigInteger.ONE)) {
            BigInteger common = numerator.gcd(denominator);
            if (!common.equals(BigInteger.ONE)) {
                numerator = numerator.divide(common);
                denominator = denominator.divide(common);
            }
        }
        if (numerator.bitLength() > 2048 || denominator.bitLength() > 2048) throw new PrecisionLimit();
        this.numerator = numerator;
        this.denominator = denominator;
    }

    BigInteger numerator() {
        return numerator;
    }

    BigInteger denominator() {
        return denominator;
    }

    static ExactRational of(BigInteger value) {
        return value.signum() == 0 ? ZERO : value.equals(BigInteger.ONE) ? ONE : new ExactRational(value, BigInteger.ONE, true);
    }

    int signum() {
        return numerator.signum();
    }

    ExactRational negate() {
        return signum() == 0 ? this : new ExactRational(numerator.negate(), denominator, true);
    }

    ExactRational add(ExactRational other) {
        return combine(other, false);
    }

    private ExactRational combine(ExactRational other, boolean subtract) {
        if (other.signum() == 0) return this;
        if (signum() == 0) return subtract ? other.negate() : other;
        BigInteger common = denominator.gcd(other.denominator);
        BigInteger left = denominator.divide(common), right = other.denominator.divide(common);
        BigInteger a = numerator.multiply(right), b = other.numerator.multiply(left);
        BigInteger result = subtract ? a.subtract(b) : a.add(b);
        if (result.signum() == 0) return ZERO;
        // With coprime inputs, any remaining cancellation divides the old
        // denominator GCD, not the much larger new denominator product.
        BigInteger cancel = common.equals(BigInteger.ONE) ? BigInteger.ONE : result.gcd(common);
        return new ExactRational(result.divide(cancel), left.multiply(other.denominator.divide(cancel)), true);
    }

    ExactRational subtract(ExactRational other) {
        return combine(other, true);
    }

    ExactRational multiply(ExactRational other) {
        if (signum() == 0 || other.signum() == 0) return ZERO;
        BigInteger a = numerator.gcd(other.denominator), b = other.numerator.gcd(denominator);
        // Cross-cancellation completely reduces this product. Recomputing a
        // GCD of the products repeats the expensive part of every LP pivot.
        return new ExactRational(numerator.divide(a).multiply(other.numerator.divide(b)),
                denominator.divide(b).multiply(other.denominator.divide(a)), true);
    }

    ExactRational divide(ExactRational other) {
        if (other.signum() == 0) throw new ArithmeticException("Zero denominator");
        if (signum() == 0) return ZERO;
        BigInteger a = numerator.gcd(other.numerator), b = denominator.gcd(other.denominator);
        return new ExactRational(numerator.divide(a).multiply(other.denominator.divide(b)),
                denominator.divide(b).multiply(other.numerator.divide(a)), true);
    }

    BigInteger floor() {
        if (integral()) return numerator;
        BigInteger[] parts = numerator.divideAndRemainder(denominator);
        return numerator.signum() < 0 && parts[1].signum() != 0 ? parts[0].subtract(BigInteger.ONE) : parts[0];
    }

    BigInteger ceil() {
        if (integral()) return numerator;
        BigInteger[] parts = numerator.divideAndRemainder(denominator);
        return numerator.signum() > 0 && parts[1].signum() != 0 ? parts[0].add(BigInteger.ONE) : parts[0];
    }

    boolean integral() {
        return denominator.equals(BigInteger.ONE);
    }

    @Override
    public int compareTo(ExactRational other) {
        if (denominator.equals(other.denominator)) return numerator.compareTo(other.numerator);
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExactRational value && numerator.equals(value.numerator) && denominator.equals(value.denominator);
    }

    @Override
    public int hashCode() {
        return 31 * numerator.hashCode() + denominator.hashCode();
    }

    @Override
    public String toString() {
        return "ExactRational[numerator=" + numerator + ", denominator=" + denominator + "]";
    }

    static final class PrecisionLimit extends RuntimeException {}
}
