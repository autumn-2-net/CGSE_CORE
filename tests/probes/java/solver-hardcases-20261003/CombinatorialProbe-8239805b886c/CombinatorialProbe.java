package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicBoolean;
public class CombinatorialProbe {
 static int checks,models,solved;
 static BigInteger bi(long x){return BigInteger.valueOf(x);}
 static void check(boolean x,String s){checks++;if(!x)throw new AssertionError(s);}
 static BigInteger[] fill(int n,long x){BigInteger[] a=new BigInteger[n];Arrays.fill(a,bi(x));return a;}
 static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,BigInteger[] a){
  for(int j=0;j<a.length;j++)if(a[j].compareTo(lo[j])<0||a[j].compareTo(hi[j])>0)return false;
  for(var r:rows){BigInteger sum=BigInteger.ZERO;for(var e:r.terms().entrySet())sum=sum.add(e.getValue().multiply(a[e.getKey()]));if(sum.compareTo(r.upper())>0)return false;}return true;
 }
 static boolean oracle(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi){
  BigInteger[] a=lo.clone();int n=a.length;
  for(int mask=0;mask<(1<<n);mask++){for(int i=0;i<n;i++)a[i]=((mask>>i)&1)==0?lo[i]:hi[i];if(valid(rows,lo,hi,a))return true;}return false;
 }
 static void test(boolean bins,List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,boolean feasible){
  PlanningBudget b=new PlanningBudget(0,20_000_000,128L<<20,()->false,()->0L);BigInteger[] value;boolean infeasible;
  if(bins){try(var s=new CountBinPackingSearch(rows,lo,hi,b,20_000_000)){while(!s.step()){}value=s.counts();infeasible=s.infeasible();}}
  else{try(var s=new CountCardinalitySearch(rows,lo,hi,b,20_000_000)){while(!s.step()){}value=s.counts();infeasible=s.infeasible();}}
  check(value==null||valid(rows,lo,hi,value),"invalid witness model="+models);check(!infeasible||!feasible,"false UNSAT model="+models);check(value==null||feasible,"false SAT model="+models);check(b.reservedBytes()==0,"leak");if(value!=null||infeasible)solved++;models++;
 }
 static List<ExactLinearProgram.Constraint> binRows(int[] w,int[] p,int[] capacities,int goal){
  List<ExactLinearProgram.Constraint> r=new ArrayList<>();int bins=capacities.length;
  for(int i=0;i<w.length;i++){Map<Integer,BigInteger> row=new TreeMap<>();for(int j=0;j<bins;j++)row.put(i*bins+j,bi(1));r.add(new ExactLinearProgram.Constraint(row,bi(1)));}
  for(int j=0;j<bins;j++){Map<Integer,BigInteger> row=new TreeMap<>();for(int i=0;i<w.length;i++)row.put(i*bins+j,bi(w[i]));r.add(new ExactLinearProgram.Constraint(row,bi(capacities[j])));}
  Map<Integer,BigInteger> row=new TreeMap<>();for(int i=0;i<w.length;i++)for(int j=0;j<bins;j++)row.put(i*bins+j,bi(-p[i]));r.add(new ExactLinearProgram.Constraint(row,bi(-goal)));return r;
 }
 static boolean binOracle(int[] w,int[] p,int[] cap,int goal,int i,int profit){
  if(i==w.length)return profit>=goal;
  if(binOracle(w,p,cap,goal,i+1,profit))return true;
  for(int j=0;j<cap.length;j++)if(cap[j]>=w[i]){cap[j]-=w[i];boolean yes=binOracle(w,p,cap,goal,i+1,profit+p[i]);cap[j]+=w[i];if(yes)return true;}return false;
 }
 static List<ExactLinearProgram.Constraint> shuffled(List<ExactLinearProgram.Constraint> rows,int n,Random random,boolean scale){
  List<Integer> ids=new ArrayList<>();for(int i=0;i<n;i++)ids.add(i);Collections.shuffle(ids,random);List<ExactLinearProgram.Constraint> out=new ArrayList<>();BigInteger factor=scale?BigInteger.TEN.pow(35):bi(1);
  for(var row:rows){Map<Integer,BigInteger> terms=new TreeMap<>();for(var e:row.terms().entrySet())terms.put(ids.get(e.getKey()),e.getValue().multiply(factor));out.add(new ExactLinearProgram.Constraint(terms,row.upper().multiply(factor)));}Collections.shuffle(out,random);return out;
 }
 public static void main(String[] args){Random random=new Random(87987123);
  for(int c=0;c<1200;c++){int n=2+random.nextInt(10);BigInteger[] lo=fill(n,0),hi=fill(n,1);List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
   for(int r=0;r<2+random.nextInt(24);r++){Map<Integer,BigInteger> terms=new TreeMap<>();for(int i=0;i<n;i++)if(random.nextInt(3)==0)terms.put(i,bi(random.nextBoolean()?1:-1));rows.add(new ExactLinearProgram.Constraint(terms,bi(random.nextInt(n+3)-2)));}
   if(c%3==0){Map<Integer,BigInteger> target=new TreeMap<>();for(int i=0;i<n;i++)target.put(i,bi(-1));rows.add(new ExactLinearProgram.Constraint(target,bi(-random.nextInt(n+1))));}
   if(c%7==0){Map<Integer,BigInteger> weighted=new TreeMap<>();for(int i=0;i<n;i++)weighted.put(i,bi(random.nextInt(11)-5));rows.add(new ExactLinearProgram.Constraint(weighted,bi(random.nextInt(15)-5)));}
   if(c%5==0){for(int i=0;i<n;i++){long x=random.nextInt(7)-3;lo[i]=bi(x);hi[i]=bi(x+random.nextInt(2));}}
   test(false,rows,lo,hi,oracle(rows,lo,hi));
  }
  for(int c=0;c<1000;c++){int n=2+random.nextInt(6),bins=2+random.nextInt(3);int[] w=new int[n],p=new int[n],cap=new int[bins];int total=0;for(int i=0;i<n;i++){w[i]=1+random.nextInt(10);p[i]=1+random.nextInt(15);total+=p[i];}for(int i=0;i<bins;i++)cap[i]=1+random.nextInt(25);int goal=1+random.nextInt(total+3);boolean feasible=binOracle(w,p,cap,goal,0,0);var rows=shuffled(binRows(w,p,cap,goal),n*bins,random,c%3==0);test(true,rows,fill(n*bins,0),fill(n*bins,1),feasible);
  }
  for(int c=0;c<400;c++){int items=2+random.nextInt(3),bins=2+random.nextInt(2),n=items*bins;int[] w=new int[items],p=new int[items],cap=new int[bins];for(int i=0;i<items;i++){w[i]=1+random.nextInt(5);p[i]=1+random.nextInt(7);}for(int i=0;i<bins;i++)cap[i]=2+random.nextInt(10);var rows=binRows(w,p,cap,1+random.nextInt(25));Map<Integer,BigInteger> extra=new TreeMap<>();for(int i=0;i<n;i++)extra.put(i,bi(random.nextInt(11)-5));rows.add(new ExactLinearProgram.Constraint(extra,bi(random.nextInt(20)-5)));if(c%4==0)rows.remove(random.nextInt(items));BigInteger[] lo=fill(n,0),hi=fill(n,1);if(c%7==0)hi[random.nextInt(n)]=bi(0);boolean feasible=oracle(rows,lo,hi);test(true,rows,lo,hi,feasible);test(false,rows,lo,hi,feasible);}
  for(int n:new int[]{62,63,64}){Map<Integer,BigInteger> goal=new TreeMap<>();for(int i=0;i<n;i++)goal.put(i,BigInteger.TEN.pow(70).negate());List<ExactLinearProgram.Constraint> rows=List.of(new ExactLinearProgram.Constraint(goal,BigInteger.TEN.pow(70).multiply(bi(-n))));test(false,rows,fill(n,0),fill(n,1),true);}
  for(boolean bins:new boolean[]{false,true})for(int stop:new int[]{0,1,5,20,200})for(long memory:new long[]{1024,16384,131072,1L<<20,32L<<20}){
   int[] w={8,5,7,6,6,8,7,7},p={2,6,5,10,7,6,3,8},cap={16,16,16};var rows=binRows(w,p,cap,49);int n=w.length*cap.length;AtomicBoolean cancel=new AtomicBoolean();PlanningBudget b=new PlanningBudget(0,20_000_000,memory,cancel::get,()->0L);
   try{
    if(bins){try(var s=new CountBinPackingSearch(rows,fill(n,0),fill(n,1),b,100_000)){for(int k=0;k<stop&&!s.step();k++){}cancel.set(true);while(!s.step()){}s.close();}}
    else{try(var s=new CountCardinalitySearch(rows,fill(n,0),fill(n,1),b,100_000)){for(int k=0;k<stop&&!s.step();k++){}cancel.set(true);while(!s.step()){}s.close();}}
   }catch(PlanningBudget.Exhausted|java.util.concurrent.CancellationException expected){}check(b.reservedBytes()==0,"cancel/memory leak");
  }
  for(boolean bins:new boolean[]{false,true})for(long allowance:new long[]{1,512,1024,1300,4096}){
   int[] w={8,5,7,6,6,8,7,7},p={2,6,5,10,7,6,3,8},cap={16,16,16};var rows=binRows(w,p,cap,49);int n=w.length*cap.length;PlanningBudget b=new PlanningBudget(0,20_000_000,32L<<20,()->false,()->0L);
   if(bins){try(var s=new CountBinPackingSearch(rows,fill(n,0),fill(n,1),b,allowance)){while(!s.step()){} }}else{try(var s=new CountCardinalitySearch(rows,fill(n,0),fill(n,1),b,allowance)){while(!s.step()){} }}check(b.nodes()<=allowance,"local quota overrun");check(b.reservedBytes()==0,"quota leak");
  }
  for(boolean bins:new boolean[]{false,true}){PlanningBudget b=new PlanningBudget(0,20_000_000,32L<<20,()->false,()->0L);var rows=binRows(new int[]{3,4},new int[]{2,3},new int[]{4,4},7);Thread.currentThread().interrupt();try{if(bins){try(var s=new CountBinPackingSearch(rows,fill(4,0),fill(4,1),b,10000)){while(!s.step()){} }}else{try(var s=new CountCardinalitySearch(rows,fill(4,0),fill(4,1),b,10000)){while(!s.step()){} }}throw new AssertionError("interrupt ignored");}catch(java.util.concurrent.CancellationException expected){}finally{Thread.interrupted();}check(b.reservedBytes()==0,"constructor interrupt leak");}
  System.out.println("CombinatorialProbe models="+models+" decided="+solved+" assertions="+checks);
 }
}
