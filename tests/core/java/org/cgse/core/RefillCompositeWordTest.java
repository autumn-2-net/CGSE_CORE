// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Segmented cyclic witnesses, independently allocated equal AST nodes and lifecycle. */
public final class RefillCompositeWordTest {
 private static int checks;
 record Fixture(GraphPlan<String> plan,Map<String,Long> stock,long needed,long factor){}
 static Fixture fixture(int parts,int width,int factor,long goal,long seed,long joint,boolean shared,boolean reverse,int shape) {
  int length=parts*width;var recipes=new ArrayList<GraphRecipe<String>>();
  for(int i=0;i<length;i++) {
   var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();
   in.put(i==0?"A":"m"+i,i==0?3L:2L);if(i==0){in.put("feed",1L);in.put("tool",1L);}
   out.put(i==length-1?"A":"m"+(i+1),i==length-1?4L:2L);
   if(i==length-1){out.put("tool",1L);out.put("joint",1L);}
   recipes.add(MissingMaterialExpansionTest.recipe("r"+i,in,out));
  }
  var end=new LinkedHashMap<String,Long>();end.put("A",(3+goal)*factor);if(joint>0)end.put("joint",joint*factor);
  recipes.add(MissingMaterialExpansionTest.recipe("finish",end,Map.of("G",1L)));if(reverse)Collections.reverse(recipes);
  long needed=Math.max(goal+seed,joint);var word=new ArrayList<PlanStep>();var cache=new ArrayList<PlanStep>();
  for(int round=0;round<needed+3;round++)for(int part=0;part<parts;part++) {
   PlanStep step;
   if(shared&&round>0)step=cache.get(part);
   else {
    var body=new ArrayList<PlanStep>();for(int i=0;i<width;i++)body.add(new PlanStep.Batch("r"+(part*width+i),1));
    step=new PlanStep.Sequence(body);if(shape==1)step=new PlanStep.Sequence(List.of(new PlanStep.Sequence(List.of(step))));
    step=new PlanStep.Repeat(step,factor);if(round==0)cache.add(step);
   }
   word.add(step);
  }
  word.add(new PlanStep.Batch("finish",1));var seeds=new LinkedHashMap<String,Long>();seeds.put("tool",(long)factor);if(seed>0)seeds.put("A",seed*factor);
  var stock=new LinkedHashMap<String,Long>();stock.put("feed",(long)factor);stock.put("tool",(long)factor);
  return new Fixture(RefillWordPruningTest.literalPlan(recipes,new PlanStep.Sequence(word),seeds,stock,Set.of()),stock,needed,factor);
 }
 public static void main(String[] args) throws Exception {
  families(); expansion(); contracts(); cutoffs(); handoff();
  System.out.println("RefillCompositeWordTest: assertions="+checks);
 }
 private static void families() {
  for(int parts:new int[]{2,4,8})for(int width:new int[]{2,3})for(int factor:new int[]{1,2})for(long goal:new long[]{1,3})
  for(long seed:new long[]{0,2})for(long joint:new long[]{0,3})for(boolean shared:List.of(false,true))for(boolean reverse:List.of(false,true))for(int shape=0;shape<2;shape++) {
   var f=fixture(parts,width,factor,goal,seed,joint,shared,reverse,shape);var b=budget();GraphPlan<String> result;
   try(var owner=owner(f.plan,b)) {
    while(!owner.step()){}long held=b.reservedBytes(),before=b.nodes();
    result=reduce(f.plan,f.stock,Set.of(),true,b,8192,owner.summary());
    check(b.nodes()-before<=8256&&b.reservedBytes()==held,"Borrowed summary/allowance");
   }
   check(b.reservedBytes()==0&&b.cachePreparationWork()==0,"Workspace/account leak");
   bound(f,result);
   for(int i=0;i<parts*width;i++)check(result.patternTimesExact().get("r"+i).equals(BigInteger.valueOf(f.needed*factor)),"Split a required segment");
  }
 }
 private static void bound(Fixture f,GraphPlan<String> p) {
  // Every outer round consumes factor feed and adds factor A/joint. The fixed
  // startup 3*factor A must fund finish and any final seed. This is a scoped
  // conservation lower bound, not a claim of optimality over other programs.
  check(p.initialExact().get("feed").equals(BigInteger.valueOf(f.needed*f.factor)),"Missed segmented loop bound");
  check(p.initialExact().get("A").equals(BigInteger.valueOf(3*f.factor)),"Changed startup seed");
  check(p.initialExact().get("tool").equals(BigInteger.valueOf(f.factor)),"Lost returned tool");
 }
 private static void expansion() {
  for(int parts:new int[]{2,4,8})for(int width:new int[]{2,3})for(int factor:new int[]{1,2})
  for(boolean shared:List.of(false,true))for(boolean reverse:List.of(false,true))for(int shape=0;shape<2;shape++) {
   var f=fixture(parts,width,factor,3,2,3,shared,reverse,shape);var catalog=new ArrayList<>(f.plan.recipes().values());
   // Normal expansion prefix supplies the existing work-scaled quality quota;
   // no direct quota override and no synthetic main-solver infeasibility claim.
   for(int i=0;i<8;i++)catalog.add(MissingMaterialExpansionTest.recipe("feed"+i,Map.of(i==7?"raw":"feed"+(i+1),1L),Map.of(i==0?"feed":"feed"+i,1L)));
   var b=budget();
   try(var e=new MissingMaterialExpansion<>(new GraphCompiler<>(catalog),f.plan,f.stock,Set.of(),Set.of(),true,false,b)) {
    while(!e.step()){}var p=e.result();RefillWordPruningTest.literalVerify(p,f.stock,Set.of());
    check(p.missingExact().equals(Map.of("A",BigInteger.valueOf(3L*factor),"raw",BigInteger.valueOf((f.needed-1)*factor))),"Expanded bill not reduced to terminal supply");
   }check(b.reservedBytes()==0,"Expanded workspace");
  }
 }
 private static void contracts() {
  var f=fixture(4,2,2,3,0,0,false,false,1);var recipes=new ArrayList<>(f.plan.recipes().values());
  var last=recipes.get(7);var outputs=new LinkedHashMap<>(last.outputs());outputs.put("G",1L);
  recipes.set(7,new GraphRecipe<>(last.id(),last.binding(),last.slots(),outputs));
  var original=RefillWordPruningTest.literalPlan(recipes,f.plan.steps(),f.plan.seeds(),f.stock,Set.of());var b=budget();
  var forced=reduce(original,f.stock,Set.of(),true,b,8192,null);
  check(forced.patternTimesExact().equals(original.patternTimesExact()),"Lost forced target credit");check(b.reservedBytes()==0,"Forced workspace");
  b=budget();var ordinary=reduce(original,f.stock,Set.of(),false,b,8192,null);
  check(ordinary.initialExact().get("feed").compareTo(original.initialExact().get("feed"))<0,"Kept unrequested surplus");check(b.reservedBytes()==0,"Ordinary workspace");
  recipes=new ArrayList<>(f.plan.recipes().values());var first=recipes.get(0);var slots=new ArrayList<>(first.slots());slots.add(new GraphRecipe.Slot<>("configuration",1L,0,true));
  recipes.set(0,new GraphRecipe<>(first.id(),first.binding(),slots,first.outputs()));
  original=RefillWordPruningTest.literalPlan(recipes,f.plan.steps(),f.plan.seeds(),f.stock,Set.of());b=budget();
  check(reduce(original,f.stock,Set.of(),true,b,8192,null)==original,"Rewrote physical configuration dispatch");check(b.reservedBytes()==0,"Configuration workspace");
  original=RefillWordPruningTest.literalPlan(new ArrayList<>(f.plan.recipes().values()),f.plan.steps(),f.plan.seeds(),f.stock,Set.of("feed"));b=budget();
  var supplied=reduce(original,f.stock,Set.of("feed"),true,b,8192,null);
  check(!supplied.missingExact().containsKey("feed"),"Invented external shortage");check(b.reservedBytes()==0,"External workspace");
  original=new GraphPlan<>(f.plan.target(),f.plan.amount(),f.plan.preserveSeeds(),f.plan.steps(),f.plan.recipes(),f.plan.initialExact(),f.plan.seeds(),f.plan.missingExact(),GraphPlan.Result.INCOMPLETE_MODEL,0,0);b=budget();
  check(reduce(original,f.stock,Set.of(),true,b,8192,null).result()==GraphPlan.Result.INCOMPLETE_MODEL,"Promoted incomplete coverage");check(b.reservedBytes()==0,"Conditional workspace");
 }
 private static void cutoffs() throws Exception {
  var f=fixture(4,3,2,3,2,3,false,false,1);
  for(long cap:new long[]{0,64,512,1024,1536,2048,4096,6144,8192}) {
   var b=budget();reduce(f.plan,f.stock,Set.of(),true,b,cap,null);
   check(b.nodes()<=cap+64&&b.reservedBytes()==0,"Quota exit");
  }
  for(String at:List.of("SHAPES","BLOCKS","BLOCK_PRUNE"))for(String mode:List.of("work","time","memory","cancel")) {
   var time=new AtomicLong();var cancel=new AtomicBoolean();var b=new PlanningBudget(1,100000,64L<<20,cancel::get,time::get);long held=0;b.failureDetail("retained proof");
   try(var owner=owner(f.plan,b)) {
    while(!owner.step()){}
    try(var work=new RefillWordPruning<>(f.plan,f.stock,Set.of(),true,false,b,8192,owner.summary())) {
     reach(work,at);var accepted=work.result();
     switch(mode) {
      case "work" -> b.charge(b.remainingWork());
      case "time" -> time.set(2000000);
      case "memory" -> {held=b.availableBytes();b.reserve(held);}
      case "cancel" -> cancel.set(true);
     }
     try {while(!work.step()){}check(!mode.equals("cancel")&&work.result()==accepted,"Lost completed incumbent");verify(f.plan,work.result(),f.stock,Set.of());}
     catch(CancellationException expected){check(mode.equals("cancel"),"Unexpected cancellation");}
     finally {b.release(held);}
    }
   }
   check(b.reservedBytes()==0&&b.failureDetail().equals("retained proof"),"Optional exit lost proof/workspace");
  }
 }
 private static void reach(RefillWordPruning<?> work,String name)throws Exception {
  var field=RefillWordPruning.class.getDeclaredField("phase");field.setAccessible(true);
  for(int i=0;!field.get(work).toString().equals(name);i++)check(i<10000&&!work.step(),"No suspended "+name);
 }
 private static void handoff()throws Exception {
  var f=fixture(8,3,2,3,2,3,false,false,1);var b=budget();
  try(var owner=owner(f.plan,b)) {
   while(!owner.step()){}long held=b.reservedBytes();
   try(var work=new RefillWordPruning<>(f.plan,f.stock,Set.of(),true,false,b,8192,owner.summary())) {
    reach(work,"SHAPES");
    try(var scheduler=new PlanningScheduler(1,4,64,1000000000)) {
     var task=scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
      public boolean advance(PlanningScheduler.Slice slice){while(slice.nextUncharged())if(work.step())return true;return false;}
      public GraphPlan<String> result(){return work.result();}public void close(){work.close();}
     },b);
     var result=task.get(10,TimeUnit.SECONDS);verify(f.plan,result,f.stock,Set.of());bound(f,result);
    }
   }check(b.reservedBytes()==held,"Worker released borrowed summary");
  }check(b.reservedBytes()==0,"Transferred workspace leak");
 }
 private static GraphPlan<String> reduce(GraphPlan<String> original,Map<String,Long> stock,Set<String> external,boolean force,PlanningBudget b,long cap,SequenceSummary<String> known) {
  GraphPlan<String> result;
  try(var p=new RefillWordPruning<>(original,stock,external,force,false,b,cap,known)) {
   while(true){long before=b.nodes();boolean done=p.step();check(b.nodes()-before<=64,"Unbounded continuation");if(done)break;}result=p.result();
  }verify(original,result,stock,external);return result;
 }
 private static void verify(GraphPlan<String> original,GraphPlan<String> result,Map<String,Long> stock,Set<String> external) {
  RefillWordPruningTest.literalVerify(result,stock,external);
  result.initialExact().forEach((k,n)->check(n.compareTo(original.initialExact().getOrDefault(k,BigInteger.ZERO))<=0,"Increased withdrawal"));
  result.missingExact().forEach((k,n)->check(n.compareTo(original.missingExact().getOrDefault(k,BigInteger.ZERO))<=0,"Increased shortage"));
  check(result.seeds().equals(original.seeds())&&result.recipes().equals(original.recipes()),"Changed binding/seeds");
 }
 private static PlanVerification<String> owner(GraphPlan<String> p,PlanningBudget b) {return PlanVerification.refill(GraphPlan.ownedRefill(p,p.steps(),p.recipes(),p.initialExact(),Map.of(),GraphPlan.Result.FEASIBLE,0),b);}
 private static PlanningBudget budget(){return MissingMaterialExpansionTest.budget(100000);}
 private static void check(boolean condition,String why){checks++;if(!condition)throw new AssertionError(why);}
}
