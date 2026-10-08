package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public final class LeafRepairProbe {
 public static void main(String[] args)throws Exception{
  Path input=Path.of(args[0]);var manual=MixedSweep.read(input.resolve("manual.json"));var extra=MixedSweep.read(input.resolve("reduced-extra.json"));var request=JsonParser.parseString(Files.readString(input.resolve("reduced-extra.json"))).getAsJsonObject().getAsJsonObject("request");String target=request.get("target").getAsString();long amount=request.get("amount").getAsLong();
  for(String mode:List.of("manual-first","extra-first"))for(int variant=0;variant<3;variant++){
   var compiler=MixedSweep.compiler(manual,extra,mode,0);var b=new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime);var excluded=new LinkedHashSet<String>();var opened=new HashSet<String>();
   int round=0;try(var ranking=GraphFallbackSources.create(compiler,manual.stock(),manual.external(),target,true,b)){
    while(++round<=64){
     var choices=new LinkedHashMap<String,Integer>();var pending=new ArrayDeque<String>();var visited=new HashSet<String>();pending.add(target);
     while(!pending.isEmpty()){
      String key=pending.removeFirst();if(!visited.add(key))continue;var sources=compiler.producers(key).stream().filter(r->!excluded.contains(r.id())).toList();
      if(!key.equals(target)&&manual.stock().getOrDefault(key,0L)>0&&!opened.contains(key)){choices.put(key,sources.size());continue;}
      var ranked=variant==0||ranking==null?sources:ranking.sources(key,variant==2).stream().filter(r->!excluded.contains(r.id())).toList();if(ranked.isEmpty())continue;var recipe=ranked.get(0);choices.put(key,sources.indexOf(recipe));pending.addAll(recipe.inputs().keySet());
     }
     var graph=compiler.compile(target,choices,excluded,b);var solve=new GraphSolve<>(graph,target,amount,manual.stock(),manual.external(),Map.of(),true,true,b,System.nanoTime(),new CatalystPolicy(4096,64),manual.stock());while(!solve.step()){}var plan=solve.result();System.out.println(mode+" variant="+variant+" round="+round+" recipes="+graph.recipes().size()+" banned="+excluded.size()+" "+plan.result()+" missing="+plan.missingExact()+" work="+b.nodes());if(plan.feasible()){PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);break;}
     boolean changed=false;for(String key:plan.missingExact().keySet())if(!key.equals(target)&&manual.stock().getOrDefault(key,0L)>0)changed|=opened.add(key);
     if(changed)continue;
     for(var recipe:graph.recipes().values())if(recipe.inputs().keySet().stream().anyMatch(plan.missingExact()::containsKey)&&!recipe.executionOutputs().containsKey(target))changed|=excluded.add(recipe.id());
     if(!changed)break;
    }
   }catch(Throwable t){System.out.println(t);}
  }
 }
}
