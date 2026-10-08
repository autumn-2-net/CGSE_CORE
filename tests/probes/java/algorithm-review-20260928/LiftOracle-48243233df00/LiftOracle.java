package org.cgse.core;
import java.math.*;import java.util.*;
public final class LiftOracle {
 static final BigInteger Z=BigInteger.ZERO,O=BigInteger.ONE;
 static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] x){for(var r:rows){var s=Z;for(var e:r.terms().entrySet())s=s.add(e.getValue().multiply(x[e.getKey()]));if(s.compareTo(r.upper())>0)return false;}return true;}
 public static void main(String[] args){var rng=new Random(720211);long assignments=0,solutions=0;int implications=0,covers=0;
  for(int sample=0;sample<4000;sample++){
   int n=2+rng.nextInt(7);BigInteger[] low=new BigInteger[n],high=new BigInteger[n],plant=new BigInteger[n];int[] sizes=new int[n];int total=1;ExactRational[] point=new ExactRational[n];
   BigInteger scale=sample%11==0?O.shiftLeft(160):O;
   for(int i=0;i<n;i++){low[i]=sample%13==0?O.shiftLeft(90):Z;sizes[i]=(sample+i)%9==0?3:2;high[i]=low[i].add(BigInteger.valueOf(sizes[i]-1));plant[i]=low[i].add(BigInteger.valueOf(rng.nextInt(sizes[i])));point[i]=ExactRational.of(low[i]).add(new ExactRational(BigInteger.valueOf(rng.nextInt((sizes[i]-1)*4+1)),BigInteger.valueOf(4)));total*=sizes[i];}
   var rows=new ArrayList<ExactLinearProgram.Constraint>();
   for(int r=0;r<2+rng.nextInt(10);r++){var terms=new LinkedHashMap<Integer,BigInteger>();BigInteger rhs=BigInteger.valueOf(rng.nextInt(3)-1).multiply(scale);for(int i=0;i<n;i++){var c=BigInteger.valueOf(rng.nextInt(9)-4).multiply(scale);if(c.signum()!=0)terms.put(i,c);rhs=rhs.add(c.multiply(plant[i]));}rows.add(new ExactLinearProgram.Constraint(terms,rhs));}
   var budget=new PlanningBudget(0,20000000,256L<<20,()->false,System::nanoTime);var journal=new CountProof.Journal(16L<<20);budget.proofJournal(journal);
   var cuts=new ArrayList<ExactLinearProgram.Constraint>();
   try(var p=new CountImplications(rows,low,high,budget)){while(!p.step()){}cuts.addAll(p.cuts());implications+=p.cuts().size();}
   try(var p=new CountCoverCuts(rows,low,high,point,budget)){while(!p.step()){}cuts.addAll(p.cuts());covers+=p.cuts().size();for(var cut:p.cuts()){var sum=ExactRational.ZERO;for(var e:cut.terms().entrySet())sum=sum.add(point[e.getKey()].multiply(ExactRational.of(e.getValue())));if(sum.compareTo(ExactRational.of(cut.upper()))<=0)throw new AssertionError("not a separating cut");}}
   for(int code=0;code<total;code++){int z=code;var x=low.clone();for(int i=0;i<n;i++){x[i]=x[i].add(BigInteger.valueOf(z%sizes[i]));z/=sizes[i];}assignments++;if(valid(rows,x)){solutions++;if(!valid(cuts,x))throw new AssertionError("excluded legal count "+sample);}}
   for(var proof:journal.entries())if(CountProof.verify(proof,20000000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("bad certificate "+sample+" "+proof.scope());
   for(var proof:journal.knapsacks())if(CountProof.verify(proof,2000000)!=CountProof.Verdict.VERIFIED)throw new AssertionError(proof); if(budget.reservedBytes()!=0)throw new AssertionError("memory leak");
  }
  var rows=new ArrayList<ExactLinearProgram.Constraint>();int n=48;var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,Z);Arrays.fill(hi,O);for(int i=0;i<n;i++)rows.add(new ExactLinearProgram.Constraint(Map.of(i,O,(i+1)%n,O.negate()),Z));
  var b=new PlanningBudget(0,20000000,256L<<20,()->false,System::nanoTime);try(var p=new CountReduction(rows,lo,hi,b)){while(!p.step()){}if(p.variables()!=1)throw new AssertionError("implication cycle not merged: "+p.variables());for(int v=0;v<=1;v++)if(!valid(rows,p.expand(new BigInteger[]{BigInteger.valueOf(v)})))throw new AssertionError("bad restoration");}
  if(b.reservedBytes()!=0)throw new AssertionError("cycle leak");
  for(int limit=1;limit<256;limit++){var budget=new PlanningBudget(0,limit,256L<<20,()->false,System::nanoTime);try(var p=new CountImplications(rows,lo,hi,budget)){while(!p.step()){}}catch(PlanningBudget.Exhausted expected){}if(budget.reservedBytes()!=0)throw new AssertionError("cutoff leak "+limit);}
  System.out.println("PASS 4000 independently enumerated models; assignments="+assignments+" solutions="+solutions+" implication_rows="+implications+" violated_covers="+covers+"; 48-variable SCC restoration, signed/large/fixed-width domains, proof replay and 255 cutoffs");
 }
}
