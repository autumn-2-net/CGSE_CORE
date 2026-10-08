package org.cgse.core;
import java.math.BigInteger;import java.util.*;
public class CoverAdmissionProbe{
 static BigInteger[] fill(int n,int value){BigInteger[] x=new BigInteger[n];Arrays.fill(x,BigInteger.valueOf(value));return x;}
 static final class NoReadRows extends AbstractList<ExactLinearProgram.Constraint>{final int length;NoReadRows(int length){this.length=length;}public int size(){return length;}public ExactLinearProgram.Constraint get(int index){throw new AssertionError("unsupported input was scanned");}}
 public static void main(String[] args){int checked=0;
  for(int mode=0;mode<4;mode++){int n=mode==0?513:89,rows=mode==1?1025:100;PlanningBudget budget=new PlanningBudget(0,mode==2?20000:20000000,mode==3?1024:64L<<20,()->false,()->0L);ExactRational[] point=new ExactRational[n];Arrays.fill(point,ExactRational.ZERO);try(var s=new CountCoverCuts(new NoReadRows(rows),fill(n,0),fill(n,1),point,budget)){if(!s.step()||!s.cuts().isEmpty())throw new AssertionError("decline");}if(budget.reservedBytes()!=0||budget.nodes()!=0)throw new AssertionError("unsupported charged/reserved");checked++;}
  List<ExactLinearProgram.Constraint> rows=new ArrayList<>();for(int r=0;r<100;r++)rows.add(new ExactLinearProgram.Constraint(Map.of(r%89,BigInteger.ONE,(r+1)%89,BigInteger.TWO),BigInteger.TWO));ExactRational[] point=new ExactRational[89];Arrays.fill(point,ExactRational.ZERO);
  PlanningBudget budget=new PlanningBudget(0,20000000,64L<<20,()->false,()->0L);Thread.currentThread().interrupt();try(var s=new CountCoverCuts(rows,fill(89,0),fill(89,1),point,budget)){throw new AssertionError("cancel ignored");}catch(java.util.concurrent.CancellationException good){}finally{Thread.interrupted();}if(budget.reservedBytes()!=0)throw new AssertionError("constructor leak");checked++;
  System.out.println("CoverAdmissionProbe checks="+checked+" unadmittedRowsRead=0 constructorCancelLeak=0");
 }
}
