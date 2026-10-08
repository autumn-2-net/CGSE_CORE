package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
final class CountGroupRoundingCuts {
 static BigInteger floor(BigInteger n,BigInteger d){BigInteger[] q=n.divideAndRemainder(d);return q[1].signum()<0?q[0].subtract(BigInteger.ONE):q[0];}
 static List<ExactLinearProgram.Constraint> separate(List<ExactLinearProgram.Constraint> rows,BigInteger[] lower,BigInteger[] upper,ExactRational[] point,PlanningBudget budget){
  long started=budget.threadWork(),allowance=Math.min(65536,budget.remainingWork()/32);List<ExactLinearProgram.Constraint> cuts=new ArrayList<>();if(lower.length>128||rows.size()>1024||allowance<4096)return cuts;
  BitSet used=new BitSet();List<ExactLinearProgram.Constraint> groups=new ArrayList<>();
  for(var row:rows)if(row.upper().equals(BigInteger.ONE)&&row.terms().size()>1&&row.terms().values().stream().allMatch(v->v.equals(BigInteger.ONE))&&row.terms().keySet().stream().allMatch(id->lower[id].signum()==0&&!used.get(id))){groups.add(row);row.terms().keySet().forEach(used::set);}
  if(groups.size()<2)return cuts;List<ExactLinearProgram.Constraint> ordered=new ArrayList<>();Map<ExactLinearProgram.Constraint,ExactRational> slack=new HashMap<>();
  for(var row:rows){if(row.terms().size()<2||groups.contains(row))continue;ExactRational activity=ExactRational.ZERO;BigInteger mag=BigInteger.ONE;for(var t:row.terms().entrySet()){budget.check();activity=activity.add(point[t.getKey()].multiply(ExactRational.of(t.getValue())));mag=mag.max(t.getValue().abs());}slack.put(row,ExactRational.of(row.upper()).subtract(activity).divide(ExactRational.of(mag)));ordered.add(row);if(budget.threadWork()-started>=allowance)return cuts;}
  ordered.sort(Comparator.comparing(slack::get));if(ordered.size()>16)ordered.subList(16,ordered.size()).clear();
  for(var source:ordered){Set<BigInteger> divisors=new LinkedHashSet<>(List.of(BigInteger.TWO,BigInteger.valueOf(3),BigInteger.valueOf(5),BigInteger.valueOf(7)));source.terms().values().stream().map(BigInteger::abs).filter(v->v.compareTo(BigInteger.ONE)>0).distinct().sorted().limit(12).forEach(divisors::add);
   for(var divisor:divisors){if(budget.threadWork()-started+8192>=allowance)return cuts;Map<Integer,BigInteger> sum=new TreeMap<>(source.terms());BigInteger bound=source.upper();List<CountProof.Row> axioms=new ArrayList<>();List<BigInteger> weights=new ArrayList<>();axioms.add(CountProof.row(source));weights.add(BigInteger.ONE);
    for(var group:groups){Set<BigInteger> choices=new LinkedHashSet<>(List.of(BigInteger.ZERO));for(int id:group.terms().keySet())choices.add(source.terms().getOrDefault(id,BigInteger.ZERO).negate().mod(divisor));ExactRational best=null;BigInteger selected=BigInteger.ZERO;
     for(var value:choices){ExactRational score=ExactRational.of(value).divide(ExactRational.of(divisor)).negate();for(int id:group.terms().keySet()){budget.check();score=score.add(point[id].multiply(ExactRational.of(floor(source.terms().getOrDefault(id,BigInteger.ZERO).add(value),divisor))));}if(best==null||score.compareTo(best)>0){best=score;selected=value;}}
     if(selected.signum()==0)continue;for(int id:group.terms().keySet())sum.merge(id,selected,BigInteger::add);bound=bound.add(selected);axioms.add(CountProof.row(group));weights.add(selected);
    }
    for(var t:sum.entrySet())bound=bound.subtract(t.getValue().multiply(lower[t.getKey()]));BigInteger rhs=floor(bound,divisor);Map<Integer,BigInteger> terms=new TreeMap<>();ExactRational activity=ExactRational.ZERO;for(var t:sum.entrySet()){budget.check();BigInteger v=floor(t.getValue(),divisor);if(v.signum()!=0)terms.put(t.getKey(),v);rhs=rhs.add(v.multiply(lower[t.getKey()]));activity=activity.add(point[t.getKey()].multiply(ExactRational.of(v)));}
    var cut=new ExactLinearProgram.Constraint(terms,rhs);if(activity.compareTo(ExactRational.of(rhs))<=0||cuts.contains(cut)||rows.contains(cut))continue;for(int id=0;id<lower.length;id++){axioms.add(new CountProof.Row(Map.of(id,BigInteger.ONE.negate()),lower[id].negate()));weights.add(BigInteger.ZERO);}var proof=new CountProof.Rounding("amo_integer_rounding",lower.length,axioms,weights,divisor,Arrays.asList(lower),CountProof.row(cut));long checks=4+3L*lower.length+axioms.stream().mapToLong(r->2+2L*r.terms().size()).sum();if(budget.threadWork()-started+checks>=allowance)return cuts;budget.charge(checks);if(CountProof.verify(proof,checks)!=CountProof.Verdict.VERIFIED)throw new AssertionError("bad group cut");cuts.add(cut);if(budget.proofJournal()!=null)budget.proofJournal().add(proof);if(cuts.size()==8)return cuts;
   }
  }
  return cuts;
 }
}
