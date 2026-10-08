package org.cgse.core;
import java.util.*;
public final class FallbackOracleProbe {
 static final String[] KEYS={"A","B","X","Y","C"};
 static record State(int a,int b,int x,int y,int c) {
  int get(int k){return switch(k){case 0->a;case 1->b;case 2->x;case 3->y;default->c;};}
 }
 static boolean oracle(List<GraphRecipe<String>> rs,Map<String,Long> stock,int amount,Map<String,Long> seeds,boolean force){
  var start=new State(stock.getOrDefault("A",0L).intValue(),stock.getOrDefault("B",0L).intValue(),stock.getOrDefault("X",0L).intValue(),stock.getOrDefault("Y",0L).intValue(),force?0:stock.getOrDefault("C",0L).intValue());
  var seen=new HashSet<State>();var q=new ArrayDeque<State>();seen.add(start);q.add(start);
  while(!q.isEmpty()){
   var s=q.removeFirst();boolean goal=s.c>=amount+seeds.getOrDefault("C",0L);
   for(int k=0;k<KEYS.length;k++)if(!KEYS[k].equals("C"))goal&=s.get(k)>=seeds.getOrDefault(KEYS[k],0L);
   if(goal)return true;
   for(var r:rs){int[] n=new int[5];boolean can=true;
    for(int k=0;k<5;k++){long in=r.inputs().getOrDefault(KEYS[k],0L);can&=s.get(k)>=in;n[k]=(int)(s.get(k)-in+r.outputs().getOrDefault(KEYS[k],0L));}
    if(can){var next=new State(n[0],n[1],n[2],n[3],Math.min(n[4],amount+seeds.getOrDefault("C",0L).intValue()));if(seen.add(next))q.addLast(next);}
   }
  }return false;
 }
 static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return OrderProbe.recipe(id,in,out);}
 public static void main(String[]args){
  var random=new Random(30320261003L);int tested=0,possible=0,feasible=0,miss=0;
  for(int sample=0;sample<150;sample++){
   var rs=new ArrayList<GraphRecipe<String>>();
   rs.add(r("xA",Map.of("A",1L+random.nextInt(3)),Map.of("X",1L+random.nextInt(3))));
   rs.add(r("xB",Map.of("B",1L+random.nextInt(3)),Map.of("X",1L+random.nextInt(3))));
   rs.add(r("yA",Map.of("A",1L+random.nextInt(3)),Map.of("Y",1L+random.nextInt(3))));
   rs.add(r("yB",Map.of("B",1L+random.nextInt(3)),Map.of("Y",1L+random.nextInt(3))));
   rs.add(r("joint",Map.of("A",1L+random.nextInt(3),"B",1L),Map.of("X",1L,"Y",1L)));
   rs.add(r("target",Map.of("X",1L+random.nextInt(2),"Y",1L+random.nextInt(2)),Map.of("C",1L+random.nextInt(3))));
   rs.add(r("direct",Map.of("A",1L+random.nextInt(3),"B",1L+random.nextInt(3)),Map.of("C",1L)));
   var stock=Map.of("A",(long)random.nextInt(6),"B",(long)random.nextInt(6),"X",(long)random.nextInt(2),"Y",(long)random.nextInt(2),"C",(long)random.nextInt(4));
   int amount=1+random.nextInt(5);var seeds=sample%3==0?Map.of("X",1L):Map.<String,Long>of();
   for(boolean force:new boolean[]{false,true})for(int order=0;order<2;order++){
    Collections.shuffle(rs,random);boolean reachable=oracle(rs,stock,amount,seeds,force);possible+=reachable?1:0;
    var budget=new PlanningBudget(0,131072,64L<<20,()->false,System::nanoTime);
    var p=GraphFallback.plan(new GraphCompiler<>(rs),"C",amount,stock,Set.of(),seeds,false,force,budget);tested++;
    if(p.feasible()){if(!reachable)throw new AssertionError("false feasible sample="+sample+" force="+force);feasible++;OrderRegression.verify(p,stock,force);}
    else if(reachable){miss++;System.out.println("UNSOLVED sample="+sample+" force="+force+" order="+order+" result="+p.result());}
    if(budget.reservedBytes()!=0)throw new AssertionError("memory leak "+budget.reservedBytes());
   }
  }
  System.out.println("PASS fallback DAG oracle tested="+tested+" possible="+possible+" feasible="+feasible+" unsolved="+miss+" false_feasible=0; all feasible serially verified and all reservations released");
 }
}
