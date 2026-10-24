// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Nested growth loops: recognition alone is not a successful refill reduction. */
public final class RefillNestedBlocksTest {
 private static int checks;
 record Fixture(GraphPlan<String> plan,Map<String,Long> stock,long needed){}
 static Fixture fixture(int length,int inner,long goal,long seed,long joint,boolean reverse,int shape) {
  return fixture(length,inner,goal,seed,joint,reverse,shape,true);
 }
 static Fixture fixture(int length,int inner,long goal,long seed,long joint,boolean reverse,int shape,boolean preserve) {
  var recipes=new ArrayList<GraphRecipe<String>>();var cycle=new ArrayList<PlanStep>();
  for(int i=0;i<length;i++) {
   var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();
   in.put(i==0?"A":i==1?"B":"m"+i,i==0?3L:i==1?2L+inner:2L);
   if(i==0){in.put("feed",1L);in.put("tool",1L);}
   out.put(i==length-1?"A":i==0?"B":"m"+(i+1),i==length-1?4L:2L);
   if(i==length-1){out.put("tool",1L);out.put("joint",1L);}
   recipes.add(MissingMaterialExpansionTest.recipe("r"+i,in,out));cycle.add(new PlanStep.Batch("r"+i,1));
   if(i==0)for(int j=0;j<inner;j++) {cycle.add(new PlanStep.Batch("x",1));cycle.add(new PlanStep.Batch("y",1));}
  }
  recipes.add(MissingMaterialExpansionTest.recipe("x",Map.of("B",2L,"innerFeed",1L),Map.of("C",2L)));
  recipes.add(MissingMaterialExpansionTest.recipe("y",Map.of("C",2L),Map.of("B",3L)));
  var end=new LinkedHashMap<String,Long>();end.put("A",3+goal);if(joint>0)end.put("joint",joint);
  recipes.add(MissingMaterialExpansionTest.recipe("finish",end,Map.of("G",1L)));
  if(reverse)Collections.reverse(recipes);
  long retained=preserve?Math.max(seed,3):seed;
  long needed=Math.max(goal+retained,joint);var word=new ArrayList<PlanStep>();
  // Keep the original word size; its existing surplus already funds startup.
  for(int i=0;i<Math.max(goal+seed,joint)+3;i++)if(shape==0)word.addAll(cycle);else word.add(new PlanStep.Sequence(List.of(new PlanStep.Sequence(cycle))));
  word.add(new PlanStep.Batch("finish",1));var seeds=new LinkedHashMap<String,Long>();seeds.put("tool",1L);if(retained>0)seeds.put("A",retained);
  var stock=Map.of("feed",1L,"innerFeed",(long)inner,"tool",1L);
  return new Fixture(RefillWordPruningTest.preservation(RefillWordPruningTest.literalPlan(recipes,new PlanStep.Sequence(word),seeds,stock,Set.of()),preserve),stock,needed);
 }

