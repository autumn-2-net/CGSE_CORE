package org.cgse.core;
import java.math.*;import java.util.*;import java.nio.file.*;import com.google.gson.Gson;
public final class LoopBatchingProbe{
 static long checks,groups,randomModels;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}static BigInteger b(long n){return BigInteger.valueOf(n);}
 static void random(){for(int seed=0;seed<10000;seed++){
  var random=new Random(seed*7919L);int n=2+random.nextInt(7),keys=2+random.nextInt(5);var recipes=new LinkedHashMap<String,GraphRecipe<String>>();var counts=new LinkedHashMap<String,BigInteger>();var stock=new HashMap<String,BigInteger>();
  for(int k=0;k<keys;k++)stock.put("k"+k,b(random.nextInt(65)));
  for(int i=0;i<n;i++){var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();for(int k=0;k<keys;k++){if(random.nextBoolean())in.put("k"+k,1L+random.nextInt(5));if(random.nextBoolean())out.put("k"+k,1L+random.nextInt(8));}if(in.isEmpty())in.put("k0",1L);if(out.isEmpty())out.put("k0",1L);String id="r"+i;recipes.put(id,new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out));counts.put(id,b(1+random.nextInt(5)));}
  int remaining=2+random.nextInt(40);long previous=LoopBatching.iterations(counts,remaining,recipes,k->stock.getOrDefault(k,b(0)));var selected=LoopBatching.group(counts,remaining,recipes,k->stock.getOrDefault(k,b(0)));
  if(selected!=null){groups++;check(selected.iterations()>=previous&&selected.iterations()<=remaining,"weakened previous group");check(selected.counts().equals(counts),"changed recipe counts");var held=new HashMap<>(stock);
   for(var entry:selected.counts().entrySet())for(long run=0;run<entry.getValue().longValueExact()*selected.iterations();run++){
    var r=recipes.get(entry.getKey());for(var input:r.inputs().entrySet()){check(held.getOrDefault(input.getKey(),b(0)).compareTo(b(input.getValue()))>=0,"unfunded permutation seed="+seed);held.merge(input.getKey(),b(-input.getValue()),BigInteger::add);}for(var output:r.outputs().entrySet()){held.merge(output.getKey(),b(output.getValue()),BigInteger::add);check(held.get(output.getKey()).compareTo(ExactAmounts.LONG_MAX)<=0,"headroom overflow");}
   }
   var expected=new HashMap<>(stock);counts.forEach((id,count)->{var summary=SequenceSummary.recipe(recipes.get(id));summary.delta().forEach((k,v)->expected.merge(k,v.multiply(count).multiply(b(selected.iterations())),BigInteger::add));});check(held.equals(expected),"changed grouped final marking");
  }else check(previous<=1,"lost valid original group");randomModels++;
 }}
 static void edges(){
  var grow=new GraphRecipe<String>("g","g",List.of(new GraphRecipe.Slot<>("S",1)),Map.of("P",2L));var back=new GraphRecipe<String>("b","b",List.of(new GraphRecipe.Slot<>("P",1)),Map.of("S",1L));var recipes=Map.of("g",grow,"b",back);var counts=new LinkedHashMap<String,BigInteger>();counts.put("g",b(1));counts.put("b",b(1));
  check(LoopBatching.group(counts,Long.MAX_VALUE,recipes,k->k.equals("S")?b(1):b(0))==null,"unfunded seed shortcut");
  var selected=LoopBatching.group(counts,Long.MAX_VALUE,recipes,k->k.equals("S")?b(1):b(Long.MAX_VALUE-4));check(selected!=null,"near long valid prefix lost");check(selected.iterations()<=4,"overflow near long");
  var config=new GraphRecipe<String>("c","c",List.of(new GraphRecipe.Slot<>("S",1),new GraphRecipe.Slot<>("circuit",1,0,true,false)),Map.of("P",2L));var sensitive=Map.of("g",config,"b",back);check(LoopBatching.group(counts,100,sensitive,k->b(100))==null,"batch-sensitive configuration regrouped");
 }
 public static void main(String[]args)throws Exception{random();edges();var result=Map.of("randomModels",randomModels,"groupsChecked",groups,"assertions",checks);Files.writeString(Path.of(args[0],"loop-batching.json"),new Gson().toJson(result));System.out.println(result);}
}
