package org.cgse.core;
import java.math.*;
import java.util.*;
public final class ViewProbe {
 static BigInteger bi(long x){return BigInteger.valueOf(x);}
 static boolean valid(BigInteger[] x,List<ExactLinearProgram.Constraint> rows){for(var r:rows){BigInteger sum=bi(0);for(var e:r.terms().entrySet())sum=sum.add(e.getValue().multiply(x[e.getKey()]));if(sum.compareTo(r.upper())>0)return false;}return true;}
 static boolean oracle(List<ExactLinearProgram.Constraint> rows,int vars){int n=1<<(vars*2);for(int m=0;m<n;m++){var x=new BigInteger[vars];for(int i=0;i<vars;i++)x[i]=bi((m>>(i*2))&3);if(valid(x,rows))return true;}return false;}
 public static void main(String[]args){var random=new Random(43103102026L);int solved=0,unknown=0,proofs=0;long work=0;
  for(int sample=0;sample<400;sample++){
   int vars=2+random.nextInt(4);var lo=new BigInteger[vars];var hi=new BigInteger[vars];Arrays.fill(lo,bi(0));Arrays.fill(hi,bi(3));var rows=new ArrayList<ExactLinearProgram.Constraint>();
   for(int r=0;r<3+sample%7;r++){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<vars;i++){int c=random.nextInt(9)-4;if(c!=0)terms.put(i,bi(c*3));}var row=new ExactLinearProgram.Constraint(terms,bi((random.nextInt(15)-6)*3));rows.add(row);if(r%2==0)rows.add(row);}
   boolean reachable=oracle(rows,vars);var budget=new PlanningBudget(0,500000,64L<<20,()->false,System::nanoTime);
   try(var models=CountModelViews.create(rows,lo,hi,budget)){models.compileLight();try(var search=new CountViewSearch(models,budget)){
    for(int turn=0;turn<8;turn++){search.resume(32768);while(!search.step()){}if(search.counts()!=null||search.infeasible()||!search.retained())break;}
    if(search.counts()!=null){if(!reachable||!valid(search.counts(),rows))throw new AssertionError("false witness");solved++;}
    else if(search.infeasible()){if(reachable)throw new AssertionError("false proof");proofs++;}else unknown++;
   }}
   if(budget.reservedBytes()!=0)throw new AssertionError("reservation leak");work+=budget.nodes();
  }
  var budget=new PlanningBudget(0,200000,128L<<20,()->false,System::nanoTime);
  var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,bi(-1),1,bi(2),2,bi(1)),bi(-1)),new ExactLinearProgram.Constraint(Map.of(0,bi(1),1,bi(-1)),bi(0)));
  try(var models=CountModelViews.create(rows,new BigInteger[]{bi(0),bi(0),bi(0)},new BigInteger[]{null,null,bi(0)},budget)){models.compileLight();try(var search=new CountViewSearch(models,budget)){
   for(int turn=0;turn<3;turn++){long before=budget.nodes();search.resume(8192);while(!search.step()){}if(budget.nodes()-before>12288)throw new AssertionError("quota replenished during handoff");if(search.counts()!=null)throw new AssertionError("feedback contradiction witness");}
   System.out.println("RESUME retained="+search.retained()+" work="+budget.nodes()+" "+budget.diagnostics());
  }}if(budget.reservedBytes()!=0)throw new AssertionError("retained workspace leak");
  System.out.println("PASS view oracle=400 witnesses="+solved+" proofs="+proofs+" unknown="+unknown+" work="+work+" false_proof=0 false_witness=0; resume and cleanup passed");
 }
}
