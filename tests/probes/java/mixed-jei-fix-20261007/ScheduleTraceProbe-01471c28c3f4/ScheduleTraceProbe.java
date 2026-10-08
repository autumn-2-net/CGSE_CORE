package org.cgse.core;
import com.google.gson.*;import java.nio.file.*;import java.util.*;import java.lang.reflect.*;import java.util.regex.*;
public final class ScheduleTraceProbe {
 static Object get(Object x,String name)throws Exception{Field f=x.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(x);}
 public static void main(String[]args)throws Exception{
 Path base=Path.of(args[0]),cfg=Path.of(args[1]);var config=JsonParser.parseString(Files.readString(cfg)).getAsJsonObject();var manual=MixedSweep.read(base.resolve("manual.json"));var extra=MixedSweep.read(base.resolve("extra-virtual.json"));long seed=config.get("seed").getAsLong();double fraction=config.get("fraction").getAsDouble();
 var shuffled=new ArrayList<>(extra.recipes());Collections.shuffle(shuffled,new Random(seed));var selected=List.copyOf(shuffled.subList(0,(int)Math.ceil(fraction*shuffled.size())));var selectedData=MinimizeStress.data(selected);var random=new Random(seed^0xC6A4A7935BD1E995L);var catalog=SubsetSweep.interleave(manual.recipes(),selected,random);var producers=new LinkedHashMap<String,List<GraphRecipe<String>>>();var keys=new LinkedHashSet<>(manual.producers().keySet());keys.addAll(selectedData.producers().keySet());for(String key:keys)producers.put(key,SubsetSweep.interleave(manual.producers().getOrDefault(key,List.of()),selectedData.producers().getOrDefault(key,List.of()),random));
 for(var element:config.getAsJsonArray("requests")){var request=element.getAsJsonObject();if(!request.get("id").getAsString().equals("focus-60"))continue;String target=request.get("target").getAsString();long amount=request.get("amount").getAsLong();
 for(int variant=0;variant<6;variant++){
 var compiler=new GraphCompiler<>(catalog,producers);var b=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);final int v=variant;final GraphSourceRanking<String>[]rank=new GraphSourceRanking[1];long previous=0;var output=new JsonArray();
 try(var w=new GraphStockViewWork<>(compiler,target,amount,manual.stock(),manual.external(),Map.of(),Set.of(),true,true,new CatalystPolicy(4096,64),b,System.nanoTime(),variant,8_000_000,()->{rank[0]=v>=4?GraphSourceRanking.createLocal(compiler,manual.stock(),manual.external(),target,true,b,262144):GraphSourceRanking.create(compiler,manual.stock(),manual.external(),target,true,b,262144);return rank[0];})){
 while(true){boolean had=get(w,"solving")!=null;boolean done=w.step();if(had&&get(w,"solving")==null){var m=Pattern.compile("stock_view@([0-9]+): round=([0-9]+); variant=([0-9]+); recipes=([0-9]+); result=([A-Z_]+); missing_keys=([0-9]+)").matcher(b.diagnostics());String[]last=null;while(m.find())last=new String[]{m.group(2),m.group(4),m.group(5),m.group(6)};if(last!=null){var row=new JsonObject();row.addProperty("round",Integer.parseInt(last[0]));row.addProperty("recipes",Integer.parseInt(last[1]));row.addProperty("result",last[2]);row.addProperty("missing",Integer.parseInt(last[3]));row.addProperty("cost",b.nodes()-previous);row.addProperty("work",b.nodes());output.add(row);previous=b.nodes();}}if(done)break;}
 var row=new JsonObject();row.addProperty("variant",variant);row.addProperty("feasible",w.result()!=null);row.addProperty("work",b.nodes());row.add("rounds",output);System.out.println(row);if(w.result()!=null){PlanVerifier.verify(w.result());PlanVerifier.verifyRuntimeInventory(w.result());}
 }finally{if(rank[0]!=null)rank[0].close();}if(b.reservedBytes()!=0)throw new AssertionError("leak "+b.reservedBytes());
 }
 }
 }
}
