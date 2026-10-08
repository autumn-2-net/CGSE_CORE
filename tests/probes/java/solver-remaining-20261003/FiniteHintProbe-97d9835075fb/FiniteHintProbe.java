package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
public final class FiniteHintProbe {
 static long checks, calls, points, enumerated, sat, unsat, unknown, certificates;
 static BigInteger b(long n){return BigInteger.valueOf(n);}
 static void check(boolean v,String msg){checks++;if(!v)throw new AssertionError(msg);}
 static PlanningBudget budget(){return new PlanningBudget(0,10000000,128L<<20,()->false,()->0);}
 static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] x){for(var row:rows){var sum=BigInteger.ZERO;for(var t:row.terms().entrySet())sum=sum.add(t.getValue().multiply(x[t.getKey()]));if(sum.compareTo(row.upper())>0)return false;}return true;}
 static boolean enumerate(List<ExactLinearProgram.Constraint>rows,BigInteger[]lo,BigInteger[]hi,BigInteger[]x,int k,int free){if(k==free){enumerated++;return valid(rows,x);}for(var v=lo[k];v.compareTo(hi[k])<=0;v=v.add(BigInteger.ONE)){x[k]=v;if(enumerate(rows,lo,hi,x,k+1,free))return true;}return false;}
 record Model(List<ExactLinearProgram.Constraint> rows,BigInteger[]low,BigInteger[]high,int free){}
 static Model make(Random rng,int dimensions,boolean planted){
  int free=2+rng.nextInt(4);var lo=new BigInteger[dimensions];var hi=new BigInteger[dimensions];var witness=new BigInteger[dimensions];
  for(int i=0;i<dimensions;i++){lo[i]=b(rng.nextInt(8));hi[i]=lo[i].add(b(i<free?1+rng.nextInt(3):0));witness[i]=lo[i].add(b(i<free?rng.nextInt(hi[i].subtract(lo[i]).intValue()+1):0));}
  var rows=new ArrayList<ExactLinearProgram.Constraint>();for(int r=0;r<3+rng.nextInt(8);r++){
   var terms=new LinkedHashMap<Integer,BigInteger>();var total=BigInteger.ZERO;var scale=r%3==0?BigInteger.TEN.pow(new int[]{0,35,70,150,250}[rng.nextInt(5)]):BigInteger.ONE;
   for(int i=0;i<dimensions;i++){int v=r==0?1+rng.nextInt(9):i<free?rng.nextInt(19)-9:0;if(v!=0){var c=b(v).multiply(scale);terms.put(i,c);total=total.add(c.multiply(witness[i]));}}
   rows.add(new ExactLinearProgram.Constraint(terms,total.add(b(planted?rng.nextInt(8):rng.nextInt(17)-12).multiply(scale))));
  }return new Model(rows,lo,hi,free);
 }
 static void helpers(){var random=new Random(3060327);for(int sample=0;sample<1500;sample++){
  var m=make(random,7,sample%2==0);var a=budget();var c=budget();
  try(var expected=CountFiniteLpLearning.solve(m.rows,m.low,m.high,a,262144);var actual=CountLpHint.solve(m.rows,m.low,m.high,c,262144)){
   calls++;if(expected!=null&&expected.point!=null){check(actual!=null,"lost old point");check(Arrays.equals(expected.point,actual.point),"projection point mismatch");}
   if(actual!=null){points++;for(int i=0;i<m.low.length;i++){check(Double.isFinite(actual.point[i]),"nonfinite point");check(actual.point[i]+1e-6>=m.low[i].doubleValue()&&actual.point[i]-1e-6<=m.high[i].doubleValue(),"box failure");}}
  }check(a.reservedBytes()==0&&c.reservedBytes()==0,"helper leak");
 }}
 static void search(){var random=new Random(551239);for(int sample=0;sample<1200;sample++){
  var m=make(random,sample<600?129+sample%3:7,sample%2==0);var x=m.low.clone();boolean feasible=enumerate(m.rows,m.low,m.high,x,0,m.free);var budget=budget();
  try(var engine=new CountLcg(m.rows,m.low,m.high,budget,131072,true)){
   int resumes=0;while(true){while(!engine.step()){}if(!engine.paused()||resumes++>=16)break;engine.resume(131072);}
   var result=engine.counts();if(result!=null){sat++;check(feasible,"falseSAT oracle");check(valid(m.rows,result),"falseSAT row");for(int i=0;i<result.length;i++)check(result[i].compareTo(m.low[i])>=0&&result[i].compareTo(m.high[i])<=0,"falseSAT domain");}
   else if(engine.infeasible()){unsat++;check(!feasible,"falseUNSAT");}else unknown++;
   if(engine.certificate()!=null){certificates++;check(CountProof.verify(engine.certificate(),10000000)==CountProof.Verdict.VERIFIED,"certificate failure");}
  }check(budget.reservedBytes()==0,"LCG leak");
 }}
 static void poisoned(){var random=new Random(426033);try{
  var hint=CountLcg.class.getDeclaredField("finiteHint");hint.setAccessible(true);var tried=CountLcg.class.getDeclaredField("finiteHintTried");tried.setAccessible(true);
  for(int sample=0;sample<300;sample++){
   var m=make(random,7,sample%2==0);var x=m.low.clone();boolean feasible=enumerate(m.rows,m.low,m.high,x,0,m.free);var b=budget();
   try(var engine=new CountLcg(m.rows,m.low,m.high,b,131072,true)){
    var noise=new double[m.low.length];for(int i=0;i<noise.length;i++)noise[i]=switch((sample+i)%6){case 0->Double.NaN;case 1->Double.POSITIVE_INFINITY;case 2->Double.NEGATIVE_INFINITY;case 3->1e40;case 4->-1e40;default->random.nextDouble()*20;};hint.set(engine,noise);tried.setBoolean(engine,true);
    while(!engine.step()){}var result=engine.counts();if(result!=null)check(feasible&&valid(m.rows,result),"poison hint false SAT");if(engine.infeasible())check(!feasible,"poison hint false UNSAT");if(engine.certificate()!=null)check(CountProof.verify(engine.certificate(),10000000)==CountProof.Verdict.VERIFIED,"poison hint false proof");
   }check(b.reservedBytes()==0,"poison hint leak");
  }
 }catch(ReflectiveOperationException e){throw new AssertionError(e);}}
 static void lifecycle(){var m=make(new Random(32),137,true);
  for(int memory:new int[]{1,1024,4096,8192,65536,100000,200000,1000000,10000000}){var b=new PlanningBudget(0,10000000,memory,()->false,()->0);try(var result=CountLpHint.solve(m.rows,m.low,m.high,b,262144)){}check(b.reservedBytes()==0,"memory helper leak");try(var engine=new CountLcg(m.rows,m.low,m.high,b,131072)){for(int i=0;i<50&&!engine.step();i++){} }check(b.reservedBytes()==0,"memory LCG leak");}
  for(long quota:new long[]{1,100,1023,1024,1050,1100,1500,3000,10000,32768,131072,262144}){var b=budget();try(var result=CountLpHint.solve(m.rows,m.low,m.high,b,quota)){}check(b.nodes()<=quota+32,"local quota overspent");check(b.reservedBytes()==0,"quota leak");}
  for(long quota:new long[]{1,100,1023,1024,1050,1100,1500,3000,10000,32768}){var b=new PlanningBudget(0,quota,128L<<20,()->false,()->0);try(var result=CountLpHint.solve(m.rows,m.low,m.high,b,262144)){}catch(PlanningBudget.Exhausted expected){}check(b.reservedBytes()==0,"global helper leak");var c=new PlanningBudget(0,quota,128L<<20,()->false,()->0);try(var engine=new CountLcg(m.rows,m.low,m.high,c,131072)){while(!engine.step()){} }catch(PlanningBudget.Exhausted expected){}check(c.reservedBytes()==0,"global LCG leak");}
  for(int threshold:new int[]{0,1,5,10,50,100,200,500,1000,2500}){var c=new AtomicInteger();var b=new PlanningBudget(0,10000000,128L<<20,()->c.incrementAndGet()>threshold,()->0);try(var result=CountLpHint.solve(m.rows,m.low,m.high,b,262144)){}catch(CancellationException expected){}check(b.reservedBytes()==0,"cancel helper leak");}
  for(int threshold:new int[]{0,1,5,10,50,100,200,500,1000,2500,10000}){var c=new AtomicInteger();var b=new PlanningBudget(0,10000000,128L<<20,()->c.incrementAndGet()>threshold,()->0);try(var engine=new CountLcg(m.rows,m.low,m.high,b,131072)){while(!engine.step()){} }catch(CancellationException expected){}check(b.reservedBytes()==0,"cancel LCG leak");}
  var b=budget();var high=m.high.clone();high[0]=null;try(var value=CountLpHint.solve(m.rows,m.low,high,b,262144)){check(value==null,"unbounded accepted");}high[0]=BigInteger.ONE.shiftLeft(31);try(var value=CountLpHint.solve(m.rows,m.low,high,b,262144)){check(value==null,"too wide accepted");}var invalid=List.of(new ExactLinearProgram.Constraint(Map.of(999,BigInteger.ONE),BigInteger.ONE));try(var value=CountLpHint.solve(invalid,m.low,m.high,b,262144)){check(value==null,"bad index accepted");}check(b.reservedBytes()==0,"unsupported leak");
 }
 public static void main(String[]args){helpers();search();poisoned();lifecycle();System.out.println("calls="+calls+" points="+points+" enumerated="+enumerated+" SAT="+sat+" UNSAT="+unsat+" UNKNOWN="+unknown+" certificates="+certificates+" assertions="+checks+" leaks=0 falseProofs=0");}
}
