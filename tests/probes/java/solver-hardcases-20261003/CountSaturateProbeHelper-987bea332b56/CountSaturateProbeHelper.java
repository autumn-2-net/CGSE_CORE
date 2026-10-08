package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
final class CountSaturateProbeHelper {
  static ExactLinearProgram.Constraint apply(ExactLinearProgram.Constraint row, BigInteger[] lo, BigInteger[] hi, PlanningBudget budget) {
    if(row.terms().size()<2 || row.terms().size()>256)return row;
    BigInteger maximum=BigInteger.ZERO,minimumCoefficient=null;
    for(var e:row.terms().entrySet()) {
      budget.check();
      BigInteger end=e.getValue().signum()>0?hi[e.getKey()]:lo[e.getKey()];
      if(end==null)return row;
      maximum=maximum.add(end.multiply(e.getValue()));
      if(!lo[e.getKey()].equals(hi[e.getKey()]))minimumCoefficient=minimumCoefficient==null?e.getValue().abs():minimumCoefficient.min(e.getValue().abs());
    }
    BigInteger demand=maximum.subtract(row.upper());
    if(demand.signum()<=0 || minimumCoefficient==null)return row;
    BigInteger second=demand.add(BigInteger.ONE).divide(BigInteger.TWO).max(demand.subtract(minimumCoefficient));
    Map<Integer,BigInteger> terms=new LinkedHashMap<>();BigInteger rhs=demand.negate();
    for(var e:row.terms().entrySet()) {
      budget.check();
      BigInteger a=e.getValue().abs().min(demand);if(a.compareTo(second)>0 && a.compareTo(demand)<0)a=second;if(e.getValue().signum()<0)a=a.negate();
      terms.put(e.getKey(),a);
      rhs=rhs.add(a.multiply(a.signum()>0?hi[e.getKey()]:lo[e.getKey()]));
    }
    return CountReduction.normalize(new ExactLinearProgram.Constraint(terms,rhs));
  }
}
