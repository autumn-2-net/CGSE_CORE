package org.cgse.core;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class SchedulingProbe {
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
 static void fairness()throws Exception{
  try(var scheduler=new PlanningScheduler(1,4,4096,60_000_000_000L)){
   var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var ops=new AtomicInteger();var atSmall=new AtomicInteger();var closed=new AtomicInteger();
   var b=new PlanningBudget(0,10_000_000,()->false);
   var large=scheduler.submit(new PlanningScheduler.Work<Integer>(){
    public boolean advance(PlanningScheduler.Slice s){
     if(ops.get()==0){entered.countDown();try{check(release.await(5,TimeUnit.SECONDS),"latch");}catch(InterruptedException e){throw new RuntimeException(e);}}
     while(s.next()){b.charge(1024);if(ops.incrementAndGet()==256)return true;}return false;
    }
    public Integer result(){return ops.get();}public void close(){closed.incrementAndGet();}
   },b);
   check(entered.await(5,TimeUnit.SECONDS),"worker start");
   var small=scheduler.submit(new PlanningScheduler.Work<Integer>(){
    public boolean advance(PlanningScheduler.Slice s){atSmall.set(ops.get());return true;}public Integer result(){return 1;}
   },new PlanningBudget(0,100,()->false));
   release.countDown();check(small.get(5,TimeUnit.SECONDS)==1,"small result");check(large.get(5,TimeUnit.SECONDS)==256,"lost work");
   check(b.nodes()==256L*1025,"weighted work reset");
   System.out.println("FAIRNESS small_starts_after_heavy_ops="+atSmall.get()+" work="+b.nodes()+" slices="+scheduler.slices());
  }
 }
 public static void main(String[]args)throws Exception{fairness();org.gtlcore.gtlcore.integration.ae2.graph.GraphSchedulingTest.run();System.out.println("PASS scheduling lifecycle and parallel graph equivalence");}
}
