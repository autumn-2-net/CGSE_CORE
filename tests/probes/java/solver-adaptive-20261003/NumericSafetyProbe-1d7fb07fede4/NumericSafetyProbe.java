package org.cgse.core;
import java.math.BigInteger;import java.util.*;
public class NumericSafetyProbe {
 static long cases, certificates, assignments, checks, declined;
 static BigInteger b(long x){return BigInteger.valueOf(x);}
 static boolean valid(List<ExactLinearProgram.Constraint> rows,int mask){for(var row:rows){BigInteger s=BigInteger.ZERO;for(var t:row.terms().entrySet())if((mask&(1<<t.getKey()))!=0)s=s.add(t.getValue());if(s.compareTo(row.upper())>0)return false;}return true;}
 static boolean certify(List<ExactLinearProgram.Constraint> rows,int n,double[] dual){if(dual==null)return false;double max=0;for(double d:dual){if(!Double.isFinite(d))return false;max=Math.max(max,d);}if(!(max>0))return false;for(int bits:new int[]{16,24,32,40}){BigInteger[] coeff=new BigInteger[n];Arrays.fill(coeff,BigInteger.ZERO);BigInteger rhs=BigInteger.ZERO;for(int i=0;i<rows.size();i++){BigInteger w=b(Math.round(Math.max(0,dual[i])/max*(1L<<bits)));if(w.signum()==0)continue;rhs=rhs.add(w.multiply(rows.get(i).upper()));for(var t:rows.get(i).terms().entrySet())coeff[t.getKey()]=coeff[t.getKey()].add(w.multiply(t.getValue()));}BigInteger min=BigInteger.ZERO;for(BigInteger c:coeff)min=min.add(c.min(BigInteger.ZERO));if(rhs.compareTo(min)<0)return true;}return false;}
 public static void main(String[] args){Random random=new Random(992881);for(int trial=0;trial<5000;trial++){
  int n=1+random.nextInt(8),m=1+random.nextInt(20);var rows=new ArrayList<ExactLinearProgram.Constraint>();BigInteger scale=trial%7==0?BigInteger.TEN.pow(35):trial%13==0?BigInteger.TEN.pow(300):BigInteger.ONE;
  for(int r=0;r<m;r++){Map<Integer,BigInteger> t=new TreeMap<>();for(int i=0;i<n;i++){int a=random.nextInt(21)-10;if(a!=0)t.put(i,b(a).multiply(scale));}rows.add(new ExactLinearProgram.Constraint(t,b(random.nextInt(81)-40).multiply(scale)));}
  for(int i=0;i<n;i++)rows.add(new ExactLinearProgram.Constraint(Map.of(i,BigInteger.ONE),BigInteger.ONE));
  boolean feasible=false;for(int mask=0;mask<(1<<n);mask++){assignments++;feasible|=valid(rows,mask);}
  BigInteger[] c=new BigInteger[n];for(int i=0;i<n;i++)c[i]=b(random.nextInt(101)-50);
  PlanningBudget budget=new PlanningBudget(0,1000000,8L<<20,()->false,()->0);var p=CountNumericRelaxation.solve(n,rows,c,budget,200000);cases++;
  if(p==null)declined++;else if(p.phaseOneInfeasible()&&certify(rows,n,p.dual())){certificates++;if(feasible)throw new AssertionError("false proof "+trial);}
  if(budget.reservedBytes()!=0)throw new AssertionError("leak "+trial);checks++;
 }
 var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,b(-1),1,b(-1)),b(-3)),new ExactLinearProgram.Constraint(Map.of(0,b(1)),b(1)),new ExactLinearProgram.Constraint(Map.of(1,b(1)),b(1)));
 for(long max:new long[]{1,4,16,64,256,1024,4096,20000})for(long mem:new long[]{1,8192,16384,1048576}){
  PlanningBudget budget=new PlanningBudget(0,max,mem,()->false,()->0);try{CountNumericRelaxation.solve(2,rows,new BigInteger[]{b(-1),b(-1)},budget,max);}catch(PlanningBudget.Exhausted expected){}if(budget.reservedBytes()!=0)throw new AssertionError("quota leak");checks++;
 }
 for(int at=1;at<300;at++){final int stop=at;int[] polls={0};PlanningBudget budget=new PlanningBudget(0,1000000,8L<<20,()->++polls[0]>=stop,()->0);try{CountNumericRelaxation.solve(2,rows,new BigInteger[]{b(-1),b(-1)},budget,100000);}catch(java.util.concurrent.CancellationException|PlanningBudget.Exhausted expected){}if(budget.reservedBytes()!=0)throw new AssertionError("cancel leak");checks++;}
 System.out.println("PASS cases="+cases+" assignments="+assignments+" exactCertificates="+certificates+" declined="+declined+" checks="+checks);
 }
}
