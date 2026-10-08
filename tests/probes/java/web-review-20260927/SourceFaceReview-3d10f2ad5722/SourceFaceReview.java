package org.cgse.core;
import java.math.*;import java.util.*;
public final class SourceFaceReview {
 public static void main(String[] args){int valid=0;for(int sample=0;sample<2000;sample++){
  var scale=BigInteger.ONE.shiftLeft(sample%160);var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE,1,BigInteger.ONE),scale.multiply(BigInteger.TWO)),new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE.negate(),1,BigInteger.ONE.negate()),scale.negate()),new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE.negate(),1,BigInteger.TWO.negate()),scale.multiply(BigInteger.valueOf(-4))));
  var lo=new BigInteger[]{BigInteger.ZERO,BigInteger.ZERO};var hi=new BigInteger[]{scale.multiply(BigInteger.TWO),scale.multiply(BigInteger.TWO)};var budget=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);
  try(var face=CountQuickSolve.sourceFace(rows,lo,hi,budget,false)){if(face==null)throw new AssertionError("missing lower face");while(!face.step()){}if(face.infeasible()||!face.learnedConflicts().isEmpty())throw new AssertionError("trial failure leaked as proof");if(face.counts()!=null)throw new AssertionError("infeasible lower face accepted");}
  try(var face=CountQuickSolve.sourceFace(rows,lo,hi,budget,true)){if(face==null)throw new AssertionError("missing upper face");while(!face.step()){}if(face.infeasible()||face.counts()==null||!EquationReview.valid(rows,face.counts(),lo,hi))throw new AssertionError("lost valid upper face");valid++;}
  if(budget.reservedBytes()!=0)throw new AssertionError("leak");
 }System.out.println("PASS 2000 globally feasible models with impossible lower faces; no trial failure/conflict exported; alternate face verified through 160-bit amounts; witnesses="+valid);}
}
