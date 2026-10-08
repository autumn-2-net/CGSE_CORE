package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
public final class DeferredHintProbe {
 static int checks;static void check(boolean p,String s){checks++;if(!p)throw new AssertionError(s);}
 static BigInteger b(long n){return BigInteger.valueOf(n);}static BigInteger[] fill(int n,int v){var x=new BigInteger[n];Arrays.fill(x,b(v));return x;}
 static PlanningBudget budget(){return new PlanningBudget(0,20000000,128L<<20,()->false,()->0);}
 static Object field(Object x,String n)throws Exception{var f=x.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(x);}
 static long value(Object x,String n)throws Exception{return (long)field(x,n);}
 static List<ExactLinearProgram.Constraint> pigeon(int p,int h){var rows=new ArrayList<ExactLinearProgram.Constraint>();for(int i=0;i<p;i++){var t=new LinkedHashMap<Integer,BigInteger>();for(int j=0;j<h;j++)t.put(i*h+j,b(-1));rows.add(new ExactLinearProgram.Constraint(t,b(-1)));}for(int j=0;j<h;j++)for(int i=0;i<p;i++)for(int k=i+1;k<p;k++)rows.add(new ExactLinearProgram.Constraint(Map.of(i*h+j,b(1),k*h+j,b(1)),b(1)));return rows;}
 static void continuation()throws Exception{
  var rows=pigeon(7,6);var lo=fill(43,0);var hi=fill(43,1);hi[42]=b(2);var budget=budget();
  try(var engine=new CountLcg(rows,lo,hi,budget,1024)){
   while(!engine.step()){}check(engine.paused(),"initial quota did not pause");check(value(engine,"finiteHintDeferredAllowance")==1024,"first fixpoint not deferred");check(!(boolean)field(engine,"finiteHintTried"),"deferred marked tried");check(value(engine,"finiteHintPreparationWork")==0,"sub-1024 preparation consumed work");
   var method=CountLcg.class.getDeclaredMethod("finiteHint");method.setAccessible(true);long old=budget.nodes();for(int i=0;i<50;i++)method.invoke(engine);check(budget.nodes()==old,"busy rescanning old allowance");
   engine.resume(131072);int steps=0;while(!(boolean)field(engine,"finiteHintTried")&&!engine.step()&&steps++<10000){}
   check((boolean)field(engine,"finiteHintTried"),"resume did not attempt");check(field(engine,"finiteHint")!=null,"resumed point missing");long used=value(engine,"finiteHintPreparationWork");check(used>0&&used<=262176,"hint lifetime cap");
   for(int i=0;i<100;i++)method.invoke(engine);check(value(engine,"finiteHintPreparationWork")==used,"actual numerical attempt repeated");
  }check(budget.reservedBytes()==0,"continuation leak");
 }
 static void outcomes()throws Exception{
  var small=List.of(new ExactLinearProgram.Constraint(Map.of(0,b(-1),1,b(-1)),b(-1)),new ExactLinearProgram.Constraint(Map.of(0,b(1),1,b(1)),b(3)));var lo=fill(2,0);var hi=fill(2,3);
  for(long quota:new long[]{0,1,1023,1024,8192}){var budget=budget();var attempt=new CountLpHint.Attempt();try(var result=CountLpHint.solve(small,lo,hi,budget,quota,attempt)){check(attempt.outcome==(quota<1024?CountLpHint.Outcome.WORK_LIMIT:CountLpHint.Outcome.ATTEMPTED),"quota outcome "+quota);}check(budget.reservedBytes()==0,"outcome leak");}
  var rows=pigeon(18,9);var low=fill(163,0);var high=fill(163,1);high[162]=b(2);var budget=budget();var attempt=new CountLpHint.Attempt();try(var result=CountLpHint.solve(rows.subList(0,1000),low,high,budget,1024,attempt)){check(result==null&&attempt.outcome==CountLpHint.Outcome.WORK_LIMIT,"preflight work outcome");}check(budget.reservedBytes()==0,"preparation leak");
  var denied=new PlanningBudget(0,20000000,1,()->false,()->0);attempt=new CountLpHint.Attempt();try(var result=CountLpHint.solve(small,lo,hi,denied,8192,attempt)){check(result==null&&attempt.outcome==CountLpHint.Outcome.MEMORY_LIMIT,"memory outcome");}check(denied.reservedBytes()==0,"memory decline leak");
  hi[0]=BigInteger.ONE.shiftLeft(40);attempt=new CountLpHint.Attempt();try(var result=CountLpHint.solve(small,lo,hi,budget,8192,attempt)){check(result==null&&attempt.outcome==CountLpHint.Outcome.UNSUPPORTED,"width outcome");}
 }
 static void compatibility(){var random=new Random(639713);for(int sample=0;sample<500;sample++){
  int n=2+random.nextInt(7);var lo=fill(n,0);var hi=fill(n,3);var rows=new ArrayList<ExactLinearProgram.Constraint>();for(int r=0;r<5;r++){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<n;i++){int a=random.nextInt(9)-4;if(a!=0)terms.put(i,b(a));}rows.add(new ExactLinearProgram.Constraint(terms,b(random.nextInt(25)-12)));}var a=budget();var c=budget();var attempt=new CountLpHint.Attempt();try(var first=CountLpHint.solve(rows,lo,hi,a,8192);var second=CountLpHint.solve(rows,lo,hi,c,8192,attempt)){check((first==null)==(second==null),"old API result mismatch");if(first!=null)check(Arrays.equals(first.point,second.point),"old API point mismatch");}check(a.nodes()==c.nodes(),"old API work mismatch");check(a.reservedBytes()==0&&c.reservedBytes()==0,"API comparison leak");
 }}
 static void boundaries(){var rows=pigeon(7,6);var lo=fill(43,0);var hi=fill(43,1);hi[42]=b(2);
  for(int stop:new int[]{0,1,5,20,100,500,2000,5000}){var calls=new AtomicInteger();var b=new PlanningBudget(0,20000000,128L<<20,()->calls.incrementAndGet()>stop,()->0);try(var engine=new CountLcg(rows,lo,hi,b,1024)){while(!engine.step()){}if(engine.paused()){engine.resume(131072);for(int i=0;i<5000&&!engine.step();i++){}}}catch(CancellationException expected){}check(b.reservedBytes()==0,"cancel leak");}
  for(long cap:new long[]{1,100,1024,2000,5000,20000}){var b=new PlanningBudget(0,cap,128L<<20,()->false,()->0);try(var engine=new CountLcg(rows,lo,hi,b,1024)){while(!engine.step()){}if(engine.paused()){engine.resume(131072);while(!engine.step()){}}}catch(PlanningBudget.Exhausted expected){}check(b.reservedBytes()==0,"global limit leak");}
 }
 static void lifetime()throws Exception{var rows=pigeon(18,9).subList(0,1000);var lo=fill(163,0);var hi=fill(163,1);hi[162]=b(2);var budget=budget();try(var engine=new CountLcg(rows,lo,hi,budget,131072)){var method=CountLcg.class.getDeclaredMethod("finiteHint");method.setAccessible(true);var allowance=CountLcg.class.getDeclaredField("allowance");allowance.setAccessible(true);int tries=0;while(!(boolean)field(engine,"finiteHintTried")&&tries++<300){allowance.setLong(engine,value(engine,"work")+1024);method.invoke(engine);check(value(engine,"finiteHintPreparationWork")<=262176,"cumulative preparation cap");long before=budget.nodes();method.invoke(engine);check(budget.nodes()==before,"same allowance retries preparation");}check((boolean)field(engine,"finiteHintTried")&&tries>=250&&tries<300,"lifetime budget not terminal");check(field(engine,"finiteHint")==null,"unexpected numerical attempt in preflight test");}check(budget.reservedBytes()==0,"lifetime leak");}
 public static void main(String[]args)throws Exception{continuation();outcomes();compatibility();boundaries();lifetime();System.out.println("checks="+checks+" fixpointDeferred=true resumedPoint=true repeatAttempt=false outcomes=4 lifetimeCap=pass legacyAPI=500exact cancelAndGlobalLimit=pass leaks=0");}
}
