// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Association, identity wrappers, ragged partitions and compulsory intervening work. */
public final class RefillNormalizedWordTest {
 private static int checks;
 record Fixture(GraphPlan<String> plan,Map<String,Long> stock,long needed,long factor){}
 static Fixture fixture(int parts,int width,int factor,long goal,long seed,long joint,boolean separated,boolean reverse,int shape) {
  return fixture(parts,width,factor,goal,seed,joint,separated,reverse,shape,false);
 }
 static Fixture fixture(int parts,int width,int factor,long goal,long seed,long joint,boolean separated,boolean reverse,int shape,boolean dependent) {
  return fixture(parts,width,factor,goal,seed,joint,separated,reverse,shape,dependent,true);
 }
 static Fixture fixture(int parts,int width,int factor,long goal,long seed,long joint,boolean separated,boolean reverse,int shape,boolean dependent,boolean preserve) {
  long rounds=Math.max(goal+seed,joint)+3;
  // One primitive restart needs 3 A, independent of the grouped repeat factor.
  // Whole grouped rounds may retain a little more, hence ceil(3 / factor).
  if(preserve)seed=Math.max(seed,(3L+factor-1)/factor);
  int length=parts*width;var recipes=new ArrayList<GraphRecipe<String>>();
  for(int i=0;i<length;i++) {
   var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();
   in.put(i==0?"A":"m"+i,i==0?3L:2L);if(i==0){in.put("feed",1L);in.put("tool",1L);}
   out.put(i==length-1?"A":"m"+(i+1),i==length-1?4L:2L);
   if(i==length-1){out.put("tool",1L);out.put("joint",1L);}
   recipes.add(MissingMaterialExpansionTest.recipe("r"+i,in,out));
  }
  var end=new LinkedHashMap<String,Long>();end.put("A",(3+goal)*factor);if(joint>0)end.put("joint",joint*factor);
  recipes.add(MissingMaterialExpansionTest.recipe("finish",end,Map.of("G",1L)));
  long needed=Math.max(goal+seed,joint);var word=new ArrayList<PlanStep>();
  for(int round=0;round<rounds;round++) {
   for(int part=0,cursor=0;cursor<length;part++) {
    int span=shape==3?Math.min(length-cursor,Math.max(1,width+((round+part)%2==0?1:-1))):Math.min(width,length-cursor);
    var body=new ArrayList<PlanStep>();for(int i=0;i<span;i++)body.add(new PlanStep.Batch("r"+(cursor+i),1));
    PlanStep step=new PlanStep.Sequence(body);
    if(shape==0)for(int depth=0;depth<=round;depth++)step=new PlanStep.Sequence(List.of(step));
    if(shape==1)for(int depth=0;depth<=round;depth++)step=new PlanStep.Repeat(new PlanStep.Sequence(List.of(step)),1);
    if(shape==2) {
     var ragged=new ArrayList<PlanStep>();for(int i=0;i<body.size();i++) {
      PlanStep child=body.get(i);for(int depth=0;depth<(round+i)%3;depth++)child=new PlanStep.Sequence(List.of(child));ragged.add(child);
     }step=new PlanStep.Sequence(ragged);
    }
    if(shape==4)word.addAll(body);else word.add(new PlanStep.Repeat(step,factor));cursor+=span;
   }
   if(separated) {
    String id="side"+round;var sideIn=new LinkedHashMap<String,Long>();var sideOut=new LinkedHashMap<String,Long>();sideIn.put("rawSide"+round,1L);sideOut.put("side"+round,1L);
    if(dependent){sideIn.put("A",(4L+round)*factor);sideOut.put("A",(4L+round)*factor);}
    recipes.add(MissingMaterialExpansionTest.recipe(id,sideIn,sideOut));
    word.add(new PlanStep.Batch(id,1));end.put("side"+round,1L);
   }
  }
  // Rebuild finish with compulsory side products after constructing the rounds.
  recipes.removeIf(r->r.id().equals("finish"));recipes.add(MissingMaterialExpansionTest.recipe("finish",end,Map.of("G",1L)));
  if(reverse)Collections.reverse(recipes);
  word.add(new PlanStep.Batch("finish",1));var seeds=new LinkedHashMap<String,Long>();seeds.put("tool",(long)factor);if(seed>0)seeds.put("A",seed*factor);
  var stock=new LinkedHashMap<String,Long>();stock.put("feed",(long)factor);stock.put("tool",(long)factor);
  var plan=RefillWordPruningTest.literalPlan(recipes,new PlanStep.Sequence(word),seeds,stock,Set.of());
  if(!preserve)plan=withPreserve(plan,false);
  return new Fixture(plan,stock,dependent?rounds:needed,factor);
 }
 public static void main(String[] args) throws Exception {
  families(false); families(true); implicitStartup(); dependentGaps(); expansion(); contracts(); cutoffs(); handoff();
  System.out.println("RefillNormalizedWordTest: assertions="+checks);
 }
 private static void families(boolean preserve) {
  int atBound=0,primaryAtBound=0,total=0;
  for(int parts:new int[]{2,4,8})for(int width:new int[]{2,3})for(int factor:new int[]{1,2})for(long goal:new long[]{1,3})
  for(long seed:new long[]{0,2})for(long joint:new long[]{0,3})for(boolean separated:List.of(false,true))for(boolean reverse:List.of(false,true))for(int shape=0;shape<(factor==1?5:3);shape++) {
   var f=fixture(parts,width,factor,goal,seed,joint,separated,reverse,shape,false,preserve);var b=budget();GraphPlan<String> result;
   try(var owner=owner(f.plan,b)) {
    while(!owner.step()){}long held=b.reservedBytes(),before=b.nodes();
    result=reduce(f.plan,f.stock,Set.of(),true,b,8192,owner.summary());
    check(b.nodes()-before<=8256&&b.reservedBytes()==held,"Borrowed summary/allowance");
   }
   check(b.reservedBytes()==0&&b.cachePreparationWork()==0,"Workspace/account leak");total++;
   long feed=result.initialExact().get("feed").longValueExact();check(feed>=f.needed*factor,"Violated conservation bound");
   if(feed==f.needed*factor){atBound++;if(shape<3)primaryAtBound++;}
   else {
    // Diagnostic quality quota, not a raised production default. Long words
    // may retain the incumbent at 8192; every member has a checked reduction.
    b=budget();try(var owner=owner(f.plan,b)) {while(!owner.step()){}result=reduce(f.plan,f.stock,Set.of(),true,b,16384,owner.summary());}
    check(b.reservedBytes()==0,"Diagnostic workspace");
   }
   try { bound(f,result); }
   catch(AssertionError failure) { throw new AssertionError(parts+"-"+width+"-"+factor+"-"+goal+"-"+seed+"-"+joint+"-"+separated+"-"+reverse+"-"+shape+" feed="+result.initialExact().get("feed"),failure); }
   for(int i=0;i<parts*width;i++)check(result.patternTimesExact().get("r"+i).equals(BigInteger.valueOf(f.needed*factor)),"Split a required segment");
   for(var e:f.plan.patternTimesExact().entrySet())if(e.getKey().startsWith("side"))check(e.getValue().equals(result.patternTimesExact().get(e.getKey())),"Lost compulsory gap");
  }
  System.out.println("Normalized families: preserve="+preserve+", default bound="+atBound+"/"+total+", primary="+primaryAtBound+"/1152");
  check(total==1536&&atBound==1536&&primaryAtBound==1152,"Default-budget quality regressed: "+atBound+"/"+total);
 }
 private static GraphPlan<String> withPreserve(GraphPlan<String> plan,boolean preserve) {
  return new GraphPlan<>(plan.target(),plan.amount(),preserve,plan.steps(),plan.recipes(),plan.initialExact(),plan.seeds(),plan.missingExact(),plan.result(),0,0);
 }
 private static BigInteger finalA(GraphPlan<String> plan) {
  return plan.initialExact().getOrDefault("A",BigInteger.ZERO).add(SequenceSummary.of(plan.steps(),plan.recipes()).delta("A"));
 }
 private static void implicitStartup() {
  for(int factor:new int[]{1,2})for(long seed:new long[]{0,2}) {
   var f=fixture(2,2,factor,1,seed,0,false,false,0,false,false);var b=budget();
   var ordinary=reduce(f.plan,f.stock,Set.of(),true,b,8192,null);bound(f,ordinary);
   check(finalA(ordinary).equals(BigInteger.valueOf(seed*factor)),"Legacy conservation oracle changed");
   if(seed*factor<3) {
    // The old tight oracle was physically executable but destroyed startup.
    // It is valid only when automatic preservation is disabled.
    var audit=budget();
    try(var guard=new SeedStartupGuard<>(ordinary.recipes().values(),SequenceSummary.of(ordinary.steps(),ordinary.recipes()),
            ordinary.initial(),ordinary.seeds(),Set.of(),ordinary.target(),ordinary.amount(),audit)) {
     while(!guard.step()){}check(guard.lost().getOrDefault("A",0L)>=3,"Unsafe legacy oracle was not detected");
    }check(audit.reservedBytes()==0,"Startup oracle workspace");
   }
   var original=withPreserve(f.plan,true);b=budget();var safe=reduce(original,f.stock,Set.of(),true,b,8192,null);
   check(finalA(safe).compareTo(BigInteger.valueOf(3))>=0,"Implicit preservation accepted an exhausted startup");
   check(b.reservedBytes()==0,"Implicit startup workspace");
  }
 }
 private static void bound(Fixture f,GraphPlan<String> p) {
  // Fixed startup A, feed per round, net A/joint growth and retained seeds
  // give a scoped conservation bound. This is not a global optimality claim.
  check(p.initialExact().get("feed").equals(BigInteger.valueOf(f.needed*f.factor)),"Missed normalized loop bound");
  check(p.initialExact().get("A").equals(BigInteger.valueOf(3*f.factor)),"Changed startup seed");
  check(p.initialExact().get("tool").equals(BigInteger.valueOf(f.factor)),"Lost returned tool");
  if(p.preserveSeeds())check(finalA(p).compareTo(BigInteger.valueOf(3))>=0,"Preserved word cannot restart its loop");
 }
 private static void dependentGaps() {
  for(int parts:new int[]{2,4,8})for(int width:new int[]{2,3})for(int factor:new int[]{1,2})for(boolean reverse:List.of(false,true))for(int shape=0;shape<3;shape++) {
   var f=fixture(parts,width,factor,3,2,3,true,reverse,shape,true);var b=budget();
   try(var owner=owner(f.plan,b)) {
    while(!owner.step()){}var result=reduce(f.plan,f.stock,Set.of(),true,b,16384,owner.summary());
    bound(f,result);check(result.patternTimesExact().equals(f.plan.patternTimesExact()),"Moved a dependent gap across required growth");
   }check(b.reservedBytes()==0,"Dependent gap workspace");
  }
 }
 private static void expansion() {
  for(int parts:new int[]{2,4})for(int width:new int[]{2,3})for(int factor:new int[]{1,2})
  for(boolean separated:List.of(false,true))for(boolean reverse:List.of(false,true))for(int shape=0;shape<3;shape++) {
   var f=fixture(parts,width,factor,3,2,3,separated,reverse,shape);var catalog=new ArrayList<>(f.plan.recipes().values());
   for(int i=0;i<8;i++)catalog.add(MissingMaterialExpansionTest.recipe("feed"+i,Map.of(i==7?"raw":"feed"+(i+1),1L),Map.of(i==0?"feed":"feed"+i,1L)));
   var b=budget();
   try(var e=new MissingMaterialExpansion<>(new GraphCompiler<>(catalog),f.plan,f.stock,Set.of(),Set.of(),true,false,b)) {
    while(!e.step()){}var p=e.result();RefillWordPruningTest.literalVerify(p,f.stock,Set.of());
    check(p.missingExact().keySet().stream().allMatch(k->k.equals("A")||k.equals("raw")||k.startsWith("rawSide")),"Left a craftable intermediate shortage");
    check(p.missingExact().get("raw").compareTo(BigInteger.valueOf((f.needed+2)*factor))<=0,"Worsened terminal supply");
   }check(b.reservedBytes()==0,"Expanded workspace");
  }
 }
 private static void contracts() {
  var f=fixture(2,4,2,3,0,0,false,false,1);var recipes=new ArrayList<>(f.plan.recipes().values());
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
  var f=fixture(2,3,2,3,2,3,true,false,1);
  for(long cap:new long[]{0,64,512,1024,1536,2048,4096,6144,8192}) {
   var b=budget();reduce(f.plan,f.stock,Set.of(),true,b,cap,null);
   check(b.nodes()<=cap+64&&b.reservedBytes()==0,"Quota exit");
  }
  for(String at:List.of("NORMAL_FORMS","SPANS","BLOCK_PRUNE"))for(String mode:List.of("work","time","memory","cancel")) {
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
  var f=fixture(2,3,2,3,2,3,true,false,1);var b=budget();
  try(var owner=owner(f.plan,b)) {
   while(!owner.step()){}long held=b.reservedBytes();
   try(var work=new RefillWordPruning<>(f.plan,f.stock,Set.of(),true,false,b,8192,owner.summary())) {
    reach(work,"NORMAL_FORMS");
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
