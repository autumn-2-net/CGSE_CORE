package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
final class CountComplementRoundingCuts {
 static List<ExactLinearProgram.Constraint> separate(List<ExactLinearProgram.Constraint> rows,BigInteger[] lower,BigInteger[] upper,ExactRational[] point,PlanningBudget budget){
  long start=budget.threadWork(),limit=Math.min(65536,budget.remainingWork()/32);List<ExactLinearProgram.Constraint> result=new ArrayList<>();
  if(lower.length>128||rows.size()>1024||limit<4096)return result;
  List<ExactLinearProgram.Constraint> ordered=new ArrayList<>();Map<ExactLinearProgram.Constraint,ExactRational> slack=new HashMap<>();
  for(var row:rows){if(row.terms().size()<2)continue;ExactRational activity=ExactRational.ZERO;BigInteger mag=BigInteger.ONE;for(var t:row.terms().entrySet()){budget.check();activity=activity.add(point[t.getKey()].multiply(ExactRational.of(t.getValue())));mag=mag.max(t.getValue().abs());}slack.put(row,ExactRational.of(row.upper()).subtract(activity).divide(ExactRational.of(mag)));ordered.add(row);if(budget.threadWork()-start>=limit)return result;}
  ordered.sort(Comparator.comparing(slack::get));if(ordered.size()>24)ordered.subList(24,ordered.size()).clear();List<List<ExactLinearProgram.Constraint>> combos=new ArrayList<>();for(var row:ordered)combos.add(List.of(row));for(int i=0;i<Math.min(8,ordered.size());i++)for(int j=i+1;j<Math.min(8,ordered.size());j++)combos.add(List.of(ordered.get(i),ordered.get(j)));
  for(var combo:combos){Map<Integer,BigInteger> sum=new TreeMap<>();BigInteger bound=BigInteger.ZERO;for(var row:combo){bound=bound.add(row.upper());for(var t:row.terms().entrySet()){budget.check();sum.merge(t.getKey(),t.getValue(),BigInteger::add);}}
   Set<BigInteger> divisors=new LinkedHashSet<>(List.of(BigInteger.TWO,BigInteger.valueOf(3),BigInteger.valueOf(5),BigInteger.valueOf(7)));sum.values().stream().map(BigInteger::abs).filter(v->v.compareTo(BigInteger.ONE)>0).distinct().sorted().limit(12).forEach(divisors::add);
   for(var divisor:divisors)for(int pass=0;pass<2;pass++){
    if(budget.threadWork()-start+1024>=limit)return result;
    Map<Integer,BigInteger> augmented=new TreeMap<>(sum),added=new TreeMap<>();BigInteger rhs=bound;
    for(var t:sum.entrySet()){budget.check();int id=t.getKey();boolean flip=upper[id]!=null&&(pass==0?point[id].subtract(ExactRational.of(lower[id])).compareTo(ExactRational.of(upper[id].subtract(lower[id])).divide(ExactRational.of(BigInteger.TWO)))>0:t.getValue().signum()<0);if(!flip)continue;BigInteger delta=t.getValue().negate().mod(divisor);if(delta.signum()==0)continue;augmented.put(id,t.getValue().add(delta));added.put(id,delta);rhs=rhs.add(delta.multiply(upper[id]));}
    for(var t:augmented.entrySet())rhs=rhs.subtract(t.getValue().multiply(lower[t.getKey()]));rhs=floor(rhs,divisor);Map<Integer,BigInteger> terms=new TreeMap<>();ExactRational activity=ExactRational.ZERO;
    for(var t:augmented.entrySet()){budget.check();int id=t.getKey();BigInteger value=floor(t.getValue(),divisor);if(value.signum()!=0)terms.put(id,value);rhs=rhs.add(value.multiply(lower[id]));activity=activity.add(point[id].multiply(ExactRational.of(value)));}
    var cut=new ExactLinearProgram.Constraint(terms,rhs);if(activity.compareTo(ExactRational.of(rhs))<=0||result.contains(cut)||rows.contains(cut))continue;
    List<CountProof.Row> axioms=new ArrayList<>();List<BigInteger> weights=new ArrayList<>();for(var row:combo){axioms.add(CountProof.row(row));weights.add(BigInteger.ONE);}for(var t:added.entrySet()){axioms.add(new CountProof.Row(Map.of(t.getKey(),BigInteger.ONE),upper[t.getKey()]));weights.add(t.getValue());}for(int i=0;i<lower.length;i++){axioms.add(new CountProof.Row(Map.of(i,BigInteger.ONE.negate()),lower[i].negate()));weights.add(BigInteger.ZERO);}
    var proof=new CountProof.Rounding("complemented_rounding",lower.length,axioms,weights,divisor,Arrays.asList(lower),CountProof.row(cut));long checks=4L+3L*lower.length+axioms.stream().mapToLong(r->2+2*r.terms().size()).sum();if(budget.threadWork()-start+checks>=limit)return result;budget.charge(checks);if(CountProof.verify(proof,checks)!=CountProof.Verdict.VERIFIED)throw new AssertionError("invalid proof");result.add(cut);if(budget.proofJournal()!=null)budget.proofJournal().add(proof);if(result.size()==8)return result;
   }
  }
  return result;
 }
 static BigInteger floor(BigInteger n,BigInteger d){BigInteger[] q=n.divideAndRemainder(d);return q[1].signum()<0?q[0].subtract(BigInteger.ONE):q[0];}
}
