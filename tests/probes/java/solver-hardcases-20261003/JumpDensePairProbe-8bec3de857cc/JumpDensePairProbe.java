package org.cgse.core;
import java.math.BigInteger;import java.util.*;import java.lang.reflect.*;
public final class JumpDensePairProbe {
 public static void main(String[]args)throws Exception{
  int n=128;var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,BigInteger.ZERO);Arrays.fill(hi,BigInteger.ONE);var rows=new ArrayList<ExactLinearProgram.Constraint>();
  int rowCount=args.length==0?512:Integer.parseInt(args[0]);for(int j=0;j<rowCount;j++){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<n;i++)terms.put(i,BigInteger.valueOf(j%2==0?1:-1));rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(j%2==0?63:-65)));}
  var budget=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);
  try(var jump=new CountJump(rows,lo,hi,budget,2_000_000).retained()){
   var pairField=CountJump.class.getDeclaredField("pairMode");pairField.setAccessible(true);pairField.setBoolean(jump,true);
   var values=(BigInteger[])JumpRetainedProbe.field(jump,"values");for(int i=0;i<64;i++)values[i]=BigInteger.ONE;
   while((int)JumpRetainedProbe.field(jump,"initialized")<rows.size()||!((BitSet)JumpRetainedProbe.field(jump,"dirty")).isEmpty())if(jump.step()&&jump.paused())jump.resume(32768);
   BigInteger[] beforeValues=values.clone(),beforeResidual=((BigInteger[])JumpRetainedProbe.field(jump,"residual")).clone();
   Method pair=CountJump.class.getDeclaredMethod("pair");pair.setAccessible(true);long before=budget.nodes();boolean improved=(boolean)pair.invoke(jump);long spent=budget.nodes()-before;
   if(spent>65536)throw new AssertionError("dense compound overran atomic bound: "+spent);
   if(improved||!Arrays.equals(values,beforeValues)||!Arrays.equals(beforeResidual,(BigInteger[])JumpRetainedProbe.field(jump,"residual")))throw new AssertionError("tentative move not restored");
   System.out.println("dense128 rows"+rowCount+" terms"+(128*rowCount)+" pair_work="+spent+" total="+budget.nodes()+" rollback_exact=true");
  }
  if(budget.reservedBytes()!=0)throw new AssertionError("leak");
 }
}
