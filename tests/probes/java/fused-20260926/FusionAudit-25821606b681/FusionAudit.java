package org.cgse.core;
import java.util.*;
import java.nio.file.*;
/** Isolated presolve/MITM diagnostics. Not end-to-end benchmark results. */
public final class FusionAudit {
 public static void main(String[] args)throws Exception{
  for(String name:Files.readAllLines(Path.of(args[1]))){
   var d=FusionProbe.map(new FusionProbe.Json(Files.readString(Path.of(args[0]).resolve(name+".json"))).value());var f=FusionProbe.fixture(d);System.out.println("CASE "+name);
   var b=new PlanningBudget(10000,20_000_000,256L<<20,()->false,System::nanoTime);
   try(var model=RecipeCountModel.create(new GraphCompiler<>(f.recipes()),f.target(),f.amount(),f.stock(),Map.of(),Set.of(),Set.of(),true,b)){
    if(model==null){System.out.println("MODEL_UNAVAILABLE");continue;}
    try(var bounds=new CountBounds(model.recipes.size(),model.constraints,b)){
     while(!bounds.step()){};System.out.println("BOUNDS blocked="+bounds.blocked());if(bounds.blocked())continue;
     try(var red=new CountReduction(model.constraints,bounds.lowerBounds(),bounds.upperBounds(),b)){
      while(!red.step()){};var domains=new TreeMap<String,Integer>();for(int i=0;i<red.lower().length;i++)domains.merge(red.lower()[i]+".."+red.upper()[i],1,Integer::sum);
      System.out.println("REDUCTION "+model.recipes.size()+" -> "+red.lower().length+" rows="+red.rows().size()+" domains="+domains);
      int[] parent=new int[red.lower().length];for(int i=0;i<parent.length;i++)parent[i]=i;
      for(var row:red.rows()){int first=-1;for(var term:row.terms().entrySet()){int id=term.getKey();if(term.getValue().signum()==0||red.lower()[id].equals(red.upper()[id]))continue;if(first<0)first=id;else{int a=first,c=id;while(parent[a]!=a)a=parent[a];while(parent[c]!=c)c=parent[c];parent[c]=a;}}}
      Map<Integer,Integer> components=new TreeMap<>();for(int i=0;i<parent.length;i++){if(red.lower()[i].equals(red.upper()[i]))continue;int a=i;while(parent[a]!=a)a=parent[a];components.merge(a,1,Integer::sum);}
      System.out.println("LIVE_CONSTRAINT_COMPONENT_SIZES "+components.values().stream().sorted().toList());
      try(var mitm=new CountMeetInMiddle(red.rows(),red.lower(),red.upper(),b)){while(!mitm.step()){}System.out.println("MITM witness="+(mitm.counts()!=null)+" infeasible="+mitm.infeasible()+" trace="+b.diagnostics());}
     }
    }
   }catch(PlanningBudget.Exhausted limit){System.out.println("LIMIT "+b.diagnostics());}
  }
 }
}
