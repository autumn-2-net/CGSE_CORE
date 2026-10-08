package org.cgse.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/** LOCAL CANDIDATE. Search observations affect only scheduling, never proofs. */
final class CountPortfolioScalePolicy {
    static final long MIN_QUANTUM = 4096, MAX_QUANTUM = 32768;
    static final class Arm {
        final long startupCost;
        long work, lastTurn = -1;
        int selections, idleSlices, productiveSlices;
        double reward;
        boolean retired;
        // The reference is a rate represented exactly, not a progress-unit constant.
        long bestProgress, bestSpent = 1;
        Arm(long startupCost) { this.startupCost = startupCost; }
    }
    private final List<Arm> arms = new ArrayList<>();
    private long turn;
    Arm add(long startupCost) {
        Arm arm = new Arm(startupCost);
        arms.add(arm);
        return arm;
    }
    Arm select() {
        Arm fresh=null, overdue=null, best=null;
        double bestScore=Double.NEGATIVE_INFINITY;
        int live=(int)arms.stream().filter(a -> !a.retired).count();
        for (Arm arm:arms) {
            if (arm.retired) continue;
            if (arm.selections==0) {
                if (fresh==null || arm.startupCost<fresh.startupCost) fresh=arm;
                continue;
            }
            if (turn-arm.lastTurn>=2L*live && (overdue==null || arm.lastTurn<overdue.lastTurn)) overdue=arm;
            double exploration=.2*StrictMath.sqrt(StrictMath.log(turn+1.0)/arm.selections);
            double score=arm.reward+exploration;
            if (best==null || score>bestScore || score==bestScore && arm.work<best.work) {
                best=arm; bestScore=score;
            }
        }
        return fresh!=null ? fresh : overdue!=null ? overdue : best;
    }
    void selected(Arm arm) {
        arm.lastTurn=turn;
        if (turn<Long.MAX_VALUE) turn++;
        if (arm.selections<Integer.MAX_VALUE) arm.selections++;
    }
    long quantum(Arm arm,long remaining) {
        long live=arms.stream().filter(a -> !a.retired).count();
        long requested=live<=1 ? MAX_QUANTUM : MIN_QUANTUM<<Math.min(3,arm.productiveSlices);
        return Math.min(requested,remaining);
    }
    void feedback(Arm arm,long spent,long progress) {
        if (spent<0) throw new IllegalArgumentException("Negative completed effort");
        long positive=Math.max(0,progress), divisor=Math.max(1,spent);
        long bestProgress=arm.bestProgress, bestSpent=arm.bestSpent;
        double sample=0;
        if (positive>0) {
            if (bestProgress==0) {
                bestProgress=positive; bestSpent=divisor; sample=1;
            } else {
                // Cross-products may exceed long. Exact division makes an independent
                // change of progress units leave every score/tie unchanged.
                BigInteger numerator=BigInteger.valueOf(positive).multiply(BigInteger.valueOf(bestSpent));
                BigInteger denominator=BigInteger.valueOf(bestProgress).multiply(BigInteger.valueOf(divisor));
                if (numerator.compareTo(denominator)>=0) {
                    bestProgress=positive; bestSpent=divisor; sample=1;
                } else sample=new BigDecimal(numerator).divide(new BigDecimal(denominator),MathContext.DECIMAL64).doubleValue();
            }
        }
        // Calculate before committing. Call only after a completed solver operation;
        // a cancelled/failed operation must not publish a partial observation.
        double reward=arm.selections==1 ? sample : .75*arm.reward+.25*sample;
        long nextWork=spent>Long.MAX_VALUE-arm.work ? Long.MAX_VALUE : arm.work+spent;
        arm.work=nextWork;
        arm.bestProgress=bestProgress;
        arm.bestSpent=bestSpent;
        arm.reward=reward;
        arm.idleSlices=positive>0 ? 0 : Math.min(4,arm.idleSlices+1);
        arm.productiveSlices=positive>0 ? Math.min(3,arm.productiveSlices+1) : 0;
    }
}
