package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

public class DumpSolverRegression {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 static void run(PlanStep p,Map<String,GraphRecipe<String>> rs,Map<String,BigInteger> held,Map<String,BigInteger> need){
  if(p instanceof PlanStep.Batch b){for(long n=0;n<b.runs();n++){
   var r=rs.get(b.recipe());r.inputs().forEach((k,v)->{held.merge(k,BigInteger.valueOf(v).negate(),BigInteger::add);need.merge(k,held.get(k).negate().max(BigInteger.ZERO),BigInteger::max);});
   r.outputs().forEach((k,v)->held.merge(k,BigInteger.valueOf(v),BigInteger::add));
  }}else if(p instanceof PlanStep.Repeat r){for(long n=0;n<r.times();n++)run(r.body(),rs,held,need);}else for(var c:((PlanStep.Sequence)p).children())run(c,rs,held,need);
 }
 static Map<String,BigInteger> counts(PlanStep root){
  record Frame(PlanStep p,BigInteger n){} var q=new ArrayDeque<Frame>();q.add(new Frame(root,BigInteger.ONE));var m=new HashMap<String,BigInteger>();
  while(!q.isEmpty()){var f=q.pop();if(f.p instanceof PlanStep.Batch b)m.merge(b.recipe(),f.n.multiply(BigInteger.valueOf(b.runs())),BigInteger::add);else if(f.p instanceof PlanStep.Repeat r)q.push(new Frame(r.body(),f.n.multiply(BigInteger.valueOf(r.times()))));else for(var c:((PlanStep.Sequence)f.p).children())q.push(new Frame(c,f.n));}return m;
 }
 static void programOracle(){var random=new Random(260925);
  for(int test=0;test<600;test++){
   var recipes=new ArrayList<GraphRecipe<String>>();var byId=new HashMap<String,GraphRecipe<String>>();int size=1+random.nextInt(8);var n=new BigInteger[size];
   for(int i=0;i<size;i++){
    var in=new HashMap<String,Long>();var out=new HashMap<String,Long>();for(int t=0;t<4;t++){in.merge("k"+random.nextInt(5),1L,Long::sum);out.merge("k"+random.nextInt(5),1L,Long::sum);}
    var r=recipe("r"+i,in,out);recipes.add(r);byId.put(r.id(),r);n[i]=test<300?BigInteger.valueOf(random.nextInt(8)):new BigInteger(64+random.nextInt(192),random);
   }
   var budget=new PlanningBudget(0,4_000_000,128L<<20,()->false,System::nanoTime);
   try(var p=new CountProgram<>(recipes,n,budget)){while(!p.step()){}check(p.available(),"unexpected program memory cap");var actual=counts(p.program());var deltas=new HashMap<String,BigInteger>();
    for(int i=0;i<size;i++){check(n[i].equals(actual.getOrDefault("r"+i,BigInteger.ZERO)),"lost exact recipe count");final BigInteger copies=n[i];SequenceSummary.recipe(recipes.get(i)).delta().forEach((k,d)->deltas.merge(k,d.multiply(copies),BigInteger::add));}
    for(var e:deltas.entrySet())check(e.getValue().equals(p.summary().delta(e.getKey())),"net mismatch");
    if(test<300){var held=new HashMap<String,BigInteger>();var need=new HashMap<String,BigInteger>();run(p.program(),byId,held,need);for(String k:deltas.keySet()){check(held.getOrDefault(k,BigInteger.ZERO).equals(p.summary().delta(k)),"explicit delta mismatch");check(need.getOrDefault(k,BigInteger.ZERO).equals(p.summary().required(k)),"explicit prefix mismatch");}}
   }
  }
  var b=new PlanningBudget(0,10000,64,()->false,System::nanoTime);try(var p=new CountProgram<>(List.of(recipe("r",Map.of("a",1L),Map.of("b",1L))),new BigInteger[]{BigInteger.TEN},b)){check(p.step()&&!p.available(),"optional program memory cap");}
 }
 static BigInteger dot(Map<Integer,BigInteger> row,int[] point){var v=BigInteger.ZERO;for(var e:row.entrySet())v=v.add(e.getValue().multiply(BigInteger.valueOf(point[e.getKey()])));return v;}
 static void eliminationOracle()throws Exception{
  var method=CountBounds.class.getDeclaredMethod("combine",ExactLinearProgram.Constraint.class,ExactLinearProgram.Constraint.class,int.class);method.setAccessible(true);var random=new Random(607122);
  for(int sample=0;sample<1500;sample++){
   var a=new HashMap<Integer,BigInteger>();var b=new HashMap<Integer,BigInteger>();a.put(0,BigInteger.valueOf(1+random.nextInt(5)));b.put(0,BigInteger.valueOf(-1-random.nextInt(5)));
   for(int i=1;i<4;i++){int x=random.nextInt(9)-4,y=random.nextInt(9)-4;if(x!=0)a.put(i,BigInteger.valueOf(x));if(y!=0)b.put(i,BigInteger.valueOf(y));}
   var first=new ExactLinearProgram.Constraint(a,BigInteger.valueOf(random.nextInt(25)-12));var second=new ExactLinearProgram.Constraint(b,BigInteger.valueOf(random.nextInt(25)-12));
   var budget=new PlanningBudget(0,1000000,128L<<20,()->false,System::nanoTime);
   try(var bounds=new CountBounds(4,List.of(first,second),budget)){
    var c=(ExactLinearProgram.Constraint)method.invoke(bounds,first,second,0);
    for(int x=0;x<625;x++){int v=x;int[] point=new int[4];for(int i=0;i<4;i++){point[i]=v%5;v/=5;}
     if(dot(a,point).compareTo(first.upper())<=0&&dot(b,point).compareTo(second.upper())<=0)check(c==null||dot(c.terms(),point).compareTo(c.upper())<=0,"invalid eliminated inequality");
    }
   }
  }
 }
 static void lossyCycle(){for(long n:new long[]{1,Integer.MAX_VALUE,Long.MAX_VALUE}){
  var recipes=List.of(recipe("compress",Map.of("dust",4L),Map.of("gem",3L)),recipe("grind",Map.of("gem",1L),Map.of("dust",1L)),recipe("finish",Map.of("dust",1L),Map.of("target",1L)));
  for(boolean funded:new boolean[]{false,true}){
   var b=new PlanningBudget(0,4_000_000,128L<<20,()->false,System::nanoTime);var w=new GraphPlanningWork<>(new GraphCompiler<>(recipes),"target",n,funded?Map.of("gem",n):Map.of(),true,true,b).catalysts(CatalystPolicy.MINIMAL);while(!w.step()){}var p=w.result();check(p.feasible()==funded,"lossy conversion result "+p.result());check(funded||!p.missingExact().isEmpty(),"missing conversion preview");if(funded)PlanVerifier.verify(p);
  }
 }}
 public static void main(String[]args)throws Exception{programOracle();eliminationOracle();lossyCycle();System.out.println("DUMP_SOLVER_REGRESSION_PASS checks="+checks);}
}
