package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
import static org.cgse.core.GeneralSearchReview.*;
import static org.cgse.core.ContrastProbe.*;

public class CountCounterReview {
 static boolean fits(List<ExactLinearProgram.Constraint> rows,BigInteger[] p){for(var row:rows){var sum=BigInteger.ZERO;for(var e:row.terms().entrySet())sum=sum.add(e.getValue().multiply(p[e.getKey()]));if(sum.compareTo(row.upper())>0)return false;}return true;}
 static BigInteger[] point(int mask,int n,int base){var p=new BigInteger[n];for(int i=0;i<n;i++){p[i]=z(mask%base);mask/=base;}return p;}
 static List<BigInteger[]> all(List<ExactLinearProgram.Constraint>rows,BigInteger[]lo,BigInteger[]hi,int base){var out=new ArrayList<BigInteger[]>();int total=1;for(int i=0;i<lo.length;i++)total*=base;for(int mask=0;mask<total;mask++){var p=point(mask,lo.length,base);boolean domain=true;for(int i=0;i<p.length;i++)if(p[i].compareTo(lo[i])<0||p[i].compareTo(hi[i])>0)domain=false;if(domain&&fits(rows,p))out.add(p);}return out;}
 static void reduction(){var rng=new Random(260926);int equalities=0;
  for(int test=0;test<700;test++){int n=2+rng.nextInt(6);var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,z(0));Arrays.fill(hi,z(test%2==0?1:2));var rows=new ArrayList<ExactLinearProgram.Constraint>();
   for(int i=0;i+1<n;i+=2)if(test%2==0)rows.add(new ExactLinearProgram.Constraint(Map.of(i,z(1),i+1,z(1)),z(1)));
   for(int j=0;j<4+rng.nextInt(5);j++){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<n;i++){int c=rng.nextInt(9)-4;if(c!=0)terms.put(i,z(c));}var rhs=z(rng.nextInt(15)-7);rows.add(new ExactLinearProgram.Constraint(terms,rhs));if(rng.nextInt(6)==0){var neg=new LinkedHashMap<Integer,BigInteger>();terms.forEach((k,v)->neg.put(k,v.negate()));rows.add(new ExactLinearProgram.Constraint(neg,rhs.negate()));}}
   var feasible=all(rows,lo,hi,test%2==0?2:3);var b=budget();try(var reduced=new CountReduction(rows,lo,hi,b)){while(!reduced.step()){}equalities+=reduced.proof().size();
    for(var p:feasible){var q=Arrays.stream(reduced.representatives()).mapToObj(i->p[i]).toArray(BigInteger[]::new);check(fits(reduced.rows(),q),"lost feasible by reduction "+test);check(Arrays.equals(p,reduced.expand(q)),"wrong reconstruction");}
    for(var q:all(reduced.rows(),reduced.lower(),reduced.upper(),test%2==0?2:3)){var p=reduced.expand(q);check(fits(rows,p),"admitted false reduction witness "+test);for(int i=0;i<n;i++)check(p[i].compareTo(lo[i])>=0&&p[i].compareTo(hi[i])<=0,"lost domain");}
   }check(b.reservedBytes()==0,"reduction memory");
  }System.out.println("REDUCTION 700 finite domains independently enumerated; equalities="+equalities+"; lost=0 false=0");
 }
 static void binary(){var rng=new Random(268899);int proved=0,found=0;
  for(int test=0;test<1200;test++){int n=2+rng.nextInt(8);var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,z(0));Arrays.fill(hi,z(1));var rows=new ArrayList<ExactLinearProgram.Constraint>();for(int j=0,m=3+rng.nextInt(12);j<m;j++){var t=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<n;i++){int c=rng.nextInt(9)-4;if(c!=0)t.put(i,z(c));}rows.add(new ExactLinearProgram.Constraint(t,z(rng.nextInt(15)-7)));}boolean oracle=!all(rows,lo,hi,2).isEmpty();var b=budget();try(var s=new CountBoolean(rows,lo,hi,b)){while(!s.step()){}if(s.counts()!=null){found++;check(oracle&&fits(rows,s.counts()),"false boolean witness");}if(s.infeasible()){proved++;check(!oracle,"FALSE boolean UNSAT "+test);}check((s.counts()!=null)==oracle,"small binary unresolved "+test);}check(b.reservedBytes()==0,"boolean memory");}
  var b=budget();try(var s=new CountBoolean(List.of(new ExactLinearProgram.Constraint(Map.of(1,z(-1)),z(-2))),new BigInteger[]{z(0),z(1)},new BigInteger[]{z(1),z(2)},b)){while(!s.step()){}check(s.counts()==null&&!s.infeasible(),"trial face promoted to UNSAT");}
  System.out.println("BOOLEAN 1200 full-domain oracles; found="+found+" proven="+proved+"; trial-face isolation passed");
 }
 static void matching(){var rng=new Random(267777);int found=0,proved=0,unknown=0;
  for(int test=0;test<1000;test++){int n=3+rng.nextInt(8);var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,z(0));Arrays.fill(hi,z(1));var target=point(rng.nextInt(1<<n),n,2);var rows=new ArrayList<ExactLinearProgram.Constraint>();
   for(int d=0;d<1+rng.nextInt(3);d++){var t=new LinkedHashMap<Integer,BigInteger>();var rhs=BigInteger.ZERO;var scale=test%5==0?BigInteger.ONE.shiftLeft(80+d).add(z(13)):z(1);for(int i=0;i<n;i++){var w=z(rng.nextInt(11)-5).multiply(scale);if(w.signum()!=0)t.put(i,w);rhs=rhs.add(w.multiply(target[i]));}if(test%2!=0&&d==0)rhs=rhs.add(z(1));rows.add(new ExactLinearProgram.Constraint(t,rhs));var neg=new LinkedHashMap<Integer,BigInteger>();t.forEach((k,v)->neg.put(k,v.negate()));rows.add(new ExactLinearProgram.Constraint(neg,rhs.negate()));}
   boolean oracle=!all(rows,lo,hi,2).isEmpty();var b=budget();try(var s=new CountMeetInMiddle(rows,lo,hi,b)){while(!s.step()){}if(s.counts()!=null){found++;check(oracle&&fits(rows,s.counts()),"false MITM witness");}else if(s.infeasible()){proved++;check(!oracle,"FALSE MITM UNSAT "+test);}else unknown++;}check(b.reservedBytes()==0,"matching memory");
  }System.out.println("MATCHING 1000 independent exhaustive checks including 80-bit coefficients; found="+found+" proved="+proved+" unknown="+unknown);
  var lo=new BigInteger[38];var hi=new BigInteger[38];Arrays.fill(lo,z(0));Arrays.fill(hi,z(1));var terms=new LinkedHashMap<Integer,BigInteger>();var neg=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<38;i++){var weight=BigInteger.ONE.shiftLeft(i);terms.put(i,weight);neg.put(i,weight.negate());}var b=budget();try(var s=new CountMeetInMiddle(List.of(new ExactLinearProgram.Constraint(terms,z(1)),new ExactLinearProgram.Constraint(neg,z(-1))),lo,hi,b)){while(!s.step()){}check(s.counts()==null&&!s.infeasible(),"MITM trial pins silently proved original domain UNSAT");}check(b.reservedBytes()==0,"trial face memory");System.out.println("MATCHING larger trial-face failure retains UNKNOWN; parent x0=1 remains feasible");
 }
 static void proofRetention(){var f=AdversarialProbe.randomSat(24,103,0);var b=budget();var w=new GraphPlanningWork<>(new GraphCompiler<>(f.recipes()),f.target(),f.amount(),f.stock(),true,true,b);try{while(!w.step()){if(b.diagnostics().contains("order_proven_infeasible")){var p=w.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT));check(p.result()==GraphPlan.Result.INFEASIBLE,"proof lost on preview limit");System.out.println("PREVIEW proof retained through forced cutoff: "+p.result());return;}}throw new AssertionError("proof never exposed");}finally{w.close();}}
 public static void main(String[]args){reduction();binary();matching();proofRetention();}
}