 public static void main(String[] args) throws Exception {
  families(); preservationModes(); contracts(); exactTraces(); cutoffs(); ownership();
  System.out.println("RefillNestedBlocksTest: assertions="+checks);
 }
 private static void preservationModes() {
  for(int length:new int[]{8,24}) for(int inner:new int[]{2,4}) for(long seed:new long[]{0,2}) {
   var f=fixture(length,inner,3,seed,0,false,0,false);var b=budget();
   var result=reduce(f.plan,f.stock,Set.of(),true,b,8192,SequenceSummary.of(f.plan.steps(),f.plan.recipes()));
   bound(f,result,inner);check(b.reservedBytes()==0,"Ordinary mode leaked");
   if(seed!=0)continue;
   var implicit=RefillWordPruningTest.preservation(f.plan,true);b=budget();
   result=reduce(implicit,f.stock,Set.of(),true,b,8192,SequenceSummary.of(implicit.steps(),implicit.recipes()));
   var summary=SequenceSummary.of(result.steps(),result.recipes());
   check(result.initialExact().get("A").add(summary.delta("A")).compareTo(BigInteger.valueOf(3))>=0,"Implicit nested startup was pruned");
   check(b.reservedBytes()==0,"Implicit mode leaked");
  }
 }
 private static void families() {
  for(int length:new int[]{8,16,24,32})for(int inner:new int[]{2,3,4})for(long goal:new long[]{1,3})
  for(long seed:new long[]{0,2})for(long joint:new long[]{0,3})for(boolean reverse:List.of(false,true))for(int shape=0;shape<2;shape++) {
   var f=fixture(length,inner,goal,seed,joint,reverse,shape);var b=budget();GraphPlan<String> result;
   try(var owner=owner(f.plan,b)) {
    while(!owner.step()){}long held=b.reservedBytes(),before=b.nodes();
    result=reduce(f.plan,f.stock,Set.of(),true,b,8192,owner.summary());
    check(b.nodes()-before<=8256,"Increased original quota");check(b.reservedBytes()==held,"Released borrowed owner");
   }
   check(b.reservedBytes()==0&&b.cachePreparationWork()==0,"Workspace/cache budget leak");
   check(result.initialExact().get("feed").compareTo(BigInteger.valueOf(f.needed))>=0,"Broken feed conservation");
   // Scoped conservation, not the old retained excess, is the positive oracle.
   bound(f,result,inner);
  }
  for(int length:new int[]{40,64,96}) {
   var f=fixture(length,4,3,2,3,false,0);var b=budget();
   reduce(f.plan,f.stock,Set.of(),true,b,8192,null);check(b.reservedBytes()==0,"Large uncached word leak");
  }
 }
 private static void bound(Fixture f,GraphPlan<String> result,int inner) {
  check(result.initialExact().get("feed").equals(BigInteger.valueOf(f.needed)),"Missed outer feed bound");
  check(result.initialExact().get("innerFeed").equals(BigInteger.valueOf(f.needed*inner)),"Missed inner feed bound");
  check(result.patternTimesExact().get("x").equals(BigInteger.valueOf(f.needed*inner)),"Lost required inner rounds");
  check(result.patternTimesExact().get("y").equals(BigInteger.valueOf(f.needed*inner)),"Changed inner execution order");
 }
 private static void contracts() {
  var f=fixture(24,3,1,0,0,false,0);var recipes=new ArrayList<>(f.plan.recipes().values());
  int last=-1;for(int i=0;i<recipes.size();i++)if(recipes.get(i).id().equals("r23"))last=i;
  var r=recipes.get(last);var outputs=new LinkedHashMap<>(r.outputs());outputs.put("G",1L);
  recipes.set(last,new GraphRecipe<>(r.id(),r.binding(),r.slots(),outputs));
  var original=RefillWordPruningTest.literalPlan(recipes,f.plan.steps(),f.plan.seeds(),f.stock,Set.of());
  var b=budget();var forced=reduce(original,f.stock,Set.of(),true,b,8192,SequenceSummary.of(original.steps(),original.recipes()));
  check(forced.patternTimesExact().equals(original.patternTimesExact()),"Lost forced target credit");check(b.reservedBytes()==0,"Forced lease");
  b=budget();var ordinary=reduce(original,f.stock,Set.of(),false,b,8192,SequenceSummary.of(original.steps(),original.recipes()));
  check(ordinary.initialExact().get("feed").compareTo(original.initialExact().get("feed"))<0,"Unused target surplus blocked reduction");
  check(b.reservedBytes()==0,"Ordinary lease");
  recipes=new ArrayList<>(f.plan.recipes().values());r=recipes.get(0);var slots=new ArrayList<>(r.slots());slots.add(new GraphRecipe.Slot<>("config",1L,0,true));
  recipes.set(0,new GraphRecipe<>(r.id(),r.binding(),slots,r.outputs()));
  original=RefillWordPruningTest.literalPlan(recipes,f.plan.steps(),f.plan.seeds(),f.stock,Set.of());b=budget();
  check(reduce(original,f.stock,Set.of(),true,b,8192,null)==original,"Rewrote batch-sensitive configuration");check(b.reservedBytes()==0,"Configuration lease");
  var external=Set.of("feed","innerFeed");original=RefillWordPruningTest.literalPlan(new ArrayList<>(f.plan.recipes().values()),f.plan.steps(),f.plan.seeds(),f.stock,external);b=budget();
  var supplied=reduce(original,f.stock,external,true,b,8192,SequenceSummary.of(original.steps(),original.recipes()));
  check(!supplied.missingExact().containsKey("feed")&&!supplied.missingExact().containsKey("innerFeed"),"Invented external shortage");check(b.reservedBytes()==0,"External lease");
  original=new GraphPlan<>(f.plan.target(),f.plan.amount(),f.plan.preserveSeeds(),f.plan.steps(),f.plan.recipes(),f.plan.initialExact(),f.plan.seeds(),f.plan.missingExact(),GraphPlan.Result.INCOMPLETE_MODEL,0,0);b=budget();
  check(reduce(original,f.stock,Set.of(),true,b,8192,SequenceSummary.of(original.steps(),original.recipes())).result()==GraphPlan.Result.INCOMPLETE_MODEL,"Promoted incomplete coverage");check(b.reservedBytes()==0,"Conditional lease");
 }
 private static void exactTraces() {
  var random=new Random(2026101906L);
  for(int test=0;test<160;test++) {
   var body=new ArrayList<PlanStep>();int length=17+random.nextInt(60);
   for(int i=0;i<length;i++)body.add(new PlanStep.Batch("r"+i,random.nextInt(4)));
   body.addAll(List.of(new PlanStep.Batch("FB",1),new PlanStep.Batch("Ea",1),new PlanStep.Batch("FB",1),new PlanStep.Batch("Ea",1)));
   var word=new ArrayList<PlanStep>();for(int i=0,copies=2+random.nextInt(4);i<copies;i++)word.addAll(body);
   word.add(new PlanStep.Batch("tail",2));var reversed=new ArrayList<>(word);Collections.reverse(reversed);var b=budget();
   try(var work=new RefillWordAnchors(reversed,b,8192,17)) {
    while(!work.step()){}check(work.result()!=null&&!work.hasShortBlocks(),"Local inner block masked long grouping");
    var trace=trace(work.result());check(trace.equals(word),"Changed exact primitive batch trace");
   }
   check(b.reservedBytes()==0,"Trace lease");
  }
  var shortWord=List.<PlanStep>of(new PlanStep.Batch("a",1),new PlanStep.Batch("b",1),new PlanStep.Batch("a",1),new PlanStep.Batch("b",1));
  for(int minimum:new int[]{2,17}) {
   var b=budget();try(var work=new RefillWordAnchors(shortWord,b,8192,minimum)) {
    while(!work.step()){}check((work.result()!=null)==(minimum==2),"Ignored period floor");check(work.hasShortBlocks()==(minimum==2),"Incorrect short-group provenance");
   }check(b.reservedBytes()==0,"Short scan lease");
  }
 }
 private static List<PlanStep> trace(PlanStep root) {
  var out=new ArrayList<PlanStep>();var todo=new ArrayDeque<PlanStep>();todo.push(root);
  while(!todo.isEmpty()) {
   var s=todo.pop();if(s instanceof PlanStep.Batch)out.add(s);
   else if(s instanceof PlanStep.Sequence q)for(int i=q.children().size()-1;i>=0;i--)todo.push(q.children().get(i));
   else {var r=(PlanStep.Repeat)s;check(r.times()<1000,"Unbounded trace");for(int i=0;i<r.times();i++)todo.push(r.body());}
  }return out;
 }
 private static void cutoffs() throws Exception {
  var f=fixture(16,3,3,2,3,false,0);var known=SequenceSummary.of(f.plan.steps(),f.plan.recipes());
  for(long cap:new long[]{0,512,2048,4096,6144,8192}) {
   var b=budget();reduce(f.plan,f.stock,Set.of(),true,b,cap,known);check(b.nodes()<=cap+64&&b.reservedBytes()==0,"Optional cap/lease");
  }
  for(long bytes:new long[]{1,256,4096,16384,32768,65536}) {
   var b=new PlanningBudget(0,100000,bytes,()->false,System::nanoTime);b.failureDetail("retained proof");
   reduce(f.plan,f.stock,Set.of(),true,b,8192,known);check(b.reservedBytes()==0&&b.failureDetail().equals("retained proof"),"Memory cutoff/proof");
  }
  for(boolean child:List.of(false,true))for(String mode:List.of("work","time","memory","cancel")) {
   var clock=new AtomicLong();var cancel=new AtomicBoolean();var b=new PlanningBudget(1,100000,64L<<20,cancel::get,clock::get);long held=0;b.failureDetail("retained proof");
   try(var work=new RefillWordPruning<>(f.plan,f.stock,Set.of(),true,false,b,8192,known)) {
    reachLong(work,child);var incumbent=work.result();
    switch(mode){case "work"->b.charge(b.remainingWork());case "time"->clock.set(2000000);case "memory"->{held=b.availableBytes();b.reserve(held);}case "cancel"->cancel.set(true);}
    try {while(!work.step()){}check(!mode.equals("cancel")&&work.result()==incumbent,"Discarded completed incumbent");}
    catch(CancellationException expected){check(mode.equals("cancel"),"Unexpected cancellation");}
   }finally{b.release(held);}
   check(b.reservedBytes()==0&&b.failureDetail().equals("retained proof"),"Continuation cleanup/proof");
  }
 }
 private static Object get(Object object,String name)throws Exception {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
 private static void reachLong(RefillWordPruning<?> work,boolean child)throws Exception {
  for(int i=0;;i++) {
   var a=get(work,"anchors");if(a!=null&&(int)get(a,"minimumPeriod")==17&&get(work,"phase").toString().equals(child?"BLOCK_PRUNE":"ANCHORS"))return;
   check(i<10000&&!work.step(),"No larger-period continuation");
  }
 }
 private static void ownership()throws Exception {
  var f=fixture(16,3,3,2,3,false,0);var b=budget();
  try(var owner=owner(f.plan,b)) {
   while(!owner.step()){}
   try(var work=new RefillWordPruning<>(f.plan,f.stock,Set.of(),true,false,b,8192,owner.summary())) {
    reachLong(work,false);
    try(var scheduler=new PlanningScheduler(1,4,64,1000000000)) {
     var task=scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
      public boolean advance(PlanningScheduler.Slice slice){while(slice.nextUncharged())if(work.step())return true;return false;}
      public GraphPlan<String> result(){return work.result();}public void close(){work.close();}
     },b);
     var result=task.get(10,TimeUnit.SECONDS);verify(f.plan,result,f.stock,Set.of());bound(f,result,3);
    }
   }
  }check(b.reservedBytes()==0,"Transferred verifier lease");
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
