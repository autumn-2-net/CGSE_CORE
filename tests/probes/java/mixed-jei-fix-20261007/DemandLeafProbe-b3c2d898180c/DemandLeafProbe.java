package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public final class DemandLeafProbe {
 public static void main(String[] args)throws Exception{
  Path input=Path.of(args[0]);var manual=MixedSweep.read(input.resolve("manual.json"));var extra=MixedSweep.read(input.resolve("reduced-extra.json"));var request=JsonParser.parseString(Files.readString(input.resolve("reduced-extra.json"))).getAsJsonObject().getAsJsonObject("request");String target=request.get("target").getAsString();long amount=request.get("amount").getAsLong();
  for(String mode:List.of("manual","manual-first","extra-first"))for(int variant=0;variant<3;variant++){
   var compiler=MixedSweep.compiler(manual,extra,mode,0);var b=new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime);var choices=new LinkedHashMap<String,Integer>();
   var ranked=GraphSourceScout.choices(compiler,target,manual.stock(),manual.external(),Set.of(),true,b);var preferred=variant==0||ranked.size()<variant?Map.<String,Integer>of():ranked.get(variant-1);choices.putAll(preferred);
   for(var e:manual.stock().entrySet())if(e.getValue()>0&&!e.getKey().equals(target))choices.put(e.getKey(),compiler.producers(e.getKey()).size());
   int round=0;try{
    while(++round<=64){var graph=compiler.compile(target,choices,Set.of(),b);var solve=new GraphSolve<>(graph,target,amount,manual.stock(),manual.external(),Map.of(),true,true,b,System.nanoTime(),new CatalystPolicy(4096,64),manual.stock());while(!solve.step()){}var plan=solve.result();System.out.println(mode+" variant="+variant+" round="+round+" recipes="+graph.recipes().size()+" "+plan.result()+" missing="+plan.missingExact()+" work="+b.nodes());if(plan.feasible()){PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);break;}boolean changed=false;for(String key:plan.missingExact().keySet())if(choices.getOrDefault(key,-1)==compiler.producers(key).size()){changed=true;if(preferred.containsKey(key))choices.put(key,preferred.get(key));else choices.remove(key);}if(!changed)break;}
   }catch(Throwable t){System.out.println(t);}
  }
 }
}
