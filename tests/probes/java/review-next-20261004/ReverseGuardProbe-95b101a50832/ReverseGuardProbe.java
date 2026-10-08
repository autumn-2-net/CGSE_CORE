package org.cgse.core;
import java.util.concurrent.CancellationException;
import java.util.*;import java.math.BigInteger;import java.nio.file.*;import java.lang.reflect.*;import java.util.concurrent.atomic.AtomicInteger;import com.google.gson.Gson;
public final class ReverseGuardProbe {
 static long checks;
 static void check(boolean x,String s){checks++;if(!x)throw new AssertionError(s);}
 static void enter(CountSchedule<String> schedule,int n,boolean reverse)throws Exception{
  var attempt=CountSchedule.class.getDeclaredField("attempt");attempt.setAccessible(true);attempt.setInt(schedule,8*Math.min(4,n)-1);
  var startup=CountSchedule.class.getDeclaredField("startupChecked");startup.setAccessible(true);startup.setBoolean(schedule,true);
  var next=CountSchedule.class.getDeclaredMethod("nextAttempt");next.setAccessible(true);next.invoke(schedule);
  if(reverse){var method=CountSchedule.class.getDeclaredMethod("prepareReverse");method.setAccessible(true);method.invoke(schedule);}
 }
 static List<GraphRecipe<String>> recipes(boolean reusable){
  var list=new ArrayList<GraphRecipe<String>>();String[] inputs={"a","b","a"},outputs={"g","a","b"};long[] qty={1,1,2};
  for(int i=0;i<3;i++){
   var slots=List.of(new GraphRecipe.Slot<>(inputs[i],1),new GraphRecipe.Slot<>("token",1,-1,true,reusable));
   var out=new LinkedHashMap<String,Long>();out.put(outputs[i],qty[i]);if(reusable)out.put("token",1L);
   list.add(new GraphRecipe<>("r"+i,"r"+i,slots,out));
  }return list;
 }
 public static void main(String[]args)throws Exception{
  for(int mode=0;mode<4;mode++)for(int permutation=0;permutation<24;permutation++){
   var rs=recipes(mode!=2);Collections.shuffle(rs,new Random(permutation));var stock=mode==1?Map.of("a",1L):Map.of("a",1L,"token",1L);var external=mode==1?Set.of("token"):Set.<String>of();
   var b=ScheduleProbe.budget();BigInteger[] counts=rs.stream().map(r->BigInteger.ONE).toArray(BigInteger[]::new);
   try(var model=RecipeCountModel.forShell(rs,Map.of(),stock,external,b);var schedule=new CountSchedule<>(model,counts,b)){
    enter(schedule,3,mode!=2);while(!schedule.step()){}
    if(mode==2)check(schedule.result()==CountSchedule.Result.UNKNOWN,"batch-sensitive exact proof escaped");
    else{check(schedule.result()==CountSchedule.Result.WITNESS,"read/external witness missing");var byId=new LinkedHashMap<String,GraphRecipe<String>>();rs.forEach(r->byId.put(r.id(),r));try(var summary=new SummaryComputation<>(schedule.witness(),byId,b)){while(!summary.step()){}for(var e:summary.result().required().entrySet())check(external.contains(e.getKey())||e.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(e.getKey(),0L)))<=0,"reverse prefix");}}
   }check(b.reservedBytes()==0,"read/external leak");
  }
  for(int i=1;i<=240;i++){
   final int at=i*3;var calls=new AtomicInteger();var b=new PlanningBudget(0,2_000_000,32L<<20,()->calls.incrementAndGet()>=at,System::nanoTime);
   var rs=recipes(true);BigInteger[] counts={BigInteger.valueOf(3),BigInteger.valueOf(5),BigInteger.valueOf(3)};
   try(var model=RecipeCountModel.forShell(rs,Map.of(),Map.of("a",1L,"token",1L),Set.of(),b);var schedule=new CountSchedule<>(model,counts,b)){
    enter(schedule,3,true);while(!schedule.step()){}
   }catch(CancellationException|PlanningBudget.Exhausted good){}catch(InvocationTargetException wrapped){check(wrapped.getCause() instanceof CancellationException||wrapped.getCause() instanceof PlanningBudget.Exhausted,"unexpected cancellation exception");}
   check(b.reservedBytes()==0,"reverse cancel leak "+i+" bytes="+b.reservedBytes());
  }
  for(int i=0;i<48;i++){
   var b=new PlanningBudget(0,2_000_000,(4L<<20)+4096L*i,()->false,System::nanoTime);var rs=recipes(true);BigInteger[] counts={BigInteger.valueOf(3),BigInteger.valueOf(5),BigInteger.valueOf(3)};
   try(var model=RecipeCountModel.forShell(rs,Map.of(),Map.of("a",1L,"token",1L),Set.of(),b);var schedule=new CountSchedule<>(model,counts,b)){
    if(schedule.result()==null){enter(schedule,3,true);while(!schedule.step()){}}
    check(schedule.result()!=CountSchedule.Result.DEAD,"memory false DEAD");
   }catch(PlanningBudget.Exhausted good){}
   check(b.reservedBytes()==0,"reverse memory leak "+i);
  }
  var report=Map.of("checks",checks,"semantic_cases",96,"lifecycle",288);Files.writeString(Path.of(args[0],"reverse-guard-probe.json"),new Gson().toJson(report));System.out.println(report);
 }
}
