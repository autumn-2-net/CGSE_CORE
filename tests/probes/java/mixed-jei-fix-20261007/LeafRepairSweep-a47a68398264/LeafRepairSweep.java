package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.math.BigInteger;
public final class LeafRepairSweep {
 public static void main(String[] args)throws Exception{
  Path input=Path.of(args[0]);var manual=MixedSweep.read(input.resolve("manual.json"));var extra=MixedSweep.read(input.resolve("extra-virtual.json"));var config=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();String mode=config.get("mode").getAsString();var compiler=MixedSweep.compiler(manual,extra,mode,config.get("seed").getAsLong());
  int n=0,win=0;try(var out=Files.newBufferedWriter(Path.of(args[2]))){for(var element:config.getAsJsonArray("requests")){
   var req=element.getAsJsonObject();String target=req.get("target").getAsString();long amount=req.get("amount").getAsLong();var row=req.deepCopy();var b=new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime);boolean solved=false;long started=System.nanoTime();
   try(var ranking=GraphFallbackSources.create(compiler,manual.stock(),manual.external(),target,true,b)){
    for(int variant=0;variant<3&&!solved;variant++){
     var excluded=new LinkedHashSet<String>();var opened=new HashSet<String>();int round=0;
     while(++round<=64){
      var choices=new LinkedHashMap<String,Integer>();var pending=new ArrayDeque<String>();var visited=new HashSet<String>();pending.add(target);
      while(!pending.isEmpty()){
       b.check();String key=pending.removeFirst();if(!visited.add(key))continue;var sources=compiler.producers(key).stream().filter(r->!excluded.contains(r.id())).toList();
       if(!key.equals(target)&&manual.stock().getOrDefault(key,0L)>0&&!opened.contains(key)){choices.put(key,sources.size());continue;}
       var ranked=variant==0||ranking==null?sources:ranking.sources(key,variant==2).stream().filter(r->!excluded.contains(r.id())).toList();if(ranked.isEmpty())continue;var recipe=ranked.get(0);choices.put(key,sources.indexOf(recipe));pending.addAll(recipe.inputs().keySet());
      }
      var graph=compiler.compile(target,choices,excluded,b);var solve=new GraphSolve<>(graph,target,amount,manual.stock(),manual.external(),Map.of(),true,true,b,System.nanoTime(),new CatalystPolicy(4096,64),manual.stock());while(!solve.step()){}var plan=solve.result();
      if(plan.feasible()){PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);plan.initialExact().forEach((k,v)->{if(!manual.external().contains(k)&&v.compareTo(BigInteger.valueOf(manual.stock().getOrDefault(k,0L)))>0)throw new AssertionError("Stockoverdraw");});solved=true;row.addProperty("variant",variant);row.addProperty("rounds",round);row.addProperty("recipes",graph.recipes().size());row.addProperty("excluded",excluded.size());break;}
      boolean changed=false;for(String key:plan.missingExact().keySet())if(!key.equals(target)&&manual.stock().getOrDefault(key,0L)>0)changed|=opened.add(key);if(changed)continue;
      for(var recipe:graph.recipes().values())if(recipe.inputs().keySet().stream().anyMatch(plan.missingExact()::containsKey)&&!recipe.executionOutputs().containsKey(target))changed|=excluded.add(recipe.id());if(!changed)break;
     }
    }
   }catch(PlanningBudget.Exhausted t){row.addProperty("limit",t.limit().name());}
   row.addProperty("feasible",solved);row.addProperty("work",b.nodes());row.addProperty("ms",(System.nanoTime()-started)/1e6);row.addProperty("peak",b.peakBytes());out.write(MixedSweep.JSON.toJson(row));out.newLine();out.flush();n++;if(solved)win++;if(n%25==0)System.out.println("done="+n+" feasible="+win);
  }}
  System.out.println("FINAL="+win+"/"+n);
 }
}
