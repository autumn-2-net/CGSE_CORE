package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
final class CountSaturateProbeHelper {
  static ExactLinearProgram.Constraint apply(ExactLinearProgram.Constraint row, BigInteger[] lo, BigInteger[] hi, PlanningBudget budget) {
    if(row.terms().size()<2 || row.terms().size()>256)return row;
    BigInteger maximum=BigInteger.ZERO;
    for(var e:row.terms().entrySet()) {
      budget.check();
      BigInteger end=e.getValue().signum()>0?hi[e.getKey()]:lo[e.getKey()];
      if(end==null)return row;
      maximum=maximum.add(end.multiply(e.getValue()));
    }
    BigInteger demand=maximum.subtract(row.upper());
    if(demand.signum()<=0 || row.terms().values().stream().noneMatch(c->c.abs().compareTo(demand)>0))return row;
    Map<Integer,BigInteger> terms=new LinkedHashMap<>();BigInteger rhs=demand.negate();
    for(var e:row.terms().entrySet()) {
      budget.check();
      BigInteger a=e.getValue().abs().min(demand);if(e.getValue().signum()<0)a=a.negate();
      terms.put(e.getKey(),a);
      rhs=rhs.add(a.multiply(a.signum()>0?hi[e.getKey()]:lo[e.getKey()]));
    }
    return CountReduction.normalize(new ExactLinearProgram.Constraint(terms,rhs));
  }
}
