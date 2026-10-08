package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
final class CountGroupRoundingCuts {
 record Parent(CountProof.Row row,BigInteger weight){}
 static BigInteger floor(BigInteger n,BigInteger d){BigInteger[] q=n.divideAndRemainder(d);return q[1].signum()<0?q[0].subtract(BigInteger.ONE):q[0];}
 static List<ExactLinearProgram.Constraint> separate(List<ExactLinearProgram.Constraint> rows,BigInteger[] lower,BigInteger[] upper,ExactRational[] point,PlanningBudget budget){
  long started=budget.threadWork(),allowance=Math.min(65536,budget.remainingWork()/32);List<ExactLinearProgram.Constraint> cuts=new ArrayList<>();if(lower.length>128||rows.size()>1024||allowance<4096)return cuts;
  BitSet used=new BitSet();List<ExactLinearProgram.Constraint> groups=new ArrayList<>();Map<ExactLinearProgram.Constraint,List<Parent>> derivations=new HashMap<>();
  for(var row:rows){Map<Integer,BigInteger> members=new TreeMap<>();BigInteger remaining=row.upper();List<Parent> parents=new ArrayList<>();parents.add(new Parent(CountProof.row(row),BigInteger.ONE));boolean eligible=true;
   for(var t:row.terms().entrySet()){int id=t.getKey();BigInteger v=t.getValue();if(v.equals(BigInteger.ONE)&&lower[id].signum()==0&&!used.get(id)){members.put(id,BigInteger.ONE);continue;}BigInteger endpoint=v.signum()>0?lower[id]:upper[id];if(endpoint==null){eligible=false;break;}remaining=remaining.subtract(v.multiply(endpoint));parents.add(new Parent(new CountProof.Row(Map.of(id,BigInteger.valueOf(-v.signum())),endpoint.multiply(BigInteger.valueOf(-v.signum()))),v.abs()));}
   if(eligible&&remaining.equals(BigInteger.ONE)&&members.size()>1){var group=new ExactLinearProgram.Constraint(members,BigInteger.ONE);groups.add(group);derivations.put(group,parents);members.keySet().forEach(used::set);}
  }
  System.err.println("groups="+groups.size()+" n="+lower.length+" rows="+rows.size());if(groups.size()<2)return cuts;List<ExactLinearProgram.Constraint> ordered=new ArrayList<>();Map<ExactLinearProgram.Constraint,ExactRational> slack=new HashMap<>();
  for(var row:rows){if(row.terms().size()<2||groups.contains(row))continue;ExactRational activity=ExactRational.ZERO;BigInteger mag=BigInteger.ONE;for(var t:row.terms().entrySet()){budget.check();activity=activity.add(point[t.getKey()].multiply(ExactRational.of(t.getValue())));mag=mag.max(t.getValue().abs());}slack.put(row,ExactRational.of(row.upper()).subtract(activity).divide(ExactRational.of(mag)));ordered.add(row);if(budget.threadWork()-started>=allowance)return cuts;}
  ordered.sort(Comparator.comparing(slack::get));if(ordered.size()>16)ordered.subList(16,ordered.size()).clear();
  for(var source:ordered){Set<BigInteger> divisors=new LinkedHashSet<>(List.of(BigInteger.TWO,BigInteger.valueOf(3),BigInteger.valueOf(5),BigInteger.valueOf(7)));source.terms().values().stream().map(BigInteger::abs).filter(v->v.compareTo(BigInteger.ONE)>0).distinct().sorted().limit(12).forEach(divisors::add);
   for(var divisor:divisors){if(budget.threadWork()-started+8192>=allowance)return cuts;Map<Integer,BigInteger> sum=new TreeMap<>(source.terms());BigInteger bound=source.upper();List<CountProof.Row> axioms=new ArrayList<>();List<BigInteger> weights=new ArrayList<>();axioms.add(CountProof.row(source));weights.add(BigInteger.ONE);
    List<BigInteger> proposed=propose(source,groups,lower,divisor,point,budget,started,allowance);if(proposed==null)continue;int gi=0;
    for(var group:groups){BigInteger selected=proposed.get(gi++);
     if(selected.signum()==0)continue;for(int id:group.terms().keySet())sum.merge(id,selected,BigInteger::add);bound=bound.add(selected);for(var parent:derivations.get(group)){axioms.add(parent.row);weights.add(selected.multiply(parent.weight));}
    }
    for(var t:sum.entrySet())bound=bound.subtract(t.getValue().multiply(lower[t.getKey()]));BigInteger rhs=floor(bound,divisor);Map<Integer,BigInteger> terms=new TreeMap<>();ExactRational activity=ExactRational.ZERO;for(var t:sum.entrySet()){budget.check();BigInteger v=floor(t.getValue(),divisor);if(v.signum()!=0)terms.put(t.getKey(),v);rhs=rhs.add(v.multiply(lower[t.getKey()]));activity=activity.add(point[t.getKey()].multiply(ExactRational.of(v)));}
    var cut=new ExactLinearProgram.Constraint(terms,rhs);if(activity.compareTo(ExactRational.of(rhs))<=0||cuts.contains(cut)||rows.contains(cut))continue;for(int id=0;id<lower.length;id++){axioms.add(new CountProof.Row(Map.of(id,BigInteger.ONE.negate()),lower[id].negate()));weights.add(BigInteger.ZERO);}var proof=new CountProof.Rounding("amo_integer_rounding",lower.length,axioms,weights,divisor,Arrays.asList(lower),CountProof.row(cut));long checks=4+3L*lower.length+axioms.stream().mapToLong(r->2+2L*r.terms().size()).sum();if(budget.threadWork()-started+checks>=allowance)return cuts;budget.charge(checks);if(CountProof.verify(proof,checks)!=CountProof.Verdict.VERIFIED)throw new AssertionError("bad group cut");cuts.add(cut);if(budget.proofJournal()!=null)budget.proofJournal().add(proof);if(cuts.size()==8)return cuts;
   }
  }
  return cuts;
 }
 static List<BigInteger> propose(ExactLinearProgram.Constraint source,List<ExactLinearProgram.Constraint> groups,BigInteger[] lower,BigInteger divisor,ExactRational[] point,PlanningBudget budget,long started,long allowance){
  if(divisor.bitLength()>8)return null;int d=divisor.intValueExact();double[] dp=new double[d];Arrays.fill(dp,Double.NEGATIVE_INFINITY);dp[0]=0;int[][] prev=new int[groups.size()][d],choice=new int[groups.size()][d];int g=0;
  for(var group:groups){Set<Integer> options=new LinkedHashSet<>(List.of(0));for(int id:group.terms().keySet())options.add(source.terms().getOrDefault(id,BigInteger.ZERO).negate().mod(divisor).intValueExact());double[] next=new double[d];Arrays.fill(next,Double.NEGATIVE_INFINITY);
   for(int m:options){double score=0;for(int id:group.terms().keySet()){budget.check();double p=point[id].numerator().doubleValue()/point[id].denominator().doubleValue();if(!Double.isFinite(p))return null;score+=floor(source.terms().getOrDefault(id,BigInteger.ZERO).add(BigInteger.valueOf(m)),divisor).doubleValue()*p;}
    for(int r=0;r<d;r++){if(budget.threadWork()-started>=allowance-2048)return null;budget.check();if(!Double.isFinite(dp[r]))continue;int nr=(r+m)%d;double value=dp[r]+score-(r+m)/d;if(value>next[nr]){next[nr]=value;prev[g][nr]=r;choice[g][nr]=m;}}
   }dp=next;g++;
  }
  BigInteger bound=source.upper();for(var t:source.terms().entrySet())bound=bound.subtract(t.getValue().multiply(lower[t.getKey()]));int best=0;double score=Double.NEGATIVE_INFINITY;for(int r=0;r<d;r++){double v=dp[r]-floor(bound.add(BigInteger.valueOf(r)),divisor).doubleValue();if(v>score){score=v;best=r;}}
  List<BigInteger> result=new ArrayList<>(Collections.nCopies(groups.size(),BigInteger.ZERO));for(int i=groups.size()-1;i>=0;i--){result.set(i,BigInteger.valueOf(choice[i][best]));best=prev[i][best];}return result;
 }
}
