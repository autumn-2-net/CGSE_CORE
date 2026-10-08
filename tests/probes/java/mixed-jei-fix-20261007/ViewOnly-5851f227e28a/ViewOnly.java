package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public class ViewOnly {
 public static void main(String[] args)throws Exception {
  var base=Path.of(args[0]); var requests=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
  var manual=MixedSweep.read(base.resolve("manual.json")); var extra=MixedSweep.read(base.resolve("extra-virtual.json"));
  var compiler=MixedSweep.compiler(manual,extra,requests.get("mode").getAsString(),73);
  try(var out=Files.newBufferedWriter(Path.of(args[2]))) {
   for(var element:requests.getAsJsonArray("requests")) {
    var request=element.getAsJsonObject(); String target=request.get("target").getAsString(); long amount=request.get("amount").getAsLong();
    var budget=new PlanningBudget(0,80_000_000,128L<<20,()->false,System::nanoTime);
    try(var work=new GraphStockViewPortfolio<>(compiler,target,amount,manual.stock(),manual.external(),Map.of(),Set.of(),true,true,new CatalystPolicy(4096,64),budget,System.nanoTime())) {
     while(!work.step()) {} var plan=work.result();
     request=request.deepCopy();request.addProperty("feasible",plan!=null);request.addProperty("work",budget.nodes());request.addProperty("diagnostics",budget.diagnostics());
     if(plan!=null){PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);for(var e:plan.initialExact().entrySet())if(!manual.external().contains(e.getKey())&&e.getValue().compareTo(java.math.BigInteger.valueOf(manual.stock().getOrDefault(e.getKey(),0L)))>0)throw new AssertionError("stock");}
     out.write(request.toString());out.newLine();out.flush();System.out.println(request.get("id")+" "+(plan!=null)+" "+budget.nodes());
    }
   }
  }
 }
}
