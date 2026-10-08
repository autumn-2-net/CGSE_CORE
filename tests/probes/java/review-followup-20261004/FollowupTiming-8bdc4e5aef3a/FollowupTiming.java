package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
import java.nio.file.*;
import com.google.gson.Gson;

public class FollowupTiming {
 static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);}
 static long metric(Object solver,String name)throws Exception{var f=solver.getClass().getDeclaredField(name);f.setAccessible(true);return f.getLong(solver);}
 record Measurement(long nanos,long work,long matchingBuilds,long augmentations,int cuts){}
 static Measurement hall(HallProbe.Model m,boolean cold)throws Exception{var b=budget();long now=System.nanoTime(),builds,aug;int cuts;
  if(cold){try(var h=new ColdHall(m.rows(),m.lo(),m.hi(),b)){while(!h.step()){}builds=metric(h,"matchingBuilds");aug=metric(h,"augmentations");cuts=h.cuts().size();}}
  else{try(var h=new CountHall(m.rows(),m.lo(),m.hi(),b)){while(!h.step()){}builds=metric(h,"matchingBuilds");aug=metric(h,"augmentations");cuts=h.cuts().size();}}
  if(b.reservedBytes()!=0)throw new AssertionError("hall leak");return new Measurement(System.nanoTime()-now,b.nodes(),builds,aug,cuts);
 }
 static List<GraphRecipe<String>> chain(int n){var out=new ArrayList<GraphRecipe<String>>();for(int i=0;i<n;i++)out.add(new GraphRecipe<>("r"+i,"r"+i,List.of(new GraphRecipe.Slot<>("k"+i,2),new GraphRecipe.Slot<>("raw"+i%3,3)),Map.of("k"+(i+1),3L)));return out;}
 static Map<String,Object> catalog(boolean legacy,boolean hot,int n,int wave){var c=new GraphCompiler<>(chain(n));var b=budget();if(hot)try(var m=RecipeCountModel.create(c,"k"+n,1,Map.of(),Map.of(),Set.of(),Set.of(),false,b)){}long start=System.nanoTime(),work=b.nodes();for(int i=0;i<200;i++){
  if(!hot)c=new GraphCompiler<>(chain(n));
  if(legacy){try(var m=ColdRecipeCountModel.create(c,"k"+n,i+1,Map.of("k0",Long.MAX_VALUE),Map.of(),Set.of(),Set.of(),true,b)){if(m==null)throw new AssertionError();}}
  else {try(var m=RecipeCountModel.create(c,"k"+n,i+1,Map.of("k0",Long.MAX_VALUE),Map.of(),Set.of(),Set.of(),true,b)){if(m==null)throw new AssertionError();}}
 }if(b.reservedBytes()!=0)throw new AssertionError("catalog leak");return Map.of("legacy",legacy,"hot",hot,"recipes",n,"wave",wave,"ms",(System.nanoTime()-start)/1e6,"work",b.nodes()-work);}
 public static void main(String[]args)throws Exception{var hallCases=new ArrayList<HallProbe.Model>();for(int n:new int[]{8,12,16,20}){int[][] g=new int[n][];for(int i=0;i<n;i++){g[i]=new int[n];for(int j=0;j<n;j++)g[i][j]=j;}hallCases.add(HallProbe.model(g,n,null));}
  for(int n:new int[]{12,24,48}){int[][] g=new int[n][];for(int i=0;i<n;i++){int block=i/3*3;g[i]=new int[]{block,block+1,block+2,Math.min(n-1,block+3)};}hallCases.add(HallProbe.model(g,n,null));}
  for(int n:new int[]{8,12,20}){int[][] g=new int[n][];for(int i=0;i<n;i++){g[i]=new int[n-1];for(int j=0;j<n-1;j++)g[i][j]=j;}hallCases.add(HallProbe.model(g,n-1,null));}
  var hallRows=new ArrayList<Map<String,Object>>();var catRows=new ArrayList<Map<String,Object>>();for(int wave=-6;wave<6;wave++)for(boolean legacy:(wave%2==0?List.of(false,true):List.of(true,false))){for(int id=0;id<hallCases.size();id++)for(int iteration=0;iteration<15;iteration++){var m=hall(hallCases.get(id),legacy);if(wave>=0)hallRows.add(Map.of("legacy",legacy,"case",id,"wave",wave,"nanos",m.nanos,"work",m.work,"builds",m.matchingBuilds,"augments",m.augmentations,"cuts",m.cuts));}for(int n:new int[]{64,256})for(boolean hot:List.of(false,true)){var r=catalog(legacy,hot,n,wave);if(wave>=0)catRows.add(r);}}
  var report=Map.of("hall",hallRows,"catalog",catRows);Files.writeString(Path.of(args[0],"followup-timing.json"),new Gson().toJson(report));System.out.println("timings hall="+hallRows.size()+" catalog="+catRows.size());
 }
}
