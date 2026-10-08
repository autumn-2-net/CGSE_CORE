package org.cgse.core;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.lang.reflect.*;import com.google.gson.*;
public class StockResumeAudit {
 static int checks,pauses,resumes,restores,evictions;static Field phase,sv,saved;static {try{phase=f("phase");sv=f("stockView");saved=f("stockResumePhase");}catch(Exception e){throw new RuntimeException(e);}}
 static Field f(String n)throws Exception{var f=GraphPlanningWork.class.getDeclaredField(n);f.setAccessible(true);return f;}
 static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static MixedSweep.Data data;static String target;static long amount;
 static void mainCase(long memory,long work,long stop,int action)throws Exception{
  var b=new PlanningBudget(0,work,memory+128,()->false,System::nanoTime);b.reserve(128);var c=new GraphCompiler<>(data.recipes(),data.producers());var w=new GraphPlanningWork<>(c,target,amount,data.stock(),data.external(),Map.of(),true,true,b).catalysts(new CatalystPolicy(4096,64));long steps=0,pressure=0;boolean didCancel=false;
  try{
   while(steps++<stop){int prior=phase.getInt(w),resume=saved.getInt(w);var view=(GraphStockViewPortfolio<?>)sv.get(w);boolean parked=view!=null&&view.paused();if(action==1&&parked){b.cancel();didCancel=true;}if(action==2&&parked&&pressure==0){pressure=Math.max(0,b.availableBytes()-1);b.reserve(pressure);}boolean done=w.step();int next=phase.getInt(w);var current=(GraphStockViewPortfolio<?>)sv.get(w);
    if(current!=null&&current.paused()&&!parked)pauses++;if(parked&&current!=null&&!current.paused())resumes++;if(parked&&current==null&&b.diagnostics().contains("evicted_parked_frontier"))evictions++;
    if(prior==19&&resume>=0&&next!=19&&next!=4){ok(next==resume,"phase restore "+resume+" -> "+next);restores++;}
    if(done){var result=w.result();if(result.feasible()){PlanVerifier.verify(result);PlanVerifier.verifyRuntimeInventory(result);}break;}
   }
  }catch(CancellationException expected){ok(didCancel,"unexpected cancellation");}finally{w.close();w.close();b.release(pressure);}
  ok(b.reservedBytes()==128,"main leak="+b.reservedBytes()+" memory="+memory+" work="+work+" stop="+stop);if(action==1&&didCancel)ok(true,"cancelled");
 }
 public static void main(String[]args)throws Exception{var file=Path.of(args[0]);data=MixedSweep.read(file);target=JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("request").get("target").getAsString();amount=JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("request").get("amount").getAsLong();
  for(long mem:new long[]{1048576,4194304,16777216,134217728})for(long stop:new long[]{0,25,250,2500,25000})mainCase(mem,2000000,stop,0);
  for(long work:new long[]{500000,2000000,8000000,20000000})mainCase(134217728,work,Long.MAX_VALUE,0);
  mainCase(134217728,20000000,Long.MAX_VALUE,1);mainCase(134217728,20000000,Long.MAX_VALUE,2);
  var b=new PlanningBudget(0,8000000,134217728,()->false,System::nanoTime);var p=new GraphStockViewPortfolio<>(new GraphCompiler<>(data.recipes(),data.producers()),target,amount,data.stock(),data.external(),Map.of(),Set.of(),true,true,new CatalystPolicy(4096,64),b,System.nanoTime());int cycles=0;
  try{while(cycles++<100){while(!p.step()){}if(!p.paused())break;pauses++;long used=b.nodes();p.resume(262144);ok(b.nodes()==used,"resume credited work");resumes++;}if(p.result()!=null){PlanVerifier.verify(p.result());PlanVerifier.verifyRuntimeInventory(p.result());}}catch(PlanningBudget.Exhausted e){}finally{p.close();p.close();}ok(b.reservedBytes()==0,"portfolio lease leak");ok(pauses>0&&resumes>0,"no park/resume exercised");System.out.println("checks="+checks+" pauses="+pauses+" resumes="+resumes+" restores="+restores+" evictions="+evictions);}
}
