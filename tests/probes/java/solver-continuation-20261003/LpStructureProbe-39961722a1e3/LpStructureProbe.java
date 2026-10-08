package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
public final class LpStructureProbe {
 static long assertions,calls,cuts,points,coldWork,newWork;
 static BigInteger b(long v){return BigInteger.valueOf(v);}
 static BigInteger[] fill(int n,long v){var a=new BigInteger[n];Arrays.fill(a,b(v));return a;}
 static PlanningBudget budget(){return new PlanningBudget(0,100000000,128L<<20,()->false,()->0L);}
 static void check(boolean value,String detail){assertions++;if(!value)throw new AssertionError(detail);}
 static boolean valid(ExactLinearProgram.Constraint row,BigInteger[]x){var sum=BigInteger.ZERO;for(var t:row.terms().entrySet())sum=sum.add(t.getValue().multiply(x[t.getKey()]));return sum.compareTo(row.upper())<=0;}
 static List<ExactLinearProgram.Constraint> rows(Random random,int n){var list=new ArrayList<ExactLinearProgram.Constraint>();int m=2+random.nextInt(10);for(int r=0;r<m;r++){var terms=new TreeMap<Integer,BigInteger>();var scale=r%3==0?BigInteger.TEN.pow(new int[]{0,35,150,250}[random.nextInt(4)]):BigInteger.ONE;for(int i=0;i<n;i++){int v=random.nextInt(21)-10;if(v!=0)terms.put(i,b(v).multiply(scale));}list.add(new ExactLinearProgram.Constraint(terms,b(random.nextInt(21)-10).multiply(scale)));}return list;}
 static void compare(ReferenceLpLearning.Result expected,CountLpLearning.Result actual,String label){
  check((expected==null)==(actual==null),"null mismatch "+label);if(expected==null)return;
  check(expected.numericalInfeasible==actual.numericalInfeasible,"numeric status "+label);
  check(Arrays.equals(expected.point,actual.point),"point bit mismatch "+label);
  check((expected.cut==null)==(actual.cut==null),"cut null mismatch "+label);
  if(expected.cut!=null){check(expected.cut.row().equals(actual.cut.row()),"cut row "+label);check(expected.cut.parents().equals(actual.cut.parents()),"cut parent "+label);check(expected.cut.divisor().equals(actual.cut.divisor()),"cut divisor "+label);}
 }
 static void random(){var rng=new Random(10580863);for(int sample=0;sample<1200;sample++){
  int n=2+rng.nextInt(7);var rows=rows(rng,n);var oldBudget=budget();var newBudget=budget();
  try(var old=new ReferenceLpLearning.Session();var next=new CountLpLearning.Session()){
   for(int step=0;step<10;step++){
    if(step==4){var row=rows.get(0);rows.set(0,new ExactLinearProgram.Constraint(row.terms(),row.upper()));}
    if(step==6)Collections.reverse(rows);
    if(step==8){var row=rows.get(0);rows.set(0,new ExactLinearProgram.Constraint(row.terms(),row.upper().add(BigInteger.ONE)));}
    var lo=fill(n,0);var hi=fill(n,1);for(int i=0;i<n;i++)if(rng.nextInt(3)==0)lo[i]=hi[i]=b(rng.nextInt(2));
    try(var a=old.solve(rows,lo,hi,oldBudget,200000);var c=next.solve(rows,lo,hi,newBudget,200000)){
     calls++;compare(a,c,"sample="+sample+" step="+step);
     if(c!=null&&c.cut!=null){cuts++;var proof=new CountProof.Derivation("metadata",n,rows.stream().map(CountProof::row).toList(),List.of(new CountProof.Combination(c.cut.parents(),c.cut.divisor(),CountProof.row(c.cut.row()))));check(CountProof.verify(proof,2000000)==CountProof.Verdict.VERIFIED,"invalid proof");
      for(int mask=0;mask<(1<<n);mask++){var x=fill(n,0);for(int i=0;i<n;i++)if((mask&(1<<i))!=0)x[i]=BigInteger.ONE;if(rows.stream().anyMatch(r->!valid(r,x)))continue;points++;check(valid(c.cut.row(),x),"global point rejected");}
     }
    }
   }
  }coldWork+=oldBudget.nodes();newWork+=newBudget.nodes();check(oldBudget.reservedBytes()==0&&newBudget.reservedBytes()==0,"random leak");
 }}
 static void lifecycle(){var rng=new Random(54);var rows=rows(rng,12);var lo=fill(12,0);var hi=fill(12,1);
  for(int memory=4096;memory<=1048576;memory=memory*3/2){var a=new PlanningBudget(0,10000000,memory,()->false,()->0L);var c=new PlanningBudget(0,10000000,memory,()->false,()->0L);try(var old=new ReferenceLpLearning.Session();var next=new CountLpLearning.Session()){for(int step=0;step<3;step++){try(var x=old.solve(rows,lo,hi,a,200000);var y=next.solve(rows,lo,hi,c,200000)){check(x==null||y!=null,"optional cache prevented old memory-admitted result "+memory);}}}check(a.reservedBytes()==0&&c.reservedBytes()==0,"memory leak");}
  for(int after:new int[]{0,1,2,5,10,50,100,200,500}){var enabled=new AtomicBoolean();var checkpoints=new AtomicInteger();var b=new PlanningBudget(0,10000000,128L<<20,()->enabled.get()&&checkpoints.incrementAndGet()>after,()->0L);try(var s=new CountLpLearning.Session()){try(var r=s.solve(rows,lo,hi,b,200000)){}enabled.set(true);try(var r=s.solve(rows,lo,hi,b,200000)){}catch(CancellationException expected){check(b.reservedBytes()==0,"cancel before close");}}check(b.reservedBytes()==0,"cancel leak");}
  for(long quota:new long[]{1,1023,1024,1100,1500,2000,5000,10000}){var b=budget();try(var s=new CountLpLearning.Session()){try(var r=s.solve(rows,lo,hi,b,200000)){}long start=b.nodes();try(var r=s.solve(rows,lo,hi,b,quota)){}check(b.nodes()-start<=quota+32,"quota overrun "+quota+" actual "+(b.nodes()-start));}check(b.reservedBytes()==0,"quota leak");}
  var first=budget();var second=budget();try(var s=new CountLpLearning.Session()){try(var r=s.solve(rows,lo,hi,first,200000)){}try(var r=s.solve(rows,lo,hi,second,200000)){}check(first.reservedBytes()==0,"order switch retained old memory");}check(second.reservedBytes()==0,"new order leak");
 }
 public static void main(String[]args){random();lifecycle();System.out.println("calls="+calls+" exactOutputs=equal cuts="+cuts+" feasiblePoints="+points+" oldWork="+coldWork+" newWork="+newWork+" assertions="+assertions+" invalid=0 leaks=0");}
}
