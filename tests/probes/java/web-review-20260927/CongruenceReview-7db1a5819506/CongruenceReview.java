package org.cgse.core;
import java.math.*;import java.util.*;import java.nio.file.*;import java.io.*;
public final class CongruenceReview {
 static void equality(List<ExactLinearProgram.Constraint> rows,Map<Integer,BigInteger> a,BigInteger rhs){rows.add(new ExactLinearProgram.Constraint(a,rhs));var b=new HashMap<Integer,BigInteger>();a.forEach((i,v)->b.put(i,v.negate()));rows.add(new ExactLinearProgram.Constraint(b,rhs.negate()));}
 public static void main(String[] args)throws Exception {var rng=new Random(237746);int closed=0;
 for(int test=0;test<4000;test++){
  int n=2+rng.nextInt(5),m=2+rng.nextInt(7);var rows=new ArrayList<ExactLinearProgram.Constraint>();BigInteger[] point=new BigInteger[n];for(int i=0;i<n;i++)point[i]=BigInteger.valueOf(rng.nextInt(7)-3);if(test%3==0)point[0]=point[0].add(BigInteger.ONE.shiftLeft(90));
  boolean planted=test%2==0;
  for(int r=0;r<m;r++){var terms=new HashMap<Integer,BigInteger>();var rhs=planted?BigInteger.ZERO:BigInteger.valueOf(rng.nextInt(29)-14);for(int i=0;i<n;i++){var c=BigInteger.valueOf(rng.nextInt(11)-5);if(c.signum()!=0)terms.put(i,c);if(planted)rhs=rhs.add(c.multiply(point[i]));}equality(rows,terms,rhs);}
  var budget=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);var journal=new CountProof.Journal(8L<<20);budget.proofJournal(journal);
  try(var work=new CountCongruence(rows,n,budget)){while(!work.step()){}if(work.infeasible()){closed++;if(planted)throw new AssertionError("false exclusion "+test);if(journal.divisibility().isEmpty())throw new AssertionError("missing certificate");}}
  for(var proof:journal.divisibility()){if(CountProof.verify(proof,1_000_000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("bad proof");var zero=new CountProof.Divisibility(proof.scope(),proof.variables(),proof.axioms(),Collections.nCopies(proof.multipliers().size(),BigInteger.ZERO));if(CountProof.verify(zero,1_000_000)!=CountProof.Verdict.INVALID)throw new AssertionError("accepted empty proof");}
  if(budget.reservedBytes()!=0)throw new AssertionError("leak");
 }
 var rows=new ArrayList<ExactLinearProgram.Constraint>();for(int type=0;type<3;type++)for(int i=0;i<4;i++){var a=new HashMap<Integer,BigInteger>();for(int r=0;r<4;r++)for(int c=0;c<4;c++)if((type==0?r:type==1?c:(r+c)%4)==i)a.put(4*r+c,BigInteger.ONE);equality(rows,a,BigInteger.ONE);}
 var journal=new CountProof.Journal(8L<<20);var budget=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);budget.proofJournal(journal);try(var work=new CountCongruence(rows,16,budget)){while(!work.step()){}if(!work.infeasible())throw new AssertionError("missed non-prime modular obstruction");}
 Path path=Path.of(args[0]);journal.write(path);var loaded=CountProof.read(path);for(var p:loaded.divisibility())if(CountProof.verify(p,1_000_000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("roundtrip");
 try(var out=new DataOutputStream(Files.newOutputStream(path.resolveSibling("empty-v2.cgp")))){out.writeInt(0x43475032);out.writeBoolean(false);out.writeInt(0);out.writeInt(0);}if(CountProof.read(path.resolveSibling("empty-v2.cgp")).truncated())throw new AssertionError("v2 compatibility");
 for(int limit=1;limit<200;limit++){budget=new PlanningBudget(0,limit,256L<<20,()->false,System::nanoTime);try(var work=new CountCongruence(rows,16,budget)){while(!work.step()){}}catch(PlanningBudget.Exhausted ok){}if(budget.reservedBytes()!=0)throw new AssertionError("limited constructor/step leaked at "+limit);}
 System.out.println("PASS 4000 exact equality systems; planted witness rejection=0; checked conflicts="+closed+"; modular-4 proof, tampering, v2/v3 archive roundtrip, 199 budget cutoffs");
 }
}
