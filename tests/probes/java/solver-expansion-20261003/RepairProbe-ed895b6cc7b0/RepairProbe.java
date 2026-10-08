package org.cgse.core;
import java.math.*;
import java.util.*;
public final class RepairProbe {
 static BigInteger bi(long v){return BigInteger.valueOf(v);}
 static void check(BigInteger[] value,List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi){
  if(value==null)return;
  for(int i=0;i<value.length;i++)if(value[i].compareTo(lo[i])<0||hi[i]!=null&&value[i].compareTo(hi[i])>0)throw new AssertionError("domain");
  for(var row:rows){var sum=BigInteger.ZERO;for(var e:row.terms().entrySet())sum=sum.add(e.getValue().multiply(value[e.getKey()]));if(sum.compareTo(row.upper())>0)throw new AssertionError("row");}
 }
 static int solve(String label,List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,ExactRational[] point,ExactRational[] alternative){
  var budget=new PlanningBudget(0,1_000_000,64L<<20,()->false,System::nanoTime);BigInteger[] result;
  try(var p=new CountRepairPortfolio(rows,lo,hi,point,alternative,budget)){int steps=0;while(!p.step()){if(++steps>2_000_000)throw new AssertionError("spin");}result=p.counts();check(result,rows,lo,hi);}
  if(budget.reservedBytes()!=0)throw new AssertionError("memory="+budget.reservedBytes());
  System.out.println(label+" witness="+(result!=null)+" work="+budget.nodes()+" "+budget.diagnostics());return result==null?0:1;
 }
 public static void main(String[]args){
  solve("unbounded_rounding",List.of(new ExactLinearProgram.Constraint(Map.of(0,bi(-1),1,bi(-2)),bi(-3))),new BigInteger[]{bi(0),bi(0)},new BigInteger[]{bi(2),null},new ExactRational[]{ExactRational.ZERO,new ExactRational(bi(3),bi(2))},null);
  int successes=0;var random=new Random(10032026);
  for(int n=0;n<100;n++){
   int vars=4;var low=new BigInteger[vars];var high=new BigInteger[vars];var point=new ExactRational[vars];Arrays.fill(low,bi(0));Arrays.fill(high,bi(5));
   for(int i=0;i<vars;i++)point[i]=new ExactRational(bi(1+random.nextInt(8)),bi(2));
   var rows=new ArrayList<ExactLinearProgram.Constraint>();
   for(int r=0;r<5;r++){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<vars;i++){int c=random.nextInt(7)-3;if(c!=0)terms.put(i,bi(c));}rows.add(new ExactLinearProgram.Constraint(terms,bi(random.nextInt(15)-5)));}
   successes+=solve("random"+n,rows,low,high,point,null);
  }
  System.out.println("PASS portfolio exact candidate checks and memory release; random_witnesses="+successes);
 }
}
