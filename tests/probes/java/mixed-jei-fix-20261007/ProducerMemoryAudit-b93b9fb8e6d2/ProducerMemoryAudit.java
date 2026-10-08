package org.cgse.core;
import java.nio.file.*;import java.util.*;import com.google.gson.*;
public class ProducerMemoryAudit {
 public static void main(String[] args)throws Exception {var file=Path.of(args[0]);var data=MixedSweep.read(file);var j=JsonParser.parseString(Files.readString(file)).getAsJsonObject();String target=j.getAsJsonObject("request").get("target").getAsString();int checks=0,witnesses=0,repaired=0;
 for(long bytes:new long[]{1024,16384,262144,1048576,16777216})for(int stop:new int[]{0,1,100,1000,10000,Integer.MAX_VALUE}) {
  var compiler=new GraphCompiler<>(data.recipes(),data.producers());var budget=new PlanningBudget(0,20000000,bytes+128,()->false,System::nanoTime);budget.reserve(128);budget.failureDetail("retained");final GraphSourceRanking<String>[] rank=new GraphSourceRanking[1];
  try(var view=new GraphStockViewWork<>(compiler,target,1,data.stock(),data.external(),Map.of(),Set.of(),true,true,new CatalystPolicy(4096,64),budget,System.nanoTime(),2,4000000,()->{if(rank[0]==null)rank[0]=GraphSourceRanking.create(compiler,data.stock(),data.external(),target,true,budget);return rank[0];})) {
   for(int i=0;i<stop;i++)if(view.step())break;
   var field=GraphStockViewWork.class.getDeclaredField("triedSources");field.setAccessible(true);if(!((Map<?,?>)field.get(view)).isEmpty())repaired++;
   if(view.result()!=null){PlanVerifier.verify(view.result());PlanVerifier.verifyRuntimeInventory(view.result());witnesses++;}
  }finally{if(rank[0]!=null)rank[0].close();}
  if(budget.reservedBytes()!=128)throw new AssertionError("leak="+budget.reservedBytes()+" bytes="+bytes+" stop="+stop);checks++;
  if(!budget.failureDetail().equals("retained"))throw new AssertionError("failure changed");checks++;
 }
 if(repaired==0||witnesses==0)throw new AssertionError("repair not exercised");System.out.println("checks="+checks+" repaired="+repaired+" witnesses="+witnesses);
 }
}
