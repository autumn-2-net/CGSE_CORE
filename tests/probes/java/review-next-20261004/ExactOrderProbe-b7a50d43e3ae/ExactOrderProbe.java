package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
import java.nio.file.*;
import com.google.gson.Gson;

public final class ExactOrderProbe {
 static long checks;static int witness,dead;
 static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 public static void main(String[]args)throws Exception{
  var attempt=CountSchedule.class.getDeclaredField("attempt");attempt.setAccessible(true);
  var startup=CountSchedule.class.getDeclaredField("startupChecked");startup.setAccessible(true);
  var preferred=CountSchedule.class.getDeclaredField("preferEnabled");preferred.setAccessible(true);
  var next=CountSchedule.class.getDeclaredMethod("nextAttempt");next.setAccessible(true);
  var reverse=CountSchedule.class.getDeclaredMethod("prepareReverse");reverse.setAccessible(true);
  for(int mode=0;mode<2;mode++)for(int seed=0;seed<2400;seed++){
   var r=new Random(seed*1009L);int n=3+r.nextInt(5);var rs=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();for(int k=0;k<4;k++)stock.put("k"+k,(long)r.nextInt(4));BigInteger[] counts=new BigInteger[n];
   for(int i=0;i<n;i++){var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();for(int j=0;j<1+r.nextInt(2);j++)in.merge("k"+r.nextInt(4),1L+r.nextInt(2),Long::sum);for(int j=0;j<1+r.nextInt(2);j++)out.merge("k"+r.nextInt(4),1L+r.nextInt(3),Long::sum);rs.add(ScheduleProbe.recipe("r"+i,in,out));counts[i]=BigInteger.valueOf(r.nextInt(3));}
   boolean truth=ScheduleProbe.oracle(rs,stock,counts);var b=ScheduleProbe.budget();
   try(var model=RecipeCountModel.forShell(rs,Map.of(),stock,Set.of(),b);var schedule=new CountSchedule<>(model,counts,b)){
    attempt.setInt(schedule,8*Math.min(4,n)-1);startup.setBoolean(schedule,true);next.invoke(schedule);preferred.setBoolean(schedule,true);
    if(mode==1)check((boolean)reverse.invoke(schedule),"reverse admission");
    while(!schedule.step()){}check(schedule.result()==(truth?CountSchedule.Result.WITNESS:CountSchedule.Result.DEAD),"exact classification "+seed+" expected="+truth+" actual="+schedule.result());
    if(truth){witness++;var byId=new LinkedHashMap<String,GraphRecipe<String>>();rs.forEach(x->byId.put(x.id(),x));try(var summary=new SummaryComputation<>(schedule.witness(),byId,b)){while(!summary.step()){}for(var e:summary.result().required().entrySet())check(e.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(e.getKey(),0L)))<=0,"prefix");}
     var actual=new HashMap<String,BigInteger>();ScheduleProbe.tally(schedule.witness(),actual,BigInteger.ONE);for(int i=0;i<n;i++)check(counts[i].equals(actual.getOrDefault(rs.get(i).id(),BigInteger.ZERO)),"counts");
    }else dead++;
   }check(b.reservedBytes()==0,"memory leak "+seed);
  }
  var report=Map.of("cases",4800,"witness",witness,"dead",dead,"assertions",checks);Files.writeString(Path.of(args[0],"exact-order-probe.json"),new Gson().toJson(report));System.out.println(report);
 }
}
