package org.cgse.core;
import java.util.*;import java.nio.file.*;import com.google.gson.*;
public class FallbackProbe {
 public static void main(String[] a)throws Exception {
  Path base=Path.of(a[0]);var manual=MixedSweep.read(base.resolve("manual.json"));var extra=MixedSweep.read(base.resolve(a[1]));
  JsonObject doc=JsonParser.parseString(Files.readString(base.resolve(a[1]))).getAsJsonObject();
  String target=doc.has("request")?doc.getAsJsonObject("request").get("target").getAsString():"{\"#c\":\"ae2:f\",id:\"gtceu:miracle\"}";
  long amount=doc.has("request")?doc.getAsJsonObject("request").get("amount").getAsLong():1;
  for(String mode:List.of("manual-first","extra-first","random")){
   var compiler=MixedSweep.compiler(manual,extra,mode,73);
   for(int i=0;i<2;i++) {
    var b=new PlanningBudget(0,131072,16L<<20,()->false,System::nanoTime);
    var plan=GraphFallback.plan(compiler,target,amount,manual.stock(),manual.external(),Map.of(),true,true,b);
    if(plan.feasible()){PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);}
    System.out.println(mode+" "+plan.result()+" work="+b.nodes()+" missing="+plan.missingExact()+" "+b.diagnostics());
   }
  }
 }
}