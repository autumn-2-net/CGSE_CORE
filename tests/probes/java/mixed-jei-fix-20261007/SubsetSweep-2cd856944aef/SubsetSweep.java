package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class SubsetSweep {
 static <T> List<T> interleave(List<T> first,List<T> second,Random random){
  var out=new ArrayList<T>(first.size()+second.size());int i=0,j=0;
  while(i<first.size()||j<second.size()){
   if(j==second.size()||i<first.size()&&random.nextInt(first.size()-i+second.size()-j)<first.size()-i)out.add(first.get(i++));else out.add(second.get(j++));
  }return out;
 }
 static String hash(List<String> values)throws Exception{
  var digest=MessageDigest.getInstance("SHA-256");for(String value:values){digest.update(value.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}return HexFormat.of().formatHex(digest.digest());
 }
 public static void main(String[] args)throws Exception{
  Path base=Path.of(args[0]),cfg=Path.of(args[1]),output=Path.of(args[2]);var config=JsonParser.parseString(Files.readString(cfg)).getAsJsonObject();
  var manual=MixedSweep.read(base.resolve("manual.json"));var extra=MixedSweep.read(base.resolve("extra-virtual.json"));long seed=config.get("seed").getAsLong();double fraction=config.get("fraction").getAsDouble();String mode=config.get("mode").getAsString();
  var shuffled=new ArrayList<>(extra.recipes());Collections.shuffle(shuffled,new Random(seed));int size=(int)Math.ceil(fraction*shuffled.size());var selected=List.copyOf(shuffled.subList(0,size));var selectedData=MinimizeStress.data(selected);
  List<GraphRecipe<String>> catalog;Map<String,List<GraphRecipe<String>>> producers;
  if(mode.equals("manual")){catalog=manual.recipes();producers=manual.producers();}
  else if(mode.equals("subset-shuffle")){
   catalog=new ArrayList<>(manual.recipes());catalog.addAll(selected);Collections.shuffle(catalog,new Random(seed^0xC6A4A7935BD1E995L));producers=MinimizeStress.data(catalog).producers();
  }else{
   var random=new Random(seed^0xC6A4A7935BD1E995L);catalog=interleave(manual.recipes(),selected,random);producers=new LinkedHashMap<>();
   var keys=new LinkedHashSet<>(manual.producers().keySet());keys.addAll(selectedData.producers().keySet());
   for(String key:keys)producers.put(key,interleave(manual.producers().getOrDefault(key,List.of()),selectedData.producers().getOrDefault(key,List.of()),random));
  }
  if(!catalog.containsAll(manual.recipes())||catalog.size()!=manual.recipes().size()+size)throw new AssertionError("Manual recipe changed/omitted");
  var signature=new ArrayList<String>();for(var e:producers.entrySet()){signature.add(e.getKey());for(var recipe:e.getValue())signature.add(recipe.id());}
  var manifest=new JsonObject();manifest.addProperty("fraction",fraction);manifest.addProperty("seed",seed);manifest.addProperty("mode",mode);manifest.addProperty("manual_patterns",manual.recipes().size());manifest.addProperty("extra_patterns",size);manifest.addProperty("catalog_sha256",hash(catalog.stream().map(GraphRecipe::id).toList()));manifest.addProperty("sources_sha256",hash(signature));manifest.add("selected_ids",MixedSweep.JSON.toJsonTree(selected.stream().map(GraphRecipe::id).toList()));
  Files.writeString(output.resolveSibling(output.getFileName()+".selection.json"),MixedSweep.JSON.toJson(manifest));
  var compiler=new GraphCompiler<>(catalog,producers);int n=0,feasible=0,errors=0;long started=System.nanoTime();
  try(var writer=Files.newBufferedWriter(output)){
   for(var element:config.getAsJsonArray("requests")){
    var row=MixedSweep.solve(manual,compiler,config,element.getAsJsonObject());row.addProperty("fraction",fraction);row.addProperty("extra_patterns",size);row.addProperty("catalog_sha256",manifest.get("catalog_sha256").getAsString());row.addProperty("sources_sha256",manifest.get("sources_sha256").getAsString());
    writer.write(MixedSweep.JSON.toJson(row));writer.newLine();writer.flush();if(row.get("result").getAsString().equals("ERROR"))errors++;if(row.get("feasible").getAsBoolean())feasible++;
    if(++n%32==0)System.out.printf("done=%d feasible=%d errors=%d seconds=%.1f%n",n,feasible,errors,(System.nanoTime()-started)/1e9);
   }
  }
  System.out.printf("FINAL=%d/%d errors=%d extra=%d mode=%s seed=%d%n",feasible,n,errors,size,mode,seed);if(errors!=0)throw new AssertionError("Invalid plans/errors="+errors);
 }
}
