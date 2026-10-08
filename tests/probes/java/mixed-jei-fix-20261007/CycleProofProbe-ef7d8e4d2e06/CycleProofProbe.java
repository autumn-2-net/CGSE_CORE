package org.cgse.core;
import java.util.*;import java.math.BigInteger;
public class CycleProofProbe {
 public static void main(String[]args){int checks=0;long work=0;for(int v=0;v<80;v++)for(boolean sat:new boolean[]{false,true}) {
  Random random=new Random(v);var perm=new ArrayList<Integer>(List.of(0,1,2,3,4));Collections.shuffle(perm,random);BigInteger scale=v%3==0?BigInteger.TEN.pow(35):BigInteger.valueOf(1+v%9);BigInteger[]lo=new BigInteger[5],hi=new BigInteger[5];Arrays.fill(lo,BigInteger.ZERO);
  lo[perm.get(0)]=BigInteger.valueOf(54);hi[perm.get(0)]=lo[perm.get(0)];lo[perm.get(3)]=BigInteger.TWO;hi[perm.get(3)]=BigInteger.TWO;hi[perm.get(4)]=BigInteger.valueOf(sat?56:34);
  var rows=new ArrayList<ExactLinearProgram.Constraint>();rows.add(new ExactLinearProgram.Constraint(Map.of(perm.get(0),scale,perm.get(1),scale.negate(),perm.get(2),scale,perm.get(3),scale),BigInteger.ZERO));rows.add(new ExactLinearProgram.Constraint(Map.of(perm.get(1),scale,perm.get(2),scale.negate(),perm.get(4),scale.negate()),BigInteger.ZERO));Collections.shuffle(rows,random);
  var budget=new PlanningBudget(0,2_000_000,64L<<20,()->false,System::nanoTime);CountProof.Certificate proof;BigInteger[]counts;boolean infeasible;
  try(var search=new CountLcg(rows,lo,hi,budget,1_000_000,true)){while(!search.step()){}counts=search.counts();infeasible=search.infeasible();proof=search.certificate();if(sat!=(counts!=null)||sat==infeasible)throw new AssertionError("wrong result "+v+" "+sat+" "+budget.diagnostics());if(counts!=null)CountBenchmark.verify(rows,lo,hi,counts);if(!sat){if(proof.derived().isEmpty())throw new AssertionError("no derived cut");var verdict=CountProof.verify(proof,1_000_000);if(verdict!=CountProof.Verdict.VERIFIED)throw new AssertionError("proof "+verdict);var cut=proof.derived().get(0);var invalid=new CountProof.Combination(cut.parents(),cut.divisor(),new CountProof.Row(cut.consequence().terms(),cut.consequence().upper().subtract(BigInteger.ONE)));if(CountProof.verify(new CountProof.Derivation("bad",5,proof.axioms(),List.of(invalid)),100000)==CountProof.Verdict.VERIFIED)throw new AssertionError("accepted invalid cut");}}
  if(budget.reservedBytes()!=0)throw new AssertionError("leak");checks++;work+=budget.nodes();
 }System.out.println("checks="+checks+" work="+work);}
}
